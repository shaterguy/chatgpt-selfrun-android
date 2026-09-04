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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Reproduces request-profile lifetime across WebView destruction and recreation. */
@RunWith(AndroidJUnit4.class)
public final class RequestProfileRecreationAndroidTest {
    private static final String BASE_URL = "https://chatgpt.com/g/g-p-test/project";
    private static final String RUN_ID = "SR-PROFILE-RECREATE";

    @Test public void readyChatTargetRestoresAgainstCurrentRegistryAfterRecreation() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = loadFixture(scenario);
            evaluateIgnoringResult(scenario, web, RequestProfileScript.documentStartScript());
            JSONObject first = new JSONObject(read(scenario, web,
                    "(()=>{" + RequestProfileScript.beginTarget("chat", RUN_ID)
                            + RequestProfileScript.setChatReasoning("medium")
                            + "return JSON.stringify(window.__selfRunRequestProfileEngine.target());})()"));
            assertEquals("chat", first.getString("mode"));
            assertEquals("medium", first.getString("reasoning"));
            assertEquals("medium", first.getString("bootstrapReasoning"));
            assertEquals("medium", first.getString("continuationReasoning"));
            assertTrue(first.getBoolean("ready"));

            recreateFixture(scenario, web);
            evaluateIgnoringResult(scenario, web, RequestProfileScript.documentStartScript());
            JSONObject restored = new JSONObject(read(scenario, web,
                    "JSON.stringify(window.__selfRunRequestProfileEngine.target())"));
            JSONObject diagnostics = new JSONObject(read(scenario, web,
                    "JSON.stringify(window.__selfRunRequestProfileEngine.diagnostics())"));
            assertEquals("chat", restored.getString("mode"));
            assertEquals("medium", restored.getString("reasoning"));
            assertEquals("medium", restored.getString("bootstrapReasoning"));
            assertEquals("medium", restored.getString("continuationReasoning"));
            assertTrue(restored.getBoolean("ready"));
            assertEquals("target_restored", diagnostics.getString("reason"));
            read(scenario, web, "(()=>{localStorage.removeItem('selfrun-drive:request-profile-target:v3');return 'cleared';})()");
        }
    }

    @Test public void workRetargetPreservesSelfRunIdentityAcrossRecreation() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = loadFixture(scenario);
            evaluateIgnoringResult(scenario, web, RequestProfileScript.documentStartScript());
            read(scenario, web, "(()=>{" + RequestProfileScript.beginTarget("work", RUN_ID) + "return 'seeded';})()");

            JSONObject modelResult = new JSONObject(read(scenario, web,
                    WorkPreferenceDom.modelForProject(BASE_URL, "luna")));
            assertEquals("READY", modelResult.getString("status"));
            assertEquals(RUN_ID, modelResult.getJSONObject("diagnostics").getString("targetRunId"));

            JSONObject reasoningResult = new JSONObject(read(scenario, web,
                    WorkPreferenceDom.reasoningForProject(BASE_URL, "max")));
            assertEquals("READY", reasoningResult.getString("status"));
            assertEquals(RUN_ID, reasoningResult.getJSONObject("diagnostics").getString("targetRunId"));

            JSONObject target = new JSONObject(read(scenario, web,
                    "JSON.stringify(window.__selfRunRequestProfileEngine.target())"));
            assertEquals("work", target.getString("mode"));
            assertEquals("luna", target.getString("model"));
            assertEquals("max", target.getString("reasoning"));
            assertEquals(RUN_ID, target.getString("runId"));
            assertTrue(target.getBoolean("ready"));

            recreateFixture(scenario, web);
            evaluateIgnoringResult(scenario, web, RequestProfileScript.documentStartScript());
            JSONObject restored = new JSONObject(read(scenario, web,
                    "JSON.stringify(window.__selfRunRequestProfileEngine.target())"));
            assertEquals("work", restored.getString("mode"));
            assertEquals("luna", restored.getString("model"));
            assertEquals("max", restored.getString("reasoning"));
            assertEquals(RUN_ID, restored.getString("runId"));
            assertTrue(restored.getBoolean("ready"));
            read(scenario, web, "(()=>{localStorage.removeItem('selfrun-drive:request-profile-target:v3');return 'cleared';})()");
        }
    }

    private static AtomicReference<WebView> loadFixture(ActivityScenario<SelfRunNewActivity> scenario) throws Exception {
        AtomicReference<WebView> web = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            configure(view, loaded);
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(BASE_URL, "<!doctype html><html><body>profile</body></html>", "text/html", "UTF-8", null);
        });
        assertTrue("initial WebView did not load", loaded.await(15, TimeUnit.SECONDS));
        return web;
    }

    private static void recreateFixture(ActivityScenario<SelfRunNewActivity> scenario,
                                        AtomicReference<WebView> web) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView old = web.get(); if (old != null) old.destroy();
            WebView replacement = new WebView(activity); configure(replacement, loaded);
            activity.setContentView(replacement); web.set(replacement);
            replacement.loadDataWithBaseURL(BASE_URL, "<!doctype html><html><body>profile-recreated</body></html>", "text/html", "UTF-8", null);
        });
        assertTrue("recreated WebView did not load", loaded.await(15, TimeUnit.SECONDS));
    }

    private static void configure(WebView view, CountDownLatch loaded) {
        view.getSettings().setJavaScriptEnabled(true); view.getSettings().setDomStorageEnabled(true);
        view.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView ignored, String url) { loaded.countDown(); }
        });
    }

    private static void evaluateIgnoringResult(ActivityScenario<SelfRunNewActivity> scenario,
                                               AtomicReference<WebView> web, String script) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        scenario.onActivity(activity -> web.get().evaluateJavascript(script, ignored -> complete.countDown()));
        assertTrue("WebView script timed out", complete.await(15, TimeUnit.SECONDS));
    }

    private static String read(ActivityScenario<SelfRunNewActivity> scenario,
                               AtomicReference<WebView> web, String expression) throws Exception {
        CountDownLatch complete = new CountDownLatch(1); AtomicReference<String> raw = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(expression, value -> { raw.set(value); complete.countDown(); }));
        assertTrue("WebView read timed out", complete.await(15, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(raw.get()).nextValue(); return String.valueOf(decoded);
    }
}
