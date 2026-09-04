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
import static org.junit.Assert.assertTrue;

/** Exercises actual Work transport wrappers against the shared protocol state machine. */
@RunWith(AndroidJUnit4.class)
public final class WorkTurnProtocolIngressWebViewTest {
    private static final String ORIGIN = "https://chatgpt.com/";
    private static final String CONVERSATION_ID = "fixture-conversation";
    private static final String WEBSOCKET_TURN_ID = "fixture-work-turn-websocket";
    private static final String BINARY_TURN_ID = "fixture-work-turn-binary";
    private static final String WORKER_TURN_ID = "fixture-work-turn-worker";
    private static final String SHARED_WORKER_TURN_ID = "fixture-work-turn-shared-worker";
    private static final String CHAT_TURN_ID = "fixture-work-turn-chat";

    @Test public void workWebSocketUsesSemanticFramesNotOuterDone() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web);
            prepare(scenario, web, "work");
            install(scenario, web);

            JSONObject started = xhrPost(scenario, web);
            assertEquals("THINKING", started.getString("phase"));
            assertFalse(started.getString("requestIdentity").isEmpty());
            newWebSocket(scenario, web);

            assertEquals("THINKING", emitSocketString(scenario, web,
                    streamFrame(marker("user_visible_token", "first"), WEBSOCKET_TURN_ID)).getString("phase"));
            assertEquals("THINKING", emitSocketString(scenario, web,
                    streamFrame(marker("cot_token", "first"), WEBSOCKET_TURN_ID)).getString("phase"));
            assertEquals("THINKING", emitSocketString(scenario, web,
                    encodedFrame("data: [DONE]\n\n", WEBSOCKET_TURN_ID)).getString("phase"));

            JSONObject answering = emitSocketString(scenario, web,
                    streamFrame(marker("final_channel_token", "first"), WEBSOCKET_TURN_ID));
            assertEquals("ANSWERING", answering.getString("phase"));
            assertTrue(answering.getBoolean("sawVisibleAnswer"));

            JSONObject afterOuterDone = emitSocketString(scenario, web, outerDoneFrame(WEBSOCKET_TURN_ID));
            assertEquals("ANSWERING", afterOuterDone.getString("phase"));
            assertFalse(afterOuterDone.getBoolean("sawStreamComplete"));

            JSONObject complete = emitSocketString(scenario, web,
                    streamFrame(terminalComplete().put("conversation_id", CONVERSATION_ID), WEBSOCKET_TURN_ID));
            assertEquals("COMPLETE", complete.getString("phase"));
            assertTrue(complete.getBoolean("sawStreamComplete"));

            JSONObject ingress = diagnostics(scenario, web);
            assertTrue(ingress.getInt("webSocketMessages") >= 6);
            assertTrue(ingress.getInt("forwardedFrames") >= 6);
        }
    }

    @Test public void workIngressDecodesBinaryAndObservesWorkerChannels() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web);
            prepare(scenario, web, "work");
            install(scenario, web);

            xhrPost(scenario, web);
            newWebSocket(scenario, web);
            emitSocketExpression(scenario, web,
                    "new Blob([" + JSONObject.quote(streamFrame(marker("final_channel_token", "first"), BINARY_TURN_ID)) + "])" );
            eventuallyPhase(scenario, web, "ANSWERING");
            emitSocketExpression(scenario, web,
                    "new TextEncoder().encode(" + JSONObject.quote(streamFrame(
                            terminalComplete().put("conversation_id", CONVERSATION_ID), BINARY_TURN_ID)) + ").buffer");
            eventuallyPhase(scenario, web, "COMPLETE");
            assertTrue(diagnostics(scenario, web).getInt("binaryDecoded") >= 2);

            xhrPost(scenario, web);
            newWorker(scenario, web);
            assertEquals("ANSWERING", emitWorkerString(scenario, web,
                    streamFrame(marker("final_channel_token", "first"), WORKER_TURN_ID)).getString("phase"));
            assertEquals("COMPLETE", emitWorkerString(scenario, web,
                    streamFrame(terminalComplete().put("conversation_id", CONVERSATION_ID), WORKER_TURN_ID)).getString("phase"));
            assertTrue(diagnostics(scenario, web).getInt("workerMessages") >= 2);

            xhrPost(scenario, web);
            newSharedWorker(scenario, web);
            assertEquals("ANSWERING", emitSharedWorkerString(scenario, web,
                    streamFrame(marker("final_channel_token", "first"), SHARED_WORKER_TURN_ID)).getString("phase"));
            assertEquals("COMPLETE", emitSharedWorkerString(scenario, web,
                    streamFrame(terminalComplete().put("conversation_id", CONVERSATION_ID), SHARED_WORKER_TURN_ID)).getString("phase"));
            assertTrue(diagnostics(scenario, web).getInt("sharedWorkerMessages") >= 2);
        }
    }

    @Test public void chatTargetLeavesWorkOnlyIngressInactive() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web);
            prepare(scenario, web, "chat");
            install(scenario, web);

            assertEquals("IDLE", xhrPost(scenario, web).getString("phase"));
            newWebSocket(scenario, web);
            JSONObject unchanged = emitSocketString(scenario, web,
                    streamFrame(marker("final_channel_token", "first"), CHAT_TURN_ID));
            assertEquals("IDLE", unchanged.getString("phase"));
            assertFalse(unchanged.getBoolean("sawStreamComplete"));
            assertEquals(0, diagnostics(scenario, web).getInt("forwardedFrames"));
        }
    }

    private static JSONObject marker(String marker, String event) throws Exception {
        return new JSONObject().put("type", "message_marker").put("marker", marker)
                .put("event", event).put("conversation_id", CONVERSATION_ID);
    }

    private static JSONObject terminalComplete() throws Exception {
        return new JSONObject().put("type", "message_stream_complete")
                .put("status", "finished_successfully").put("end_turn", true)
                .put("message", new JSONObject().put("id", "terminal-final")
                        .put("author", new JSONObject().put("role", "assistant"))
                        .put("channel", "final").put("content",
                                new JSONObject().put("parts", new org.json.JSONArray().put("terminal answer"))));
    }

    private static String streamFrame(JSONObject semantic, String turnId) throws Exception {
        return encodedFrame("data: " + semantic + "\n\n", turnId);
    }

    private static String encodedFrame(String encodedItem, String turnId) throws Exception {
        JSONObject payload = new JSONObject().put("type", "stream-item")
                .put("conversation_id", CONVERSATION_ID).put("turn_id", turnId)
                .put("encoded_item", encodedItem);
        return new JSONObject().put("payload", new JSONObject().put("payload", payload)).toString();
    }

    private static String outerDoneFrame(String turnId) throws Exception {
        JSONObject done = new JSONObject().put("type", "done")
                .put("conversation_id", CONVERSATION_ID).put("turn_id", turnId);
        return new JSONObject().put("payload", new JSONObject().put("payload", done)).toString();
    }

    private static void prepare(ActivityScenario<SelfRunNewActivity> scenario,
                                AtomicReference<WebView> web, String mode) throws Exception {
        evaluateRaw(scenario, web,
                "window.__selfRunRequestProfileEngine={target:()=>({mode:"
                        + JSONObject.quote(mode) + ",runId:'fixture-run'})};"
                        + "XMLHttpRequest.prototype.open=function(method,url){this.__fixtureOpen=[method,url];};"
                        + "XMLHttpRequest.prototype.send=function(body){this.__fixtureBody=body;};"
                        + "window.__fixtureSocket=null;window.__fixtureWorker=null;window.__fixtureSharedWorker=null;"
                        + "class FixtureWebSocket extends EventTarget{constructor(url){super();this.url=url;window.__fixtureSocket=this;}emit(data){this.dispatchEvent(new MessageEvent('message',{data:data}));}}"
                        + "FixtureWebSocket.CONNECTING=0;FixtureWebSocket.OPEN=1;FixtureWebSocket.CLOSING=2;FixtureWebSocket.CLOSED=3;window.WebSocket=FixtureWebSocket;"
                        + "class FixtureWorker extends EventTarget{constructor(url){super();this.url=url;window.__fixtureWorker=this;}emit(data){this.dispatchEvent(new MessageEvent('message',{data:data}));}}window.Worker=FixtureWorker;"
                        + "class FixturePort extends EventTarget{start(){}emit(data){this.dispatchEvent(new MessageEvent('message',{data:data}));}}"
                        + "class FixtureSharedWorker{constructor(url){this.url=url;this.port=new FixturePort();window.__fixtureSharedWorker=this;}}window.SharedWorker=FixtureSharedWorker;");
    }

    private static void install(ActivityScenario<SelfRunNewActivity> scenario,
                                AtomicReference<WebView> web) throws Exception {
        evaluateRaw(scenario, web, ChatGptTurnProtocolScript.documentStartScript());
        evaluateRaw(scenario, web, "window.__selfRunTurnProtocol.bindTurn(\'fixture-run\',\'fixture-token\');");
        evaluateRaw(scenario, web, WorkTurnProtocolIngressScript.documentStartScript());
        assertEquals(ChatGptTurnProtocolScript.ENGINE_VERSION,
                readString(scenario, web, "window.__selfRunTurnProtocol.version"));
        assertEquals(WorkTurnProtocolIngressScript.ENGINE_VERSION,
                readString(scenario, web, "window.__selfRunWorkTurnProtocolIngress.version"));
    }

    private static JSONObject xhrPost(ActivityScenario<SelfRunNewActivity> scenario,
                                      AtomicReference<WebView> web) throws Exception {
        return state(scenario, web,
                "(()=>{const xhr=new XMLHttpRequest();xhr.open('POST','https://chatgpt.com/backend-api/f/conversation');"
                        + "xhr.send('{}');return window.__selfRunTurnProtocol.snapshot();})()");
    }

    private static void newWebSocket(ActivityScenario<SelfRunNewActivity> scenario,
                                     AtomicReference<WebView> web) throws Exception {
        evaluateRaw(scenario, web, "new WebSocket('wss://chatgpt.com/p19/ws/user/fixture');");
    }

    private static void newWorker(ActivityScenario<SelfRunNewActivity> scenario,
                                  AtomicReference<WebView> web) throws Exception {
        evaluateRaw(scenario, web, "new Worker('fixture-worker.js');");
    }

    private static void newSharedWorker(ActivityScenario<SelfRunNewActivity> scenario,
                                        AtomicReference<WebView> web) throws Exception {
        evaluateRaw(scenario, web, "new SharedWorker('fixture-shared-worker.js');");
    }

    private static JSONObject emitSocketString(ActivityScenario<SelfRunNewActivity> scenario,
                                               AtomicReference<WebView> web, String frame) throws Exception {
        return state(scenario, web,
                "(()=>{window.__fixtureSocket.emit(" + JSONObject.quote(frame)
                        + ");return window.__selfRunTurnProtocol.snapshot();})()");
    }

    private static void emitSocketExpression(ActivityScenario<SelfRunNewActivity> scenario,
                                             AtomicReference<WebView> web, String expression) throws Exception {
        evaluateRaw(scenario, web, "window.__fixtureSocket.emit(" + expression + ");");
    }

    private static JSONObject emitWorkerString(ActivityScenario<SelfRunNewActivity> scenario,
                                               AtomicReference<WebView> web, String frame) throws Exception {
        return state(scenario, web,
                "(()=>{window.__fixtureWorker.emit(" + JSONObject.quote(frame)
                        + ");return window.__selfRunTurnProtocol.snapshot();})()");
    }

    private static JSONObject emitSharedWorkerString(ActivityScenario<SelfRunNewActivity> scenario,
                                                     AtomicReference<WebView> web, String frame) throws Exception {
        return state(scenario, web,
                "(()=>{window.__fixtureSharedWorker.port.emit(" + JSONObject.quote(frame)
                        + ");return window.__selfRunTurnProtocol.snapshot();})()");
    }

    private static JSONObject diagnostics(ActivityScenario<SelfRunNewActivity> scenario,
                                          AtomicReference<WebView> web) throws Exception {
        return state(scenario, web, "window.__selfRunWorkTurnProtocolIngress.diagnostics()");
    }

    private static void eventuallyPhase(ActivityScenario<SelfRunNewActivity> scenario,
                                        AtomicReference<WebView> web, String expected) throws Exception {
        JSONObject last = null;
        for (int i = 0; i < 80; i++) {
            last = state(scenario, web, "window.__selfRunTurnProtocol.snapshot()");
            if (expected.equals(last.getString("phase"))) return;
            Thread.sleep(25L);
        }
        throw new AssertionError("Expected phase " + expected + " but was "
                + (last == null ? "UNKNOWN" : last.optString("phase", "UNKNOWN")));
    }

    private static void load(ActivityScenario<SelfRunNewActivity> scenario,
                             AtomicReference<WebView> web) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView ignored, String url) {
                    if (url != null && url.startsWith(ORIGIN)) loaded.countDown();
                }
            });
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(ORIGIN,
                    "<!doctype html><html><body>work protocol ingress fixture</body></html>",
                    "text/html", "UTF-8", null);
        });
        assertTrue("Work protocol ingress fixture did not load", loaded.await(15, TimeUnit.SECONDS));
    }

    private static JSONObject state(ActivityScenario<SelfRunNewActivity> scenario,
                                    AtomicReference<WebView> web, String expression) throws Exception {
        String raw = evaluateRaw(scenario, web, "JSON.stringify(" + expression + ")");
        Object decoded = new JSONTokener(raw).nextValue();
        return new JSONObject(String.valueOf(decoded));
    }

    private static String readString(ActivityScenario<SelfRunNewActivity> scenario,
                                     AtomicReference<WebView> web, String expression) throws Exception {
        return String.valueOf(new JSONTokener(evaluateRaw(scenario, web, expression)).nextValue());
    }

    private static String evaluateRaw(ActivityScenario<SelfRunNewActivity> scenario,
                                      AtomicReference<WebView> web, String script) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(script, value -> {
            result.set(value);
            complete.countDown();
        }));
        assertTrue("Work protocol ingress WebView script timed out", complete.await(15, TimeUnit.SECONDS));
        return result.get();
    }
}
