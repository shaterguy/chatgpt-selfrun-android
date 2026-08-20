package com.shaterguy.chatgptselfrun;

import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

final class WebViewConfig {
    private WebViewConfig() {}

    /** Shared browser settings; the scale follows the WebView's actual display density. */
    @SuppressWarnings("SetJavaScriptEnabled")
    static void applyAutomation(WebView webView) {
        WebSettings settings = common(webView);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        String current = settings.getUserAgentString();
        String marker = "SelfRunDrive/" + BuildConfig.VERSION_NAME;
        if (current != null && !current.contains(marker)) settings.setUserAgentString(current + " " + marker);
        int densityDpi = webView.getResources().getDisplayMetrics().densityDpi;
        webView.setInitialScale(AutomationViewportPolicy.initialScaleForDensityDpi(densityDpi));
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
