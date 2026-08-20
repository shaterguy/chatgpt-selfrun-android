package com.shaterguy.chatgptselfrun;

import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

final class WebViewConfig {
    private WebViewConfig() {}

    /** Fixed mdpi profile for the private background automation WebView. */
    @SuppressWarnings("SetJavaScriptEnabled")
    static void applyAutomation(WebView webView) {
        applyAutomationSettings(webView);
        // The headless host itself is 160 dpi, so 100% preserves its fixed mdpi contract.
        webView.setInitialScale(100);
    }

    /** Visible calibration keeps the same browser settings but lets WebView honor device density. */
    @SuppressWarnings("SetJavaScriptEnabled")
    static void applyCalibration(WebView webView) {
        applyAutomationSettings(webView);
        // 0 selects WebView's density-aware default scale. With wide viewport disabled,
        // the visible Activity is exposed to the page in device-independent CSS pixels.
        webView.setInitialScale(0);
    }

    private static void applyAutomationSettings(WebView webView) {
        WebSettings settings = common(webView);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        String current = settings.getUserAgentString();
        String marker = "SelfRunDrive/" + BuildConfig.VERSION_NAME;
        if (current != null && !current.contains(marker)) settings.setUserAgentString(current + " " + marker);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
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
