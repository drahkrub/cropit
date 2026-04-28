package com.cropit.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.cropit.backend.service.PdfService.TARGET_LONG_SIDE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PdfService#computeScaledCropParams}.
 *
 * <p>The key invariant: after scaling, {@code max(scaledW, scaledH) == TARGET_LONG_SIDE}
 * regardless of the input crop dimensions.
 */
class PdfServiceScalingTest {

    // ------------------------------------------------------------------
    // Invariant: longer side of scaled crop must always equal TARGET_LONG_SIDE
    // ------------------------------------------------------------------

    /**
     * Representative cases from the user's bug report and a variety of aspect ratios.
     *
     * columns: cropX(0), cropY(1), cropW(2), cropH(3), previewImgW(4), previewImgH(5)
     *
     * The test checks only the invariant (longer side == TARGET_LONG_SIDE) because
     * the exact scaleTo and coordinate values depend on rounding and are verified
     * separately.
     */
    @ParameterizedTest(name = "crop=[w={2},h={3}] on {4}x{5}")
    @CsvSource({
        // user's exact bug-report example: portrait page 1091x1540, crop 970x1414
        "62, 82, 970, 1414, 1091, 1540",
        // landscape page
        "10, 20, 800, 600, 1540, 1091",
        // square crop
        "50, 50, 500, 500, 1091, 1540",
        // narrow vertical strip
        "100, 0, 200, 1400, 1091, 1540",
        // wide horizontal strip
        "0, 100, 1000, 200, 1091, 1540",
        // crop equals full page (identity)
        "0, 0, 1091, 1540, 1091, 1540",
        // single pixel – degenerate minimum
        "0, 0, 1, 1, 1091, 1540",
        // crop taller than wide (portrait crop on portrait page)
        "30, 10, 400, 1200, 800, 1540",
        // crop wider than tall (landscape crop on landscape page)
        "0, 100, 1400, 400, 1540, 800",
    })
    void longerSideIsExactlyTargetForVariousCrops(
            int cropX, int cropY, int cropW, int cropH,
            int imgW, int imgH) {

        // Clamp inputs the same way renderAllPages does
        cropX = Math.max(0, Math.min(cropX, imgW - 1));
        cropY = Math.max(0, Math.min(cropY, imgH - 1));
        cropW = Math.max(1, Math.min(cropW, imgW - cropX));
        cropH = Math.max(1, Math.min(cropH, imgH - cropY));

        int[] result = PdfService.computeScaledCropParams(cropX, cropY, cropW, cropH);
        int scaleTo = result[0];
        int scaledW = result[3];
        int scaledH = result[4];

        int longerSide = Math.max(scaledW, scaledH);

        assertEquals(TARGET_LONG_SIDE, longerSide,
                String.format("Expected longer side %d but got max(%d,%d)=%d "
                                + "for crop [x=%d y=%d w=%d h=%d] scaleTo=%d",
                        TARGET_LONG_SIDE, scaledW, scaledH, longerSide,
                        cropX, cropY, cropW, cropH, scaleTo));
    }

    // ------------------------------------------------------------------
    // Concrete values for the user's exact bug-report example
    // ------------------------------------------------------------------

    @Test
    void bugReportExample_portraitPage_croppedDimensionsPreserveTarget() {
        // Full preview page: 1091 x 1540 (longer side = 1540 = TARGET_LONG_SIDE)
        // Crop in preview space: x=62 y=82 w=970 h=1414
        // Before fix: pdftoppm -scale-to 1540 -W 970 -H 1414 would produce 970x1414.
        // After fix: longer side of scaled output must be exactly 1540.

        int[] result = PdfService.computeScaledCropParams(62, 82, 970, 1414);

        int scaleTo = result[0];
        int scaledW = result[3];
        int scaledH = result[4];

        // scaleTo must be greater than TARGET_LONG_SIDE (we need to render bigger)
        assertTrue(scaleTo > TARGET_LONG_SIDE,
                "scaleTo should exceed TARGET_LONG_SIDE when crop < full page, got " + scaleTo);

        // The longer side of the crop must equal TARGET_LONG_SIDE exactly
        assertEquals(TARGET_LONG_SIDE, Math.max(scaledW, scaledH));

        // Result array has 5 elements: [scaleTo, x, y, w, h]
        assertEquals(5, result.length);
    }

    // ------------------------------------------------------------------
    // Edge case: crop already fills the whole preview (identity)
    // ------------------------------------------------------------------

    @Test
    void fullPageCrop_scaleToEqualsTarget() {
        // When the crop covers the entire preview page the scale factor stays at TARGET.
        int[] result = PdfService.computeScaledCropParams(0, 0, TARGET_LONG_SIDE, TARGET_LONG_SIDE);

        int scaleTo = result[0];
        assertEquals(TARGET_LONG_SIDE, scaleTo,
                "For a full-page square crop scaleTo should equal TARGET_LONG_SIDE");
        assertEquals(TARGET_LONG_SIDE, Math.max(result[3], result[4]));
    }

    // ------------------------------------------------------------------
    // scaleTo is always a positive integer
    // ------------------------------------------------------------------

    @Test
    void scaleToIsAlwaysPositive() {
        // Even with the smallest possible crop (1x1)
        int[] result = PdfService.computeScaledCropParams(0, 0, 1, 1);
        assertTrue(result[0] > 0, "scaleTo must be positive");
    }
}
