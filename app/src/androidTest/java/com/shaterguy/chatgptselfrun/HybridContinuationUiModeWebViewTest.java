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

/** Verifies the exact HYBRID UI-mode readback gate before opaque continuation transports. */
@RunWith(AndroidJUnit4.class)
public final class HybridContinuationUiModeWebViewTest {
    private static final String CHAT_URL = "https://chatgpt.com/";
    private static final String RUN = "SR-20260905-121811-HYBRID";

    @Test public void lunaMaxToChatInstantRequiresCheckedUiAtPrepareAndSubmitBoundaries()
            throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario =
                     ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = loadFixture(scenario);
            HybridRunProfileStore.Selection selection = exactSelection(scenario);
            read(scenario, web, "localStorage.removeItem('" + WebUiCalibrationStore.STORAGE_KEY + "')");
            read(scenario, web, RequestProfileScript.documentStartScript());
            read(scenario, web, HybridRequestProfileScript.documentStartScript(selection));

            String prepareAction = "(()=>{window.prepareCount+=1;return JSON.stringify({status:'READY_TO_SUBMIT',detail:'fixture-prepared'});})()";
            JSONObject switching = evaluate(scenario, web,
                    HybridRequestProfileScript.prepareContinuationAndThen(RUN, prepareAction));
            assertEquals("UI_WAIT", switching.getString("status"));
            assertEquals("switching",
                    switching.getJSONObject("diagnostics").getString("hybridModeOutcome"));
            assertEquals("0", read(scenario, web, "String(window.prepareCount)"));
            assertEquals("bootstrap", read(scenario, web,
                    "window.__selfRunHybridProfileBridge.stage()"));
            assertEquals("work", read(scenario, web,
                    "window.__selfRunRequestProfileEngine.target().mode"));

            Thread.sleep(100L);
            JSONObject prepared = evaluate(scenario, web,
                    HybridRequestProfileScript.prepareContinuationAndThen(RUN, prepareAction));
            assertEquals("READY_TO_SUBMIT", prepared.getString("status"));
            assertEquals("verified",
                    prepared.getJSONObject("diagnostics").getString("hybridModeOutcome"));
            assertEquals("chat",
                    prepared.getJSONObject("diagnostics").getString("hybridModeObserved"));
            assertEquals("1", read(scenario, web, "String(window.prepareCount)"));
            assertEquals("continuation", read(scenario, web,
                    "window.__selfRunHybridProfileBridge.stage()"));
            assertEquals("chat", read(scenario, web,
                    "window.__selfRunRequestProfileEngine.target().mode"));
            assertEquals("instant", read(scenario, web,
                    "window.__selfRunRequestProfileEngine.target().reasoning"));

            read(scenario, web, "window.selectFixtureMode('work')");
            String workerAction = "(()=>{new Worker('fixture-worker.js');return JSON.stringify({status:'CONTINUE_CLICKED',detail:'fixture-worker-dispatch'});})()";
            JSONObject submitSwitching = evaluate(scenario, web,
                    HybridRequestProfileScript.selectContinuationAndThen(RUN, workerAction));
            assertEquals("UI_WAIT", submitSwitching.getString("status"));
            assertEquals("0", read(scenario, web, "String(window.workerDispatchCount)"));

