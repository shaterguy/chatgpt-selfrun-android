package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.WebViewFeature;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SelfRunParentGuardXhrLivenessInstrumentedTest {
    private WebView webView;

    @After public void tearDown() {
        if (webView == null) return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { webView.destroy(); } catch (Throwable ignored) {}
        });
    }

    @Test public void xhrPrototypeReplacementMakesGuardLivenessFalse() throws Exception {
        assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));
        Context context = ApplicationProvider.getApplicationContext();
        CountDownLatch loaded = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            webView = new WebView(context);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            WebViewConfig.applyAutomation(webView);
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) { loaded.countDown(); }
            });
            webView.loadDataWithBaseURL(
                    "https://chatgpt.com/g/g-p-test/c/conversation123",
                    "<!doctype html><html><body>test</body></html>",
                    "text/html", "UTF-8", null);
        });
        assertTrue(loaded.await(15, TimeUnit.SECONDS));
        assertTrue(eval("window." + SelfRunNetworkGuard.LIVENESS_FN + "()===true").contains("true"));
        assertTrue(eval("(()=>{XMLHttpRequest.prototype.send=function(){};return window."
                + SelfRunNetworkGuard.LIVENESS_FN + "()===false})()").contains("true"));
    }

    private String eval(String script) throws Exception {
        AtomicReference<String> value = new AtomicReference<>("");
        CountDownLatch done = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                webView.evaluateJavascript(script, raw -> {
                    value.set(raw == null ? "" : raw);
                    done.countDown();
                }));
        assertTrue(done.await(10, TimeUnit.SECONDS));
        return value.get();
    }
}
