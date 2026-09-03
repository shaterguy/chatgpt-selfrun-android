package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Adds Work-only native request observation and bounded completion recovery without replacing delegate behavior. */
final class WorkProtocolObservingWebViewClient extends WebViewClient {
    private final Context context;
    private final WebViewClient delegate;

    WorkProtocolObservingWebViewClient(Context context, WebViewClient delegate) {
        this.context = context.getApplicationContext();
        this.delegate = delegate;
    }

    @Override public WebResourceResponse shouldInterceptRequest(
            WebView view, WebResourceRequest request) {
        WorkProtocolNativeObserver.observeWebViewRequest(context, view, request);
        return delegate.shouldInterceptRequest(view, request);
    }

    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (request != null && request.isForMainFrame()
                && TurnCompletionRecoveryCoordinator.handleNavigation(context, view, request.getUrl())) return true;
        if (request != null) WorkProtocolCoverageTracker.observeCompletionNavigation(context, request.getUrl());
        return delegate.shouldOverrideUrlLoading(view, request);
    }

    @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
        delegate.onPageStarted(view, url, favicon);
    }

    @Override public void onPageFinished(WebView view, String url) {
        delegate.onPageFinished(view, url);
    }

    @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        delegate.doUpdateVisitedHistory(view, url, isReload);
    }

    @Override public void onReceivedHttpError(
            WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
        delegate.onReceivedHttpError(view, request, errorResponse);
    }

    @Override public void onReceivedError(
            WebView view, WebResourceRequest request, WebResourceError error) {
        delegate.onReceivedError(view, request, error);
    }

    @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
        delegate.onReceivedSslError(view, handler, error);
    }

    @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        return delegate.onRenderProcessGone(view, detail);
    }
}
