package com.shaterguy.chatgptselfrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HeadlessWebViewPowerPolicyTest {
    @Test public void highDensityRasterIsReducedWithoutChangingLogicalViewport() {
        HeadlessWebViewPowerPolicy.RasterSize raster =
                HeadlessWebViewPowerPolicy.capRasterDensity(1080, 2400, 480);

        assertEquals(720, raster.width);
        assertEquals(1600, raster.height);
        assertEquals(320, raster.densityDpi);
        assertEquals(360.0, cssPixels(raster.width, raster.densityDpi), 0.01);
        assertEquals(800.0, cssPixels(raster.height, raster.densityDpi), 0.01);
    }

    @Test public void densityAtOrBelowCapIsUnchanged() {
        HeadlessWebViewPowerPolicy.RasterSize raster =
                HeadlessWebViewPowerPolicy.capRasterDensity(720, 1600, 320);

        assertEquals(720, raster.width);
        assertEquals(1600, raster.height);
        assertEquals(320, raster.densityDpi);
    }

    @Test public void oddDensityPreservesLogicalViewportWithinRoundingTolerance() {
        int width = 1440;
        int height = 3120;
        int density = 560;
        HeadlessWebViewPowerPolicy.RasterSize raster =
                HeadlessWebViewPowerPolicy.capRasterDensity(width, height, density);

        assertEquals(320, raster.densityDpi);
        assertTrue(raster.width < width);
        assertTrue(raster.height < height);
        assertEquals(cssPixels(width, density), cssPixels(raster.width, raster.densityDpi), 0.6);
        assertEquals(cssPixels(height, density), cssPixels(raster.height, raster.densityDpi), 0.6);
    }

    private static double cssPixels(int pixels, int densityDpi) {
        return pixels * 160.0 / densityDpi;
    }
}
