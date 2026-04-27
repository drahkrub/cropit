package com.cropit.backend.service;

import com.cropit.backend.model.CropJob;
import com.cropit.backend.model.JobState;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates, stores and retrieves {@link CropJob} instances.
 * Jobs are kept in memory; for production use a persistent store would be needed.
 */
@Service
public class JobService {

    private final Map<String, CropJob> jobs = new ConcurrentHashMap<>();

    /** Base directory under which all job directories are created. */
    private final Path baseDir;

    public JobService() throws IOException {
        this.baseDir = Files.createTempDirectory("cropit-jobs");
    }

    /**
     * Creates a new job with its own work directory and registers it.
     */
    public CropJob createJob() throws IOException {
        String jobId = UUID.randomUUID().toString();
        Path jobDir = baseDir.resolve(jobId);
        Files.createDirectories(jobDir.resolve("preview"));
        Files.createDirectories(jobDir.resolve("output"));
        CropJob job = new CropJob(jobId, jobDir);
        jobs.put(jobId, job);
        return job;
    }

    /**
     * Returns the job with the given ID, or {@code null} if not found.
     */
    public CropJob getJob(String jobId) {
        return jobs.get(jobId);
    }

    /**
     * Marks a job as failed with the given message.
     */
    public void failJob(CropJob job, String message) {
        job.setErrorMessage(message);
        job.setState(JobState.ERROR);
    }
}
