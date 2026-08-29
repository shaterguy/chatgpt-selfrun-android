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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Keeps the still-supported new-chat and bootstrap-state WebView regressions. */
@RunWith(AndroidJUnit4.class)
public final class ChatReasoningDelayedDomWebViewTest {
    private static final String PROJECT_URL = "https://chatgpt.com/g/g-p-test";

    @Test public void newChatTransitionWaitsForAcknowledgementBeforeFiniteFailure() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, newChatTransitionFixture());
            assertEquals("/c/fixture", read(scenario, web,
                    "(()=>{history.replaceState(null,'','/c/fixture');return location.pathname;})()"));
            String script = SelfRunDom.prepareInitialContext(SelfRunScript.GENERAL_CHAT_URL,
                    SelfRunStore.MODE_CHAT, "SR-NEW-CHAT-WINDOW");

            JSONObject first = evaluate(scenario, web, script);
            assertEquals("UI_WAIT", first.getString("status"));
            assertEquals("1", read(scenario, web, "String(window.newChatClicks)"));

            setNow(scenario, web, 2_200L);
            JSONObject protectedWait = evaluate(scenario, web, script);
            assertEquals("UI_WAIT", protectedWait.getString("status"));
            assertEquals("1", read(scenario, web, "String(window.newChatClicks)"));

            setNow(scenario, web, 3_000L);
            JSONObject secondClick = evaluate(scenario, web, script);
            assertEquals("UI_WAIT", secondClick.getString("status"));
            assertEquals("2", read(scenario, web, "String(window.newChatClicks)"));

            setNow(scenario, web, 5_200L);
            assertEquals("UI_WAIT", evaluate(scenario, web, script).getString("status"));

            setNow(scenario, web, 5_600L);
            JSONObject finiteFailure = evaluate(scenario, web, script);
            assertEquals("CHAT_BOOTSTRAP_NEW_CHAT_FAILED", finiteFailure.getString("status"));
            assertEquals(2, finiteFailure.getJSONObject("diagnostics").getInt("newChatClicks"));
        }
    }

    @Test public void bootstrapResultPolicyParsesAndClassifiesOnAndroid() {
        BootstrapResultPolicy.Parsed valid = BootstrapResultPolicy.parse(
                "\"{\\\"status\\\":\\\"UI_WAIT\\\",\\\"detail\\\":\\\"mode\\\"}\"");
        assertTrue(valid.valid);
        assertEquals("UI_WAIT", valid.status);
        assertEquals("", BootstrapResultPolicy.fatalStatus(valid, 20_000L, 10_000L));

        BootstrapResultPolicy.Parsed malformed = BootstrapResultPolicy.parse(null);
        assertFalse(malformed.valid);
        assertEquals(BootstrapResultPolicy.CALLBACK_INVALID,
                BootstrapResultPolicy.fatalStatus(malformed, 20_000L, 10_000L));

        BootstrapResultPolicy.Parsed scriptError = BootstrapResultPolicy.parse(
                "{\"status\":\"SCRIPT_ERROR\"}");
        assertEquals(BootstrapResultPolicy.SCRIPT_ERROR,
                BootstrapResultPolicy.fatalStatus(scriptError, 20_000L, 10_000L));

        BootstrapResultPolicy.Parsed unknown = BootstrapResultPolicy.parse(
                "{\"status\":\"SURPRISE\"}");
        assertEquals(BootstrapResultPolicy.UNKNOWN_STATUS,
                BootstrapResultPolicy.fatalStatus(unknown, 20_000L, 10_000L));

        assertEquals(BootstrapResultPolicy.TIMEOUT,
                BootstrapResultPolicy.fatalStatus(valid, 20_000L, 20_000L));

        BootstrapResultPolicy.Parsed modeFailure = BootstrapResultPolicy.parse(
                "{\"status\":\"CHAT_BOOTSTRAP_MODE_READBACK_FAILED\"}");
        assertEquals("CHAT_BOOTSTRAP_MODE_READBACK_FAILED",
                BootstrapResultPolicy.fatalStatus(modeFailure, 20_000L, 10_000L));
    }

    @Test public void bootstrapRunStatePersistsDeadlineAndPerRunEvidence() {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            scenario.onActivity(activity -> {
                String runId = "SR-BOOTSTRAP-STATE-A";
                assertTrue(BootstrapRunStateStore.startRun(activity, runId,
                        ChatReasoningPreferenceStore.HIGH));
                BootstrapRunStateStore.Window first = BootstrapRunStateStore.touchBootstrap(
                        activity, runId, ChatReasoningPreferenceStore.HIGH, 1_000L);
                BootstrapRunStateStore.Window recorded = BootstrapRunStateStore.recordBootstrapResult(
                        activity, runId, "UI_WAIT", "mode", 1_500L);
                BootstrapRunStateStore.Window second = BootstrapRunStateStore.touchBootstrap(
                        activity, runId, ChatReasoningPreferenceStore.HIGH, 2_000L);
                assertTrue(first.persisted);
                assertTrue(recorded.persisted);
                assertTrue(second.persisted);
                assertEquals(first.deadlineAt, second.deadlineAt);
                assertEquals(2, second.attempts);
                assertTrue(BootstrapRunStateStore.markReasoningApplied(activity, runId,
                        ChatReasoningPreferenceStore.HIGH));
                assertTrue(BootstrapRunStateStore.markBootstrapCompleted(activity, runId, "READY"));

                JSONObject history = new JSONObject();
                BootstrapRunStateStore.appendHistory(activity, runId, history);
                assertEquals(ChatReasoningPreferenceStore.HIGH,
                        history.optString("chatReasoningRequested"));
                assertEquals(BootstrapRunStateStore.REASONING_APPLIED,
                        history.optString("chatReasoningStatus"));
                assertEquals(ChatReasoningPreferenceStore.HIGH,
                        history.optString("chatReasoningVerified"));
                assertEquals("요청: High / 적용: 확인 완료 / 확인: High",
                        BootstrapRunStateStore.summary(history));

                String firstSummary = BootstrapRunStateStore.summary(activity, runId);
                assertTrue(BootstrapRunStateStore.startRun(activity, "SR-BOOTSTRAP-STATE-B",
                        ChatReasoningPreferenceStore.EXTRA_HIGH));
                assertEquals(firstSummary, BootstrapRunStateStore.summary(activity, runId));
            });
        }
    }

    private static void load(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web,
                             String html) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView ignored, String url) {
                    if (PROJECT_URL.equals(url)) loaded.countDown();
                }
            });
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(PROJECT_URL, html, "text/html", "UTF-8", null);
        });
        assertTrue("Bootstrap WebView fixture did not load", loaded.await(15, TimeUnit.SECONDS));
    }

    private static JSONObject evaluate(ActivityScenario<SelfRunNewActivity> scenario,
                                       AtomicReference<WebView> web, String script) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<String> raw = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(script, value -> {
            raw.set(value);
            complete.countDown();
        }));
        assertTrue("WebView script timed out", complete.await(15, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(raw.get()).nextValue();
        return new JSONObject(String.valueOf(decoded));
    }

    private static String read(ActivityScenario<SelfRunNewActivity> scenario,
                               AtomicReference<WebView> web, String expression) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<String> raw = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(expression, value -> {
            raw.set(value);
            complete.countDown();
        }));
        assertTrue("WebView read timed out", complete.await(15, TimeUnit.SECONDS));
        return String.valueOf(new JSONTokener(raw.get()).nextValue());
    }

    private static void setNow(ActivityScenario<SelfRunNewActivity> scenario,
                               AtomicReference<WebView> web, long now) throws Exception {
        assertEquals(String.valueOf(now), read(scenario, web,
                "(()=>{window.testNow=" + now + ";return String(window.testNow);})()"));
    }

    private static String newChatTransitionFixture() {
        return """
                <!doctype html><html><head><style>
                body{margin:0;min-height:800px}button{display:block;width:160px;height:48px}
                </style></head><body><button id="new-chat" type="button" aria-label="New chat">New chat</button>
                <script>
                window.testNow=1000;Date.now=()=>window.testNow;window.newChatClicks=0;
                document.getElementById('new-chat').addEventListener('click',()=>{window.newChatClicks++;});
                </script></body></html>
                """;
    }
}
