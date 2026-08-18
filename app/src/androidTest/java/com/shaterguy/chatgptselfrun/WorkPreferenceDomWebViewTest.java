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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Exercises Work continuation selectors and Drive composer safety on a real Android WebView fixture. */
@RunWith(AndroidJUnit4.class)
public final class WorkPreferenceDomWebViewTest {
    private static final String CONVERSATION_URL = "https://chatgpt.com/c/conversation123";

    @Test public void modelSelectorCompletesForClickPointerAndMouseTriggersUsingLunaProfile() throws Exception {
        assertSelection("", "click", false);
        assertSelection("aria-haspopup=\"dialog\" aria-expanded=\"false\"", "pointerdown", false);
        assertSelection("aria-haspopup=\"dialog\" aria-expanded=\"false\"", "mousedown", false);
    }

    @Test public void reasoningSelectorCompletesForClickPointerAndMouseTriggersUsingMaxProfile() throws Exception {
        assertSelection("", "click", true);
        assertSelection("aria-haspopup=\"dialog\" aria-expanded=\"false\"", "pointerdown", true);
        assertSelection("aria-haspopup=\"dialog\" aria-expanded=\"false\"", "mousedown", true);
    }

    @Test public void v1WorkTargetsPopulateAllScopedV2TargetsWithoutReplacingRecaptures() throws Exception {
        JSONObject profile = new JSONObject();
        JSONObject targets = new JSONObject();
        JSONObject model = new JSONObject().put("testid", "legacy-model");
        JSONObject reasoning = new JSONObject().put("aria", "legacy-reasoning");
        JSONObject scoped = new JSONObject().put("id", "fresh-continuation-model");
        targets.put(WebUiCalibrationStore.PURPOSE_LEGACY_WORK_MODEL, model);
        targets.put(WebUiCalibrationStore.PURPOSE_LEGACY_WORK_REASONING, reasoning);
        targets.put(WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_MODEL, scoped);
        profile.put("version", 1).put("targets", targets);

        assertTrue(WebUiCalibrationStore.migrateLegacyWorkTargets(profile));
        assertEquals(2, profile.getInt("version"));
        assertEquals("v1-work-targets", profile.getString("migratedFrom"));
        assertEquals("legacy-model", targets.getJSONObject(WebUiCalibrationStore.PURPOSE_GENERAL_BOOTSTRAP_WORK_MODEL).getString("testid"));
        assertEquals("fresh-continuation-model", targets.getJSONObject(WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_MODEL).getString("id"));
        assertEquals("legacy-model", targets.getJSONObject(WebUiCalibrationStore.PURPOSE_PROJECT_CONTINUATION_WORK_MODEL).getString("testid"));
        assertEquals("legacy-reasoning", targets.getJSONObject(WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_REASONING).getString("aria"));
        assertEquals("legacy-reasoning", targets.getJSONObject(WebUiCalibrationStore.PURPOSE_PROJECT_BOOTSTRAP_WORK_REASONING).getString("aria"));
    }

