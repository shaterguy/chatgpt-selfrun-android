package com.shaterguy.chatgptselfrun;

import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

final class WebViewConfig {
    static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36 ChatGPTSelfRun/0.1.0";

    private WebViewConfig() {}

    @SuppressWarnings("SetJavaScriptEnabled")
    static void apply(WebView webView, boolean allowThirdPartyCookies) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(DESKTOP_UA);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        webView.setInitialScale(100);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, allowThirdPartyCookies);
    }
}
