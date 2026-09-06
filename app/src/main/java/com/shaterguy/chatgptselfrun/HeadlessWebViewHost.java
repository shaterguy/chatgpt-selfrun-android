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
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONTokener;

/** Private mobile WebView host whose viewport mirrors the visible calibration WebView. */
final class HeadlessWebViewHost {
    private static volatile WebView activeWebView;

    private final WebView webView;
    private final Presentation presentation;
    private final VirtualDisplay virtualDisplay;
    private final Surface surface;
    private final ImageReader imageReader;
    private final HandlerThread drainThread;
    private final DisplayDrainState drainState;
    private final DisplayDiagnostics diagnostics;
    private boolean outputAttached;
    private boolean completedRunResourceCacheCleared;

    private HeadlessWebViewHost(WebView webView, Presentation presentation,
                                VirtualDisplay virtualDisplay, Surface surface,
                                ImageReader imageReader, HandlerThread drainThread,
                                DisplayDrainState drainState, DisplayDiagnostics diagnostics) {
        this.webView = webView;
        this.presentation = presentation;
        this.virtualDisplay = virtualDisplay;
        this.surface = surface;
        this.imageReader = imageReader;
        this.drainThread = drainThread;
        this.drainState = drainState;
        this.diagnostics = diagnostics;
        this.outputAttached = virtualDisplay != null && surface != null;
        if (diagnostics != null) diagnostics.surface("created", outputAttached);
        activeWebView = webView;
    }

