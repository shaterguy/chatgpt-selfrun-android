package com.shaterguy.chatgptselfrun;

import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

final class WebViewConfig {
    private WebViewConfig() {}

    @SuppressWarnings("SetJavaScriptEnabled")
    static void applyAutomation(WebView webView) {
        WebSettings settings = common(webView);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        String current = settings.getUserAgentString();
        if (current != null && !current.contains("ChatGPTSelfRun/0.2.3-dev2")) {
            settings.setUserAgentString(current + " ChatGPTSelfRun/0.2.3-dev2");
        }
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        webView.setInitialScale(100);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    static void applyLogin(WebView webView) {
        WebSettings settings = common(webView);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
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
