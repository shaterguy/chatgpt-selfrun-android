package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Compatibility fixtures derived from the inspector's recursive Work encoded-item decoder. */
@RunWith(AndroidJUnit4.class)
public final class WorkTurnProtocolInspectorCompatibilityWebViewTest {
    private static final String ORIGIN = "https://chatgpt.com/";
    private static final String CONVERSATION_ID = "inspector-compat-conversation";

    @Test public void nestedEncodedItemsSupportInspectorJsonUrlBase64AndSseWithoutEarlyComplete()
            throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web); prepare(scenario, web); install(scenario, web);

            assertEquals("THINKING", xhrPost(scenario, web).getString("phase"));
            newWebSocket(scenario, web);
            String turnId = "compat-turn-one";

            assertEquals("THINKING", emitString(scenario, web, nestedEncoded(
                    sse(marker("last_token", "first")), turnId)).getString("phase"));
            assertEquals("THINKING", emitString(scenario, web, nestedEncoded(
                    sse(new JSONObject().put("type", "stream_handoff")
                            .put("conversation_id", CONVERSATION_ID)), turnId)).getString("phase"));
            assertEquals("THINKING", emitString(scenario, web, nestedEncoded(
                    "data: [DONE]\n\n", turnId)).getString("phase"));

            String urlJson = URLEncoder.encode(marker("final_channel_token", "first").toString(),
                    StandardCharsets.UTF_8).replace("+", "%20");
            JSONObject answering = emitString(scenario, web, nestedEncoded(urlJson, turnId));
            assertEquals("ANSWERING", answering.getString("phase"));

            String completeSse = sse(new JSONObject().put("type", "message_stream_complete")
                    .put("conversation_id", CONVERSATION_ID));
            String b64Sse = Base64.getEncoder().encodeToString(
                    completeSse.getBytes(StandardCharsets.UTF_8));
            JSONObject complete = emitString(scenario, web, nestedEncoded(b64Sse, turnId));
            assertEquals("COMPLETE", complete.getString("phase"));
            assertTrue(complete.getBoolean("sawStreamComplete"));

            JSONObject ingress = diagnostics(scenario, web);
            assertTrue(ingress.getInt("encodedItemsFound") >= 5);
            assertTrue(ingress.getInt("decodedItems") >= 4);
            JSONObject decoders = ingress.getJSONObject("decoderKinds");
            assertTrue(decoders.optInt("sse", 0) >= 1);
            assertTrue(decoders.optInt("url-json", 0) >= 1);
            assertTrue(decoders.optInt("b64-sse", 0) >= 1);
        }
    }

    @Test public void arrayBufferViewQuotedAndBase64JsonPreserveStaleTurnFence() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web); prepare(scenario, web); install(scenario, web);

            xhrPost(scenario, web); newWebSocket(scenario, web);
            String oldTurnId = "compat-old-turn";
            String quotedMarker = JSONObject.quote(marker("final_channel_token", "first").toString());
            emitExpression(scenario, web,
                    "new TextEncoder().encode(" + JSONObject.quote(nestedEncoded(quotedMarker, oldTurnId)) + ")");
            eventuallyPhase(scenario, web, "ANSWERING");

            assertEquals("THINKING", xhrPost(scenario, web).getString("phase"));
            JSONObject stale = emitString(scenario, web, nestedEncoded(
                    sse(new JSONObject().put("type", "message_stream_complete")
                            .put("conversation_id", CONVERSATION_ID)), oldTurnId));
            assertEquals("THINKING", stale.getString("phase"));
            assertFalse(stale.getBoolean("sawStreamComplete"));
            assertTrue(diagnostics(scenario, web).getInt("staleFrames") >= 1);

            String newTurnId = "compat-new-turn";
            String b64Json = Base64.getEncoder().encodeToString(
                    marker("final_channel_token", "first").toString()
                            .getBytes(StandardCharsets.UTF_8));
            assertEquals("ANSWERING", emitString(scenario, web,
                    nestedEncoded(b64Json, newTurnId)).getString("phase"));
            assertEquals("COMPLETE", emitString(scenario, web, nestedEncoded(
                    new JSONObject().put("type", "message_stream_complete")
                            .put("conversation_id", CONVERSATION_ID).toString(), newTurnId))
                    .getString("phase"));

            JSONObject ingress = diagnostics(scenario, web);
            JSONObject decoders = ingress.getJSONObject("decoderKinds");
            assertTrue(decoders.optInt("json", 0) >= 2);
            assertTrue(decoders.optInt("b64-json", 0) >= 1);
            assertTrue(ingress.getInt("binaryDecoded") >= 1);
        }
    }

    private static JSONObject marker(String marker, String event) throws Exception {
        return new JSONObject().put("type", "message_marker").put("marker", marker)
                .put("event", event).put("conversation_id", CONVERSATION_ID);
    }

    private static String sse(JSONObject semantic) {
        return "data: " + semantic + "\n\n";
    }

    private static String nestedEncoded(String encodedItem, String turnId) throws Exception {
        JSONObject carrier = new JSONObject().put("conversation_id", CONVERSATION_ID)
                .put("turn_id", turnId)
                .put("envelope", new JSONObject().put("opaque", new JSONObject()
                        .put("encoded_item", encodedItem)));
        return new JSONObject().put("outer", new JSONObject()
                .put("transport", new JSONObject().put("carrier", carrier))).toString();
    }

    private static void prepare(ActivityScenario<SelfRunNewActivity> scenario,
                                AtomicReference<WebView> web) throws Exception {
        evaluateRaw(scenario, web,
                "window.__selfRunRequestProfileEngine={target:()=>({mode:'work',runId:'compat-run'})};"
                        + "XMLHttpRequest.prototype.open=function(method,url){this.__fixtureOpen=[method,url];};"
                        + "XMLHttpRequest.prototype.send=function(body){this.__fixtureBody=body;};"
                        + "window.__fixtureSocket=null;"
                        + "class FixtureWebSocket extends EventTarget{constructor(url){super();this.url=url;window.__fixtureSocket=this;}emit(data){this.dispatchEvent(new MessageEvent('message',{data:data}));}}"
                        + "FixtureWebSocket.CONNECTING=0;FixtureWebSocket.OPEN=1;FixtureWebSocket.CLOSING=2;FixtureWebSocket.CLOSED=3;window.WebSocket=FixtureWebSocket;");
    }

    private static void install(ActivityScenario<SelfRunNewActivity> scenario,
                                AtomicReference<WebView> web) throws Exception {
        evaluateRaw(scenario, web, ChatGptTurnProtocolScript.documentStartScript());
        evaluateRaw(scenario, web, WorkTurnProtocolIngressScript.documentStartScript());
        evaluateRaw(scenario, web, WorkProtocolTransportCaptureScript.documentStartScript());
        assertEquals(ChatGptTurnProtocolScript.ENGINE_VERSION,
                readString(scenario, web, "window.__selfRunTurnProtocol.version"));
        assertEquals(WorkTurnProtocolIngressScript.ENGINE_VERSION,
                readString(scenario, web, "window.__selfRunWorkTurnProtocolIngress.version"));
        assertEquals(WorkProtocolTransportCaptureScript.ENGINE_VERSION,
                readString(scenario, web, "window.__selfRunWorkProtocolTransportCapture.version"));
    }

    private static JSONObject xhrPost(ActivityScenario<SelfRunNewActivity> scenario,
                                      AtomicReference<WebView> web) throws Exception {
        return state(scenario, web,
                "(()=>{const xhr=new XMLHttpRequest();xhr.open('POST','https://chatgpt.com/backend-api/f/conversation');"
                        + "xhr.send('{}');return window.__selfRunTurnProtocol.snapshot();})()");
    }

    private static void newWebSocket(ActivityScenario<SelfRunNewActivity> scenario,
                                     AtomicReference<WebView> web) throws Exception {
        evaluateRaw(scenario, web, "new WebSocket('wss://chatgpt.com/p19/ws/user/compat');");
    }

    private static JSONObject emitString(ActivityScenario<SelfRunNewActivity> scenario,
                                         AtomicReference<WebView> web, String frame) throws Exception {
        evaluateRaw(scenario, web,
                "window.__fixtureSocket.emit(" + JSONObject.quote(frame) + ");'emitted';");
        return eventuallyQueueIdleState(scenario, web);
    }

    private static void emitExpression(ActivityScenario<SelfRunNewActivity> scenario,
                                       AtomicReference<WebView> web, String expression) throws Exception {
        evaluateRaw(scenario, web, "window.__fixtureSocket.emit(" + expression + ");");
    }

    private static JSONObject eventuallyQueueIdleState(ActivityScenario<SelfRunNewActivity> scenario,
                                                        AtomicReference<WebView> web) throws Exception {
        JSONObject ingress = null;
        for (int i = 0; i < 100; i++) {
            ingress = diagnostics(scenario, web);
            if (ingress.optInt("queueDepth", -1) == 0 && !ingress.optBoolean("queueRunning", true)) {
                return state(scenario, web, "window.__selfRunTurnProtocol.snapshot()");
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Work decoder queue did not become idle: " + ingress);
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
            activity.setContentView(view); web.set(view);
            view.loadDataWithBaseURL(ORIGIN,
                    "<!doctype html><html><body>inspector compatibility fixture</body></html>",
                    "text/html", "UTF-8", null);
        });
        assertTrue("Inspector compatibility fixture did not load", loaded.await(15, TimeUnit.SECONDS));
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
            result.set(value); complete.countDown();
        }));
        assertTrue("Inspector compatibility WebView script timed out", complete.await(15, TimeUnit.SECONDS));
        return result.get();
    }
}
