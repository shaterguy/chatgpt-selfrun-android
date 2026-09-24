package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class SelfRun3ProjectDirectoryNavigationWebViewTest {
    private static final String PROJECT = "g-p-6a507cce80cc81919eeb9ba553b6ad9e";
    private static final String TARGET = "https://chatgpt.com/g/" + PROJECT + "/project";

    @Test public void directProjectRouteRemainsTheWebViewEntryContext() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> pathname = new AtomicReference<>("");
        AtomicReference<String> href = new AtomicReference<>("");
        AtomicReference<HeadlessWebViewHost> hostRef = new AtomicReference<>();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            HeadlessWebViewHost host = HeadlessWebViewHost.create(context);
            hostRef.set(host);
            host.attachOutput();
            WebView webView = host.webView();
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) {
                    view.evaluateJavascript("location.pathname", pathRaw -> {
                        pathname.set(decodeJsString(pathRaw));
                        view.evaluateJavascript("location.href", hrefRaw -> {
                            href.set(decodeJsString(hrefRaw));
                            done.countDown();
                        });
                    });
                }
            });
            webView.loadDataWithBaseURL(
                    TARGET,
                    "<!doctype html><html><body><textarea></textarea></body></html>",
                    "text/html",
                    "UTF-8",
                    TARGET);
        });

        assertTrue("Direct project route WebView scenario timed out",
                done.await(12, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            HeadlessWebViewHost host = hostRef.get();
            if (host != null) host.destroy();
        });

        assertEquals("/g/" + PROJECT + "/project", pathname.get());
        assertTrue(ProjectUrlPolicy.sameProject(TARGET, href.get()));
        assertFalse(pathname.get().equals("/projects"));
    }

    private static String decodeJsString(String raw) {
        try {
            Object decoded = new JSONTokener(raw == null ? "\"\"" : raw).nextValue();
            return decoded instanceof String ? (String) decoded : String.valueOf(decoded);
        } catch (Exception ignored) {
            return "";
        }
    }
}
