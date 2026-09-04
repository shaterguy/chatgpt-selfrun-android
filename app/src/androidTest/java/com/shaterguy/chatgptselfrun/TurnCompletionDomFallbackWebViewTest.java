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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Runtime WebView regression for the no-STOP assistant completion fallback. */
@RunWith(AndroidJUnit4.class)
public final class TurnCompletionDomFallbackWebViewTest {
    private static final String ORIGIN = "https://chatgpt.com/g/g-p-selfrun-dom/c/domfallback123";
    private static final String RUN_ID = "SR-DOM-FALLBACK";
    private static final String TOKEN = "dom-fallback-token";

    @Test public void mismatchedThinkingAndNoStopCompleteOnlyAfterNewAssistantFinalUi() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            AtomicReference<String> completion = new AtomicReference<>("");
            load(scenario, web, completion);

            evaluateRaw(scenario, web, TurnCompletionDomFallbackScript.documentStartScript(200L));
            evaluateRaw(scenario, web,
                    "window.__selfRunRequestProfileEngine={target:()=>({runId:'" + RUN_ID + "'})};"
                            + "window.__selfRunTurnProtocol={diagnostics:()=>({phase:'THINKING',observerToken:'stale-token',completionDispatched:false})};'ready';");

            assertEquals("old-appended", evaluate(scenario, web, appendTurn("old user", "old assistant")));
            assertEquals("armed", evaluate(scenario, web,
                    "(()=>{window.__selfRunDriveTurnObserver={token:'" + TOKEN + "',fired:false,timer:0,observer:{disconnect(){}}};return 'armed';})()"));

            Thread.sleep(350L);
            assertEquals("", completion.get());
            assertFalse(Boolean.parseBoolean(evaluate(scenario, web,
                    "String(window.__selfRunDomAssistantFallback.diagnostics().assistantBound)")));

            assertEquals("new-appended", evaluate(scenario, web, appendTurn("new user", "new assistant")));
            for (int attempt = 0; attempt < 30 && completion.get().isEmpty(); attempt++) Thread.sleep(50L);

