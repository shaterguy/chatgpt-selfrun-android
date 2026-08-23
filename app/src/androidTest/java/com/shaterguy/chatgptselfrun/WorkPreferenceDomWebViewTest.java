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

/** Exercises Work and Chat preference selectors on real Android WebView fixtures. */
@RunWith(AndroidJUnit4.class)
public final class WorkPreferenceDomWebViewTest {
    private static final String PROJECT_URL = "https://chatgpt.com/g/g-p-test";
    private static final String CONVERSATION_URL = "https://chatgpt.com/c/conversation123";
    private static final String CONTINUE_PROMPT = "SELF_RUN_CONTINUE_PROBE";
    private static final String OBSERVER_RUN_ID = "SR-WEBVIEW-TEST";
    private static final String OBSERVER_TOKEN = "observer-token";
    private static final long OBSERVER_STABILITY_MS = 5_000L;

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

    @Test public void chatReasoningNativeRangeMovesBySemanticSteps() throws Exception {
        assertChatReasoningFixture(nativeChatReasoningFixture(), ChatReasoningPreferenceStore.PRO,
                "document.getElementById('slider').value", "4");
    }

    @Test public void chatReasoningAriaSliderMovesBySemanticSteps() throws Exception {
        assertChatReasoningFixture(ariaChatReasoningFixture(), ChatReasoningPreferenceStore.HIGH,
                "document.getElementById('slider').getAttribute('aria-valuenow')", "2");
    }

    @Test public void chatReasoningReducedRangeAppliesAvailableHigh() throws Exception {
    assertChatReasoningFixture(reducedChatReasoningFixture(), ChatReasoningPreferenceStore.HIGH,
            "document.getElementById('slider').value", "2");
}

    @Test public void chatReasoningReducedRangeRejectsUnavailableLevels() throws Exception {
    assertChatReasoningTerminal(reducedChatReasoningFixture(), ChatReasoningPreferenceStore.EXTRA_HIGH,
            "CHAT_REASONING_OPTION_UNAVAILABLE");
    assertChatReasoningTerminal(reducedChatReasoningFixture(), ChatReasoningPreferenceStore.PRO,
            "CHAT_REASONING_OPTION_UNAVAILABLE");
}

    @Test public void chatReasoningSemanticMismatchNeverFallsBackToNumericPosition() throws Exception {
    assertChatReasoningTerminal(semanticMismatchFixture(), ChatReasoningPreferenceStore.EXTRA_HIGH,
            "CHAT_REASONING_READBACK_MISMATCH");
}

    @Test public void chatReasoningMissingTriggerAndSliderTerminate() throws Exception {
    assertChatReasoningTerminal(missingTriggerFixture(), ChatReasoningPreferenceStore.HIGH,
            "CHAT_REASONING_TRIGGER_NOT_FOUND");
    assertChatReasoningTerminal(missingSliderFixture(), ChatReasoningPreferenceStore.HIGH,
            "CHAT_REASONING_SLIDER_NOT_FOUND");
}

