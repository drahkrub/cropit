package com.cropit.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class PdfServiceScalingTest {
    @ParameterizedTest
    @ValueSource(ints = {512, 1540, 2048, 4096})
    void directRenderingEnlargesPageToKeepCroppedOutputAtTarget(int target) {
        assertArrayEquals(new int[]{target * 2, (target / 8) * 2, (target / 8) * 2, target, target},
                PdfService.computeScaledCropParams(target / 8, target / 8,
                        target / 2, target / 2, target));
        assertArrayEquals(new int[]{target * 2, 0, 0, target / 2, target},
                PdfService.computeScaledCropParams(0, 0, target / 4, target / 2, target));
        assertArrayEquals(new int[]{target * 2, 0, 0, target, target / 2},
                PdfService.computeScaledCropParams(0, 0, target / 2, target / 4, target));
    }

    @Test
    void directRenderingCorrectsLegacyRoundingRegression() {
        assertArrayEquals(new int[]{1677, 68, 89, 1056, 1540},
                PdfService.computeScaledCropParams(62, 82, 970, 1414, 1540));
    }

    @Test
    void thinDirectCropRetainsAtLeastOnePixel() {
        assertArrayEquals(new int[]{2048, 0, 0, 1, 2048},
                PdfService.computeScaledCropParams(0, 0, 1, 2048, 2048));
    }

    @Test
    void cropIsMappedToOriginalRasterRatherThanTargetOutputSize() {
        assertArrayEquals(new int[]{400, 600, 2000, 3000},
                PdfService.computeRasterCropParams(200, 300, 1000, 1500,
                        1600, 2048, 3200, 4096));
    }

    @Test
    void fullPagePreservesRasterDimensionsDespitePreviewRounding() {
        assertArrayEquals(new int[]{0, 0, 3308, 4678},
                PdfService.computeRasterCropParams(0, 0, 1448, 2048,
                        1448, 2048, 3308, 4678));
    }

    @Test
    void cropAtBottomRightStaysWithinRaster() {
        assertArrayEquals(new int[]{3306, 4676, 2, 2},
                PdfService.computeRasterCropParams(1447, 2047, 1, 1,
                        1448, 2048, 3308, 4678));
    }

    @Test
    void smallPageUsesOriginalCoordinatesWithoutUpscaling() {
        assertArrayEquals(new int[]{10, 20, 100, 200},
                PdfService.computeRasterCropParams(10, 20, 100, 200,
                        400, 600, 400, 600));
    }
}
