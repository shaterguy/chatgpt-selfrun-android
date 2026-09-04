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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Executes the response protocol observer inside Android WebView. */
@RunWith(AndroidJUnit4.class)
public final class TurnProtocolStateWebViewTest {
    private static final String ORIGIN = "https://chatgpt.com/";
    private static final String CONVERSATION_ID = "fixture-conversation";

    @Test public void chatAndWorkUseCanonicalPostVisibleAnswerAndSemanticComplete() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web); install(scenario, web);

            assertPhase("IDLE", request(scenario, web, "POST", "/backend-api/f/conversation/prepare"));
            assertPhase("IDLE", request(scenario, web, "POST", "/backend-api/f/responses"));

            JSONObject chatStart = request(scenario, web, "POST", "/backend-api/f/conversation");
            assertPhase("THINKING", chatStart);
            String firstIdentity = chatStart.getString("requestIdentity");
            assertFalse(firstIdentity.isEmpty());
            assertFalse(chatStart.has("turnSequence"));
            assertFalse(chatStart.has("turnKind"));

            assertPhase("THINKING", semanticForIdentity(scenario, web, firstIdentity,
                    marker("user_visible_token", "first")));
            JSONObject chatAnswering = semanticForIdentity(scenario, web, firstIdentity,
                    marker("final_channel_token", "first"));
            assertPhase("ANSWERING", chatAnswering);
            assertTrue(chatAnswering.getBoolean("sawVisibleAnswer"));
            JSONObject chatComplete = semanticForIdentity(scenario, web, firstIdentity,
                    new JSONObject().put("type", "message_stream_complete")
                            .put("conversation_id", CONVERSATION_ID));
            assertPhase("COMPLETE", chatComplete);

            JSONObject workStart = request(scenario, web, "POST", "/backend-api/f/conversation");
            assertPhase("THINKING", workStart);
            assertNotEquals(firstIdentity, workStart.getString("requestIdentity"));
            assertPhase("THINKING", socketEvent(scenario, web, "work-request",
                    marker("cot_token", "first")));
            assertPhase("ANSWERING", socketEvent(scenario, web, "work-request",
                    marker("final_channel_token", "first")));
            JSONObject outerDone = socketOuterDone(scenario, web, "work-request");
            assertPhase("ANSWERING", outerDone);
            assertFalse(outerDone.getBoolean("sawStreamComplete"));
            assertPhase("COMPLETE", socketEvent(scenario, web, "work-request",
                    new JSONObject().put("type", "message_stream_complete")));
        }
    }

    @Test public void proIgnoresHandoffAndInnerDoneThenUsesVisibleAnswer() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web); install(scenario, web);

            assertPhase("THINKING", request(scenario, web, "POST", "/backend-api/f/conversation"));
            assertPhase("THINKING", semantic(scenario, web, new JSONObject()
                    .put("type", "stream_handoff").put("conversation_id", CONVERSATION_ID)
                    .put("turn_id", "pro-request")));
            assertPhase("THINKING", socketEncoded(scenario, web, "pro-request", "data: [DONE]\n\n"));

            JSONObject visible = socketEvent(scenario, web, "pro-request", new JSONObject()
                    .put("type", "message_start")
                    .put("message", finalAssistant("pro-message", "Pro 답변")));
            assertPhase("ANSWERING", visible);
            assertTrue(visible.getBoolean("sawVisibleAnswer"));
            assertTrue(visible.getBoolean("sawAssistantFinalText"));
            assertPhase("ANSWERING", socketOuterDone(scenario, web, "pro-request"));
            assertPhase("COMPLETE", socketEvent(scenario, web, "pro-request",
                    new JSONObject().put("type", "message_stream_complete")));
        }
    }

    @Test public void newestCanonicalPostSupersedesActiveResponseWithoutTurnNumbers() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web); install(scenario, web);

            JSONObject first = request(scenario, web, "POST", "/backend-api/f/conversation");
            String oldIdentity = first.getString("requestIdentity");
            assertPhase("THINKING", socketEvent(scenario, web, "old-work-id",
                    marker("cot_token", "first")));

            JSONObject replacement = request(scenario, web, "POST", "/backend-api/f/conversation");
            String newIdentity = replacement.getString("requestIdentity");
            assertPhase("THINKING", replacement);
            assertNotEquals(oldIdentity, newIdentity);

            JSONObject staleFetch = semanticForIdentity(scenario, web, oldIdentity,
                    marker("final_channel_token", "first"));
            assertPhase("THINKING", staleFetch);
            assertFalse(staleFetch.getBoolean("sawVisibleAnswer"));

            JSONObject staleSocket = socketEvent(scenario, web, "old-work-id",
                    marker("final_channel_token", "first"));
            assertPhase("THINKING", staleSocket);
            assertFalse(staleSocket.getBoolean("sawVisibleAnswer"));

            JSONObject replacementAnswer = semanticForIdentity(scenario, web, newIdentity,
                    marker("final_channel_token", "first"));
            assertPhase("ANSWERING", replacementAnswer);
            assertTrue(replacementAnswer.getBoolean("sawVisibleAnswer"));
            assertPhase("COMPLETE", semanticForIdentity(scenario, web, newIdentity,
                    new JSONObject().put("type", "message_stream_complete")
                            .put("conversation_id", CONVERSATION_ID)));
        }
    }

    private static JSONObject marker(String marker, String event) throws Exception {
        return new JSONObject().put("type", "message_marker").put("marker", marker)
                .put("event", event).put("conversation_id", CONVERSATION_ID);
    }

    private static JSONObject finalAssistant(String id, String text) throws Exception {
        return new JSONObject().put("id", id)
                .put("author", new JSONObject().put("role", "assistant"))
                .put("channel", "final")
                .put("content", new JSONObject().put("parts",
                        text.isEmpty() ? new org.json.JSONArray() : new org.json.JSONArray().put(text)));
    }

    private static JSONObject request(ActivityScenario<SelfRunNewActivity> scenario,
                                      AtomicReference<WebView> web, String method, String path) throws Exception {
        return state(scenario, web, "window.__selfRunTurnProtocol.observeRequest("
                + JSONObject.quote(method) + "," + JSONObject.quote(ORIGIN.substring(0, ORIGIN.length() - 1) + path)
                + ")");
    }

    private static JSONObject semantic(ActivityScenario<SelfRunNewActivity> scenario,
                                       AtomicReference<WebView> web, JSONObject event) throws Exception {
        return state(scenario, web, "window.__selfRunTurnProtocol.observeSseText("
                + JSONObject.quote("data: " + event + "\n\n") + ",'fixture',{})");
    }

    private static JSONObject semanticForIdentity(ActivityScenario<SelfRunNewActivity> scenario,
                                                  AtomicReference<WebView> web, String identity,
                                                  JSONObject event) throws Exception {
        String context = new JSONObject().put("requestIdentity", identity).toString();
        return state(scenario, web, "window.__selfRunTurnProtocol.observeSseText("
                + JSONObject.quote("data: " + event + "\n\n") + ",'fixture'," + context + ")");
    }

    private static JSONObject socketEvent(ActivityScenario<SelfRunNewActivity> scenario,
                                          AtomicReference<WebView> web, String turnId,
                                          JSONObject event) throws Exception {
        return socketEncoded(scenario, web, turnId, "data: " + event + "\n\n");
    }

    private static JSONObject socketEncoded(ActivityScenario<SelfRunNewActivity> scenario,
                                            AtomicReference<WebView> web, String turnId,
                                            String encodedItem) throws Exception {
        JSONObject payload = new JSONObject().put("type", "stream-item")
                .put("conversation_id", CONVERSATION_ID).put("turn_id", turnId)
                .put("encoded_item", encodedItem);
        return socketFrame(scenario, web, payload);
    }

    private static JSONObject socketOuterDone(ActivityScenario<SelfRunNewActivity> scenario,
                                              AtomicReference<WebView> web, String turnId) throws Exception {
        return socketFrame(scenario, web, new JSONObject().put("type", "done")
                .put("conversation_id", CONVERSATION_ID).put("turn_id", turnId));
    }

    private static JSONObject socketFrame(ActivityScenario<SelfRunNewActivity> scenario,
                                          AtomicReference<WebView> web, JSONObject payload) throws Exception {
        JSONObject frame = new JSONObject().put("payload", new JSONObject().put("payload", payload));
        return state(scenario, web, "window.__selfRunTurnProtocol.observeSocketFrame("
                + JSONObject.quote(frame.toString()) + ")");
    }

    private static void assertPhase(String expected, JSONObject state) throws Exception {
        assertEquals(expected, state.getString("phase"));
    }

    private static void install(ActivityScenario<SelfRunNewActivity> scenario,
                                AtomicReference<WebView> web) throws Exception {
        evaluateRaw(scenario, web, ChatGptTurnProtocolScript.documentStartScript());
        assertEquals("true", readString(scenario, web, "String(window.__selfRunTurnProtocol.bindTurn(\'fixture-run\',\'fixture-token\'))"));
        assertEquals(ChatGptTurnProtocolScript.ENGINE_VERSION,
                readString(scenario, web, "window.__selfRunTurnProtocol.version"));
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
            view.loadDataWithBaseURL(ORIGIN, "<!doctype html><html><body>response protocol fixture</body></html>",
                    "text/html", "UTF-8", null);
        });
        assertTrue("Response protocol fixture did not load", loaded.await(15, TimeUnit.SECONDS));
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
        assertTrue("Response protocol WebView script timed out", complete.await(15, TimeUnit.SECONDS));
        return result.get();
    }
}
