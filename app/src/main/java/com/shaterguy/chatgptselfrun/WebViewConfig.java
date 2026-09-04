package com.shaterguy.chatgptselfrun;

import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

final class WebViewConfig {
    private WebViewConfig() {}

    /** Shared by the visible calibration WebView and the background automation WebView. */
    @SuppressWarnings("SetJavaScriptEnabled")
    static boolean applyAutomation(WebView webView) {
        ChatReasoningPreferenceStore.initialize(webView.getContext());
        WebSettings settings = common(webView);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setOffscreenPreRaster(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setVerticalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        String current = settings.getUserAgentString();
        String marker = "SelfRunV2/" + BuildConfig.VERSION_NAME;
        if (current != null && !current.contains(marker)) settings.setUserAgentString(current + " " + marker);
        webView.setInitialScale(100);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        boolean protocolAvailable = TurnProtocolLogBridge.install(webView);
        if (!protocolAvailable) {
            WorkProtocolNativeObserver.recordEnvironmentIfWork(webView.getContext());
            return false;
        }
        RequestProfileScript.installDocumentStart(webView);
        HybridRequestProfileScript.installDocumentStart(webView);
        ChatGptTurnProtocolScript.installDocumentStart(webView);
        WorkTurnProtocolIngressScript.installDocumentStart(webView);
        WorkProtocolTransportCaptureScript.installDocumentStart(webView);
        WorkProtocolNativeObserver.recordEnvironmentIfWork(webView.getContext());
        return true;
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
