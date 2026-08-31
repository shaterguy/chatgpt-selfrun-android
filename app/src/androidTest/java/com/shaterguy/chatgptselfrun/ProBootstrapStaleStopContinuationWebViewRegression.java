package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ActivityScenario;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Actual-WebView regression for the Pro-bootstrap stale STOP control race. */
final class ProBootstrapStaleStopContinuationWebViewRegression {
    private static final String PROJECT_ID = "g-p-6a582c824ba08191ac7e74e9bad721fc";
    private static final String CONVERSATION_ID = "pro-stale-stop-conversation";
    private static final String CONVERSATION_URL = "https://chatgpt.com/g/" + PROJECT_ID
            + "-vibe-coding/c/" + CONVERSATION_ID;
    private static final String PROMPT = "[2026.08.31 | 12:00:00] [SELF_RUN_CONTINUE %s]";

    private ProBootstrapStaleStopContinuationWebViewRegression() {}

    static void run() throws Exception {
        proBootstrapUsesRealSendWhenStaleStopCoexists();
        proBootstrapUsesFormSubmitWhenStaleStopOutlivesSend();
        nonProBootstrapKeepsStopPriority();
        proBootstrapStillBlocksWithoutARealSubmitPath();
    }

    private static void proBootstrapUsesRealSendWhenStaleStopCoexists() throws Exception {
        String runId = "SR-PRO-STALE-SEND";
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            persistRun(scenario, runId, "pro");
            AtomicReference<WebView> web = loadFixture(scenario, true, true);
            JSONObject prepared = prepareUntilTerminal(scenario, web, runId);
            assertEquals("READY_TO_SUBMIT", prepared.getString("status"));

            JSONObject dispatched = evaluate(scenario, web,
                    SelfRunContinuationDom.clickPreparedDriveTurn(
                            CONVERSATION_URL, prompt(runId), marker(runId), runId,
                            "pro-stale-stop-button-token", 5_000L));
            assertEquals(SelfRunContinuationDom.SUBMISSION_PENDING,
                    dispatched.getString("status"));
            assertTrue(dispatched.getString("detail").contains("submit=button"));
            assertEquals("1", read(scenario, web, "String(window.submitCount)"));
        }
    }

    private static void proBootstrapUsesFormSubmitWhenStaleStopOutlivesSend() throws Exception {
        String runId = "SR-PRO-STALE-FORM";
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            persistRun(scenario, runId, "pro");
            AtomicReference<WebView> web = loadFixture(scenario, false, true);
            JSONObject prepared = prepareUntilTerminal(scenario, web, runId);
            assertEquals("READY_TO_SUBMIT", prepared.getString("status"));

            JSONObject dispatched = evaluate(scenario, web,
                    SelfRunContinuationDom.clickPreparedDriveTurn(
                            CONVERSATION_URL, prompt(runId), marker(runId), runId,
                            "pro-stale-stop-form-token", 5_000L));
            assertEquals(SelfRunContinuationDom.SUBMISSION_PENDING,
                    dispatched.getString("status"));
            assertTrue(dispatched.getString("detail").contains("submit=form_request_submit"));
            assertEquals("1", read(scenario, web, "String(window.submitCount)"));
        }
    }

    private static void nonProBootstrapKeepsStopPriority() throws Exception {
        String runId = "SR-MEDIUM-STALE-SEND";
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            persistRun(scenario, runId, ChatReasoningPreferenceStore.MEDIUM);
            AtomicReference<WebView> web = loadFixture(scenario, true, true);
            JSONObject blocked = prepareUntilTerminal(scenario, web, runId);
            assertEquals(SelfRunContinuationDom.STOP, blocked.getString("status"));
            assertEquals("0", read(scenario, web, "String(window.submitCount)"));
        }
    }

    private static void proBootstrapStillBlocksWithoutARealSubmitPath() throws Exception {
        String runId = "SR-PRO-STALE-NO-PATH";
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            persistRun(scenario, runId, "pro");
            AtomicReference<WebView> web = loadFixture(scenario, false, false);
            JSONObject blocked = prepareUntilTerminal(scenario, web, runId);
            assertEquals(SelfRunContinuationDom.STOP, blocked.getString("status"));
            assertEquals("0", read(scenario, web, "String(window.submitCount)"));
        }
    }

    private static JSONObject prepareUntilTerminal(
            ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web,
            String runId) throws Exception {
        JSONObject state = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            state = evaluate(scenario, web, SelfRunContinuationDom.prepareDriveTurn(
                    CONVERSATION_URL, prompt(runId), marker(runId)));
            String status = state.getString("status");
            if (!"COMPOSER_CLEARING".equals(status)
                    && !"COMPOSER_INPUTTING".equals(status)) return state;
        }
        return state;
    }

    private static void persistRun(ActivityScenario<SelfRunNewActivity> scenario,
                                   String runId, String bootstrapReasoning) {
        AtomicReference<Boolean> saved = new AtomicReference<>(false);
        scenario.onActivity(activity -> {
            ProfileRegistry.initialize(activity);
            if ("pro".equals(bootstrapReasoning) && ProfileRegistry.resolveChat("pro") == null) {
                ProfileRegistry.CapturedProfile captured = ProfileRegistry.parseCaptured(
                        "{\"mode\":\"CHAT\",\"operations\":["
                                + "{\"op\":\"SET\",\"path\":\"model\",\"value\":\"gpt-5-6-pro-fixture\"},"
                                + "{\"op\":\"REMOVE\",\"path\":\"thinking_effort\"},"
                                + "{\"op\":\"REMOVE\",\"path\":\"conversation_origin\"},"
                                + "{\"op\":\"REMOVE\",\"path\":\"service_tier\"}]}" );
                ProfileRegistry.registerCaptured(captured, "", "pro");
            }
            saved.set(ChatReasoningPreferenceStore.save(activity, runId,
                    bootstrapReasoning, ChatReasoningPreferenceStore.INSTANT));
        });
        assertTrue("Chat bootstrap/continuation profile was not persisted", saved.get());
    }

    private static AtomicReference<WebView> loadFixture(
            ActivityScenario<SelfRunNewActivity> scenario, boolean includeSend,
            boolean includeForm) throws Exception {
        AtomicReference<WebView> web = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView ignored, String url) {
                    if (url != null && url.startsWith("https://chatgpt.com/")) loaded.countDown();
                }
            });
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(CONVERSATION_URL, fixture(includeSend, includeForm),
                    "text/html", "UTF-8", null);
        });
        assertTrue("Stale STOP continuation fixture did not load",
                loaded.await(15, TimeUnit.SECONDS));
        return web;
    }

    private static String fixture(boolean includeSend, boolean includeForm) {
        String send = includeSend
                ? "<button type=\"submit\" data-testid=\"send-button\" aria-label=\"Send\">Send</button>"
                : "";
        String openContainer = includeForm ? "<form>" : "<section>";
        String closeContainer = includeForm ? "</form>" : "</section>";
        String submitListener = includeForm
                ? "document.querySelector('form').addEventListener('submit',event=>{"
                        + "event.preventDefault();window.submitCount++;"
                        + "document.querySelector('[data-testid=\\\"stop-button\\\"]')?.remove();});"
                : "";
        return "<!doctype html><html><head><style>"
                + "body{margin:20px}#prompt-textarea{min-height:48px;border:1px solid #999}"
                + "button{display:block;margin:8px}</style></head><body><main>"
                + openContainer
                + "<div id=\"prompt-textarea\" contenteditable=\"true\" data-lexical-editor=\"true\"><p><br></p></div>"
                + "<button type=\"button\" data-testid=\"stop-button\" aria-label=\"Stop generating\">Stop generating</button>"
                + send + closeContainer
                + "</main><script>window.submitCount=0;" + submitListener
                + "</script></body></html>";
    }

    private static JSONObject evaluate(ActivityScenario<SelfRunNewActivity> scenario,
                                       AtomicReference<WebView> web, String script) throws Exception {
        return new JSONObject(read(scenario, web, script));
    }

    private static String read(ActivityScenario<SelfRunNewActivity> scenario,
                               AtomicReference<WebView> web, String expression) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<String> raw = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(expression, value -> {
            raw.set(value);
            complete.countDown();
        }));
        assertTrue("Stale STOP WebView script timed out",
                complete.await(15, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(raw.get()).nextValue();
        return String.valueOf(decoded);
    }

    private static String prompt(String runId) { return String.format(PROMPT, runId); }
    private static String marker(String runId) { return runId + ":continue:fixture-turn"; }
}
