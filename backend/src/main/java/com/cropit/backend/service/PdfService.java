package com.cropit.backend.service;

import com.cropit.backend.model.CropJob;
import com.cropit.backend.model.ConversionMode;
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
 * <p>By default, pdftoppm renders directly to the target pixel size. Jobs may
 * instead rasterise at the configured DPI (default 400), then resize in place
 * with mogrify. The selection applies to both previews and cropped output.
 *
 * <p>Both preview generation and final rendering use a shared fixed-size pool of
 * {@code pdftoppm} worker processes.  Preview pages are processed in batches of
 * {@value #PREVIEW_BATCH_SIZE}; final render batches are {@value #BATCH_SIZE} pages each.
 * The pool size defaults to 8 and is configurable via
 * {@code cropit.render.worker-pool-size} in {@code application.properties}.
 */
@Service
public class PdfService {

    private static final Logger log = LoggerFactory.getLogger(PdfService.class);

    /** Batch size for preview generation (number of pages per pdftoppm invocation). */
    private static final int PREVIEW_BATCH_SIZE = 4;

    /** Batch size for full-page rendering (number of pages per pdftoppm invocation). */
    private static final int BATCH_SIZE = 10;

    /**
     * Maximum length (in pixels) of the longer side of every output PNG.
     * Preview PNGs and cropped output PNGs both target this size.
     */
    private final int targetLongSide;

    private final int renderDpi;

    public PdfService(@Value("${cropit.render.target-long-side:2048}") int targetLongSide,
                      @Value("${cropit.render.dpi:400}") int renderDpi) {
        if (targetLongSide <= 0) {
            throw new IllegalArgumentException("cropit.render.target-long-side must be a positive pixel count");
        }
        if (renderDpi <= 0) {
            throw new IllegalArgumentException("cropit.render.dpi must be positive");
        }
        this.targetLongSide = targetLongSide;
        this.renderDpi = renderDpi;
    }

    /**
     * Number of concurrent {@code pdftoppm} worker processes shared between preview
     * generation and final rendering.
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
     * Generates up to {@code previewCount} preview PNGs for the given job using the
     * shared worker pool.  Pages are processed in batches of {@value #PREVIEW_BATCH_SIZE}
     * concurrently.  The files are written to {@code {jobDir}/preview/page-NNN.png}.
     * On success the job state transitions to {@link JobState#PREVIEWS_READY}.
     *
     * @return list of relative file names that were created (e.g. {@code ["page-001.png", ...]})
     */
    public List<String> generatePreviews(CropJob job) throws IOException, InterruptedException {
        Path pdfPath = job.getPdfPath();
        Path previewDir = job.getJobDir().resolve("preview");
        int count = job.getPreviewCount();
        boolean twoStep = job.getConversionMode() == ConversionMode.PDFTOPPM_MOGRIFY;

        // Pass -l batchEnd directly; pdftoppm stops at the actual last page even when
        // batchEnd exceeds the page count, so no upfront pdfinfo call is needed.
        String outputPrefix = previewDir.resolve("page").toString();

        // Submit preview batches to the worker pool concurrently.
        // Each batch covers a non-overlapping page range (e.g. pages 1-4, 5-8, ...).
        // pdftoppm names output files by page number so there are no file collisions.
        List<Future<Void>> futures = new ArrayList<>();
        for (int start = 1; start <= count; start += PREVIEW_BATCH_SIZE) {
            final int batchStart = start;
            final int batchEnd = Math.min(start + PREVIEW_BATCH_SIZE - 1, count);

            futures.add(workerPool.submit((Callable<Void>) () -> {
                List<String> cmd = new ArrayList<>(List.of("pdftoppm", "-png"));
                cmd.addAll(twoStep
                        ? List.of("-r", String.valueOf(renderDpi))
                        : List.of("-scale-to", String.valueOf(targetLongSide)));
                cmd.addAll(List.of(
                        "-f", String.valueOf(batchStart),
                        "-l", String.valueOf(batchEnd),
                        pdfPath.toString(),
                        outputPrefix
                ));
                runProcess(cmd, "pdftoppm preview batch " + batchStart + "-" + batchEnd);
                List<Path> batchFiles = listBatchPngs(previewDir, batchStart, batchEnd);
                if (batchStart == 1 && !batchFiles.isEmpty()) {
                    int[] rawDimensions = readPngDimensions(batchFiles.get(0));
                    job.setRasterImageWidth(rawDimensions[0]);
                    job.setRasterImageHeight(rawDimensions[1]);
                }
                if (twoStep) resizePngs(batchFiles);
                return null;
            }));
        }

        // Wait for all batches to complete; surface the first failure if any.
        IOException firstError = null;
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                futures.forEach(f -> f.cancel(true));
                Thread.currentThread().interrupt();
                throw new IOException("Preview generation interrupted while waiting for workers", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (firstError == null) {
                    firstError = cause instanceof IOException ioe
                            ? ioe
                            : new IOException("Batch preview failed: " + cause.getMessage(), cause);
                }
            }
        }

        if (firstError != null) {
            throw firstError;
        }

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

        // Convert normalised coordinates to pixel values in the preview pixel space.
        int cropX = (int) Math.round(normX * imgW);
        int cropY = (int) Math.round(normY * imgH);
        int cropW = (int) Math.round(normW * imgW);
        int cropH = (int) Math.round(normH * imgH);

        // Clamp to valid ranges
        cropX = Math.max(0, Math.min(cropX, imgW - 1));
        cropY = Math.max(0, Math.min(cropY, imgH - 1));
        cropW = Math.max(1, Math.min(cropW, imgW - cropX));
        cropH = Math.max(1, Math.min(cropH, imgH - cropY));

        boolean twoStep = job.getConversionMode() == ConversionMode.PDFTOPPM_MOGRIFY;
        int[] directParams = twoStep ? null
                : computeScaledCropParams(cropX, cropY, cropW, cropH, targetLongSide);
        int[] scaled = twoStep
                ? computeRasterCropParams(cropX, cropY, cropW, cropH,
                        imgW, imgH, job.getRasterImageWidth(), job.getRasterImageHeight())
                : new int[]{directParams[1], directParams[2], directParams[3], directParams[4]};
        int cropXScaled = scaled[0];
        int cropYScaled = scaled[1];
        int cropWScaled = scaled[2];
        int cropHScaled = scaled[3];

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
                List<String> cmd = new ArrayList<>(List.of("pdftoppm", "-png"));
                cmd.addAll(twoStep
                        ? List.of("-r", String.valueOf(renderDpi))
                        : List.of("-scale-to", String.valueOf(directParams[0])));
                cmd.addAll(List.of(
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
                if (twoStep) resizePngs(listBatchPngs(outputDir, batchStart, batchEnd));

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
     * Direct rendering scales the full page so the cropped PNG's longer side
     * reaches the target, including a correction for integer rounding.
     * Returns [scaleTo, x, y, width, height].
     */
    static int[] computeScaledCropParams(int x, int y, int w, int h, int target) {
        int scaleTo = (int) Math.round((double) target * target / Math.max(w, h));
        int scaledX = (int) Math.round((double) x * scaleTo / target);
        int scaledY = (int) Math.round((double) y * scaleTo / target);
        int scaledW = (int) Math.round((double) w * scaleTo / target);
        int scaledH = (int) Math.round((double) h * scaleTo / target);
        if (h >= w) scaledH = target;
        else scaledW = target;
        return new int[]{scaleTo, scaledX, scaledY, Math.max(1, scaledW), Math.max(1, scaledH)};
    }

    /**
     * Maps preview crop edges to the raster at renderDpi, including rounding at
     * page boundaries. No forced output size: mogrify only shrinks large images.
     */
    static int[] computeRasterCropParams(int x, int y, int w, int h,
                                        int previewW, int previewH, int rasterW, int rasterH) {
        int left = Math.max(0, Math.min(rasterW - 1,
                (int) Math.round((double) x * rasterW / previewW)));
        int top = Math.max(0, Math.min(rasterH - 1,
                (int) Math.round((double) y * rasterH / previewH)));
        int right = Math.min(rasterW, (int) Math.round((double) (x + w) * rasterW / previewW));
        int bottom = Math.min(rasterH, (int) Math.round((double) (y + h) * rasterH / previewH));
        return new int[]{left, top, Math.max(1, right - left), Math.max(1, bottom - top)};
    }

    /**
     * Expand page-*.png ourselves and restrict to this worker's page range:
     * a wildcard spanning parallel batches could resize partially written files.
     */
    private List<Path> listBatchPngs(Path directory, int first, int last) throws IOException {
        return listPngsInDir(directory).stream().filter(path -> {
            String name = path.getFileName().toString();
            if (!name.matches("page-[0-9]+\\.png")) return false;
            int page = Integer.parseInt(name.substring(5, name.length() - 4));
            return page >= first && page <= last;
        }).toList();
    }

    private void resizePngs(List<Path> files) throws IOException, InterruptedException {
        if (files.isEmpty()) return;
        List<String> cmd = new ArrayList<>(List.of("mogrify", "-resize",
                targetLongSide + "x" + targetLongSide + ">"));
        files.forEach(path -> cmd.add(path.toString()));
        runProcess(cmd, "mogrify resize");
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
