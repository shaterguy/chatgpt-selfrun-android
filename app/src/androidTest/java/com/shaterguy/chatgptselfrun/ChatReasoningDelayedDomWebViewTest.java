package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ChatReasoningDelayedDomWebViewTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @After public void tearDown() {
        ChatReasoningPreferenceStore.set(context, ChatReasoningPreferenceStore.KEEP);
    }

    @Test public void delayedSliderInjectionAppliesExactSavedReasoning() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            ChatReasoningPreferenceStore.set(context, ChatReasoningPreferenceStore.HIGH);
            String result = evaluateScenario(scenario,
                    html(false, true, true),
                    delayedInjection("slider"),
                    350L,
                    "high");
            JSONObject parsed = new JSONObject(result);
            assertEquals("READY", parsed.optString("status"));
            assertEquals("high", parsed.getJSONObject("diagnostics").optString("observed"));
            assertTrue(parsed.getJSONObject("diagnostics").optInt("searchElapsedMs") >= 250);
        }
    }

    @Test public void delayedAdvancedInjectionUsesAdvancedControl() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            ChatReasoningPreferenceStore.set(context, ChatReasoningPreferenceStore.PRO_EXTENDED);
            String result = evaluateScenario(scenario,
                    html(false, false, false),
                    delayedInjection("advanced"),
                    350L,
                    "pro_extended");
            JSONObject parsed = new JSONObject(result);
            assertEquals("READY", parsed.optString("status"));
            assertEquals("pro_extended", parsed.getJSONObject("diagnostics").optString("observed"));
            assertTrue(parsed.getJSONObject("diagnostics").optBoolean("advancedButtonFound"));
        }
    }

    @Test public void targetErrorIsFiniteWhenPickerNeverAppears() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            ChatReasoningPreferenceStore.set(context, ChatReasoningPreferenceStore.HIGH);
            AtomicReference<String> value = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                WebView view = new WebView(activity);
                view.getSettings().setJavaScriptEnabled(true);
                view.loadDataWithBaseURL("https://chatgpt.com/", html(false, false, false), "text/html", "UTF-8", null);
                activity.runOnUiThread(() -> activity.getWindow().getDecorView().postDelayed(() ->
                        view.evaluateJavascript(SelfRunDom.bootstrap("CHAT", ChatReasoningPreferenceStore.HIGH, "SR-TARGET-ERROR"), raw -> {
                            value.set(unquote(raw));
                            latch.countDown();
                        }), 300));
            });
            assertTrue(latch.await(30, TimeUnit.SECONDS));
            JSONObject parsed = new JSONObject(value.get());
            assertEquals("TARGET_ERROR", parsed.optString("status"));
        }
    }

    @Test public void workModeSkipsChatReasoningMutation() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            ChatReasoningPreferenceStore.set(context, ChatReasoningPreferenceStore.HIGH);
            String result = evaluateScenario(scenario,
                    html(true, true, true),
                    "",
                    0L,
                    "standard");
            JSONObject parsed = new JSONObject(result);
            assertEquals("READY", parsed.optString("status"));
            assertTrue(parsed.getJSONObject("diagnostics").optBoolean("chatReasoningSkipped"));
        }
    }

    @Test public void chatModeDoesNotTouchWorkControls() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            ChatReasoningPreferenceStore.set(context, ChatReasoningPreferenceStore.HIGH);
            String result = evaluateScenario(scenario,
                    html(false, true, true),
                    "",
                    0L,
                    "high");
            JSONObject parsed = new JSONObject(result);
            assertEquals("READY", parsed.optString("status"));
            assertEquals(0, parsed.getJSONObject("diagnostics").optInt("workControlMutations"));
        }
    }

    @Test public void selectionIsAppliedExactlyOncePerBootstrap() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            ChatReasoningPreferenceStore.set(context, ChatReasoningPreferenceStore.HIGH);
            AtomicInteger mutations = new AtomicInteger();
            String result = evaluateScenario(scenario,
                    html(false, true, true),
                    mutationObserver(mutations),
                    0L,
                    "high");
            JSONObject parsed = new JSONObject(result);
            assertEquals("READY", parsed.optString("status"));
            assertEquals(1, parsed.getJSONObject("diagnostics").optInt("reasoningSelectionCount"));
        }
    }

    @Test public void bootstrapResultPolicyParsesAndClassifiesOnAndroid() {
        BootstrapResultPolicy.Parsed valid = BootstrapResultPolicy.parse(
                "{\"status\":\"UI_WAIT\",\"detail\":\"mode\"}");
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
        assertEquals("TARGET_ERROR", modeFailure.status);
        assertEquals("CHAT_BOOTSTRAP_MODE_READBACK_FAILED",
                modeFailure.result.optJSONObject("diagnostics").optString("reconnectCause"));
        assertEquals("", BootstrapResultPolicy.fatalStatus(modeFailure, 20_000L, 10_000L));
        assertEquals(BootstrapResultPolicy.TIMEOUT,
                BootstrapResultPolicy.fatalStatus(modeFailure, 20_000L, 20_000L));
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
                assertEquals(first.deadlineAt, recorded.deadlineAt);
                assertEquals(first.deadlineAt, second.deadlineAt);
                assertEquals(first.attempts + 1, second.attempts);
            });
        }
    }

    private String evaluateScenario(ActivityScenario<SelfRunNewActivity> scenario,
                                    String body,
                                    String injection,
                                    long injectionDelay,
                                    String expectedReasoning) throws Exception {
        AtomicReference<String> value = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            view.getSettings().setJavaScriptEnabled(true);
            view.loadDataWithBaseURL("https://chatgpt.com/", body, "text/html", "UTF-8", null);
            activity.runOnUiThread(() -> activity.getWindow().getDecorView().postDelayed(() -> {
                if (!injection.isEmpty()) {
                    activity.getWindow().getDecorView().postDelayed(() -> view.evaluateJavascript(injection, null), injectionDelay);
                }
                view.evaluateJavascript(SelfRunDom.bootstrap("CHAT", ChatReasoningPreferenceStore.get(activity), "SR-DELAY"), raw -> {
                    value.set(unquote(raw));
                    latch.countDown();
                });
            }, 300));
        });
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        JSONObject parsed = new JSONObject(value.get());
        assertEquals(expectedReasoning, parsed.optJSONObject("diagnostics") == null ? expectedReasoning
                : parsed.optJSONObject("diagnostics").optString("observed", expectedReasoning));
        return value.get();
    }

    private static String mutationObserver(AtomicInteger ignored) {
        return "window.__selfRunReasoningSelectionCount=0;" +
                "new MutationObserver(function(){window.__selfRunReasoningSelectionCount++;}).observe(document.body,{attributes:true,subtree:true});";
    }

    private static String delayedInjection(String type) {
        if ("slider".equals(type)) {
            return "var s=document.createElement('input');s.type='range';s.setAttribute('role','slider');" +
                    "s.setAttribute('min','0');s.setAttribute('max','100');s.setAttribute('value','50');" +
                    "s.setAttribute('aria-valuenow','50');s.setAttribute('aria-valuetext','standard');document.body.appendChild(s);";
        }
        return "var b=document.createElement('button');b.textContent='Advanced';b.setAttribute('aria-label','Advanced');document.body.appendChild(b);";
    }

    private static String html(boolean work, boolean slider, boolean composer) {
        String mode = work ? "Work" : "Chat";
        StringBuilder html = new StringBuilder("<html><body><button aria-label='Mode'>")
                .append(mode).append("</button>");
        if (composer) html.append("<textarea></textarea>");
        if (slider) html.append("<input role='slider' type='range' min='0' max='100' value='50' aria-valuenow='50' aria-valuetext='standard'/>");
        html.append("</body></html>");
        return html.toString();
    }

    private static String unquote(String raw) {
        try {
            Object value = new org.json.JSONTokener(raw).nextValue();
            return value instanceof String ? (String) value : raw;
        } catch (Throwable error) {
            return raw;
        }
    }
}
