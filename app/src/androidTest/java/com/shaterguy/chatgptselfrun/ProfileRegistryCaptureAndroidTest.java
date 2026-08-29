package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ProfileRegistryCaptureAndroidTest {
    private static final String BASE_URL = "https://chatgpt.com/";

    @Test public void menuClickDoesNotCaptureButFirstRealFetchSubmissionDoesAndIsUnmodified() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = loadFixture(scenario);
            eval(scenario, web, "window.__nativeBodies=[];window.fetch=async function(input,init){let text='';if(input instanceof Request){text=await input.clone().text();}else{text=String(init?.body??'');}window.__nativeBodies.push(text);return {ok:true};};'stubbed';");
            eval(scenario, web, RequestProfileScript.documentStartScript());
            eval(scenario, web, RequestProfileScript.armCapture("work"));
            eval(scenario, web, "document.body.click();'clicked';");
            assertEquals("null", read(scenario, web, RequestProfileScript.consumeCapture()));

            String body = "{\\\"messages\\\":[{\\\"author\\\":\\\"user\\\",\\\"content\\\":\\\"SECRET_PROMPT\\\"}],"
                    + "\\\"conversation_id\\\":\\\"SECRET_CONVERSATION\\\","
                    + "\\\"model\\\":\\\"gpt-5.7-nova-wm\\\",\\\"thinking_effort\\\":\\\"extreme\\\","
                    + "\\\"conversation_origin\\\":\\\"tpp\\\",\\\"service_tier\\\":\\\"standard\\\"}";
            eval(scenario, web, "window.__captureDone=false;fetch('https://chatgpt.com/backend-api/conversation',{method:'POST',headers:{'content-type':'application/json'},body:\"" + body + "\"}).then(()=>window.__captureDone=true);'started';");
            waitForTrue(scenario, web, "window.__captureDone===true");

            String capturedRaw = read(scenario, web, RequestProfileScript.consumeCapture());
            JSONObject captured = new JSONObject(capturedRaw);
            assertEquals("work", captured.getString("mode"));
            assertEquals(4, captured.getJSONArray("operations").length());
            assertFalse(capturedRaw.contains("SECRET_PROMPT"));
            assertFalse(capturedRaw.contains("SECRET_CONVERSATION"));
            assertFalse(capturedRaw.contains("messages"));
            String nativeBody = read(scenario, web, "JSON.stringify(window.__nativeBodies[0])");
            assertTrue(nativeBody.contains("SECRET_PROMPT"));
            assertTrue(nativeBody.contains("SECRET_CONVERSATION"));

            eval(scenario, web, RequestProfileScript.beginTarget("work", "capture-test"));
            eval(scenario, web, RequestProfileScript.setWorkModel("sol"));
            eval(scenario, web, RequestProfileScript.setWorkReasoning("max"));
            eval(scenario, web, "window.__captureDone=false;fetch('https://chatgpt.com/backend-api/conversation',{method:'POST',headers:{'content-type':'application/json'},body:'{\\\"messages\\\":[],\\\"model\\\":\\\"native-other\\\"}'}).then(()=>window.__captureDone=true);'started';");
            waitForTrue(scenario, web, "window.__captureDone===true");
            assertEquals("null", read(scenario, web, RequestProfileScript.consumeCapture()));
            String patchedBody = read(scenario, web, "JSON.stringify(window.__nativeBodies[1])");
            assertTrue(patchedBody.contains("gpt-5.6-sol-wm"));
            assertTrue(patchedBody.contains("max"));
        }
    }

    private static AtomicReference<WebView> loadFixture(ActivityScenario<SelfRunNewActivity> scenario) throws Exception {
        AtomicReference<WebView> web = new AtomicReference<>(); CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity); view.getSettings().setJavaScriptEnabled(true); view.getSettings().setDomStorageEnabled(true);
            view.setWebViewClient(new WebViewClient() { @Override public void onPageFinished(WebView ignored, String url) { loaded.countDown(); } });
            activity.setContentView(view); web.set(view);
            view.loadDataWithBaseURL(BASE_URL, "<!doctype html><html><body><button>model menu</button></body></html>", "text/html", "UTF-8", null);
        });
        assertTrue(loaded.await(15, TimeUnit.SECONDS)); return web;
    }

    private static void waitForTrue(ActivityScenario<SelfRunNewActivity> scenario,
                                    AtomicReference<WebView> web, String expression) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            if ("true".equals(read(scenario, web, expression))) return;
            Thread.sleep(50L);
        }
        fail("condition timed out: " + expression);
    }

    private static void eval(ActivityScenario<SelfRunNewActivity> scenario,
                             AtomicReference<WebView> web, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        scenario.onActivity(activity -> web.get().evaluateJavascript(script, ignored -> done.countDown()));
        assertTrue(done.await(15, TimeUnit.SECONDS));
    }

    private static String read(ActivityScenario<SelfRunNewActivity> scenario,
                               AtomicReference<WebView> web, String expression) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> raw = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(expression, value -> { raw.set(value); done.countDown(); }));
        assertTrue(done.await(15, TimeUnit.SECONDS)); Object decoded = new JSONTokener(raw.get()).nextValue(); return String.valueOf(decoded);
    }
}