    @Test public void editOnlyComposerFailsClosedWithoutOverwritingSubmittedPrompt() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadRawFixture(scenario, web, composerFixture(false));
            JSONObject result = evaluate(scenario, web, SelfRunDom.prepareDriveTurn(
                    CONVERSATION_URL, "app-continue", "marker-edit-only"));
            assertEquals("UI_WAIT", result.getString("status"));
            assertEquals("old submitted prompt", read(scenario, web,
                    "document.getElementById('old-edit').value"));
        }
    }

    @Test public void historicalCalibratedComposerFailsClosedWithoutOverwritingSubmittedPrompt() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadRawFixture(scenario, web, composerFixture(false));
            JSONObject profile = new JSONObject().put("targets", new JSONObject().put(
                    WebUiCalibrationStore.TARGET_GENERAL_COMPOSER,
                    new JSONObject().put("id", "old-edit").put("tag", "textarea")));
            assertEquals("ok", read(scenario, web,
                    "localStorage.setItem(" + SelfRunScript.quote(WebUiCalibrationStore.STORAGE_KEY) + ","
                            + SelfRunScript.quote(profile.toString()) + ");'ok'"));
            JSONObject result = evaluate(scenario, web, SelfRunDom.prepareDriveTurn(
                    CONVERSATION_URL, "app-continue", "marker-calibrated-edit"));
            assertEquals("UI_WAIT", result.getString("status"));
            assertEquals("old submitted prompt", read(scenario, web,
                    "document.getElementById('old-edit').value"));
        }
    }

    @Test public void newestNonTurnComposerWinsWhileHistoricalEditRemainsUntouched() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadRawFixture(scenario, web, composerFixture(true));
            String script = SelfRunDom.prepareDriveTurn(
                    CONVERSATION_URL, "app-continue", "marker-newest-live");
            JSONObject first = evaluate(scenario, web, script);
            assertEquals("UI_WAIT", first.getString("status"));
            assertEquals("old submitted prompt", read(scenario, web,
                    "document.getElementById('old-edit').value"));
            assertEquals("app-continue", read(scenario, web,
                    "document.getElementById('prompt-textarea').value"));
            JSONObject second = evaluate(scenario, web, script);
            assertEquals("READY_TO_SUBMIT", second.getString("status"));
            assertFalse(read(scenario, web, "document.getElementById('send').disabled").contains("true"));
        }
    }

    private static void assertSelection(String triggerAttributes, String triggerEvent, boolean reasoning) throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadFixture(scenario, web, triggerAttributes, triggerEvent, reasoning);
            assertNotNull("Android System WebView must be available", WebView.getCurrentWebViewPackage());
            String wanted = reasoning ? "max" : "luna";
            String script = reasoning
                    ? WorkPreferenceDom.reasoningForConversation(CONVERSATION_URL, wanted)
                    : WorkPreferenceDom.modelForConversation(CONVERSATION_URL, wanted);

            JSONObject result = null;
            boolean sawWait = false;
            for (int attempt = 0; attempt < 10; attempt++) {
                result = evaluate(scenario, web, script);
                if ("READY".equals(result.getString("status"))) break;
                assertEquals("UI_WAIT", result.getString("status"));
                sawWait = true;
            }
            assertNotNull(result);
            assertTrue("selector must exercise an asynchronous UI_WAIT before READY", sawWait);
            assertEquals("READY", result.getString("status"));
            assertEquals(wanted, result.getJSONObject("diagnostics").getString("current"));
            assertTrue("fixture must update the combined composer control", read(scenario, web,
                    "document.getElementById('trigger').textContent").contains(wanted));
        }
    }

    private static void loadFixture(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web,
                                    String triggerAttributes, String triggerEvent, boolean reasoning) throws Exception {
        loadRawFixture(scenario, web, fixture(triggerAttributes, triggerEvent, reasoning));
    }

    private static void loadRawFixture(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web,
                                       String html) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            view.getSettings().setJavaScriptEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView ignored, String url) {
                    if (CONVERSATION_URL.equals(url)) loaded.countDown();
                }
            });
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(CONVERSATION_URL, html, "text/html", "UTF-8", null);
        });
        assertTrue("WebView fixture did not load", loaded.await(15, TimeUnit.SECONDS));
    }

    private static JSONObject evaluate(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web,
                                       String script) throws Exception {
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

    private static String read(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web,
                               String expression) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<String> raw = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(expression, value -> {
            raw.set(value);
            complete.countDown();
        }));
        assertTrue("WebView read timed out", complete.await(15, TimeUnit.SECONDS));
        return String.valueOf(new JSONTokener(raw.get()).nextValue());
    }

    private static String fixture(String triggerAttributes, String triggerEvent, boolean reasoning) {
        String current = reasoning ? "medium" : "sol";
        String wanted = reasoning ? "max" : "luna";
        return "<!doctype html><html><head><style>body{margin:20px}form{height:54px}button{display:block;margin:8px}#popup[hidden]{display:none}</style></head>"
                + "<body><form><textarea id='prompt-textarea'></textarea></form>"
                + "<button id='trigger' " + triggerAttributes + ">" + current + "</button>"
                + "<div id='popup' role='dialog' hidden><button id='wanted' role='option' aria-selected='false'>" + wanted + "</button></div>"
                + "<script>const trigger=document.getElementById('trigger'),popup=document.getElementById('popup'),wanted=document.getElementById('wanted');"
                + "function toggle(){popup.hidden=!popup.hidden;if(trigger.hasAttribute('aria-expanded'))trigger.setAttribute('aria-expanded',String(!popup.hidden));}"
                + "trigger.addEventListener('" + triggerEvent + "',toggle);"
                + "wanted.addEventListener('click',()=>{trigger.textContent=wanted.textContent;wanted.setAttribute('aria-selected','true');});</script>"
                + "</body></html>";
    }

    private static String composerFixture(boolean includeLiveComposer) {
        String live = includeLiveComposer
                ? "<form id='live-form'><textarea id='prompt-textarea'></textarea><button id='send' data-testid='send-button'>Send</button></form>"
                : "";
        return "<!doctype html><html><head><style>body{margin:20px}textarea,button{display:block}</style></head><body><main>"
                + "<article data-testid='conversation-turn-1'><div data-message-author-role='user'><form>"
                + "<textarea id='old-edit' data-testid='prompt-textarea'>old submitted prompt</textarea>"
                + "<button id='old-send' data-testid='send-button'>Send</button></form></div></article>"
                + live + "</main></body></html>";
    }
}
