package com.cropit.backend.controller;

import com.cropit.backend.model.CropBoxRequest;
import com.cropit.backend.model.CropJob;
import com.cropit.backend.model.JobState;
import com.cropit.backend.service.JobService;
import com.cropit.backend.service.PdfService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller exposing the crop-and-render workflow API.
 *
 * <pre>
 * POST   /api/upload                    – upload PDF + choose preview count
 * GET    /api/jobs/{jobId}/status        – poll job state and progress
 * POST   /api/jobs/{jobId}/render        – submit crop box, start full render
 * GET    /api/jobs/{jobId}/files/{name}  – download a generated PNG file
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class CropController {

    private static final Logger log = LoggerFactory.getLogger(CropController.class);

    private final JobService jobService;
    private final PdfService pdfService;

    public CropController(JobService jobService, PdfService pdfService) {
        this.jobService = jobService;
        this.pdfService = pdfService;
    }

    // -------------------------------------------------------------------------
    // Upload endpoint
    // -------------------------------------------------------------------------

    /**
     * Accepts a PDF file upload, generates preview PNGs, and returns the job ID
     * together with URLs for the generated preview images.
     *
     * <p>Request: {@code multipart/form-data}
     * <ul>
     *   <li>{@code file}         – the PDF file</li>
     *   <li>{@code previewCount} – number of previews to generate (1–20, default 4)</li>
     * </ul>
     *
     * <p>Response:
     * <pre>
     * {
     *   "jobId": "...",
     *   "previews": [
     *     { "filename": "page-001.png", "url": "/api/jobs/{jobId}/files/page-001.png" }
     *   ],
     *   "imageWidth": 1088,
     *   "imageHeight": 1540
     * }
     * </pre>
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "previewCount", defaultValue = "4") int previewCount) {

        // Validate inputs
        if (file.isEmpty()) {
            return badRequest("No file provided");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".pdf")) {
            return badRequest("Only PDF files are accepted");
        }
        if (previewCount < 1 || previewCount > 20) {
            return badRequest("previewCount must be between 1 and 20");
        }

        CropJob job;
        try {
            job = jobService.createJob();
        } catch (IOException e) {
            log.error("Failed to create job directory", e);
            return serverError("Failed to create job: " + e.getMessage());
        }

        try {
            // Save the PDF to the job directory
            Path pdfPath = job.getJobDir().resolve("input.pdf");
            file.transferTo(pdfPath);
            job.setPdfPath(pdfPath);
            job.setPreviewCount(previewCount);

            // Generate preview PNGs (synchronous – preview generation is fast)
            List<String> previewFiles = pdfService.generatePreviews(job);

            List<Map<String, String>> previews = new ArrayList<>();
            for (String filename : previewFiles) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("filename", filename);
                entry.put("url", "/api/jobs/" + job.getJobId() + "/files/preview/" + filename);
                previews.add(entry);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jobId", job.getJobId());
            response.put("previews", previews);
            response.put("imageWidth", job.getPreviewImageWidth());
            response.put("imageHeight", job.getPreviewImageHeight());
            response.put("totalPages", job.getTotalPages());

            return ResponseEntity.ok(response);

        } catch (IOException | InterruptedException e) {
            log.error("Preview generation failed for job {}", job.getJobId(), e);
            String msg = e.getMessage();
            if (msg != null && (msg.contains("pdftoppm") || msg.contains("No such file"))) {
                msg = "pdftoppm is not installed or not on PATH. Please install poppler-utils.";
            }
            jobService.failJob(job, msg);
            return serverError("Preview generation failed: " + msg);
        }
    }

    // -------------------------------------------------------------------------
    // Status endpoint
    // -------------------------------------------------------------------------

    /**
     * Returns the current state and progress of a job.
     *
     * <pre>
     * {
     *   "jobId": "...",
     *   "state": "RENDERING",
     *   "donePages": 40,
     *   "totalPages": 245,
     *   "outputFiles": ["/api/jobs/{jobId}/files/output/page-001.png", ...],
     *   "errorMessage": null
     * }
     * </pre>
     */
    @GetMapping("/jobs/{jobId}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String jobId) {
        CropJob job = jobService.getJob(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        List<String> outputUrls = new ArrayList<>();
        for (String name : job.getOutputFiles()) {
            outputUrls.add("/api/jobs/" + jobId + "/files/output/" + name);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", jobId);
        response.put("state", job.getState().name());
        response.put("donePages", job.getDonePages().get());
        response.put("totalPages", job.getTotalPages());
        response.put("outputFiles", outputUrls);
        response.put("errorMessage", job.getErrorMessage());

        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // Render endpoint
    // -------------------------------------------------------------------------

    /**
     * Submits the crop box and starts asynchronous full-page rendering.
     *
     * <p>Request body (JSON):
     * <pre>
     * { "x": 0.05, "y": 0.10, "w": 0.90, "h": 0.80 }
     * </pre>
     */
    @PostMapping("/jobs/{jobId}/render")
    public ResponseEntity<Map<String, Object>> render(
            @PathVariable String jobId,
            @RequestBody CropBoxRequest cropBox) {

        CropJob job = jobService.getJob(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        if (job.getState() != JobState.PREVIEWS_READY) {
            return badRequest("Job is not in PREVIEWS_READY state (current: " + job.getState() + ")");
        }
        if (job.getPreviewImageWidth() == 0 || job.getPreviewImageHeight() == 0) {
            return badRequest("Preview image dimensions are not available");
        }

        // Validate crop box values
        if (cropBox.getX() < 0 || cropBox.getY() < 0
                || cropBox.getW() <= 0 || cropBox.getH() <= 0
                || cropBox.getX() + cropBox.getW() > 1.0
                || cropBox.getY() + cropBox.getH() > 1.0) {
            return badRequest("Crop box values are out of range: " + cropBox);
        }

        pdfService.startRenderAsync(job, cropBox.getX(), cropBox.getY(),
                cropBox.getW(), cropBox.getH());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", jobId);
        response.put("message", "Rendering started");
        response.put("totalPages", job.getTotalPages());

        return ResponseEntity.accepted().body(response);
    }

    // -------------------------------------------------------------------------
    // File serving endpoints
    // -------------------------------------------------------------------------

    /**
     * Serves a generated preview PNG by file name.
     * URL pattern: {@code /api/jobs/{jobId}/files/preview/{filename}}
     */
    @GetMapping("/jobs/{jobId}/files/preview/{filename:.+}")
    public ResponseEntity<Resource> servePreview(
            @PathVariable String jobId,
            @PathVariable String filename) {
        return serveFile(jobId, "preview", filename);
    }

    /**
     * Serves a generated output PNG by file name.
     * URL pattern: {@code /api/jobs/{jobId}/files/output/{filename}}
     */
    @GetMapping("/jobs/{jobId}/files/output/{filename:.+}")
    public ResponseEntity<Resource> serveOutput(
            @PathVariable String jobId,
            @PathVariable String filename) {
        return serveFile(jobId, "output", filename);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private ResponseEntity<Resource> serveFile(String jobId, String subDir, String filename) {
        CropJob job = jobService.getJob(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        // Prevent path-traversal attacks
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }

        Path filePath = job.getJobDir().resolve(subDir).resolve(filename);
        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new PathResource(filePath);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(resource);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.badRequest().body(body);
    }

    private ResponseEntity<Map<String, Object>> serverError(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
