package com.cropit.backend.service;

import com.cropit.backend.model.CropJob;
import com.cropit.backend.model.JobState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Executes pdftoppm and pdfinfo as external processes via {@link ProcessBuilder}.
 *
 * <p>Preview rendering always uses {@code -scale-to 1540} so the longer side of every
 * preview PNG is exactly 1540 px.
 *
 * <p>Crop rendering computes an <em>adjusted</em> {@code -scale-to} value so that after
 * pdftoppm applies the {@code -x/-y/-W/-H} crop window, the longer side of the resulting
 * PNG is still exactly {@value #TARGET_LONG_SIDE} px. See {@link #renderAllPages} for the
 * detailed calculation.
 *
 * <p>Final rendering uses a fixed-size pool of {@code pdftoppm} worker processes to
 * parallelise page-batch conversion. The pool size defaults to 8 and is configurable
 * via {@code cropit.render.worker-pool-size} in {@code application.properties}.
 */
@Service
public class PdfService {

    private static final Logger log = LoggerFactory.getLogger(PdfService.class);

    /** Batch size for full-page rendering (number of pages per pdftoppm invocation). */
    private static final int BATCH_SIZE = 10;

    /**
     * Required length (in pixels) of the longer side of every output PNG.
     * Preview PNGs and cropped output PNGs both target this size.
     */
    static final int TARGET_LONG_SIDE = 1540;

    /**
     * Number of concurrent {@code pdftoppm} worker processes used during final rendering.
     * Configurable via {@code cropit.render.worker-pool-size} in {@code application.properties}.
     * Default is 8.
     */
    @Value("${cropit.render.worker-pool-size:8}")
    private int workerPoolSize;

    /** Single-thread executor that dispatches the overall async render job. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pdf-render-thread");
        t.setDaemon(true);
        return t;
    });

    /**
     * Fixed-size thread pool for running {@code pdftoppm} page-batch processes in parallel.
     * Initialised in {@link #init()} after Spring has injected {@link #workerPoolSize}.
     */
    private ExecutorService workerPool;

    @PostConstruct
    public void init() {
        java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger(1);
        workerPool = Executors.newFixedThreadPool(workerPoolSize, r -> {
            Thread t = new Thread(r, "pdftoppm-worker-" + idx.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        log.info("pdftoppm worker pool initialised with {} threads", workerPoolSize);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        workerPool.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            if (!workerPool.awaitTermination(10, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates up to {@code previewCount} preview PNGs for the given job.
     * The files are written to {@code {jobDir}/preview/page-NNN.png}.
     * On success the job state transitions to {@link JobState#PREVIEWS_READY}.
     *
     * @return list of relative file names that were created (e.g. {@code ["page-001.png", ...]})
     */
    public List<String> generatePreviews(CropJob job) throws IOException, InterruptedException {
        Path pdfPath = job.getPdfPath();
        Path previewDir = job.getJobDir().resolve("preview");
        int count = job.getPreviewCount();

        // Pass -l count directly; pdftoppm stops at the actual last page even when
        // count exceeds the page count, so no upfront pdfinfo call is needed.
        String outputPrefix = previewDir.resolve("page").toString();

        List<String> cmd = new ArrayList<>(List.of(
                "pdftoppm",
                "-png",
                "-r", "200",
                "-scale-to", String.valueOf(TARGET_LONG_SIDE),
                "-f", "1",
                "-l", String.valueOf(count),
                pdfPath.toString(),
                outputPrefix
        ));

        runProcess(cmd, "pdftoppm preview");

        // Collect created files and read the dimensions of the first one
        List<Path> files = listPngsInDir(previewDir);
        if (files.isEmpty()) {
            throw new IOException("pdftoppm produced no output files for preview generation");
        }

        // If pdftoppm produced fewer files than requested, the PDF has exactly that
        // many pages and no further call is needed.  When all requested previews were
        // produced the total page count may be higher, so we fall back to pdfinfo.
        if (files.size() < count) {
            job.setTotalPages(files.size());
        } else {
            job.setTotalPages(getPdfPageCount(pdfPath));
        }

        // Read natural dimensions from the first PNG
        int[] dims = readPngDimensions(files.get(0));
        job.setPreviewImageWidth(dims[0]);
        job.setPreviewImageHeight(dims[1]);
        job.setState(JobState.PREVIEWS_READY);

        List<String> names = new ArrayList<>();
        for (Path f : files) {
            names.add(f.getFileName().toString());
        }
        return names;
    }

    /**
     * Starts an asynchronous full-page render with the given normalised crop box.
     * Progress is tracked via {@link CropJob#getDonePages()}.
     *
     * @param normX  normalised left edge  (0..1)
     * @param normY  normalised top edge   (0..1)
     * @param normW  normalised width      (0..1)
     * @param normH  normalised height     (0..1)
     */
    public void startRenderAsync(CropJob job, double normX, double normY, double normW, double normH) {
        job.setState(JobState.RENDERING);
        job.getDonePages().set(0);
        job.clearOutputFiles();

        executor.submit(() -> {
            try {
                renderAllPages(job, normX, normY, normW, normH);
                job.setState(JobState.DONE);
            } catch (Exception e) {
                log.error("Render failed for job {}", job.getJobId(), e);
                job.setErrorMessage(e.getMessage());
                job.setState(JobState.ERROR);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void renderAllPages(CropJob job, double normX, double normY, double normW, double normH)
            throws IOException, InterruptedException {

        int total = job.getTotalPages();
        int imgW = job.getPreviewImageWidth();
        int imgH = job.getPreviewImageHeight();

        // Convert normalised coordinates to pixel values in the preview (1540-scale) space.
        int cropX = (int) Math.round(normX * imgW);
        int cropY = (int) Math.round(normY * imgH);
        int cropW = (int) Math.round(normW * imgW);
        int cropH = (int) Math.round(normH * imgH);

        // Clamp to valid ranges
        cropX = Math.max(0, Math.min(cropX, imgW - 1));
        cropY = Math.max(0, Math.min(cropY, imgH - 1));
        cropW = Math.max(1, Math.min(cropW, imgW - cropX));
        cropH = Math.max(1, Math.min(cropH, imgH - cropY));

        // Compute an adjusted -scale-to value so the longer side of the *cropped*
        // output PNG equals exactly TARGET_LONG_SIDE pixels. See computeScaledCropParams
        // for the full derivation.
        int[] scaled = computeScaledCropParams(cropX, cropY, cropW, cropH);
        int scaleTo    = scaled[0];
        int cropXScaled = scaled[1];
        int cropYScaled = scaled[2];
        int cropWScaled = scaled[3];
        int cropHScaled = scaled[4];

        log.info("Render job={} total={} previewBox=[{},{},{},{}] scaleTo={} scaledBox=[{},{},{},{}]",
                job.getJobId(), total,
                cropX, cropY, cropW, cropH,
                scaleTo,
                cropXScaled, cropYScaled, cropWScaled, cropHScaled);

        Path outputDir = job.getJobDir().resolve("output");
        Path pdfPath = job.getPdfPath();

        // Submit all page batches to the worker pool concurrently.
        // Each pdftoppm process writes to non-overlapping page numbers (e.g. page-001.png
        // through page-010.png for the first batch), so there are no output-file collisions.
        List<Future<Void>> futures = new ArrayList<>();
        for (int start = 1; start <= total; start += BATCH_SIZE) {
            final int batchStart = start;
            final int batchEnd = Math.min(start + BATCH_SIZE - 1, total);
            final String outputPrefix = outputDir.resolve("page").toString();

            futures.add(workerPool.submit((Callable<Void>) () -> {
                List<String> cmd = new ArrayList<>(List.of(
                        "pdftoppm",
                        "-png",
                        "-r", "200",
                        "-scale-to", String.valueOf(scaleTo),
                        "-x", String.valueOf(cropXScaled),
                        "-y", String.valueOf(cropYScaled),
                        "-W", String.valueOf(cropWScaled),
                        "-H", String.valueOf(cropHScaled),
                        "-f", String.valueOf(batchStart),
                        "-l", String.valueOf(batchEnd),
                        pdfPath.toString(),
                        outputPrefix
                ));

                runProcess(cmd, "pdftoppm render batch " + batchStart + "-" + batchEnd);

                // Update progress atomically: each worker increments by the number of
                // pages it just completed.
                job.getDonePages().addAndGet(batchEnd - batchStart + 1);
                return null;
            }));
        }

        // Wait for all batches to complete; surface the first failure if any.
        IOException firstError = null;
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                // Propagate interrupt: cancel still-queued futures and rethrow.
                futures.forEach(f -> f.cancel(true));
                Thread.currentThread().interrupt();
                throw new IOException("Render interrupted while waiting for workers", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (firstError == null) {
                    firstError = cause instanceof IOException ioe
                            ? ioe
                            : new IOException("Batch render failed: " + cause.getMessage(), cause);
                }
            }
        }

        if (firstError != null) {
            throw firstError;
        }

        // Register all output PNGs produced by the parallel workers.
        List<Path> allFiles = listPngsInDir(outputDir);
        synchronized (job) {
            job.clearOutputFiles();
            for (Path f : allFiles) {
                job.addOutputFile(f.getFileName().toString());
            }
        }
    }

    /**
     * Computes the adjusted pdftoppm {@code -scale-to} value and the scaled crop
     * parameters such that the longer side of the cropped output PNG equals exactly
     * {@link #TARGET_LONG_SIDE} pixels.
     *
     * <p>When pdftoppm is invoked with {@code -scale-to S -x x -y y -W w -H h} it
     * renders the full page at scale {@code S} (longer side = S) and then crops the
     * window {@code [x, y, w, h]} out of that rendering. The resulting PNG has
     * dimensions {@code w × h}. To guarantee {@code max(w, h) = TARGET_LONG_SIDE} we
     * must choose S so that the crop window—after scaling from the preview space to
     * the new rendering space—produces the target size.
     *
     * <p><b>Algorithm</b>
     * <ol>
     *   <li>Let {@code L = max(cropW, cropH)} (longer side of the crop in preview
     *       pixel space where the full page's longer side = TARGET_LONG_SIDE).</li>
     *   <li>Set {@code scaleTo = round(TARGET_LONG_SIDE² / L)}. This produces an
     *       integer S such that {@code round(L × S / TARGET_LONG_SIDE) ≈
     *       TARGET_LONG_SIDE} (within ±1 before the corrective step below).</li>
     *   <li>Scale all four crop parameters by {@code scaleTo / TARGET_LONG_SIDE}.</li>
     *   <li>Force the longer scaled crop dimension to exactly TARGET_LONG_SIDE.
     *       Due to integer rounding the value is at most ±1 off, so this adjustment
     *       is within the ≤1 px tolerance explicitly allowed by the specification.</li>
     * </ol>
     *
     * @param cropX  crop origin X in preview pixel space
     * @param cropY  crop origin Y in preview pixel space
     * @param cropW  crop width in preview pixel space
     * @param cropH  crop height in preview pixel space
     * @return int array {@code [scaleTo, scaledX, scaledY, scaledW, scaledH]}
     */
    static int[] computeScaledCropParams(int cropX, int cropY, int cropW, int cropH) {
        int longerCrop = Math.max(cropW, cropH);
        int scaleTo = (int) Math.round((double) TARGET_LONG_SIDE * TARGET_LONG_SIDE / longerCrop);

        int scaledX = (int) Math.round((double) cropX * scaleTo / TARGET_LONG_SIDE);
        int scaledY = (int) Math.round((double) cropY * scaleTo / TARGET_LONG_SIDE);
        int scaledW = (int) Math.round((double) cropW * scaleTo / TARGET_LONG_SIDE);
        int scaledH = (int) Math.round((double) cropH * scaleTo / TARGET_LONG_SIDE);

        // Force the longer side to exactly TARGET_LONG_SIDE (≤ 1 px tolerance).
        if (cropH >= cropW) {
            scaledH = TARGET_LONG_SIDE;
        } else {
            scaledW = TARGET_LONG_SIDE;
        }

        return new int[]{scaleTo, scaledX, scaledY, scaledW, scaledH};
    }

    /**
     * Uses {@code pdfinfo} to determine the total number of pages in the PDF.
     */
    int getPdfPageCount(Path pdfPath) throws IOException, InterruptedException {
        List<String> cmd = List.of("pdfinfo", pdfPath.toString());
        String output = runProcessCaptureOutput(cmd, "pdfinfo");

        for (String line : output.split("\\R")) {
            if (line.startsWith("Pages:")) {
                String[] parts = line.split(":\\s+");
                if (parts.length >= 2) {
                    return Integer.parseInt(parts[1].trim());
                }
            }
        }
        throw new IOException("Could not parse page count from pdfinfo output:\n" + output);
    }

    /**
     * Runs a command and waits for it to finish. Throws on non-zero exit code.
     */
    private void runProcess(List<String> cmd, String label) throws IOException, InterruptedException {
        log.info("Executing [{}]: {}", label, String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Process [" + label + "] exited with code " + exitCode
                    + ". Output:\n" + output);
        }
        if (!output.isBlank()) {
            log.debug("[{}] output: {}", label, output.trim());
        }
    }

    /**
     * Runs a command, captures stdout+stderr, and returns the output as a string.
     */
    private String runProcessCaptureOutput(List<String> cmd, String label)
            throws IOException, InterruptedException {
        log.info("Executing [{}]: {}", label, String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Process [" + label + "] exited with code " + exitCode
                    + ". Output:\n" + output);
        }
        return output;
    }

    /**
     * Returns all {@code .png} files in a directory, sorted by name.
     */
    private List<Path> listPngsInDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Reads the width and height of a PNG file without decoding all pixel data.
     * Falls back to full decoding via ImageIO if fast read fails.
     */
    int[] readPngDimensions(Path pngPath) throws IOException {
        // Try fast path: read PNG IHDR chunk (bytes 16-23 = width/height as big-endian int32)
        try (var fis = new java.io.FileInputStream(pngPath.toFile())) {
            byte[] header = fis.readNBytes(24);
            if (header.length == 24) {
                int w = ((header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16)
                        | ((header[18] & 0xFF) << 8) | (header[19] & 0xFF);
                int h = ((header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16)
                        | ((header[22] & 0xFF) << 8) | (header[23] & 0xFF);
                if (w > 0 && h > 0) {
                    log.debug("PNG dimensions for {}: {}x{}", pngPath.getFileName(), w, h);
                    return new int[]{w, h};
                }
            }
        } catch (IOException e) {
            log.warn("Fast PNG dimension read failed, falling back to ImageIO", e);
        }

        // Fallback: use ImageIO
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
        if (readers.hasNext()) {
            ImageReader reader = readers.next();
            try (FileImageInputStream stream = new FileImageInputStream(pngPath.toFile())) {
                reader.setInput(stream, true, true);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        }

        // Last resort: full decode
        BufferedImage img = ImageIO.read(pngPath.toFile());
        if (img == null) throw new IOException("Cannot read image: " + pngPath);
        return new int[]{img.getWidth(), img.getHeight()};
    }
}
