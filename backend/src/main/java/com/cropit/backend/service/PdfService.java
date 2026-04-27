package com.cropit.backend.service;

import com.cropit.backend.model.CropJob;
import com.cropit.backend.model.JobState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Executes pdftoppm and pdfinfo as external processes via {@link ProcessBuilder}.
 *
 * <p>pdftoppm rendering parameters {@code -r 200 -scale-to 1540} are always preserved.
 */
@Service
public class PdfService {

    private static final Logger log = LoggerFactory.getLogger(PdfService.class);

    /** Batch size for full-page rendering (number of pages per pdftoppm invocation). */
    private static final int BATCH_SIZE = 10;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pdf-render-thread");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
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

        // Determine total page count first so we can pick a sensible subset
        int totalPages = getPdfPageCount(pdfPath);
        job.setTotalPages(totalPages);

        int lastPreviewPage = Math.min(count, totalPages);

        String outputPrefix = previewDir.resolve("page").toString();

        List<String> cmd = new ArrayList<>(List.of(
                "pdftoppm",
                "-png",
                "-r", "200",
                "-scale-to", "1540",
                "-f", "1",
                "-l", String.valueOf(lastPreviewPage),
                pdfPath.toString(),
                outputPrefix
        ));

        runProcess(cmd, "pdftoppm preview");

        // Collect created files and read the dimensions of the first one
        List<Path> files = listPngsInDir(previewDir);
        if (files.isEmpty()) {
            throw new IOException("pdftoppm produced no output files for preview generation");
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

        // Convert normalised coordinates to pixel values in the pdftoppm output space.
        // The full render uses the same -r 200 -scale-to 1540 parameters, so the
        // pixel dimensions are identical to the preview images.
        int cropX = (int) Math.round(normX * imgW);
        int cropY = (int) Math.round(normY * imgH);
        int cropW = (int) Math.round(normW * imgW);
        int cropH = (int) Math.round(normH * imgH);

        // Clamp to valid ranges
        cropX = Math.max(0, Math.min(cropX, imgW - 1));
        cropY = Math.max(0, Math.min(cropY, imgH - 1));
        cropW = Math.max(1, Math.min(cropW, imgW - cropX));
        cropH = Math.max(1, Math.min(cropH, imgH - cropY));

        log.info("Render job={} total={} cropBox px=[{},{},{},{}]",
                job.getJobId(), total, cropX, cropY, cropW, cropH);

        Path outputDir = job.getJobDir().resolve("output");
        Path pdfPath = job.getPdfPath();

        // Process pages in batches of BATCH_SIZE
        for (int start = 1; start <= total; start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE - 1, total);

            String outputPrefix = outputDir.resolve("page").toString();

            List<String> cmd = new ArrayList<>(List.of(
                    "pdftoppm",
                    "-png",
                    "-r", "200",
                    "-scale-to", "1540",
                    "-x", String.valueOf(cropX),
                    "-y", String.valueOf(cropY),
                    "-W", String.valueOf(cropW),
                    "-H", String.valueOf(cropH),
                    "-f", String.valueOf(start),
                    "-l", String.valueOf(end),
                    pdfPath.toString(),
                    outputPrefix
            ));

            runProcess(cmd, "pdftoppm render batch " + start + "-" + end);

            // Register newly created output files – collect all output PNGs atomically
            List<Path> newFiles = listPngsInDir(outputDir);
            synchronized (job) {
                job.clearOutputFiles();
                for (Path f : newFiles) {
                    job.addOutputFile(f.getFileName().toString());
                }
            }

            job.getDonePages().set(end);
        }
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
