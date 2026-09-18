package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.webkit.CookieManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SelfRun3WebViewDisposalAndroidTest {
    @Test public void duplicateDestroyLeavesNoActiveHostAndNextTurnGetsFreshHost() {
        Context context=ApplicationProvider.getApplicationContext();
        AtomicReference<HeadlessWebViewHost> first=new AtomicReference<>(), second=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            HeadlessWebViewHost one=HeadlessWebViewHost.create(context);
            first.set(one);
            assertSame(one,HeadlessWebViewHost.activeHost());
            assertNotNull(HeadlessWebViewHost.activeWebView());
            one.destroy();
            one.destroy();
            assertNull(HeadlessWebViewHost.activeHost());
            assertNull(HeadlessWebViewHost.activeWebView());
            HeadlessWebViewHost two=HeadlessWebViewHost.create(context);
            second.set(two);
            assertSame(two,HeadlessWebViewHost.activeHost());
            assertNotNull(HeadlessWebViewHost.activeWebView());
            assertNotSame(first.get(),second.get());
            two.destroy();
            assertNull(HeadlessWebViewHost.activeHost());
            assertNull(HeadlessWebViewHost.activeWebView());
        });
    }

    @Test public void hostDestroyDoesNotClearPersistentCookieStore() {
        Context context=ApplicationProvider.getApplicationContext();
        String origin="https://example.com", name="selfrun_wait_dispose_"+System.nanoTime();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CookieManager cookies=CookieManager.getInstance();
            cookies.setAcceptCookie(true);
            cookies.setCookie(origin,name+"=1");
            cookies.flush();
            assertTrue(String.valueOf(cookies.getCookie(origin)).contains(name+"=1"));
            HeadlessWebViewHost host=HeadlessWebViewHost.create(context);
            host.destroy();
            cookies.flush();
            assertTrue(String.valueOf(cookies.getCookie(origin)).contains(name+"=1"));
            cookies.setCookie(origin,name+"=; Max-Age=0");
            cookies.flush();
        });
    }
}
