package com.shaterguy.chatgptselfrun;

/** Reduces hidden WebView raster work while preserving the same logical CSS viewport. */
final class HeadlessWebViewPowerPolicy {
    static final int MAX_RASTER_DENSITY_DPI = 320;

    private HeadlessWebViewPowerPolicy() {}

    static RasterSize capRasterDensity(int width, int height, int densityDpi) {
        int safeWidth = Math.max(1, width);
        int safeHeight = Math.max(1, height);
        int safeDensity = Math.max(1, densityDpi);
        if (safeDensity <= MAX_RASTER_DENSITY_DPI) {
            return new RasterSize(safeWidth, safeHeight, safeDensity);
        }
        double scale = MAX_RASTER_DENSITY_DPI / (double) safeDensity;
        return new RasterSize(
                Math.max(1, (int) Math.round(safeWidth * scale)),
                Math.max(1, (int) Math.round(safeHeight * scale)),
                MAX_RASTER_DENSITY_DPI);
    }

    static final class RasterSize {
        final int width;
        final int height;
        final int densityDpi;

        RasterSize(int width, int height, int densityDpi) {
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
        }
    }
}