    static HeadlessWebViewHost create(Context context) {
        HandlerThread drainThread = null;
        ImageReader imageReader = null;
        Surface surface = null;
        VirtualDisplay display = null;
        Presentation presentation = null;
        DisplayDrainState drainState = new DisplayDrainState();
        DisplayDiagnostics diagnostics = new DisplayDiagnostics(context);
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
            VirtualDisplay.Callback callback = new VirtualDisplay.Callback() {
                @Override public void onPaused() { diagnostics.callback("paused"); }
                @Override public void onResumed() { diagnostics.callback("resumed"); }
                @Override public void onStopped() { diagnostics.callback("stopped"); }
            };
            display = manager.createVirtualDisplay("SelfRunDriveMobile", dimensions.width, dimensions.height,
                    dimensions.densityDpi, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION, callback, diagnostics.handler());
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
                    webView, presentation, display, surface, imageReader, drainThread, drainState, diagnostics);
        } catch (Throwable error) {
            diagnostics.surface("fallback", false);
            if (presentation != null) try { presentation.dismiss(); } catch (Throwable ignored) {}
            if (display != null) try { display.release(); } catch (Throwable ignored) {}
            if (surface != null) try { surface.release(); } catch (Throwable ignored) {}
            if (imageReader != null) try { imageReader.close(); } catch (Throwable ignored) {}
            if (drainThread != null) try { drainThread.quitSafely(); } catch (Throwable ignored) {}
            WebView fallback = new FocusPreservingWebView(context);
            fallback.setFocusable(true);
            fallback.setFocusableInTouchMode(true);
            fallback.requestFocus();
            return new HeadlessWebViewHost(fallback, null, null, null, null, null, null, diagnostics);
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

    private static final class DisplayDiagnostics {
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final SelfRunStore store;
        private final SelfRunRunLog runLog;
        private final String runId;
        private int callbackSequence;
        private int pausedCount;
        private int resumedCount;
        private int stoppedCount;
        private String lastCallback = "none";
        private long lastCallbackElapsed;
        private int attachGeneration;
        private int visualCompletedGeneration;
        private boolean destroyed;

        DisplayDiagnostics(Context context) {
            Context app = context.getApplicationContext();
            store = new SelfRunStore(app);
            runLog = new SelfRunRunLog(app);
            runId = store.runId();
        }

        Handler handler() { return mainHandler; }

        synchronized void callback(String event) {
            if (destroyed) return;
            callbackSequence++;
            lastCallback = event;
            lastCallbackElapsed = SystemClock.elapsedRealtime();
            if ("paused".equals(event)) pausedCount++;
            else if ("resumed".equals(event)) resumedCount++;
            else if ("stopped".equals(event)) stoppedCount++;
            log("DISPLAY_CALLBACK", "event=" + event + ";" + callbackState());
        }

        void surface(String event, boolean attached) {
            log("DISPLAY_SURFACE", "event=" + event + ";attached=" + (attached ? 1 : 0) + ";" + callbackState());
        }

        void afterAttach(WebView webView) {
            if (destroyed || webView == null || !sameRun()) return;
            int generation;
            long started = SystemClock.elapsedRealtime();
            synchronized (this) { generation = ++attachGeneration; }
            log("WEBVIEW_VISUAL_STATE", "result=requested;after_attach_ms=0;" + callbackState());
            try {
                webView.postVisualStateCallback(generation, new WebView.VisualStateCallback() {
                    @Override public void onComplete(long requestId) {
                        if (!active(generation)) return;
                        synchronized (DisplayDiagnostics.this) { visualCompletedGeneration = generation; }
                        log("WEBVIEW_VISUAL_STATE", "result=complete;after_attach_ms="
                                + (SystemClock.elapsedRealtime() - started) + ";" + callbackState());
                    }
                });
            } catch (Throwable error) {
                log("WEBVIEW_VISUAL_STATE", "result=error;kind=" + error.getClass().getSimpleName()
                        + ";" + callbackState());
            }
            scheduleComposerProbe(webView, generation, started, 0L);
            scheduleComposerProbe(webView, generation, started, 250L);
            scheduleComposerProbe(webView, generation, started, 1_000L);
            mainHandler.postDelayed(() -> {
                if (!active(generation)) return;
                boolean complete;
                synchronized (DisplayDiagnostics.this) { complete = visualCompletedGeneration >= generation; }
                if (!complete) log("WEBVIEW_VISUAL_STATE", "result=pending;after_attach_ms="
                        + (SystemClock.elapsedRealtime() - started) + ";" + callbackState());
            }, 1_500L);
        }

        private void scheduleComposerProbe(WebView webView, int generation, long started, long delay) {
            mainHandler.postDelayed(() -> {
                if (!active(generation)) return;
                try {
                    webView.evaluateJavascript(SelfRunContinuationDiagnosticsDom.snapshot(), raw -> {
                        if (!active(generation)) return;
                        String detail = decodeJsString(raw);
                        log("WEBVIEW_CONTINUATION_DIAG", "after_attach_ms="
                                + (SystemClock.elapsedRealtime() - started) + ";" + detail + ";" + callbackState());
                    });
                } catch (Throwable error) {
                    log("WEBVIEW_CONTINUATION_DIAG", "after_attach_ms="
                            + (SystemClock.elapsedRealtime() - started) + ";diag=error;kind="
                            + error.getClass().getSimpleName() + ";" + callbackState());
                }
            }, delay);
        }

        private synchronized String callbackState() {
            long age = lastCallbackElapsed <= 0L ? -1L : Math.max(0L, SystemClock.elapsedRealtime() - lastCallbackElapsed);
            return "callback=" + lastCallback + ";callback_seq=" + callbackSequence
                    + ";callback_age_ms=" + age + ";paused=" + pausedCount
                    + ";resumed=" + resumedCount + ";stopped=" + stoppedCount;
        }

        private synchronized boolean active(int generation) {
            return !destroyed && generation == attachGeneration && sameRun();
        }

        private boolean sameRun() { return !runId.isEmpty() && runId.equals(store.runId()); }

        private void log(String event, String detail) {
            if (!sameRun()) return;
            try { runLog.record(store, event, detail); } catch (Throwable ignored) {}
        }

        synchronized void destroy() {
            destroyed = true;
            attachGeneration++;
        }

        private static String decodeJsString(String raw) {
            if (raw == null || raw.isEmpty()) return "diag=empty";
            try {
                Object value = new JSONTokener(raw).nextValue();
                return value instanceof String ? (String) value : "diag=non_string";
            } catch (Throwable ignored) {
                return "diag=decode_error";
            }
        }
    }

    static WebView activeWebView() { return activeWebView; }

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
        if (diagnostics != null) diagnostics.surface("detach_request", true);
        virtualDisplay.setSurface(null);
        outputAttached = false;
        if (diagnostics != null) diagnostics.surface("detached", false);
        return true;
    }

    boolean attachOutput() {
        requireMainThread();
        if (!hasDetachableOutput() || outputAttached) return false;
        if (diagnostics != null) diagnostics.surface("attach_request", false);
        virtualDisplay.setSurface(surface);
        outputAttached = true;
        if (diagnostics != null) {
            diagnostics.surface("attached", true);
            diagnostics.afterAttach(webView);
        }
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
        if (diagnostics != null) diagnostics.destroy();
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
