package com.shaterguy.chatgptselfrun;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ProtocolDetachedSurfaceWebViewTest {
    private static final String RUN_ID="SR-DETACHED-PROTOCOL";
    private static final String TOKEN="protocol-token-current";
    private static final String ORIGIN="https://chatgpt.com/";

    @Test public void detachedSurfaceSeparatesProBoundaryFromFinalCompletion() throws Exception {
        try(ActivityScenario<SelfRunNewActivity> scenario=ActivityScenario.launch(SelfRunNewActivity.class)){
            AtomicReference<HeadlessWebViewHost> hostRef=new AtomicReference<>();
            AtomicReference<WebView> webRef=new AtomicReference<>();
            AtomicReference<SelfRunStore> storeRef=new AtomicReference<>();
            AtomicReference<String> callbackRef=new AtomicReference<>("");
            AtomicInteger callbackCount=new AtomicInteger();
            CountDownLatch loaded=new CountDownLatch(1),completed=new CountDownLatch(1);

            scenario.onActivity(activity->{
                activity.getSharedPreferences("selfrun_drive",0).edit()
                        .putString("runId",RUN_ID)
                        .putString("mode",SelfRunStore.MODE_CHAT)
                        .putString("phase",SelfRunStore.PHASE_BOOTSTRAP_SEND)
                        .putBoolean("active",true).putBoolean("paused",false).commit();
                SelfRunStore store=new SelfRunStore(activity);
                store.prepareTurnProtocolToken(TOKEN);
                HeadlessWebViewHost host=HeadlessWebViewHost.create(activity);
                assertTrue(host.hasDetachableOutput());
                WebView web=host.webView();
                assertTrue(WebViewConfig.applyAutomation(web));
                web.setWebViewClient(new WebViewClient(){
                    @Override public void onPageFinished(WebView view,String url){
                        if(url!=null&&url.startsWith(ORIGIN))loaded.countDown();
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                        Uri uri=request.getUrl();
                        if(!ChatGptTurnProtocolScript.COMPLETION_SCHEME.equals(uri.getScheme()))return false;
                        String run=uri.getQueryParameter("run"),token=uri.getQueryParameter("token");
                        String source=uri.getQueryParameter("source");
                        callbackRef.set(uri.toString());callbackCount.incrementAndGet();
                        if(RUN_ID.equals(run)&&TOKEN.equals(token)
                                &&SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase())
                                &&TurnProtocolLogBridge.isAllowedCompletionSource(source)){
                            store.beginPostProtocolDriveSync(token,source);
                        }
                        completed.countDown();return true;
                    }
                });
                hostRef.set(host);webRef.set(web);storeRef.set(store);
                web.loadDataWithBaseURL(ORIGIN,"<!doctype html><html><body>protocol fixture</body></html>",
                        "text/html","UTF-8",null);
            });
            assertTrue("fixture load timed out",loaded.await(15,TimeUnit.SECONDS));
            assertEquals("true",evaluate(scenario,webRef,
                    "String(window.__selfRunTurnProtocol.bindTurn('"+RUN_ID+"','"+TOKEN+"'))"));
            scenario.onActivity(activity->{
                SelfRunStore store=storeRef.get();
                store.beginTurnCompletionWait(TOKEN,"protocol wait");
                assertTrue(hostRef.get().detachOutput());
                assertFalse(hostRef.get().isOutputAttached());
                webRef.get().evaluateJavascript(
                        ChatGptTurnProtocolScript.armCompletion(RUN_ID,TOKEN),null);
                assertFalse(store.beginPostProtocolDriveSync(
                        "stale-token","message_stream_complete"));
                assertEquals(SelfRunStore.PHASE_WAIT_TURN_COMPLETION,store.phase());
                assertFalse(hostRef.get().isOutputAttached());
            });

            JSONObject thinking=state(scenario,webRef,
                    "window.__selfRunTurnProtocol.observeRequest('POST','/backend-api/f/conversation')");
            assertEquals("THINKING",thinking.getString("phase"));
            assertEquals("CHAT",thinking.getString("detectorLane"));
            assertFalse(thinking.getBoolean("sawVisibleAnswer"));
            assertEquals("",thinking.getString("currentFinalMessageId"));
            assertEquals("",callbackRef.get());
            scenario.onActivity(activity->assertFalse(hostRef.get().isOutputAttached()));

            JSONObject handoff=state(scenario,webRef,
                    "window.__selfRunTurnProtocol.observeSseText("
                            +"'data: {\\\"type\\\":\\\"stream_handoff\\\"}\\n\\n',"
                            +"'fixture',{requestIdentity:window.__selfRunTurnProtocol.snapshot().requestIdentity})");
            assertEquals("THINKING",handoff.getString("phase"));
            assertEquals("PRO",handoff.getString("detectorLane"));
            assertTrue(handoff.getBoolean("sawStreamHandoff"));

            JSONObject premature=state(scenario,webRef,
                    "window.__selfRunTurnProtocol.observeSseText("
                            +"'data: {\\\"type\\\":\\\"message_stream_complete\\\"}\\n\\n',"
                            +"'fixture',{requestIdentity:window.__selfRunTurnProtocol.snapshot().requestIdentity})");
            assertEquals("THINKING",premature.getString("phase"));
            assertEquals("PRO",premature.getString("detectorLane"));
            assertTrue(premature.getBoolean("sawStreamComplete"));
            assertTrue(premature.getBoolean("proBoundarySeen"));
            assertFalse(premature.getBoolean("sawVisibleAnswer"));
            assertEquals("completion_without_final_answer_evidence",premature.getString("lastError"));
            assertEquals("",callbackRef.get());
            scenario.onActivity(activity->{
                assertEquals(SelfRunStore.PHASE_WAIT_TURN_COMPLETION,storeRef.get().phase());
                assertFalse(hostRef.get().isOutputAttached());
            });

            JSONObject answering=state(scenario,webRef,
                    "window.__selfRunTurnProtocol.observeSseText("
                            +"'data: {\\\"type\\\":\\\"message_start\\\",\\\"message\\\":{\\\"id\\\":\\\"final-message\\\",\\\"author\\\":{\\\"role\\\":\\\"assistant\\\"},\\\"channel\\\":\\\"final\\\",\\\"content\\\":{\\\"parts\\\":[\\\"최종 답변\\\"]}}}\\n\\n',"
                            +"'fixture',{requestIdentity:window.__selfRunTurnProtocol.snapshot().requestIdentity})");
            assertEquals("ANSWERING",answering.getString("phase"));
            assertTrue(answering.getBoolean("sawAssistantFinalText"));
            assertEquals("",answering.getString("lastError"));
            assertEquals("",callbackRef.get());

            JSONObject complete=state(scenario,webRef,
                    "window.__selfRunTurnProtocol.observeSseText("
                            +"'data: {\\\"status\\\":\\\"finished_successfully\\\",\\\"end_turn\\\":true}\\n\\n',"
                            +"'fixture',{requestIdentity:window.__selfRunTurnProtocol.snapshot().requestIdentity})");
            assertEquals("COMPLETE",complete.getString("phase"));
            assertTrue(complete.getBoolean("sawStreamComplete"));
            assertTrue(complete.getBoolean("sawVisibleAnswer"));
            assertEquals("finished_successfully_end_turn",complete.getString("completionSource"));
            assertTrue("protocol completion callback timed out",completed.await(15,TimeUnit.SECONDS));
            scenario.onActivity(activity->{
                assertEquals(SelfRunStore.PHASE_POST_PROTOCOL_DRIVE_SYNC,storeRef.get().phase());
                assertFalse(hostRef.get().isOutputAttached());
            });
            String callback=callbackRef.get();
            assertTrue(callback.contains("run="+RUN_ID));
            assertTrue(callback.contains("token="+TOKEN));
            assertTrue(callback.contains("source=finished_successfully_end_turn"));
            assertEquals(1,callbackCount.get());

            state(scenario,webRef,
                    "window.__selfRunTurnProtocol.observeSseText("
                            +"'data: {\\\"status\\\":\\\"finished_successfully\\\",\\\"end_turn\\\":true}\\n\\n',"
                            +"'fixture',{requestIdentity:window.__selfRunTurnProtocol.snapshot().requestIdentity})");
            Thread.sleep(100L);
            assertEquals(1,callbackCount.get());
            scenario.onActivity(activity->assertFalse(hostRef.get().isOutputAttached()));

            scenario.onActivity(activity->hostRef.get().destroy());
        }
    }

    @Test public void proIngressDecodesHandoffAndFinalSocketFramesWithoutDom() throws Exception {
        final String run="SR-PRO-INGRESS",token="pro-ingress-token",conversation="pro-conversation",turn="pro-turn";
        try(ActivityScenario<SelfRunNewActivity> scenario=ActivityScenario.launch(SelfRunNewActivity.class)){
            AtomicReference<WebView> web=new AtomicReference<>();CountDownLatch loaded=new CountDownLatch(1);
            scenario.onActivity(activity->{
                WebView view=new WebView(activity);view.getSettings().setJavaScriptEnabled(true);view.getSettings().setDomStorageEnabled(true);
                view.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView ignored,String url){if(url!=null&&url.startsWith(ORIGIN))loaded.countDown();}});
                activity.setContentView(view);web.set(view);view.loadDataWithBaseURL(ORIGIN,"<!doctype html><html><body>pro ingress fixture</body></html>","text/html","UTF-8",null);
            });
            assertTrue("pro fixture load timed out",loaded.await(15,TimeUnit.SECONDS));
            evaluate(scenario,web,
                    "window.__selfRunRequestProfileEngine={target:()=>({runId:'"+run+"',mode:'chat'})};"
                    +"window.__fixtureSocket=null;class FixtureWebSocket extends EventTarget{constructor(){super();window.__fixtureSocket=this;}emit(data){this.dispatchEvent(new MessageEvent('message',{data:data}));}}"
                    +"FixtureWebSocket.CONNECTING=0;FixtureWebSocket.OPEN=1;FixtureWebSocket.CLOSING=2;FixtureWebSocket.CLOSED=3;window.WebSocket=FixtureWebSocket;"
                    +"window.Worker=undefined;window.SharedWorker=undefined;'ready'");
            evaluate(scenario,web,ChatGptTurnProtocolScript.documentStartScript());
            assertEquals("true",evaluate(scenario,web,"String(window.__selfRunTurnProtocol.bindTurn('"+run+"','"+token+"'))"));
            evaluate(scenario,web,ProTurnProtocolIngressScript.documentStartScript());
            evaluate(scenario,web,"window.__fixtureClient=new WebSocket('wss://chatgpt.com/pro');'created'");
            JSONObject started=state(scenario,web,"window.__selfRunTurnProtocol.observeRequest('POST','/backend-api/f/conversation')");
            assertEquals("CHAT",started.getString("detectorLane"));assertEquals("THINKING",started.getString("phase"));

            JSONObject handoff=new JSONObject().put("type","stream_handoff").put("conversation_id",conversation).put("turn_id",turn);
            JSONObject early=new JSONObject().put("type","message_stream_complete");
            String earlyEncoded="data: "+handoff+"\n\ndata: "+early+"\n\n";
            JSONObject earlyPayload=new JSONObject().put("type","stream-item").put("conversation_id",conversation).put("turn_id",turn).put("encoded_item",earlyEncoded);
            String earlyFrame=new JSONObject().put("payload",new JSONObject().put("payload",earlyPayload)).toString();
            JSONObject afterEarly=state(scenario,web,"(()=>{window.__fixtureSocket.emit("+JSONObject.quote(earlyFrame)+");return window.__selfRunTurnProtocol.snapshot();})()");
            assertEquals("PRO",afterEarly.getString("detectorLane"));assertEquals("THINKING",afterEarly.getString("phase"));
            assertTrue(afterEarly.getBoolean("proBoundarySeen"));assertTrue(afterEarly.getBoolean("sawStreamComplete"));

            JSONObject finalMessage=new JSONObject().put("type","message_start").put("message",new JSONObject().put("id","pro-final")
                    .put("author",new JSONObject().put("role","assistant")).put("content",new JSONObject().put("parts",new org.json.JSONArray().put("Pro 최종 답변"))));
            JSONObject terminal=new JSONObject().put("status","finished_successfully").put("end_turn",true);
            String finalEncoded="data: "+finalMessage+"\n\ndata: "+terminal+"\n\n";
            JSONObject finalPayload=new JSONObject().put("type","stream-item").put("conversation_id",conversation).put("turn_id",turn).put("encoded_item",finalEncoded);
            String finalFrame=new JSONObject().put("payload",new JSONObject().put("payload",finalPayload)).toString();
            JSONObject finished=state(scenario,web,"(()=>{window.__fixtureSocket.emit("+JSONObject.quote(finalFrame)+");return window.__selfRunTurnProtocol.snapshot();})()");
            assertEquals("COMPLETE",finished.getString("phase"));assertTrue(finished.getBoolean("sawAssistantFinalText"));
            assertEquals("finished_successfully_end_turn",finished.getString("completionSource"));
            JSONObject diagnostics=state(scenario,web,"window.__selfRunProTurnProtocolIngress.diagnostics()");
            assertTrue(diagnostics.getInt("forwardedFrames")>=2);assertTrue(diagnostics.getInt("semanticSignals")>=4);
        }
    }

    private static JSONObject state(ActivityScenario<SelfRunNewActivity> scenario,
                                    AtomicReference<WebView> web,String expression) throws Exception {
        return new JSONObject(evaluate(scenario,web,"JSON.stringify("+expression+")"));
    }

    private static String evaluate(ActivityScenario<SelfRunNewActivity> scenario,
                                   AtomicReference<WebView> web,String script) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>("");
        scenario.onActivity(activity->web.get().evaluateJavascript(script,value->{
            try{
                Object parsed=new org.json.JSONTokener(value).nextValue();
                result.set(parsed instanceof String?(String)parsed:String.valueOf(parsed));
            }catch(Throwable ignored){result.set(value);}
            done.countDown();
        }));
        assertTrue("JavaScript timed out",done.await(15,TimeUnit.SECONDS));
        return result.get();
    }
}
