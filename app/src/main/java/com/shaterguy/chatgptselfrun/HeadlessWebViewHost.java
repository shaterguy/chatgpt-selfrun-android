package com.shaterguy.chatgptselfrun;

import android.app.Presentation;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
import android.widget.FrameLayout;

/** Private mobile WebView host whose viewport mirrors the visible calibration WebView. */
final class HeadlessWebViewHost {
    private final WebView webView;
    private final Presentation presentation;
    private final VirtualDisplay virtualDisplay;
    private final Surface surface;
    private final SurfaceTexture texture;

    private HeadlessWebViewHost(WebView webView, Presentation presentation,
                                VirtualDisplay virtualDisplay, Surface surface, SurfaceTexture texture) {
        this.webView = webView;
        this.presentation = presentation;
        this.virtualDisplay = virtualDisplay;
        this.surface = surface;
        this.texture = texture;
    }

    static HeadlessWebViewHost create(Context context) {
        SurfaceTexture texture = null;
        Surface surface = null;
        VirtualDisplay display = null;
        Presentation presentation = null;
        MobileDimensions dimensions = dimensions(context);
        try {
            texture = new SurfaceTexture(false);
            texture.setDefaultBufferSize(dimensions.width, dimensions.height);
            surface = new Surface(texture);
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
            return new HeadlessWebViewHost(webView, presentation, display, surface, texture);
        } catch (Throwable error) {
            if (presentation != null) try { presentation.dismiss(); } catch (Throwable ignored) {}
            if (display != null) try { display.release(); } catch (Throwable ignored) {}
            if (surface != null) try { surface.release(); } catch (Throwable ignored) {}
            if (texture != null) try { texture.release(); } catch (Throwable ignored) {}
            WebView fallback = new FocusPreservingWebView(context);
            fallback.setFocusable(true);
            fallback.setFocusableInTouchMode(true);
            fallback.requestFocus();
            return new HeadlessWebViewHost(fallback, null, null, null, null);
        }
    }

    private static MobileDimensions dimensions(Context context) {
        WebUiCalibrationStore.Viewport viewport = new WebUiCalibrationStore(context).viewport();
        if (viewport != null) {
            return new MobileDimensions(viewport.pixelWidth(), viewport.pixelHeight(), viewport.densityDpi());
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int shorter = Math.max(320, Math.min(metrics.widthPixels, metrics.heightPixels));
        int longer = Math.max(480, Math.max(metrics.widthPixels, metrics.heightPixels));
        int density = Math.max(120, Math.min(640, metrics.densityDpi));
        return new MobileDimensions(shorter, longer, density);
    }

    private static final class MobileDimensions {
        final int width;
        final int height;
        final int densityDpi;
        MobileDimensions(int width, int height, int densityDpi) {
            this.width = width; this.height = height; this.densityDpi = densityDpi;
        }
    }

    private static final class FocusPreservingWebView extends WebView {
        FocusPreservingWebView(Context context) { super(context); }

        @Override public void onResume() {
            super.onResume();
            requestFocus();
        }

        @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            if (hasWindowFocus) requestFocus();
        }
    }

    WebView webView() { return webView; }

    void destroy() {
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
        if (virtualDisplay != null) try { virtualDisplay.release(); } catch (Throwable ignored) {}
        if (surface != null) try { surface.release(); } catch (Throwable ignored) {}
        if (texture != null) try { texture.release(); } catch (Throwable ignored) {}
    }
}
