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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Exercises Work continuation selectors on a real Android WebView fixture for an existing general chat. */
@RunWith(AndroidJUnit4.class)
public final class WorkPreferenceDomWebViewTest {
    private static final String PROJECT_URL = "https://chatgpt.com/g/g-p-test";
    private static final String CONVERSATION_URL = "https://chatgpt.com/c/conversation123";
    private static final String CONTINUE_PROMPT = "SELF_RUN_CONTINUE_PROBE";

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

    @Test public void continuationClassifierPrioritizesAStopOutsideTheComposerForm() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadContinuationFixture(scenario, web, "<div id='stop' role='button' data-testid='stop-stream-action' aria-label='Stop streaming'>Stop</div>");

            JSONObject state = evaluate(scenario, web, SelfRunContinuationDom.buttonState(CONVERSATION_URL));
            assertEquals(SelfRunContinuationDom.STOP, state.getString("status"));

            JSONObject prepare = evaluate(scenario, web,
                    SelfRunContinuationDom.prepareDriveTurn(CONVERSATION_URL, CONTINUE_PROMPT, "stop-before-input-probe"));
            assertEquals(SelfRunContinuationDom.STOP, prepare.getString("status"));
            assertEquals("", read(scenario, web, "document.getElementById('prompt-textarea').value"));
            assertEquals("0", read(scenario, web, "String(window.stopClicks)"));

