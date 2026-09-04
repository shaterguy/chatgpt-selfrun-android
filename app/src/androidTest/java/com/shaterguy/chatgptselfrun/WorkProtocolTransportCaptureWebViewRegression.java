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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Actual-WebView regression for dev4 native admission, relay, SW message, and stale fencing. */
final class WorkProtocolTransportCaptureWebViewRegression {
    private static final String ORIGIN = "https://chatgpt.com/";
    private static final String CONVERSATION_ID = "capture-conversation";

    private WorkProtocolTransportCaptureWebViewRegression() {}

    static void run() throws Exception {
        duplicateNativeObservationDoesNotRestartMainTurn();
        serviceWorkerAndPortReuseExistingDecoderWithoutEarlyComplete();
        relayedSemanticUsesMainAuthorityAndRejectsStaleTurn();
        sameRunNewTokenRejectsRetiredWorkTurn();
        chatModeIgnoresNewWorkSources();
    }

    private static void duplicateNativeObservationDoesNotRestartMainTurn() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = load(scenario);prepare(scenario, web, "work");install(scenario, web);
            JSONObject first = xhrPost(scenario, web);String firstIdentity = first.getString("requestIdentity");
            JSONObject result = state(scenario, web,
                    "(()=>{const accepted=window.__selfRunWorkProtocolTransportCapture.observeNativeCanonical('native_webview','fixture-run');"
                            + "return {accepted,phase:window.__selfRunTurnProtocol.snapshot().phase,identity:window.__selfRunTurnProtocol.snapshot().requestIdentity};})()");
            assertFalse(result.getBoolean("accepted"));assertEquals("THINKING", result.getString("phase"));
            assertEquals(firstIdentity, result.getString("identity"));
        }
    }

    private static void serviceWorkerAndPortReuseExistingDecoderWithoutEarlyComplete() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = load(scenario);prepare(scenario, web, "work");install(scenario, web);xhrPost(scenario, web);
            callTransport(scenario, web, "observeServiceWorkerData", encodedFrame("data: [DONE]\n\n", "sw-turn"));
            eventuallyPhase(scenario, web, "THINKING");
            callTransport(scenario, web, "observeServiceWorkerData", outerDoneFrame("sw-turn"));
            assertEquals("THINKING", snapshot(scenario, web).getString("phase"));
            callTransport(scenario, web, "observeServiceWorkerData", streamFrame(marker("final_channel_token", "first"), "sw-turn"));
            eventuallyPhase(scenario, web, "ANSWERING");
            callTransport(scenario, web, "observeServiceWorkerPortData",
                    streamFrame(terminalComplete("sw-final").put("conversation_id", CONVERSATION_ID), "sw-turn"));
            eventuallyPhase(scenario, web, "COMPLETE");
        }
    }

    private static void relayedSemanticUsesMainAuthorityAndRejectsStaleTurn() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = load(scenario);prepare(scenario, web, "work");install(scenario, web);
            JSONObject first = xhrPost(scenario, web);String firstIdentity=first.getString("requestIdentity");
            relay(scenario, web, marker("final_channel_token", "first").put("turn_id", "relay-old"));
            assertEquals("ANSWERING", snapshot(scenario, web).getString("phase"));
            JSONObject second=xhrPost(scenario, web);assertEquals("THINKING",second.getString("phase"));
            assertNotEquals(firstIdentity,second.getString("requestIdentity"));
            relay(scenario, web, new JSONObject().put("type","message_stream_complete")
                    .put("conversation_id",CONVERSATION_ID).put("turn_id","relay-old"));
            assertEquals("THINKING",snapshot(scenario, web).getString("phase"));
            relay(scenario, web, marker("final_channel_token", "first").put("turn_id", "relay-current"));
            assertEquals("ANSWERING",snapshot(scenario, web).getString("phase"));
            relay(scenario, web, terminalComplete("relay-final")
                    .put("conversation_id",CONVERSATION_ID).put("turn_id","relay-current"));
            assertEquals("COMPLETE",snapshot(scenario, web).getString("phase"));
        }
    }

    private static void sameRunNewTokenRejectsRetiredWorkTurn() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web=load(scenario);prepare(scenario,web,"work");install(scenario,web);
            xhrPost(scenario,web);
            assertTrue(relayAccepted(scenario,web,marker("final_channel_token","first").put("turn_id","retired-turn")));
            assertEquals("ANSWERING",snapshot(scenario,web).getString("phase"));
            assertEquals("true",read(scenario,web,
                    "String(window.__selfRunTurnProtocol.bindTurn('fixture-run','fixture-token-2'))"));
            JSONObject current=xhrPost(scenario,web);
            assertEquals("fixture-token-2",current.getString("turnToken"));
            assertEquals("THINKING",current.getString("phase"));
            String currentIdentity=current.getString("requestIdentity");
            String currentWorkTurn=current.getString("currentWorkTurnId");
            assertTrue(relayAccepted(scenario,web,marker("final_channel_token","first").put("turn_id","retired-turn")));
            JSONObject afterStaleFinal=snapshot(scenario,web);
            assertEquals("fixture-token-2",afterStaleFinal.getString("turnToken"));
            assertEquals(currentIdentity,afterStaleFinal.getString("requestIdentity"));
            assertEquals(currentWorkTurn,afterStaleFinal.getString("currentWorkTurnId"));
            assertEquals("THINKING",afterStaleFinal.getString("phase"));
            assertFalse(afterStaleFinal.getBoolean("sawFinalChannelToken"));
            assertTrue(relayAccepted(scenario,web,new JSONObject().put("type","message_stream_complete")
                    .put("conversation_id",CONVERSATION_ID).put("turn_id","retired-turn")));
            JSONObject afterStaleComplete=snapshot(scenario,web);
            assertEquals("fixture-token-2",afterStaleComplete.getString("turnToken"));
            assertEquals(currentIdentity,afterStaleComplete.getString("requestIdentity"));
            assertEquals(currentWorkTurn,afterStaleComplete.getString("currentWorkTurnId"));
            assertEquals("THINKING",afterStaleComplete.getString("phase"));
            assertFalse(afterStaleComplete.getBoolean("sawStreamComplete"));
            assertEquals("",afterStaleComplete.getString("completionSource"));
            assertTrue(relayAccepted(scenario,web,marker("final_channel_token","first").put("turn_id","current-turn")));
            assertEquals("ANSWERING",snapshot(scenario,web).getString("phase"));
            assertTrue(relayAccepted(scenario,web,terminalComplete("current-final")
                    .put("conversation_id",CONVERSATION_ID).put("turn_id","current-turn")));
            assertEquals("COMPLETE",snapshot(scenario,web).getString("phase"));
        }
    }

    private static void chatModeIgnoresNewWorkSources() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = load(scenario);prepare(scenario, web, "chat");install(scenario, web);
            JSONObject result=state(scenario, web,
                    "(()=>({accepted:window.__selfRunWorkProtocolTransportCapture.observeNativeCanonical('native_webview','fixture-run'),phase:window.__selfRunTurnProtocol.snapshot().phase}))()");
            assertFalse(result.getBoolean("accepted"));assertEquals("IDLE",result.getString("phase"));
            assertFalse(callTransport(scenario, web,"observeServiceWorkerData",streamFrame(marker("final_channel_token","first"),"chat-turn")));
            assertEquals("IDLE",snapshot(scenario, web).getString("phase"));
        }
    }

    private static JSONObject marker(String marker,String event)throws Exception{return new JSONObject().put("type","message_marker")
            .put("marker",marker).put("event",event).put("conversation_id",CONVERSATION_ID);}
    private static JSONObject terminalComplete(String id)throws Exception{return new JSONObject().put("type","message_stream_complete")
            .put("status","finished_successfully").put("end_turn",true)
            .put("message",new JSONObject().put("id",id).put("author",new JSONObject().put("role","assistant"))
                    .put("channel","final").put("content",new JSONObject().put("parts",new org.json.JSONArray().put("terminal answer"))));}
    private static String streamFrame(JSONObject semantic,String turnId)throws Exception{return encodedFrame("data: "+semantic+"\n\n",turnId);}
    private static String encodedFrame(String encoded,String turnId)throws Exception{JSONObject payload=new JSONObject().put("type","stream-item")
            .put("conversation_id",CONVERSATION_ID).put("turn_id",turnId).put("encoded_item",encoded);
        return new JSONObject().put("payload",new JSONObject().put("payload",payload)).toString();}
    private static String outerDoneFrame(String turnId)throws Exception{return new JSONObject().put("payload",new JSONObject().put("payload",
            new JSONObject().put("type","done").put("conversation_id",CONVERSATION_ID).put("turn_id",turnId))).toString();}

    private static AtomicReference<WebView> load(ActivityScenario<SelfRunNewActivity> scenario)throws Exception{
        AtomicReference<WebView> web=new AtomicReference<>();CountDownLatch loaded=new CountDownLatch(1);
        scenario.onActivity(activity->{WebView view=new WebView(activity);view.getSettings().setJavaScriptEnabled(true);view.getSettings().setDomStorageEnabled(true);
            view.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView ignored,String url){if(url!=null&&url.startsWith(ORIGIN))loaded.countDown();}});
            activity.setContentView(view);web.set(view);view.loadDataWithBaseURL(ORIGIN,"<!doctype html><html><body>capture fixture</body></html>","text/html","UTF-8",null);});
        assertTrue("capture fixture did not load",loaded.await(15,TimeUnit.SECONDS));return web;}
    private static void prepare(ActivityScenario<SelfRunNewActivity> scenario,AtomicReference<WebView> web,String mode)throws Exception{
        eval(scenario,web,"window.__selfRunRequestProfileEngine={target:()=>({mode:"+JSONObject.quote(mode)+",runId:'fixture-run'})};"
                +"XMLHttpRequest.prototype.open=function(method,url){this.__fixture=[method,url];};XMLHttpRequest.prototype.send=function(body){};"
                +"class FWS extends EventTarget{constructor(url){super();}}FWS.CONNECTING=0;FWS.OPEN=1;FWS.CLOSING=2;FWS.CLOSED=3;window.WebSocket=FWS;"
                +"class FW extends EventTarget{constructor(url){super();}}window.Worker=FW;class FP extends EventTarget{start(){}};class FSW{constructor(url){this.port=new FP();}}window.SharedWorker=FSW;");}
    private static void install(ActivityScenario<SelfRunNewActivity> scenario,AtomicReference<WebView> web)throws Exception{
        eval(scenario,web,ChatGptTurnProtocolScript.documentStartScript());eval(scenario,web,"window.__selfRunTurnProtocol.bindTurn(\'fixture-run\',\'fixture-token\');");eval(scenario,web,WorkTurnProtocolIngressScript.documentStartScript());
        eval(scenario,web,WorkProtocolTransportCaptureScript.documentStartScript());
        assertEquals(WorkProtocolTransportCaptureScript.ENGINE_VERSION,read(scenario,web,"window.__selfRunWorkProtocolTransportCapture.version"));}
    private static JSONObject xhrPost(ActivityScenario<SelfRunNewActivity> scenario,AtomicReference<WebView> web)throws Exception{return state(scenario,web,
            "(()=>{const xhr=new XMLHttpRequest();xhr.open('POST','https://chatgpt.com/backend-api/f/conversation');xhr.send('{}');return window.__selfRunTurnProtocol.snapshot();})()");}
    private static JSONObject snapshot(ActivityScenario<SelfRunNewActivity> scenario,AtomicReference<WebView> web)throws Exception{return state(scenario,web,"window.__selfRunTurnProtocol.snapshot()");}
    private static boolean callTransport(ActivityScenario<SelfRunNewActivity> scenario,AtomicReference<WebView> web,String method,String frame)throws Exception{
        return Boolean.parseBoolean(read(scenario,web,"String(window.__selfRunWorkProtocolTransportCapture."+method+"("+JSONObject.quote(frame)+"))"));}
    private static boolean relayAccepted(ActivityScenario<SelfRunNewActivity> scenario,AtomicReference<WebView> web,JSONObject semantic)throws Exception{
        return Boolean.parseBoolean(read(scenario,web,
                "String(window.__selfRunWorkProtocolTransportCapture.observeRelayedSemantic("+semantic+",'subframe_websocket'))"));}
    private static void relay(ActivityScenario<SelfRunNewActivity> scenario,AtomicReference<WebView> web,JSONObject semantic)throws Exception{
        assertTrue(relayAccepted(scenario,web,semantic));}
    private static void eventuallyPhase(ActivityScenario<SelfRunNewActivity> scenario,AtomicReference<WebView> web,String expected)throws Exception{
        for(int i=0;i<80;i++){if(expected.equals(snapshot(scenario,web).optString("phase")))return;Thread.sleep(25L);}throw new AssertionError("phase != "+expected);}
    private static JSONObject state(ActivityScenario<SelfRunNewActivity> scenario,AtomicReference<WebView> web,String expression)throws Exception{
        return new JSONObject(String.valueOf(new JSONTokener(eval(scenario,web,"JSON.stringify("+expression+")")).nextValue()));}
    private static String read(ActivityScenario<SelfRunNewActivity> scenario,AtomicReference<WebView> web,String expression)throws Exception{
        return String.valueOf(new JSONTokener(eval(scenario,web,expression)).nextValue());}
    private static String eval(ActivityScenario<SelfRunNewActivity> scenario,AtomicReference<WebView> web,String script)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();scenario.onActivity(activity->web.get().evaluateJavascript(script,value->{result.set(value);done.countDown();}));
        assertTrue("capture script timed out",done.await(15,TimeUnit.SECONDS));return result.get();}
}
