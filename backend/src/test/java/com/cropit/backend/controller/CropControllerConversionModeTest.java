package com.cropit.backend.controller;

import com.cropit.backend.model.ConversionMode;
import com.cropit.backend.model.CropJob;
import com.cropit.backend.model.JobState;
import com.cropit.backend.service.JobService;
import com.cropit.backend.service.PdfService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CropControllerConversionModeTest {
    @TempDir Path directory;
    private JobService jobs;
    private PdfService pdf;
    private CropJob job;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        jobs = mock(JobService.class);
        pdf = mock(PdfService.class);
        job = new CropJob("test", directory);
        when(jobs.createJob()).thenReturn(job);
        when(jobs.getJob("test")).thenReturn(job);
        when(pdf.generatePreviews(any())).thenAnswer(invocation -> {
            job.setPreviewImageWidth(1388);
            job.setPreviewImageHeight(2048);
            job.setState(JobState.PREVIEWS_READY);
            return List.of("page-1.png");
        });
        mvc = MockMvcBuilders.standaloneSetup(new CropController(jobs, pdf)).build();
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "test.pdf", "application/pdf", new byte[]{1});
    }

    @Test
    void existingClientsDefaultToPdftoppm() throws Exception {
        mvc.perform(multipart("/api/upload").file(file()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversionMode").value("PDFTOPPM"));
        assertThat(job.getConversionMode()).isEqualTo(ConversionMode.PDFTOPPM);
        verify(pdf).generatePreviews(job);
    }

    @Test
    void explicitTwoStepChoiceIsPreservedThroughRender() throws Exception {
        mvc.perform(multipart("/api/upload").file(file())
                        .param("conversionMode", "PDFTOPPM_MOGRIFY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversionMode").value("PDFTOPPM_MOGRIFY"));
        assertThat(job.getConversionMode()).isEqualTo(ConversionMode.PDFTOPPM_MOGRIFY);
        mvc.perform(post("/api/jobs/test/render").contentType("application/json")
                        .content("{\"x\":0.1,\"y\":0.15,\"w\":0.7,\"h\":0.65}"))
                .andExpect(status().isAccepted());
        verify(pdf).startRenderAsync(job, .1, .15, .7, .65);
        mvc.perform(get("/api/jobs/test/status"))
                .andExpect(jsonPath("$.conversionMode").value("PDFTOPPM_MOGRIFY"));
    }

    @Test
    void unsupportedModeIsRejectedBeforeCreatingJob() throws Exception {
        mvc.perform(multipart("/api/upload").file(file()).param("conversionMode", "INVALID"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(jobs, pdf);
    }

    @Test
    void missingMogrifyReportsTheCorrectDependency() throws Exception {
        when(pdf.generatePreviews(any())).thenThrow(
                new java.io.IOException("Cannot run program \"mogrify\": No such file or directory"));
        mvc.perform(multipart("/api/upload").file(file()).param("conversionMode", "PDFTOPPM_MOGRIFY"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("install ImageMagick")));
    }
}
