package com.shaterguy.chatgptselfrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CompletedRunCacheCleanupWebViewTest {
    @Test public void completedRunCacheCleanupIsIdempotentAndPreservesSessionState() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences drive = context.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE);
        String priorAccount = drive.getString("driveAccountId", null);
        boolean priorContains = drive.contains("driveAccountId");
        assertTrue(drive.edit().putString("driveAccountId", "cleanup-test-drive-account").commit());

        final Throwable[] failure = new Throwable[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CookieManager cookies = CookieManager.getInstance();
            HeadlessWebViewHost first = null;
            HeadlessWebViewHost second = null;
            try {
                cookies.setAcceptCookie(true);
                cookies.setCookie("https://chatgpt.com", "SelfRunCleanupProbe=keep; Path=/; SameSite=Lax");
                cookies.flush();
                first = HeadlessWebViewHost.create(context);
                assertSame(first.webView(), HeadlessWebViewHost.activeWebView());
                assertTrue(first.clearResourceCacheAfterCompletedRun());
                assertFalse(first.clearResourceCacheAfterCompletedRun());
                String cookieValue = cookies.getCookie("https://chatgpt.com");
                assertNotNull(cookieValue);
                assertTrue(cookieValue.contains("SelfRunCleanupProbe=keep"));
                assertEquals("cleanup-test-drive-account", drive.getString("driveAccountId", ""));
                first.destroy();
                first = null;
                assertNull(HeadlessWebViewHost.activeWebView());

                second = HeadlessWebViewHost.create(context);
                assertSame(second.webView(), HeadlessWebViewHost.activeWebView());
                String cookieAfterRecreate = cookies.getCookie("https://chatgpt.com");
                assertNotNull(cookieAfterRecreate);
                assertTrue(cookieAfterRecreate.contains("SelfRunCleanupProbe=keep"));
                assertEquals("cleanup-test-drive-account", drive.getString("driveAccountId", ""));
            } catch (Throwable error) {
                failure[0] = error;
            } finally {
                if (first != null) first.destroy();
                if (second != null) second.destroy();
                try {
                    cookies.setCookie("https://chatgpt.com", "SelfRunCleanupProbe=; Max-Age=0; Path=/");
                    cookies.flush();
                } catch (Throwable ignored) {}
            }
        });

        if (priorContains) assertTrue(drive.edit().putString("driveAccountId", priorAccount == null ? "" : priorAccount).commit());
        else assertTrue(drive.edit().remove("driveAccountId").commit());
        if (failure[0] != null) throw new AssertionError("completed-run cache cleanup instrumentation failed", failure[0]);
    }
}
