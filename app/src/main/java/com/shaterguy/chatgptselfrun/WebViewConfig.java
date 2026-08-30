package com.shaterguy.chatgptselfrun;

import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebViewRenderProcess;
import androidx.webkit.WebViewRenderProcessClient;

final class WebViewConfig {
    private static final int RENDERER_UNRESPONSIVE_LIMIT = 3;
    private static final long RENDERER_UNRESPONSIVE_WINDOW_MS = 60_000L;

    private WebViewConfig() {}

    /** Shared by the visible calibration WebView and the background automation WebView. */
    @SuppressWarnings("SetJavaScriptEnabled")
    static void applyAutomation(WebView webView) {
        ChatReasoningPreferenceStore.initialize(webView.getContext());
        WebSettings settings = common(webView);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        String current = settings.getUserAgentString();
        String marker = "SelfRunV2/" + BuildConfig.VERSION_NAME;
        if (current != null && !current.contains(marker)) settings.setUserAgentString(current + " " + marker);
        webView.setInitialScale(100);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        RequestProfileScript.installDocumentStart(webView);
        installBackgroundRendererWatchdog(webView);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    static void applyLogin(WebView webView) {
        WebSettings settings = common(webView);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
    }

    private static void installBackgroundRendererWatchdog(WebView webView) {
        if (HeadlessWebViewHost.activeWebView() != webView
                || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE)) {
            return;
        }
        WebViewCompat.setWebViewRenderProcessClient(webView, new WebViewRenderProcessClient() {
            private int consecutiveUnresponsive;
            private long firstUnresponsiveElapsed;

            @Override public void onRenderProcessResponsive(WebView view, WebViewRenderProcess renderer) {
                consecutiveUnresponsive = 0;
                firstUnresponsiveElapsed = 0L;
            }

            @Override public void onRenderProcessUnresponsive(WebView view, WebViewRenderProcess renderer) {
                long now = SystemClock.elapsedRealtime();
                if (firstUnresponsiveElapsed <= 0L
                        || now < firstUnresponsiveElapsed
                        || now - firstUnresponsiveElapsed > RENDERER_UNRESPONSIVE_WINDOW_MS) {
                    firstUnresponsiveElapsed = now;
                    consecutiveUnresponsive = 1;
                } else {
                    consecutiveUnresponsive++;
                }
                if (consecutiveUnresponsive < RENDERER_UNRESPONSIVE_LIMIT
                        || SelfRunScript.conversationId(view.getUrl()).isEmpty()) {
                    return;
                }
                consecutiveUnresponsive = 0;
                firstUnresponsiveElapsed = 0L;
                if (renderer != null
                        && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_TERMINATE)
                        && renderer.terminate()) {
                    return;
                }
                view.post(view::reload);
            }
        });
    }

    private static WebSettings common(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        return settings;
    }
}