            Thread.sleep(100L);
            JSONObject submitted = evaluate(scenario, web,
                    HybridRequestProfileScript.selectContinuationAndThen(RUN, workerAction));
            assertEquals("CONTINUE_CLICKED", submitted.getString("status"));
            assertEquals("submit",
                    submitted.getJSONObject("diagnostics").getString("hybridModeBoundary"));
            assertEquals("verified",
                    submitted.getJSONObject("diagnostics").getString("hybridModeOutcome"));
            assertEquals("1", read(scenario, web, "String(window.workerDispatchCount)"));
            assertEquals("chat", read(scenario, web,
                    "window.workerObserved.mode"));
            assertEquals("instant", read(scenario, web,
                    "window.workerObserved.reasoning"));
            assertEquals("true", read(scenario, web,
                    "String(window.workerObserved.chatChecked)"));
        }
    }

    @Test public void lateRenderedModeRadioWaitsThenContinuesWithinBoundaryWindow()
            throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario =
                     ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = loadFixture(scenario);
            HybridRunProfileStore.Selection selection = exactSelection(scenario);
            read(scenario, web, "localStorage.removeItem('" + WebUiCalibrationStore.STORAGE_KEY + "')");
            read(scenario, web, RequestProfileScript.documentStartScript());
            read(scenario, web, HybridRequestProfileScript.documentStartScript(selection));
            String action = "(()=>{window.prepareCount+=1;return JSON.stringify({status:'READY_TO_SUBMIT'});})()";

            read(scenario, web,
                    "window.lateModeGroup=document.querySelector('[role=radiogroup]');"
                            + "window.lateModeGroup.remove()");
            JSONObject pending = evaluate(scenario, web,
                    HybridRequestProfileScript.prepareContinuationAndThen(RUN, action));
            assertEquals("UI_WAIT", pending.getString("status"));
            assertEquals("target_missing",
                    pending.getJSONObject("diagnostics").getString("hybridModeReason"));
            assertEquals("0", read(scenario, web, "String(window.prepareCount)"));
            assertEquals("bootstrap", read(scenario, web,
                    "window.__selfRunHybridProfileBridge.stage()"));

            read(scenario, web, "document.body.append(window.lateModeGroup)");
            JSONObject switching = evaluate(scenario, web,
                    HybridRequestProfileScript.prepareContinuationAndThen(RUN, action));
            assertEquals("UI_WAIT", switching.getString("status"));
            assertEquals("radio_clicked",
                    switching.getJSONObject("diagnostics").getString("hybridModeReason"));

            Thread.sleep(100L);
            JSONObject prepared = evaluate(scenario, web,
                    HybridRequestProfileScript.prepareContinuationAndThen(RUN, action));
            assertEquals("READY_TO_SUBMIT", prepared.getString("status"));
            assertEquals("1", read(scenario, web, "String(window.prepareCount)"));
            assertEquals("continuation", read(scenario, web,
                    "window.__selfRunHybridProfileBridge.stage()"));
        }
    }

    @Test public void missingModeRadioExpiresFailClosedWithoutStageOrAction()
            throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario =
                     ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = loadFixture(scenario);
            HybridRunProfileStore.Selection selection = exactSelection(scenario);
            read(scenario, web, "localStorage.removeItem('" + WebUiCalibrationStore.STORAGE_KEY + "')");
            read(scenario, web, RequestProfileScript.documentStartScript());
            read(scenario, web, HybridRequestProfileScript.documentStartScript(selection));
            String action = "(()=>{window.prepareCount+=1;return JSON.stringify({status:'READY_TO_SUBMIT'});})()";

            read(scenario, web, "document.querySelector('[role=radiogroup]').remove()");
            JSONObject pending = evaluate(scenario, web,
                    HybridRequestProfileScript.prepareContinuationAndThen(RUN, action));
            assertEquals("UI_WAIT", pending.getString("status"));
            read(scenario, web,
                    "window.__selfRunHybridModeUi.startedAt=Date.now()-10001");
            JSONObject expired = evaluate(scenario, web,
                    HybridRequestProfileScript.prepareContinuationAndThen(RUN, action));
            assertEquals("HYBRID_MODE_UNAVAILABLE", expired.getString("status"));
            assertEquals("target_missing",
                    expired.getJSONObject("diagnostics").getString("hybridModeReason"));
            assertEquals("0", read(scenario, web, "String(window.prepareCount)"));
            assertEquals("bootstrap", read(scenario, web,
                    "window.__selfRunHybridProfileBridge.stage()"));
            assertEquals("work", read(scenario, web,
                    "window.__selfRunRequestProfileEngine.target().mode"));
        }
    }

    @Test public void conflictingCounterpartStateFailsClosedBeforeStageOrAction()
            throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario =
                     ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = loadFixture(scenario);
            HybridRunProfileStore.Selection selection = exactSelection(scenario);
            read(scenario, web, "localStorage.removeItem('" + WebUiCalibrationStore.STORAGE_KEY + "')");
            read(scenario, web, RequestProfileScript.documentStartScript());
            read(scenario, web, HybridRequestProfileScript.documentStartScript(selection));
            String action = "(()=>{window.prepareCount+=1;return JSON.stringify({status:'READY_TO_SUBMIT'});})()";

            read(scenario, web,
                    "window.selectFixtureMode('chat');"
                            + "document.querySelector('[data-tpp-toggle-value=work]').dataset.state='on'");
            JSONObject conflict = evaluate(scenario, web,
                    HybridRequestProfileScript.prepareContinuationAndThen(RUN, action));
            assertEquals("HYBRID_MODE_UNAVAILABLE", conflict.getString("status"));
            assertEquals("state_conflict",
                    conflict.getJSONObject("diagnostics").getString("hybridModeReason"));
            assertEquals("conflict",
                    conflict.getJSONObject("diagnostics").getString("hybridModeObserved"));
            assertEquals("0", read(scenario, web, "String(window.prepareCount)"));
            assertEquals("bootstrap", read(scenario, web,
                    "window.__selfRunHybridProfileBridge.stage()"));
            assertEquals("work", read(scenario, web,
                    "window.__selfRunRequestProfileEngine.target().mode"));
        }
    }

    @Test public void ambiguousOrDisabledRadioFailsClosedWithoutActionOrStageChange()
            throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario =
                     ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = loadFixture(scenario);
            HybridRunProfileStore.Selection selection = exactSelection(scenario);
            read(scenario, web, "localStorage.removeItem('" + WebUiCalibrationStore.STORAGE_KEY + "')");
            read(scenario, web, RequestProfileScript.documentStartScript());
            read(scenario, web, HybridRequestProfileScript.documentStartScript(selection));
            String action = "(()=>{window.prepareCount+=1;return JSON.stringify({status:'READY_TO_SUBMIT'});})()";

            read(scenario, web,
                    "document.querySelector('[data-tpp-toggle-value=chatgpt]').after(document.querySelector('[data-tpp-toggle-value=chatgpt]').cloneNode(true))");
            JSONObject ambiguous = evaluate(scenario, web,
                    HybridRequestProfileScript.prepareContinuationAndThen(RUN, action));
            assertEquals("HYBRID_MODE_UNAVAILABLE", ambiguous.getString("status"));
            assertEquals("ambiguous_target",
                    ambiguous.getJSONObject("diagnostics").getString("hybridModeReason"));
            assertEquals("0", read(scenario, web, "String(window.prepareCount)"));
            assertEquals("bootstrap", read(scenario, web,
                    "window.__selfRunHybridProfileBridge.stage()"));

            read(scenario, web,
                    "document.querySelectorAll('[data-tpp-toggle-value=chatgpt]')[1].remove();"
                            + "document.querySelector('[data-tpp-toggle-value=chatgpt]').disabled=true");
            JSONObject disabled = evaluate(scenario, web,
                    HybridRequestProfileScript.prepareContinuationAndThen(RUN, action));
            assertEquals("HYBRID_MODE_UNAVAILABLE", disabled.getString("status"));
            assertEquals("target_disabled",
                    disabled.getJSONObject("diagnostics").getString("hybridModeReason"));
            assertEquals("0", read(scenario, web, "String(window.prepareCount)"));
            assertEquals("work", read(scenario, web,
                    "window.__selfRunRequestProfileEngine.target().mode"));
        }
    }

    private static HybridRunProfileStore.Selection exactSelection(
            ActivityScenario<SelfRunNewActivity> scenario) {
        AtomicReference<HybridRunProfileStore.Selection> value = new AtomicReference<>();
        scenario.onActivity(activity -> {
            ProfileRegistry.initialize(activity);
            ProfileRegistry.Profile work = ProfileRegistry.resolveWork("luna", "max");
            ProfileRegistry.Profile chat = ProfileRegistry.resolveChat("instant");
            assertTrue(work != null);
            assertTrue(chat != null);
            value.set(new HybridRunProfileStore.Selection(
                    RUN, HybridRunProfileStore.STAGE_BOOTSTRAP,
                    HybridRunProfileStore.Endpoint.fromProfile(work),
                    HybridRunProfileStore.Endpoint.fromProfile(chat)));
        });
        return value.get();
    }

    private static AtomicReference<WebView> loadFixture(
            ActivityScenario<SelfRunNewActivity> scenario) throws Exception {
        AtomicReference<WebView> web = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            view.getSettings().setJavaScriptEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView ignored, String url) {
                    loaded.countDown();
                }
            });
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(CHAT_URL, fixture(), "text/html", "UTF-8", null);
        });
        assertTrue("HYBRID UI fixture did not load", loaded.await(15, TimeUnit.SECONDS));
        return web;
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
        assertTrue("WebView read timed out", complete.await(15, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(raw.get()).nextValue();
        return String.valueOf(decoded);
    }

    private static String fixture() {
        return """
                <!doctype html><html><head><style>
                body{margin:0;padding:16px} [role=radiogroup]{display:flex;gap:8px}
                button{display:block;width:120px;height:48px}
                </style></head><body>
                <div role="radiogroup" aria-label="Mode">
                  <button role="radio" aria-checked="false" data-state="off"
                    data-tpp-toggle-value="chatgpt">Chat</button>
                  <button role="radio" aria-checked="true" data-state="on"
                    data-tpp-toggle-value="work"><span>Work</span></button>
                </div>
                <script>
                window.prepareCount=0;window.workerDispatchCount=0;window.workerObserved={};
                window.selectFixtureMode=value=>{
                  for(const radio of document.querySelectorAll('button[role=radio]')){
                    const selected=radio.dataset.tppToggleValue===value;
                    radio.setAttribute('aria-checked',selected?'true':'false');
                    radio.dataset.state=selected?'on':'off';
                  }
                };
                for(const radio of document.querySelectorAll('button[role=radio]')){
                  radio.addEventListener('click',()=>setTimeout(
                    ()=>window.selectFixtureMode(radio.dataset.tppToggleValue),0));
                }
                window.Worker=function(){
                  window.workerDispatchCount+=1;
                  const target=window.__selfRunRequestProfileEngine.target();
                  window.workerObserved={mode:target.mode,reasoning:target.reasoning,
                    chatChecked:document.querySelector('[data-tpp-toggle-value=chatgpt]')
                      .getAttribute('aria-checked')==='true'};
                };
                </script></body></html>
                """;
    }
}
