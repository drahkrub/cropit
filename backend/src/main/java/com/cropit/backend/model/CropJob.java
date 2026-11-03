package com.cropit.backend.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds all mutable state for a single processing job.
 * Access is only partially thread-safe; the render runs on a background
 * thread while status polling happens on the HTTP thread.
 */
public class CropJob {

    private final String jobId;
    private final Path jobDir;

    /** Volatile because it is read from the HTTP thread and written by the render thread. */
    private volatile JobState state = JobState.PENDING;

    /** Path to the uploaded PDF. */
    private Path pdfPath;

    /** Number of preview images requested (1–20). */
    private int previewCount;

    private ConversionMode conversionMode = ConversionMode.PDFTOPPM;

    /** Total pages in the PDF. Set during preview generation (from file count or pdfinfo). */
    private volatile int totalPages;

    /** Pixel dimensions of the first preview image (set after preview generation). */
    private volatile int previewImageWidth;
    private volatile int previewImageHeight;

    /** Dimensions before preview resizing, at the configured rendering DPI. */
    private volatile int rasterImageWidth;
    private volatile int rasterImageHeight;

    /** Progress counters updated by the render thread. */
    private final AtomicInteger donePages = new AtomicInteger(0);

    /** Error message (if any). */
    private volatile String errorMessage;

    /** File names of generated output PNGs (relative to jobDir/output/). */
    private final List<String> outputFiles = new ArrayList<>();

    public CropJob(String jobId, Path jobDir) {
        this.jobId = jobId;
        this.jobDir = jobDir;
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public String getJobId() { return jobId; }
    public Path getJobDir() { return jobDir; }

    public JobState getState() { return state; }
    public void setState(JobState state) { this.state = state; }

    public Path getPdfPath() { return pdfPath; }
    public void setPdfPath(Path pdfPath) { this.pdfPath = pdfPath; }

    public int getPreviewCount() { return previewCount; }
    public void setPreviewCount(int previewCount) { this.previewCount = previewCount; }

    public ConversionMode getConversionMode() { return conversionMode; }
    public void setConversionMode(ConversionMode conversionMode) { this.conversionMode = conversionMode; }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public int getPreviewImageWidth() { return previewImageWidth; }
    public void setPreviewImageWidth(int previewImageWidth) { this.previewImageWidth = previewImageWidth; }

    public int getPreviewImageHeight() { return previewImageHeight; }
    public void setPreviewImageHeight(int previewImageHeight) { this.previewImageHeight = previewImageHeight; }

    public int getRasterImageWidth() { return rasterImageWidth; }
    public void setRasterImageWidth(int width) { rasterImageWidth = width; }
    public int getRasterImageHeight() { return rasterImageHeight; }
    public void setRasterImageHeight(int height) { rasterImageHeight = height; }

    public AtomicInteger getDonePages() { return donePages; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public synchronized List<String> getOutputFiles() { return new ArrayList<>(outputFiles); }
    public synchronized void addOutputFile(String filename) { outputFiles.add(filename); }
    public synchronized void clearOutputFiles() { outputFiles.clear(); }
}
