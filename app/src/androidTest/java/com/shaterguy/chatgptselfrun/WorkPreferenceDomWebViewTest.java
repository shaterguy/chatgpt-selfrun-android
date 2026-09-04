package com.shaterguy.chatgptselfrun;

import android.webkit.WebResourceRequest;
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

/** Exercises the canonical submission and voice-idle WebView regressions only. */
@RunWith(AndroidJUnit4.class)
public final class WorkPreferenceDomWebViewTest {
    private static final String PROJECT_URL = "https://chatgpt.com/g/g-p-test";
    private static final String CONVERSATION_URL = "https://chatgpt.com/c/conversation123";
    private static final String CONTINUE_PROMPT = "SELF_RUN_CONTINUE_PROBE";
    private static final String RUN_ID = "SR-WEBVIEW-TEST";

    @Test public void continuationClassifierIgnoresAStopOutsideTheComposerForm() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadContinuationFixture(scenario, web,
                    "<div id='stop' role='button' data-testid='stop-stream-action' aria-label='Stop streaming'>Stop</div>");

            JSONObject prepare = null;
            for (int attempt = 0; attempt < 8; attempt++) {
                prepare = evaluate(scenario, web,
                        SelfRunContinuationDom.prepareDriveTurn(CONVERSATION_URL, CONTINUE_PROMPT, "global-stop-probe"));
                if ("READY_TO_SUBMIT".equals(prepare.getString("status"))) break;
            }
            assertNotNull(prepare);
            assertEquals("READY_TO_SUBMIT", prepare.getString("status"));
            assertEquals(CONTINUE_PROMPT,
                    read(scenario, web, "document.getElementById('prompt-textarea').value"));
            assertEquals("0", read(scenario, web, "String(window.stopClicks)"));

            JSONObject pending = evaluate(scenario, web,
                    SelfRunContinuationDom.clickPreparedDriveTurn(CONVERSATION_URL, CONTINUE_PROMPT,
                            "global-stop-probe", RUN_ID));
            assertEquals("SUBMISSION_PENDING", pending.getString("status"));
            assertTrue(pending.getString("detail").contains("dispatch=CONTINUE_CLICKED"));
            assertEquals("0", read(scenario, web, "String(window.stopClicks)"));
            assertEquals("1", read(scenario, web, "String(window.submitCount)"));