            String callback = completion.get();
            assertTrue("DOM fallback did not emit completion callback", callback.startsWith("selfrun-drive://turn-completed?"));
            assertTrue(callback.contains("run=" + RUN_ID));
            assertTrue(callback.contains("token=" + TOKEN));
            assertTrue(callback.contains("source=dom_assistant_final_ui"));
        }
    }

    @Test public void correlatedThinkingAndAnsweringSuppressCompletionUntilProtocolLeavesActiveGeneration() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            AtomicReference<String> completion = new AtomicReference<>("");
            load(scenario, web, completion);

            evaluateRaw(scenario, web, TurnCompletionDomFallbackScript.documentStartScript(120L));
            assertEquals("old-appended", evaluate(scenario, web, appendTurn("old user", "old assistant")));
            evaluateRaw(scenario, web,
                    "window.__selfRunRequestProfileEngine={target:()=>({runId:'" + RUN_ID + "'})};"
                            + "window.__selfRunProtocolPhase='THINKING';"
                            + "window.__selfRunProtocolToken='" + TOKEN + "';"
                            + "window.__selfRunTurnProtocol={diagnostics:()=>({phase:window.__selfRunProtocolPhase,observerToken:window.__selfRunProtocolToken,completionDispatched:false})};'ready';");
            assertEquals("armed", evaluate(scenario, web,
                    "(()=>{window.__selfRunDriveTurnObserver={token:'" + TOKEN + "',fired:false,timer:0,observer:{disconnect(){}}};return 'armed';})()"));
            assertEquals("new-appended", evaluate(scenario, web, appendTurn("new user", "new assistant")));

            Thread.sleep(300L);
            assertEquals("correlated THINKING must suppress DOM completion", "", completion.get());
            evaluateRaw(scenario, web,
                    "window.__selfRunProtocolPhase='ANSWERING';window.__selfRunDomAssistantFallback.evaluate();'answering';");
            Thread.sleep(300L);
            assertEquals("correlated ANSWERING must suppress DOM completion", "", completion.get());

            evaluateRaw(scenario, web,
                    "window.__selfRunProtocolPhase='IDLE';window.__selfRunDomAssistantFallback.evaluate();'idle';");
            for (int attempt = 0; attempt < 30 && completion.get().isEmpty(); attempt++) Thread.sleep(50L);

            String callback = completion.get();
            assertTrue("DOM fallback did not resume after correlated generation ended",
                    callback.startsWith("selfrun-drive://turn-completed?"));
            assertTrue(callback.contains("run=" + RUN_ID));
            assertTrue(callback.contains("token=" + TOKEN));
            assertTrue(callback.contains("source=dom_assistant_final_ui"));
        }
    }

    @Test public void tokenlessMutationBurstIsDormantAndActiveBurstIsCoalesced() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            AtomicReference<String> completion = new AtomicReference<>("");
            load(scenario, web, completion);

            evaluateRaw(scenario, web, TurnCompletionDomFallbackScript.documentStartScript(1_000L));
            evaluateRaw(scenario, web,
                    "window.__selfRunRequestProfileEngine={target:()=>({runId:'" + RUN_ID + "'})};"
                            + "window.__selfRunProtocolPhase='THINKING';"
                            + "window.__selfRunProtocolToken='';"
                            + "window.__selfRunTurnProtocol={diagnostics:()=>({phase:window.__selfRunProtocolPhase,observerToken:window.__selfRunProtocolToken,completionDispatched:false})};'ready';");

            evaluateRaw(scenario, web, mutationBurst(400));
            Thread.sleep(350L);
            JSONObject dormant = new JSONObject(evaluate(scenario, web,
                    "JSON.stringify(window.__selfRunDomAssistantFallback.diagnostics())"));
            assertFalse(dormant.getBoolean("observerConnected"));
            assertEquals(0, dormant.getInt("mutationCallbacks"));
            assertEquals(0, dormant.getInt("expensiveEvaluations"));

            evaluateRaw(scenario, web,
                    "window.__selfRunProtocolToken='" + TOKEN + "';"
                            + "window.__selfRunDriveTurnObserver={token:'" + TOKEN + "',fired:false,timer:0,observer:{disconnect(){}}};'armed';");
            evaluateRaw(scenario, web, mutationBurst(400));
            Thread.sleep(600L);
            JSONObject active = new JSONObject(evaluate(scenario, web,
                    "JSON.stringify(window.__selfRunDomAssistantFallback.diagnostics())"));
            assertTrue(active.getBoolean("observerConnected"));
            assertTrue(active.getInt("mutationCallbacks") > 0);
            assertTrue("mutation burst must be coalesced", active.getInt("evaluations") <= 3);
            assertEquals("healthy protocol must suppress expensive fallback scan", 0,
                    active.getInt("expensiveEvaluations"));
            assertEquals("", completion.get());
        }
    }

    @Test public void workDecoderFrameBurstPreservesOrderAndYieldsBetweenBoundedBatches() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            AtomicReference<String> completion = new AtomicReference<>("");
            load(scenario, web, completion);

            evaluateRaw(scenario, web,
                    "window.__workOrder=[];window.__workPosts=0;window.__workDone=false;"
                            + "window.selfRunTurnLog={postMessage:()=>{window.__workPosts++;}};"
                            + "window.__selfRunRequestProfileEngine={target:()=>({mode:'work',runId:'SR-WORK-STRESS'})};"
                            + "window.__selfRunTurnProtocol={"
                            + "snapshot:()=>({phase:'THINKING',completionDispatched:false}),"
                            + "diagnostics:()=>({workTurnId:'WT-1'}),"
                            + "observeRequest:()=>true,"
                            + "observeSseText:(text)=>{const node=JSON.parse(String(text).slice(6).trim());window.__workOrder.push(node.seq);return {};}};'ready';");
            evaluateRaw(scenario, web, WorkTurnProtocolIngressScript.documentStartScript());
            evaluateRaw(scenario, web,
                    "(()=>{const p=[];for(let i=0;i<40;i++){p.push(window.__selfRunWorkTurnProtocolIngress.observeTransportData({type:'message_delta',turn_id:'WT-1',seq:i},'fixture'));}"
                            + "Promise.all(p).then(()=>{window.__workDone=true;});return 'queued';})()" );

            for (int attempt = 0; attempt < 100; attempt++) {
                if (Boolean.parseBoolean(evaluate(scenario, web, "String(window.__workDone)"))) break;
                Thread.sleep(50L);
            }
            assertTrue(Boolean.parseBoolean(evaluate(scenario, web, "String(window.__workDone)")));
            JSONObject result = new JSONObject(evaluate(scenario, web,
                    "JSON.stringify({diag:window.__selfRunWorkTurnProtocolIngress.diagnostics(),order:window.__workOrder,posts:window.__workPosts})"));
            JSONObject diag = result.getJSONObject("diag");
            assertEquals(40, diag.getInt("framesSeen"));
            assertTrue(diag.getInt("maxSynchronousBatch") <= 4);
            assertTrue("40 frames at batch 4 must yield repeatedly", diag.getInt("eventLoopYields") >= 9);
            assertTrue(diag.getInt("maxQueueDepth") >= 36);
            assertTrue("diagnostic bridge must be aggregated", result.getInt("posts") <= 4);
            assertEquals(40, result.getJSONArray("order").length());
            for (int i = 0; i < 40; i++) assertEquals(i, result.getJSONArray("order").getInt(i));
        }
    }

    private static String mutationBurst(int count) {
        return "(()=>{const main=document.querySelector('main');for(let i=0;i<" + count
                + ";i++){const n=document.createElement('span');n.textContent='s'+i;main.appendChild(n);}return 'mutated';})()";
    }

    private static String appendTurn(String userText, String assistantText) {
        String result = userText.startsWith("old") ? "old-appended" : "new-appended";
        return "(()=>{const main=document.querySelector('main'),form=main.querySelector('form');"
                + "const user=document.createElement('div');user.setAttribute('data-message-author-role','user');user.textContent="
                + SelfRunScript.quote(userText) + ";"
                + "const article=document.createElement('article');article.setAttribute('data-testid','conversation-turn-fixture');"
                + "const assistant=document.createElement('div');assistant.setAttribute('data-message-author-role','assistant');assistant.textContent="
                + SelfRunScript.quote(assistantText) + ";article.appendChild(assistant);"
                + "const action=document.createElement('button');action.setAttribute('data-testid','copy-turn');action.setAttribute('aria-label','Copy');action.textContent='Copy';article.appendChild(action);"
                + "main.insertBefore(user,form);main.insertBefore(article,form);return '" + result + "';})()";
    }

    private static void load(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web,
                             AtomicReference<String> completion) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView ignored, String url) {
                    if (url != null && url.startsWith("https://chatgpt.com/")) loaded.countDown();
                }

                @Override public boolean shouldOverrideUrlLoading(WebView ignored, WebResourceRequest request) {
                    String url = request == null || request.getUrl() == null ? "" : request.getUrl().toString();
                    if (url.startsWith("selfrun-drive://")) { completion.set(url); return true; }
                    return false;
                }

                @SuppressWarnings("deprecation")
                @Override public boolean shouldOverrideUrlLoading(WebView ignored, String url) {
                    if (url != null && url.startsWith("selfrun-drive://")) { completion.set(url); return true; }
                    return false;
                }
            });
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(ORIGIN, fixture(), "text/html", "UTF-8", null);
        });
        assertTrue("DOM fallback fixture did not load", loaded.await(15, TimeUnit.SECONDS));
    }

    private static String fixture() {
        return "<!doctype html><html><head><style>button{display:block}</style></head><body><main><form>"
                + "<div id='prompt-textarea' contenteditable='true' data-lexical-editor='true'><p><br></p></div>"
                + "<button type='submit' data-testid='send-button' aria-label='Send'>Send</button>"
                + "</form></main></body></html>";
    }

    private static String evaluate(ActivityScenario<SelfRunNewActivity> scenario,
                                   AtomicReference<WebView> web, String script) throws Exception {
        Object decoded = new JSONTokener(evaluateRaw(scenario, web, script)).nextValue();
        return String.valueOf(decoded);
    }

    private static String evaluateRaw(ActivityScenario<SelfRunNewActivity> scenario,
                                      AtomicReference<WebView> web, String script) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(script, value -> {
            result.set(value);
            complete.countDown();
        }));
        assertTrue("DOM fallback WebView script timed out", complete.await(15, TimeUnit.SECONDS));
        return result.get();
    }
}
