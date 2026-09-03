package com.shaterguy.chatgptselfrun;

import android.app.Presentation;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/** Private mobile WebView host whose viewport mirrors the visible calibration WebView. */
final class HeadlessWebViewHost {
    private static volatile WebView activeWebView;
    private static volatile HeadlessWebViewHost activeHost;

    private final WebView webView;
    private final Presentation presentation;
    private final VirtualDisplay virtualDisplay;
    private final Surface surface;
    private final ImageReader imageReader;
    private final HandlerThread drainThread;
    private final DisplayDrainState drainState;
    private boolean outputAttached;
    private boolean completedRunResourceCacheCleared;

    private HeadlessWebViewHost(WebView webView, Presentation presentation,
                                VirtualDisplay virtualDisplay, Surface surface,
                                ImageReader imageReader, HandlerThread drainThread,
                                DisplayDrainState drainState) {
        this.webView = webView;
        this.presentation = presentation;
        this.virtualDisplay = virtualDisplay;
        this.surface = surface;
        this.imageReader = imageReader;
        this.drainThread = drainThread;
        this.drainState = drainState;
        this.outputAttached = virtualDisplay != null && surface != null;
        activeWebView = webView;
        activeHost = this;
    }

    static HeadlessWebViewHost create(Context context) {
        HandlerThread drainThread = null;
        ImageReader imageReader = null;
        Surface surface = null;
        VirtualDisplay display = null;
        Presentation presentation = null;
        DisplayDrainState drainState = new DisplayDrainState();
        MobileDimensions dimensions = dimensions(context);
        try {
            drainThread = new HandlerThread("SelfRunDisplayDrain");
            drainThread.start();
            imageReader = ImageReader.newInstance(
                    dimensions.width, dimensions.height, PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(
                    reader -> drainLatestImage(reader, drainState),
                    new Handler(drainThread.getLooper()));
            surface = imageReader.getSurface();
            DisplayManager manager = context.getSystemService(DisplayManager.class);
            display = manager.createVirtualDisplay("SelfRunDriveMobile", dimensions.width, dimensions.height,
                    dimensions.densityDpi, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
            if (display == null || display.getDisplay() == null) {
                throw new IllegalStateException("virtual display unavailable");
            }
            presentation = new Presentation(context, display.getDisplay(), android.R.style.Theme_DeviceDefault_NoActionBar);
            FrameLayout root = new FrameLayout(presentation.getContext());
            WebView webView = new FocusPreservingWebView(presentation.getContext());
            webView.setFocusable(true);
            webView.setFocusableInTouchMode(true);
            root.addView(webView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            presentation.setContentView(root);
            presentation.show();
            Window window = presentation.getWindow();
            if (window != null) window.setLayout(dimensions.width, dimensions.height);
            webView.requestFocus();
            return new HeadlessWebViewHost(
                    webView, presentation, display, surface, imageReader, drainThread, drainState);
        } catch (Throwable error) {
            if (presentation != null) try { presentation.dismiss(); } catch (Throwable ignored) {}
            if (display != null) try { display.release(); } catch (Throwable ignored) {}
            if (surface != null) try { surface.release(); } catch (Throwable ignored) {}
            if (imageReader != null) try { imageReader.close(); } catch (Throwable ignored) {}
            if (drainThread != null) try { drainThread.quitSafely(); } catch (Throwable ignored) {}
            WebView fallback = new FocusPreservingWebView(context);
            fallback.setFocusable(true);
            fallback.setFocusableInTouchMode(true);
            fallback.requestFocus();
            return new HeadlessWebViewHost(fallback, null, null, null, null, null, null);
        }
    }

    private static void drainLatestImage(ImageReader reader, DisplayDrainState state) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
        } catch (Throwable error) {
            state.failure = error.getClass().getSimpleName();
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Throwable error) {
                    state.failure = error.getClass().getSimpleName();
                }
            }
        }
    }

    private static MobileDimensions dimensions(Context context) {
        WebUiCalibrationStore.Viewport viewport = new WebUiCalibrationStore(context).viewport();
        if (viewport != null) {
            return powerOptimizedDimensions(viewport.pixelWidth(), viewport.pixelHeight(), viewport.densityDpi());
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int shorter = Math.max(320, Math.min(metrics.widthPixels, metrics.heightPixels));
        int longer = Math.max(480, Math.max(metrics.widthPixels, metrics.heightPixels));
        int density = Math.max(120, Math.min(640, metrics.densityDpi));
        return powerOptimizedDimensions(shorter, longer, density);
    }

    static MobileDimensions powerOptimizedDimensions(int width, int height, int densityDpi) {
        HeadlessWebViewPowerPolicy.RasterSize raster =
                HeadlessWebViewPowerPolicy.capRasterDensity(width, height, densityDpi);
        return new MobileDimensions(raster.width, raster.height, raster.densityDpi);
    }

    static final class MobileDimensions {
        final int width;
        final int height;
        final int densityDpi;
        MobileDimensions(int width, int height, int densityDpi) {
            this.width = width; this.height = height; this.densityDpi = densityDpi;
        }
    }

    private static final class FocusPreservingWebView extends WebView {
        FocusPreservingWebView(Context context) { super(context); }

        @Override public void setWebViewClient(WebViewClient client) {
            super.setWebViewClient(client == null ? null
                    : new WorkProtocolObservingWebViewClient(getContext(), client));
        }

        @Override public void onResume() {
            super.onResume();
            requestFocus();
        }

        @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            if (hasWindowFocus) requestFocus();
        }
    }

    private static final class DisplayDrainState {
        volatile String failure = "";
    }

    static WebView activeWebView() { return activeWebView; }

    static boolean attachOutputFor(WebView view) {
        HeadlessWebViewHost host = activeHost;
        if (host == null || host.webView != view) return false;
        try { return host.attachOutput(); }
        catch (Throwable ignored) { return false; }
    }

    static boolean detachOutputFor(WebView view) {
        HeadlessWebViewHost host = activeHost;
        if (host == null || host.webView != view) return false;
        try { return host.detachOutput(); }
        catch (Throwable ignored) { return false; }
    }

    WebView webView() { return webView; }

    boolean hasDetachableOutput() {
        return virtualDisplay != null && surface != null;
    }

    boolean isOutputAttached() {
        return outputAttached;
    }

    boolean detachOutput() {
        requireMainThread();
        if (!hasDetachableOutput() || !outputAttached) return false;
        virtualDisplay.setSurface(null);
        outputAttached = false;
        return true;
    }

    boolean attachOutput() {
        requireMainThread();
        if (!hasDetachableOutput() || outputAttached) return false;
        virtualDisplay.setSurface(surface);
        outputAttached = true;
        return true;
    }

    String takeDisplayDrainFailure() {
        if (drainState == null) return "";
        String failure = drainState.failure;
        drainState.failure = "";
        return failure;
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("display output changes must run on the main thread");
        }
    }

    boolean clearResourceCacheAfterCompletedRun() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("completed-run cache cleanup must run on the main thread");
        }
        if (completedRunResourceCacheCleared || activeWebView != webView) return false;
        completedRunResourceCacheCleared = true;
        webView.clearCache(true);
        return true;
    }

    void destroy() {
        if (activeHost == this) activeHost = null;
        if (activeWebView == webView) activeWebView = null;
        try {
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
        } catch (Throwable ignored) {}
        if (presentation != null) try { presentation.dismiss(); } catch (Throwable ignored) {}
        if (virtualDisplay != null && outputAttached) {
            try { virtualDisplay.setSurface(null); } catch (Throwable ignored) {}
            outputAttached = false;
        }
        if (virtualDisplay != null) try { virtualDisplay.release(); } catch (Throwable ignored) {}
        if (surface != null) try { surface.release(); } catch (Throwable ignored) {}
        if (imageReader != null) try { imageReader.close(); } catch (Throwable ignored) {}
        if (drainThread != null) try { drainThread.quitSafely(); } catch (Throwable ignored) {}
    }
}