            evaluate(scenario, web, "(()=>{window.__selfRunDriveMarkers={'selfrun-drive:verified-continuation:stop-probe':JSON.stringify({state:'prepared'})};document.getElementById('prompt-textarea').value='" + CONTINUE_PROMPT + "';return JSON.stringify({status:'READY'});})()");
            JSONObject click = evaluate(scenario, web,
                    SelfRunContinuationDom.clickPreparedDriveTurn(CONVERSATION_URL, CONTINUE_PROMPT, "stop-probe"));
            assertEquals(SelfRunContinuationDom.STOP, click.getString("status"));
            assertEquals("0", read(scenario, web, "String(window.stopClicks)"));
        }
    }

    @Test public void continuationClassifierSeparatesSendDisabledAndEditableIdle() throws Exception {
        assertContinuationState("<button type='submit' data-testid='send-button' aria-label='Send'>Send</button>",
                SelfRunContinuationDom.SEND_ENABLED);
        assertContinuationState("<button type='submit' data-testid='send-button' aria-label='Send' disabled>Send</button>",
                SelfRunContinuationDom.SEND_DISABLED);
        assertContinuationState("<button type='button' aria-label='Attach files'>Attach</button>",
                SelfRunContinuationDom.COMPOSER_IDLE);
    }

    @Test public void voiceIdleComposerBecomesSendAfterInputWithoutClickingVoice() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadVoiceIdleFixture(scenario, web, CONVERSATION_URL);

            JSONObject idle = evaluate(scenario, web, SelfRunContinuationDom.buttonState(CONVERSATION_URL));
            assertEquals(SelfRunContinuationDom.COMPOSER_IDLE, idle.getString("status"));

            JSONObject prepared = null;
            for (int attempt = 0; attempt < 8; attempt++) {
                prepared = evaluate(scenario, web,
                        SelfRunContinuationDom.prepareDriveTurn(CONVERSATION_URL, CONTINUE_PROMPT, "voice-idle-probe"));
                if ("READY_TO_SUBMIT".equals(prepared.getString("status"))) break;
            }
            assertNotNull(prepared);
            assertEquals("READY_TO_SUBMIT", prepared.getString("status"));
            assertEquals("0", read(scenario, web, "String(window.voiceClicks)"));

            JSONObject clicked = evaluate(scenario, web,
                    SelfRunContinuationDom.clickPreparedDriveTurn(CONVERSATION_URL, CONTINUE_PROMPT, "voice-idle-probe"));
            assertEquals("CONTINUE_CLICKED", clicked.getString("status"));
            assertEquals("0", read(scenario, web, "String(window.voiceClicks)"));
            assertEquals("1", read(scenario, web, "String(window.sendClicks)"));
        }
    }

    @Test public void bootstrapVoiceIdleComposerAlsoClicksOnlySend() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadVoiceIdleFixture(scenario, web, PROJECT_URL);

            JSONObject prepared = null;
            for (int attempt = 0; attempt < 8; attempt++) {
                prepared = evaluate(scenario, web,
                        SelfRunContinuationDom.prepareBootstrap(PROJECT_URL, CONTINUE_PROMPT, "bootstrap-voice-probe"));
                if ("READY_TO_SUBMIT".equals(prepared.getString("status"))) break;
            }
            assertNotNull(prepared);
            assertEquals("READY_TO_SUBMIT", prepared.getString("status"));
            assertEquals("0", read(scenario, web, "String(window.voiceClicks)"));

            JSONObject clicked = evaluate(scenario, web,
                    SelfRunContinuationDom.clickPreparedBootstrap(PROJECT_URL, CONTINUE_PROMPT, "bootstrap-voice-probe"));
            assertEquals("BOOTSTRAP_CLICKED", clicked.getString("status"));
            assertEquals("0", read(scenario, web, "String(window.voiceClicks)"));
            assertEquals("1", read(scenario, web, "String(window.sendClicks)"));
        }
    }

    private static void assertContinuationState(String controls, String expected) throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadContinuationFixture(scenario, web, controls);
            assertEquals(expected, evaluate(scenario, web,
                    SelfRunContinuationDom.buttonState(CONVERSATION_URL)).getString("status"));
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
            view.loadDataWithBaseURL(CONVERSATION_URL,
                    fixture(triggerAttributes, triggerEvent, reasoning), "text/html", "UTF-8", null);
        });
        assertTrue("WebView fixture did not load", loaded.await(15, TimeUnit.SECONDS));
    }

    private static void loadContinuationFixture(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web,
                                                String controls) throws Exception {
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
            view.loadDataWithBaseURL(CONVERSATION_URL, continuationFixture(controls), "text/html", "UTF-8", null);
        });
        assertTrue("Continuation WebView fixture did not load", loaded.await(15, TimeUnit.SECONDS));
    }

    private static void loadVoiceIdleFixture(ActivityScenario<SelfRunNewActivity> scenario,
                                             AtomicReference<WebView> web, String baseUrl) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            view.getSettings().setJavaScriptEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView ignored, String url) {
                    if (baseUrl.equals(url)) loaded.countDown();
                }
            });
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(baseUrl, voiceIdleFixture(), "text/html", "UTF-8", null);
        });
        assertTrue("Voice-idle WebView fixture did not load", loaded.await(15, TimeUnit.SECONDS));
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

    private static String continuationFixture(String controls) {
        String formControls = controls.contains("stop-stream-action")
                ? "<button type='submit' data-testid='send-button' aria-label='Send'>Send</button>"
                : controls;
        return "<!doctype html><html><head><style>body{margin:20px}button,[role=button]{display:block;margin:8px}</style></head>"
                + "<body><main><div id='composer-shell'><form><textarea id='prompt-textarea'></textarea>"
                + formControls + "</form><div id='continuation-controls'>" + controls + "</div></div></main>"
                + "<script>window.stopClicks=0;const stop=document.getElementById('stop');if(stop)stop.addEventListener('click',()=>window.stopClicks++);</script>"
                + "</body></html>";
    }

    private static String voiceIdleFixture() {
        return "<!doctype html><html><head><style>body{margin:20px}button{display:block;margin:8px}</style></head>"
                + "<body><main><form id='composer'><textarea id='prompt-textarea'></textarea>"
                + "<button id='voice' type='submit' data-testid='composer-speech-button' aria-label='Start voice mode'>Voice</button>"
                + "</form></main><script>window.voiceClicks=0;window.sendClicks=0;"
                + "const form=document.getElementById('composer'),composer=document.getElementById('prompt-textarea'),voice=document.getElementById('voice');"
                + "voice.addEventListener('click',event=>{event.preventDefault();window.voiceClicks++;});"
                + "composer.addEventListener('input',()=>{if(!composer.value||document.getElementById('send'))return;voice.remove();"
                + "const send=document.createElement('button');send.id='send';send.type='submit';send.dataset.testid='send-button';send.setAttribute('aria-label','Send');send.textContent='Send';"
                + "send.addEventListener('click',event=>{event.preventDefault();window.sendClicks++;});form.appendChild(send);});</script>"
                + "</body></html>";
    }
}
