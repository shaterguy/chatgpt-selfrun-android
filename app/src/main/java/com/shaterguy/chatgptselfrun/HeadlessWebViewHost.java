package com.shaterguy.chatgptselfrun;

import android.app.Presentation;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.Surface;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
import android.widget.FrameLayout;

/** Private automation WebView host fixed to a 390x844 mdpi mobile portrait display. */
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
        WebView webView = null;
        try {
            WebUiCalibrationStore calibration = new WebUiCalibrationStore(context);
            if ("재보정 필요".equals(AutomationViewportPolicy.profileStatus(calibration.profile()))) {
                calibration.record("SYSTEM", "RECALIBRATION_REQUIRED",
                        "automation=" + AutomationViewportPolicy.runtimeContract());
            }

            texture = new SurfaceTexture(false);
            texture.setDefaultBufferSize(AutomationViewportPolicy.WIDTH_PX,
                    AutomationViewportPolicy.HEIGHT_PX);
            surface = new Surface(texture);
            DisplayManager manager = context.getSystemService(DisplayManager.class);
            if (manager == null) throw new IllegalStateException("display manager unavailable");
            display = manager.createVirtualDisplay("SelfRunDriveMobile",
                    AutomationViewportPolicy.WIDTH_PX,
                    AutomationViewportPolicy.HEIGHT_PX,
                    AutomationViewportPolicy.DENSITY_DPI,
                    surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
            if (display == null || display.getDisplay() == null) {
                throw new IllegalStateException("virtual display unavailable");
            }
            presentation = new Presentation(context, display.getDisplay(),
                    android.R.style.Theme_DeviceDefault_NoActionBar);
            FrameLayout root = new FrameLayout(presentation.getContext());
            webView = new FocusPreservingWebView(presentation.getContext());
            webView.setFocusable(true);
            webView.setFocusableInTouchMode(true);
            root.addView(webView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            presentation.setContentView(root);
            presentation.show();
            Window window = presentation.getWindow();
            if (window != null) {
                window.setLayout(AutomationViewportPolicy.WIDTH_PX,
                        AutomationViewportPolicy.HEIGHT_PX);
            }
            webView.requestFocus();
            return new HeadlessWebViewHost(webView, presentation, display, surface, texture);
        } catch (Throwable error) {
            if (presentation != null) try { presentation.dismiss(); } catch (Throwable ignored) {}
            if (webView != null) try { webView.destroy(); } catch (Throwable ignored) {}
            if (display != null) try { display.release(); } catch (Throwable ignored) {}
            if (surface != null) try { surface.release(); } catch (Throwable ignored) {}
            if (texture != null) try { texture.release(); } catch (Throwable ignored) {}
            throw new IllegalStateException("fixed mobile virtual display unavailable", error);
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