            evaluate(scenario, web,
                    "(()=>{const m=document.createElement('div');m.setAttribute('data-message-author-role','user');document.querySelector('main').prepend(m);return JSON.stringify({status:'USER_APPENDED'});})()");
            JSONObject confirmed = evaluate(scenario, web,
                    SelfRunContinuationDom.prepareDriveTurn(CONVERSATION_URL, CONTINUE_PROMPT,
                            "global-stop-probe"));
            assertEquals("SUBMISSION_CONFIRMED", confirmed.getString("status"));
            assertEquals("1", read(scenario, web, "String(window.submitCount)"));
        }
    }

    @Test public void continuationClassifierStillBlocksAStopInsideTheComposerForm() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadContinuationFixture(scenario, web,
                    "<div id='stop' data-selfrun-scope='composer' role='button' data-testid='stop-stream-action' aria-label='Stop streaming'>Stop</div>");

            JSONObject prepare = evaluate(scenario, web,
                    SelfRunContinuationDom.prepareDriveTurn(CONVERSATION_URL, CONTINUE_PROMPT,
                            "composer-stop-probe"));
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
                        SelfRunContinuationDom.prepareDriveTurn(CONVERSATION_URL, CONTINUE_PROMPT,
                                "voice-idle-probe"));
                if ("READY_TO_SUBMIT".equals(prepared.getString("status"))) break;
            }
            assertNotNull(prepared);
            assertEquals("READY_TO_SUBMIT", prepared.getString("status"));
            assertEquals("0", read(scenario, web, "String(window.voiceClicks)"));

            JSONObject pending = evaluate(scenario, web,
                    SelfRunContinuationDom.clickPreparedDriveTurn(CONVERSATION_URL, CONTINUE_PROMPT,
                            "voice-idle-probe", RUN_ID));
            assertEquals("SUBMISSION_PENDING", pending.getString("status"));
            assertTrue(pending.getString("detail").contains("dispatch=CONTINUE_CLICKED"));
            assertEquals("0", read(scenario, web, "String(window.voiceClicks)"));
            assertEquals("1", read(scenario, web, "String(window.submitCount)"));

            evaluate(scenario, web,
                    "(()=>{const m=document.createElement('div');m.setAttribute('data-message-author-role','user');document.querySelector('main').prepend(m);return JSON.stringify({status:'USER_APPENDED'});})()");
            JSONObject confirmed = evaluate(scenario, web,
                    SelfRunContinuationDom.prepareDriveTurn(CONVERSATION_URL, CONTINUE_PROMPT,
                            "voice-idle-probe"));
            assertEquals("SUBMISSION_CONFIRMED", confirmed.getString("status"));
            assertEquals("1", read(scenario, web, "String(window.submitCount)"));
        }
    }

    @Test public void bootstrapVoiceIdleComposerAlsoClicksOnlySend() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadVoiceIdleFixture(scenario, web, PROJECT_URL);

            JSONObject prepared = null;
            for (int attempt = 0; attempt < 8; attempt++) {
                prepared = evaluate(scenario, web,
                        SelfRunContinuationDom.prepareBootstrap(PROJECT_URL, CONTINUE_PROMPT,
                                "bootstrap-voice-probe"));
                if ("READY_TO_SUBMIT".equals(prepared.getString("status"))) break;
            }
            assertNotNull(prepared);
            assertEquals("READY_TO_SUBMIT", prepared.getString("status"));
            assertEquals("0", read(scenario, web, "String(window.voiceClicks)"));

            JSONObject pending = evaluate(scenario, web,
                    SelfRunContinuationDom.clickPreparedBootstrap(PROJECT_URL, CONTINUE_PROMPT,
                            "bootstrap-voice-probe"));
            assertEquals("SUBMISSION_PENDING", pending.getString("status"));
            assertTrue(pending.getString("detail").contains("dispatch=BOOTSTRAP_CLICKED"));
            assertEquals("0", read(scenario, web, "String(window.voiceClicks)"));
            assertEquals("1", read(scenario, web, "String(window.submitCount)"));

            evaluate(scenario, web,
                    "(()=>{const m=document.createElement('div');m.setAttribute('data-message-author-role','user');document.querySelector('main').prepend(m);return JSON.stringify({status:'USER_APPENDED'});})()");
            JSONObject routeMissing = evaluate(scenario, web,
                    SelfRunContinuationDom.prepareBootstrap(PROJECT_URL, CONTINUE_PROMPT,
                            "bootstrap-voice-probe"));
            assertEquals("COMPOSER_INPUTTING", routeMissing.getString("status"));
            evaluate(scenario, web,
                    "(()=>{history.replaceState({},'', '/g/g-p-test/c/conversation123');return JSON.stringify({status:'ROUTE_CREATED'});})()");
            JSONObject confirmed = evaluate(scenario, web,
                    SelfRunContinuationDom.prepareBootstrap(PROJECT_URL, CONTINUE_PROMPT,
                            "bootstrap-voice-probe"));
            assertEquals("SUBMISSION_CONFIRMED", confirmed.getString("status"));
            assertEquals("1", read(scenario, web, "String(window.submitCount)"));
        }
    }

    private static void assertContinuationState(String controls, String expected) throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            String markerId = "classifier-" + expected.toLowerCase();
            loadContinuationFixture(scenario, web, controls);
            evaluate(scenario, web,
                    "(()=>{window.__selfRunDriveMarkers={'selfrun-drive:verified-continuation:"
                            + markerId + "':JSON.stringify({state:'prepared'})};document.getElementById('prompt-textarea').value='"
                            + CONTINUE_PROMPT + "';return JSON.stringify({status:'READY'});})()");
            JSONObject result = evaluate(scenario, web,
                    SelfRunContinuationDom.clickPreparedDriveTurn(CONVERSATION_URL, CONTINUE_PROMPT,
                            markerId, RUN_ID));
            boolean dispatchExpected = SelfRunContinuationDom.SEND_ENABLED.equals(expected)
                    || SelfRunContinuationDom.COMPOSER_IDLE.equals(expected);
            String expectedResult = dispatchExpected ? "SUBMISSION_PENDING" : expected;
            assertEquals(expectedResult, result.getString("status"));
            if (dispatchExpected) {
                assertTrue(result.getString("detail").contains("dispatch=CONTINUE_CLICKED"));
                String expectedSubmitPath = SelfRunContinuationDom.SEND_ENABLED.equals(expected)
                        ? "submit=button" : "submit=form_request_submit";
                assertTrue(result.getString("detail").contains(expectedSubmitPath));
                assertEquals("clicked", read(scenario, web,
                        "JSON.parse(window.__selfRunDriveMarkers['selfrun-drive:verified-continuation:"
                                + markerId + "']).state"));
                assertEquals("1", read(scenario, web, "String(window.submitCount)"));
            }
        }
    }

    private static void loadContinuationFixture(ActivityScenario<SelfRunNewActivity> scenario,
                                                AtomicReference<WebView> web, String controls) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            view.getSettings().setJavaScriptEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView ignored, String url) {
                    if (CONVERSATION_URL.equals(url)) loaded.countDown();
                }
                @Override public boolean shouldOverrideUrlLoading(WebView ignored, WebResourceRequest request) {
                    return String.valueOf(request.getUrl()).startsWith("selfrun-drive://");
                }
            });
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(CONVERSATION_URL, continuationFixture(controls),
                    "text/html", "UTF-8", null);
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
                @Override public boolean shouldOverrideUrlLoading(WebView ignored, WebResourceRequest request) {
                    return String.valueOf(request.getUrl()).startsWith("selfrun-drive://");
                }
            });
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(baseUrl, voiceIdleFixture(), "text/html", "UTF-8", null);
        });
        assertTrue("Voice-idle WebView fixture did not load", loaded.await(15, TimeUnit.SECONDS));
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
                + "<script>window.stopClicks=0;window.submitCount=0;const form=document.querySelector('form');"
                + "form?.addEventListener('submit',event=>{event.preventDefault();window.submitCount++;});"
                + "const stop=document.getElementById('stop');if(stop)stop.addEventListener('click',()=>window.stopClicks++);</script>"
                + "</body></html>";
    }

    private static String voiceIdleFixture() {
        return "<!doctype html><html><head><style>body{margin:20px}button{display:block;margin:8px}</style></head>"
                + "<body><main><form id='composer'><textarea id='prompt-textarea'></textarea>"
                + "<button id='voice' type='submit' data-testid='composer-speech-button' aria-label='Start voice mode'>Voice</button>"
                + "</form></main><script>window.voiceClicks=0;window.submitCount=0;"
                + "const form=document.getElementById('composer'),composer=document.getElementById('prompt-textarea'),voice=document.getElementById('voice');"
                + "voice.addEventListener('click',event=>{event.preventDefault();window.voiceClicks++;});"
                + "form.addEventListener('submit',event=>{event.preventDefault();window.submitCount++;});"
                + "composer.addEventListener('input',()=>{if(!composer.value||document.getElementById('send'))return;voice.remove();"
                + "const send=document.createElement('button');send.id='send';send.type='submit';send.dataset.testid='send-button';send.setAttribute('aria-label','Send');send.textContent='Send';form.appendChild(send);});</script>"
                + "</body></html>";
    }
}
