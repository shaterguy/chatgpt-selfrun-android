package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

final class WebViewConfig {
    private WebViewConfig() {}

    static final class AutomationPlan {
        final boolean requestProfile;
        final boolean hybridProfile;
        final boolean domFallback;
        final boolean chatProtocol;
        final boolean workIngress;
        final boolean workTransport;

        AutomationPlan(boolean requestProfile, boolean hybridProfile, boolean domFallback,
                       boolean chatProtocol, boolean workIngress, boolean workTransport) {
            this.requestProfile = requestProfile;
            this.hybridProfile = hybridProfile;
            this.domFallback = domFallback;
            this.chatProtocol = chatProtocol;
            this.workIngress = workIngress;
            this.workTransport = workTransport;
        }

        int documentStartScriptCount() {
            int count = requestProfile ? 1 : 0;
            if (hybridProfile) count++;
            if (domFallback) count++;
            if (chatProtocol) count++;
            if (workIngress) count++;
            if (workTransport) count++;
            return count;
        }
    }

    static AutomationPlan automationPlan(String mode, boolean hybridValid,
                                         boolean hybridUsesWork, boolean protocolObservable) {
        boolean hybrid = HybridRunProfileStore.MODE_HYBRID.equals(mode) && hybridValid;
        boolean work = SelfRunStore.MODE_WORK.equals(mode) || (hybrid && hybridUsesWork);
        return new AutomationPlan(true, hybrid, true, protocolObservable,
                protocolObservable && work, protocolObservable && work);
    }

    /** Background SelfRun WebView: install only the engines required by the active run profile. */
    @SuppressWarnings("SetJavaScriptEnabled")
    static void applyAutomation(WebView webView) {
        ChatReasoningPreferenceStore.initialize(webView.getContext());
        configureMobileAutomationSurface(webView, false);

        Context rawContext = webView.getContext();
        if (rawContext instanceof ProfileRegistryActivity) {
            RequestProfileScript.installDocumentStart(webView);
            return;
        }
        if (rawContext instanceof WebUiCalibrationActivity) {
            return;
        }

        Context context = rawContext.getApplicationContext();
        if (context == null) context = rawContext;
        SelfRunStore store = new SelfRunStore(context);
        HybridRunProfileStore.initialize(context);
        HybridRunProfileStore.Selection selection = HybridRunProfileStore.currentSelection();
        boolean hybridValid = selection != null && selection.valid();
        boolean hybridUsesWork = hybridValid
                && (selection.bootstrap.isWork() || selection.continuation.isWork());
        boolean protocolObservable = TurnProtocolLogBridge.install(webView);
        AutomationPlan plan = automationPlan(store.mode(), hybridValid, hybridUsesWork, protocolObservable);

        if (plan.requestProfile) RequestProfileScript.installDocumentStart(webView);
        if (plan.hybridProfile) HybridRequestProfileScript.installDocumentStart(webView);
        if (plan.domFallback) TurnCompletionDomFallbackScript.installDocumentStart(webView);
        if (protocolObservable) {
            if (plan.chatProtocol) ChatGptTurnProtocolScript.installDocumentStart(webView);
            if (plan.workIngress) WorkTurnProtocolIngressScript.installDocumentStart(webView);
            if (plan.workTransport) WorkProtocolTransportCaptureScript.installDocumentStart(webView);
        }
        if (plan.workIngress) WorkProtocolNativeObserver.recordEnvironmentIfWork(context);
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

    private static void configureMobileAutomationSurface(WebView webView, boolean thirdPartyCookies) {
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, thirdPartyCookies);
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
