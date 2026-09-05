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

/** Verifies HYBRID continuation activation through API profile readback, without a mode UI. */
@RunWith(AndroidJUnit4.class)
public final class HybridContinuationUiModeWebViewTest {
    private static final String CHAT_URL = "https://chatgpt.com/";
    private static final String RUN = "SR-20260905-142723-HYBRID";

    @Test public void noModeRadioPrepareThenSubmitUsesContinuationProfileAndDispatchesOnce()
            throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario =
                     ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = install(scenario);
            String prepareAction = "(()=>{window.prepareCount+=1;return JSON.stringify({status:'READY_TO_SUBMIT'});})()";
            JSONObject prepared = evaluate(scenario, web,
                    HybridRequestProfileScript.prepareContinuationAndThen(RUN, prepareAction));
            assertEquals("READY_TO_SUBMIT", prepared.getString("status"));
            assertEquals("verified", prepared.getJSONObject("diagnostics")
                    .getString("hybridProfileOutcome"));
            assertEquals("prepare", prepared.getJSONObject("diagnostics")
                    .getString("hybridProfileBoundary"));
            assertEquals("1", read(scenario, web, "String(window.prepareCount)"));
            assertEquals("continuation", read(scenario, web,
                    "window.__selfRunHybridProfileBridge.stage()"));
            assertEquals("chat", read(scenario, web,
                    "window.__selfRunHybridProfileBridge.target().mode"));
            assertEquals("instant", read(scenario, web,
                    "window.__selfRunHybridProfileBridge.target().reasoning"));
            assertEquals("instant", read(scenario, web,
                    "window.__selfRunHybridProfileBridge.target().bootstrapReasoning"));
            assertEquals("instant", read(scenario, web,
                    "window.__selfRunHybridProfileBridge.target().continuationReasoning"));
            assertEquals("0", read(scenario, web, "String(window.records.length)"));

            String submitAction = "(()=>{window.submitCount+=1;window.emitXhr();return JSON.stringify({status:'CONTINUE_CLICKED'});})()";
            JSONObject submitted = evaluate(scenario, web,
                    HybridRequestProfileScript.selectContinuationAndThen(RUN, submitAction));
            assertEquals("CONTINUE_CLICKED", submitted.getString("status"));
            assertEquals("verified", submitted.getJSONObject("diagnostics")
                    .getString("hybridProfileOutcome"));
            assertEquals("submit", submitted.getJSONObject("diagnostics")
                    .getString("hybridProfileBoundary"));
            assertEquals("1", read(scenario, web, "String(window.submitCount)"));
            assertEquals("1", read(scenario, web, "String(window.records.length)"));
            JSONObject payload = new JSONObject(read(scenario, web,
                    "window.records[0].body"));
            assertEquals("continuation", payload.getString("opaque"));
            assertEquals("gpt-5-6", payload.getString("model"));
            assertTrue(!payload.has("thinking_effort"));
            assertEquals("conversation-fixed", payload.getString("conversation_id"));
            assertEquals("preserve-me", payload.getJSONObject("custom").getString("value"));
        }
    }

    @Test public void activationFailurePreventsActionWithoutAnyModeRadio() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario =
                     ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = install(scenario);
            read(scenario, web,
                    "window.__selfRunHybridProfileBridge.selectStage=()=>{throw new Error('fixture');}");
            JSONObject failed = evaluate(scenario, web,
                    HybridRequestProfileScript.prepareContinuationAndThen(RUN,
                            "(()=>{window.prepareCount+=1;return JSON.stringify({status:'READY_TO_SUBMIT'});})()"));
            assertEquals("HYBRID_PROFILE_UNAVAILABLE", failed.getString("status"));
            assertEquals("profile_activation_failed", failed.getJSONObject("diagnostics")
                    .getString("hybridProfileReason"));
            assertEquals("0", read(scenario, web, "String(window.prepareCount)"));
            assertEquals("0", read(scenario, web, "String(window.records.length)"));
        }
    }

    @Test public void profileDriftPreventsActionAfterStageActivation() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario =
                     ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = install(scenario);
            read(scenario, web,
                    "const target=window.__selfRunHybridProfileBridge.target;"
                            + "window.__selfRunHybridProfileBridge.target=()=>({...target(),reasoning:'xhigh'});");
            JSONObject failed = evaluate(scenario, web,
                    HybridRequestProfileScript.selectContinuationAndThen(RUN,
                            "(()=>{window.submitCount+=1;window.emitXhr();return JSON.stringify({status:'CONTINUE_CLICKED'});})()"));
            assertEquals("HYBRID_PROFILE_UNAVAILABLE", failed.getString("status"));
            assertEquals("profile_readback_failed", failed.getJSONObject("diagnostics")
                    .getString("hybridProfileReason"));
            assertEquals("0", read(scenario, web, "String(window.submitCount)"));
            assertEquals("0", read(scenario, web, "String(window.records.length)"));
        }
    }

    private static AtomicReference<WebView> install(
            ActivityScenario<SelfRunNewActivity> scenario) throws Exception {
        AtomicReference<WebView> web = loadFixture(scenario);
        HybridRunProfileStore.Selection selection = exactSelection(scenario);
        read(scenario, web, RequestProfileScript.documentStartScript());
        read(scenario, web, HybridRequestProfileScript.documentStartScript(selection));
        assertEquals("0", read(scenario, web,
                "String(document.querySelectorAll('[role=radio]').length)"));
        return web;
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
        assertTrue("HYBRID profile fixture did not load", loaded.await(15, TimeUnit.SECONDS));
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
                <!doctype html><html><body><script>
                window.records=[];window.prepareCount=0;window.submitCount=0;
                class FixtureXHR {
                  open(method,url){this.method=method;this.url=new URL(url,location.href).href;}
                  setRequestHeader(){}
                  send(body){window.records.push({url:this.url,body});}
                }
                window.XMLHttpRequest=FixtureXHR;
                window.emitXhr=()=>{const xhr=new XMLHttpRequest();
                  xhr.open('POST','/backend-api/f/conversation');
                  xhr.send(JSON.stringify({action:'next',opaque:'continuation',
                    conversation_id:'conversation-fixed',parent_message_id:'parent-fixed',
                    custom:{value:'preserve-me'},model:'source-model',
                    thinking_effort:'source-effort',conversation_origin:'source-origin',
                    service_tier:'source-tier'}));};
                </script></body></html>
                """;
    }
}
