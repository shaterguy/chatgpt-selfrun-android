package com.shaterguy.chatgptselfrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Regression for same-session headless WebView lifecycle power optimization. */
@RunWith(AndroidJUnit4.class)
public final class HeadlessWebViewPowerAndroidTest {
    private static final String BASE_URL =
            "https://chatgpt.com/g/g-p-6a582c824ba08191ac7e74e9bad721fc-vibe-coding/project";

    @Test public void observerHealthcheckPausesSameWebViewWithoutStoppingJavascript() throws Exception {
        AtomicReference<HeadlessWebViewHost> host = new AtomicReference<>();
        AtomicReference<WebView> web = new AtomicReference<>();
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            CountDownLatch loaded = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                HeadlessWebViewHost created = HeadlessWebViewHost.create(activity);
                WebView view = created.webView();
                view.getSettings().setJavaScriptEnabled(true);
                view.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView ignored, String url) {
                        loaded.countDown();
                    }
                });
                host.set(created);
                web.set(view);
                view.loadDataWithBaseURL(BASE_URL,
                        "<html><body data-ready='1'>power probe</body></html>",
                        "text/html", "UTF-8", null);
            });
            assertTrue("Headless WebView fixture did not load", loaded.await(15, TimeUnit.SECONDS));

            WebView identity = web.get();
            String armed = evaluate(scenario, identity, healthcheckScript());
            assertTrue(armed.contains("OBSERVER_ARMED"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue("Observer healthcheck must lifecycle-pause the same WebView",
                    lifecyclePaused(identity));

            assertEquals(BASE_URL + "#observer-hit-1",
                    awaitUrlWithoutJavascript(scenario, identity, BASE_URL + "#observer-hit-1"));
            assertTrue("Reading the URL must not resume the WebView", lifecyclePaused(identity));

            assertEquals("42", evaluate(scenario, identity, "String(40+2)"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertFalse("Active automation must resume before JavaScript evaluation",
                    lifecyclePaused(identity));
            assertSame("Power optimization must retain the exact WebView instance",
                    identity, host.get().webView());
        } finally {
            HeadlessWebViewHost created = host.get();
            if (created != null) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(created::destroy);
            }
        }
    }

    private static String healthcheckScript() {
        return "(() =>{"
                + "window.__selfRunPowerObserverHits=0;"
                + "new MutationObserver(()=>{window.__selfRunPowerObserverHits++;})"
                + ".observe(document.body,{attributes:true});"
                + "setTimeout(()=>document.body.setAttribute('data-power-probe','1'),100);"
                + "setTimeout(()=>{location.hash='observer-hit-'+window.__selfRunPowerObserverHits;},300);"
                + "const armCompletionObserver=()=>JSON.stringify({status:'OBSERVER_ARMED'});"
                + "return armCompletionObserver(false);"
                + "})()";
    }

    private static String evaluate(ActivityScenario<SelfRunNewActivity> scenario,
                                   WebView webView, String script) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<String> raw = new AtomicReference<>();
        scenario.onActivity(activity -> webView.evaluateJavascript(script, value -> {
            raw.set(value);
            complete.countDown();
        }));
        assertTrue("Headless WebView JavaScript timed out", complete.await(15, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(raw.get()).nextValue();
        return String.valueOf(decoded);
    }

    private static String awaitUrlWithoutJavascript(ActivityScenario<SelfRunNewActivity> scenario,
                                                     WebView webView, String expected) throws Exception {
        AtomicReference<String> current = new AtomicReference<>("");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            scenario.onActivity(activity -> current.set(webView.getUrl()));
            if (expected.equals(current.get())) return current.get();
            Thread.sleep(100L);
        }
        return current.get();
    }

    private static boolean lifecyclePaused(WebView webView) throws Exception {
        Field field = webView.getClass().getDeclaredField("automationLifecyclePaused");
        field.setAccessible(true);
        return field.getBoolean(webView);
    }
}