    @Test public void chatReasoningMenuCloseFailureTerminates() throws Exception {
    assertChatReasoningTerminal(closeBlockedFixture(), ChatReasoningPreferenceStore.INSTANT,
            "CHAT_REASONING_MENU_CLOSE_FAILED");
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

    @Test public void continuationClassifierIgnoresAStopOutsideTheComposerForm() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadContinuationFixture(scenario, web, "<div id='stop' role='button' data-testid='stop-stream-action' aria-label='Stop streaming'>Stop</div>");

            JSONObject prepare = evaluate(scenario, web,
                    SelfRunContinuationDom.prepareDriveTurn(CONVERSATION_URL, CONTINUE_PROMPT, "global-stop-probe"));
            assertEquals("READY_TO_SUBMIT", prepare.getString("status"));
            assertEquals(CONTINUE_PROMPT, read(scenario, web, "document.getElementById('prompt-textarea').value"));
            assertEquals("0", read(scenario, web, "String(window.stopClicks)"));

            JSONObject click = evaluate(scenario, web,
                    SelfRunContinuationDom.clickPreparedDriveTurn(CONVERSATION_URL, CONTINUE_PROMPT, "global-stop-probe",
                            OBSERVER_RUN_ID, OBSERVER_TOKEN, OBSERVER_STABILITY_MS));
            assertEquals("CONTINUE_CLICKED", click.getString("status"));
            assertEquals("0", read(scenario, web, "String(window.stopClicks)"));
        }
    }

    @Test public void continuationClassifierStillBlocksAStopInsideTheComposerForm() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadContinuationFixture(scenario, web, "<div id='stop' data-selfrun-scope='composer' role='button' data-testid='stop-stream-action' aria-label='Stop streaming'>Stop</div>");

            JSONObject prepare = evaluate(scenario, web,
                    SelfRunContinuationDom.prepareDriveTurn(CONVERSATION_URL, CONTINUE_PROMPT, "composer-stop-probe"));
            assertEquals(SelfRunContinuationDom.STOP, prepare.getString("status"));
            assertEquals("", read(scenario, web, "document.getElementById('prompt-textarea').value"));
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
                    SelfRunContinuationDom.clickPreparedDriveTurn(CONVERSATION_URL, CONTINUE_PROMPT, "voice-idle-probe",
                            OBSERVER_RUN_ID, OBSERVER_TOKEN, OBSERVER_STABILITY_MS));
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
                    SelfRunContinuationDom.clickPreparedBootstrap(PROJECT_URL, CONTINUE_PROMPT, "bootstrap-voice-probe",
                            OBSERVER_RUN_ID, OBSERVER_TOKEN, OBSERVER_STABILITY_MS));
            assertEquals("BOOTSTRAP_CLICKED", clicked.getString("status"));
            assertEquals("0", read(scenario, web, "String(window.voiceClicks)"));
            assertEquals("1", read(scenario, web, "String(window.sendClicks)"));
        }
    }

    private static void assertContinuationState(String controls, String expected) throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            String markerId = "classifier-" + expected.toLowerCase();
            loadContinuationFixture(scenario, web, controls);
            evaluate(scenario, web, "(()=>{window.__selfRunDriveMarkers={'selfrun-drive:verified-continuation:"
                    + markerId + "':JSON.stringify({state:'prepared'})};document.getElementById('prompt-textarea').value='"
                    + CONTINUE_PROMPT + "';return JSON.stringify({status:'READY'});})()");
            JSONObject result = evaluate(scenario, web,
                    SelfRunContinuationDom.clickPreparedDriveTurn(CONVERSATION_URL, CONTINUE_PROMPT, markerId,
                            OBSERVER_RUN_ID, OBSERVER_TOKEN, OBSERVER_STABILITY_MS));
            boolean formFallback = SelfRunContinuationDom.COMPOSER_IDLE.equals(expected);
            String expectedResult = SelfRunContinuationDom.SEND_ENABLED.equals(expected) || formFallback
                    ? "CONTINUE_CLICKED" : expected;
            assertEquals(expectedResult, result.getString("status"));
            if (formFallback) {
                assertTrue(result.getString("detail").contains("submit=form_request_submit"));
                assertEquals("clicked", read(scenario, web,
                        "JSON.parse(window.__selfRunDriveMarkers['selfrun-drive:verified-continuation:"
                                + markerId + "']).state"));
            }
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

    private static void assertChatReasoningFixture(String html, String selection,
                                                   String valueExpression, String expectedValue) throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadChatReasoningFixture(scenario, web, html);
            assertNotNull("Android System WebView must be available", WebView.getCurrentWebViewPackage());
            assertEquals("true", read(scenario, web,
                    "String(document.getElementById('prompt-textarea').getBoundingClientRect().top-document.getElementById('trigger').getBoundingClientRect().bottom>280)"));
            String script = chatReasoningScript(selection);

            JSONObject result = null;
            boolean sawOpen = false;
            boolean sawSet = false;
            for (int attempt = 0; attempt < 12; attempt++) {
                result = evaluate(scenario, web, script);
                if ("READY".equals(result.getString("status"))) break;
                assertEquals("UI_WAIT", result.getString("status"));
                JSONObject diagnostics = result.optJSONObject("diagnostics");
                String action = diagnostics == null ? "" : diagnostics.optString("action");
                if ("open-menu".equals(action)) sawOpen = true;
                if ("set-slider".equals(action)) sawSet = true;
            }
            assertNotNull(result);
            assertTrue("Chat selector must open the menu", sawOpen);
            assertTrue("Chat selector must move the slider", sawSet);
            assertEquals("READY", result.getString("status"));
            assertEquals(expectedValue, read(scenario, web, valueExpression));
            assertEquals("1", read(scenario, web, "String(window.menuOpenClicks)"));
        }
    }

    private static void assertChatReasoningTerminal(String html, String selection,
                                             String expectedStatus) throws Exception {
    try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
        AtomicReference<WebView> web = new AtomicReference<>();
        loadChatReasoningFixture(scenario, web, html);
        JSONObject result = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            result = evaluate(scenario, web, chatReasoningScript(selection));
            if (!"UI_WAIT".equals(result.getString("status"))) break;
        }
        assertNotNull(result);
        assertEquals(expectedStatus, result.getString("status"));
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

    private static void loadChatReasoningFixture(ActivityScenario<SelfRunNewActivity> scenario,
                                                 AtomicReference<WebView> web, String html) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            view.getSettings().setJavaScriptEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView ignored, String url) {
                    if (PROJECT_URL.equals(url)) loaded.countDown();
                }
            });
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(PROJECT_URL, html, "text/html", "UTF-8", null);
        });
        assertTrue("Chat reasoning WebView fixture did not load", loaded.await(15, TimeUnit.SECONDS));
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

    private static String chatReasoningScript(String selection) {
        return "(()=>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,diagnostics});"
                + "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;const exactText=s=>String(s??'').replace(/\\s+/g,' ').trim().toLowerCase();"
                + "const labelOf=e=>exactText(e?.innerText||'')||exactText(e?.getAttribute?.('aria-label')||'');const composer=document.getElementById('prompt-textarea');"
                + ChatReasoningDom.inline(selection, "SR-CHAT-REASONING-TEST")
                + "return result('READY','Chat reasoning fixture ready');})()";
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

    private static String fullLevelsMarkup() {
    return "<span data-level='instant'>Instant</span><span data-level='medium'>Medium</span>"
            + "<span data-level='high'>High</span><span data-level='xhigh'>Extra High</span>"
            + "<span data-level='pro'>Pro</span>";
}

    private static String reducedLevelsMarkup() {
    return "<span data-level='instant'>Instant</span><span data-level='medium'>Medium</span>"
            + "<span data-level='high'>High</span>";
}

    private static String nativeChatReasoningFixture() {
    return chatReasoningFixture("<input id='slider' type='range' min='0' max='4' step='1' value='0' aria-valuetext='Instant'>",
            fullLevelsMarkup(), "Flash Instant",
            "const labels=['Instant','Medium','High','Extra High','Pro'];const slider=document.getElementById('slider');"
                    + "function updateNative(){const value=Math.max(0,Math.min(4,Math.round(Number(slider.value))));const text=labels[value];slider.setAttribute('aria-valuetext',text);trigger.textContent=text;}"
                    + "slider.addEventListener('input',updateNative);slider.addEventListener('change',updateNative);", false);
}

    private static String ariaChatReasoningFixture() {
    return chatReasoningFixture("<div id='track'><div id='slider' role='slider' tabindex='0' aria-valuemin='0' aria-valuemax='4' aria-valuenow='1' aria-valuetext='Medium'></div></div>",
            fullLevelsMarkup(), "Flash Standard",
            "const labels=['Instant','Medium','High','Extra High','Pro'];const slider=document.getElementById('slider'),track=document.getElementById('track');"
                    + "function updateAria(event){const rect=track.getBoundingClientRect();const value=Math.round(Math.max(0,Math.min(1,(event.clientX-rect.left)/rect.width))*4);const text=labels[value];slider.setAttribute('aria-valuenow',String(value));slider.setAttribute('aria-valuetext',text);trigger.textContent=text;}"
                    + "for(const type of ['pointerup','mouseup','click']){track.addEventListener(type,updateAria);slider.addEventListener(type,updateAria);}", false);
}

    private static String reducedChatReasoningFixture() {
    return chatReasoningFixture("<input id='slider' type='range' min='0' max='2' step='1' value='0' aria-valuetext='Instant'>",
            reducedLevelsMarkup(), "Instant",
            "const labels=['Instant','Medium','High'];const slider=document.getElementById('slider');"
                    + "function updateReduced(){const value=Math.max(0,Math.min(2,Math.round(Number(slider.value))));const text=labels[value];slider.setAttribute('aria-valuetext',text);trigger.textContent=text;}"
                    + "slider.addEventListener('input',updateReduced);slider.addEventListener('change',updateReduced);", false);
}

    private static String semanticMismatchFixture() {
    return chatReasoningFixture("<input id='slider' type='range' min='0' max='4' step='1' value='2' aria-valuetext='High'>",
            fullLevelsMarkup(), "High",
            "const slider=document.getElementById('slider');function keepHigh(){slider.setAttribute('aria-valuetext','High');trigger.textContent='High';}"
                    + "slider.addEventListener('input',keepHigh);slider.addEventListener('change',keepHigh);", false);
}

    private static String closeBlockedFixture() {
    return chatReasoningFixture("<input id='slider' type='range' min='0' max='4' step='1' value='0' aria-valuetext='Instant'>",
            fullLevelsMarkup(), "Instant", "", true);
}

    private static String missingTriggerFixture() {
    return "<!doctype html><html><head><style>body{margin:0}main{min-height:720px;display:flex;align-items:flex-end}form{height:72px;width:100%}</style></head>"
            + "<body><main><form><textarea id='prompt-textarea'></textarea></form></main></body></html>";
}

    private static String missingSliderFixture() {
    return "<!doctype html><html><head><style>body{margin:0}header{height:64px;padding:8px}main{min-height:720px;display:flex;align-items:flex-end}form{height:72px;width:100%}#reasoning-menu[hidden]{display:none}</style></head>"
            + "<body><header><button id='trigger' type='button' aria-haspopup='menu' aria-controls='reasoning-menu' aria-expanded='false'>Instant</button></header>"
            + "<main><form><textarea id='prompt-textarea'></textarea></form></main><div id='reasoning-menu' role='menu' hidden><span data-level='instant'>Instant</span><span data-level='high'>High</span></div>"
            + "<script>const trigger=document.getElementById('trigger'),menu=document.getElementById('reasoning-menu');trigger.addEventListener('click',()=>{menu.hidden=false;trigger.setAttribute('aria-expanded','true');});</script></body></html>";
}

    private static String chatReasoningFixture(String sliderMarkup, String levelsMarkup,
                                             String initialLabel, String sliderScript,
                                             boolean refuseClose) {
    String closeBranch = refuseClose ? "" : "else{menu.hidden=true;window.menuCloseClicks++;trigger.setAttribute('aria-expanded','false');}";
    return "<!doctype html><html><head><style>body{margin:0}header{height:64px;padding:8px}main{min-height:720px;display:flex;align-items:flex-end}form{height:72px;width:100%}button{display:block;margin:8px}#reasoning-menu[hidden]{display:none}#reasoning-menu{width:320px;min-height:72px}#levels{display:flex;gap:8px}#track{position:relative;width:240px;height:24px;background:#ddd}#slider[role=slider]{position:absolute;left:0;top:3px;width:18px;height:18px;background:#222}</style></head>"
            + "<body><header><button id='trigger' type='button' aria-haspopup='menu' aria-controls='reasoning-menu' aria-expanded='false'>" + initialLabel + "</button></header>"
            + "<main><form><textarea id='prompt-textarea'></textarea></form></main>"
            + "<div id='reasoning-menu' role='menu' hidden><div id='levels'>" + levelsMarkup + "</div>" + sliderMarkup + "</div>"
            + "<script>window.menuOpenClicks=0;window.menuCloseClicks=0;const trigger=document.getElementById('trigger'),menu=document.getElementById('reasoning-menu');"
            + "trigger.addEventListener('click',()=>{if(menu.hidden){menu.hidden=false;window.menuOpenClicks++;trigger.setAttribute('aria-expanded','true');}" + closeBranch + "});"
            + sliderScript + "</script></body></html>";
}

    private static String continuationFixture(String controls) {
        boolean composerScopedStop = controls.contains("data-selfrun-scope='composer'");
        String formControls = composerScopedStop
                ? controls
                : controls.contains("stop-stream-action")
                        ? "<button type='submit' data-testid='send-button' aria-label='Send'>Send</button>"
                        : controls;
        String outsideControls = composerScopedStop ? "" : controls;
        return "<!doctype html><html><head><style>body{margin:20px}button,[role=button]{display:block;margin:8px}</style></head>"
                + "<body><main><div id='composer-shell'><form><textarea id='prompt-textarea'></textarea>"
                + formControls + "</form><div id='continuation-controls'>" + outsideControls + "</div></div></main>"
                + "<script>window.stopClicks=0;document.querySelector('form')?.addEventListener('submit',event=>event.preventDefault());const stop=document.getElementById('stop');if(stop)stop.addEventListener('click',()=>window.stopClicks++);</script>"
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
