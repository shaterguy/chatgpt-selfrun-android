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

/** Executes the protocol observer inside Android WebView with captured Chat, Work, and Pro ordering. */
@RunWith(AndroidJUnit4.class)
public final class TurnProtocolStateWebViewTest {
    private static final String ORIGIN = "https://chatgpt.com/";
    private static final String CONVERSATION_ID = "fixture-conversation";

    @Test public void chatAndWorkUseCanonicalPostVisibleAnswerAndSemanticComplete() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web);
            install(scenario, web);

            assertPhase("IDLE", request(scenario, web, "POST",
                    "/backend-api/f/conversation/prepare"));
            assertPhase("IDLE", request(scenario, web, "POST",
                    "/backend-api/conversation/init"));
            assertPhase("IDLE", request(scenario, web, "POST",
                    "/backend-api/f/responses"));

            JSONObject chatStart = request(scenario, web, "POST",
                    "/backend-api/f/conversation");
            assertPhase("THINKING", chatStart);
            assertEquals(1, chatStart.getInt("turnSequence"));
            assertEquals("FIRST_TURN", chatStart.getString("turnKind"));

            JSONObject firstMetadata = semantic(scenario, web, new JSONObject()
                    .put("type", "server_ste_metadata")
                    .put("conversation_id", CONVERSATION_ID)
                    .put("metadata", new JSONObject().put("is_first_turn", true)));
            assertTrue(firstMetadata.getBoolean("serverFirstTurn"));
            assertFalse(firstMetadata.getBoolean("firstTurnMismatch"));

            assertPhase("THINKING", semantic(scenario, web, marker("user_visible_token", "first")));
            JSONObject chatAnswering = semantic(scenario, web, marker("final_channel_token", "first"));
            assertPhase("ANSWERING", chatAnswering);
            assertTrue(chatAnswering.getBoolean("sawVisibleAnswer"));
            assertPhase("ANSWERING", semantic(scenario, web, marker("last_token", "last")));

            JSONObject chatComplete = semantic(scenario, web, new JSONObject()
                    .put("type", "message_stream_complete")
                    .put("conversation_id", CONVERSATION_ID));
            assertPhase("COMPLETE", chatComplete);
            assertTrue(chatComplete.getBoolean("sawStreamComplete"));

            JSONObject workStart = request(scenario, web, "POST",
                    "/backend-api/f/conversation");
            assertPhase("THINKING", workStart);
            assertEquals(2, workStart.getInt("turnSequence"));
            assertEquals("FOLLOWUP_TURN", workStart.getString("turnKind"));

            JSONObject secondMetadata = socketEvent(scenario, web, "work-turn-2", new JSONObject()
                    .put("type", "server_ste_metadata")
                    .put("metadata", new JSONObject().put("is_first_turn", false)));
            assertFalse(secondMetadata.getBoolean("serverFirstTurn"));
            assertFalse(secondMetadata.getBoolean("firstTurnMismatch"));
            assertPhase("THINKING", socketEvent(scenario, web, "work-turn-2",
                    marker("user_visible_token", "first")));
            assertPhase("THINKING", socketEvent(scenario, web, "work-turn-2",
                    marker("cot_token", "first")));
            assertPhase("ANSWERING", socketEvent(scenario, web, "work-turn-2",
                    marker("final_channel_token", "first")));

            JSONObject outerDoneBeforeSemanticComplete = socketOuterDone(scenario, web, "work-turn-2");
            assertPhase("ANSWERING", outerDoneBeforeSemanticComplete);
            assertFalse(outerDoneBeforeSemanticComplete.getBoolean("sawStreamComplete"));

            JSONObject workComplete = socketEvent(scenario, web, "work-turn-2", new JSONObject()
                    .put("type", "message_stream_complete"));
            assertPhase("COMPLETE", workComplete);
            assertPhase("COMPLETE", socketOuterDone(scenario, web, "work-turn-2"));
        }
    }

    @Test public void proIgnoresHandoffAndInnerDoneThenUsesVisibleAnswerForBothTurns() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web);
            install(scenario, web);

            JSONObject firstStart = request(scenario, web, "POST",
                    "/backend-api/f/conversation");
            assertPhase("THINKING", firstStart);
            assertEquals("FIRST_TURN", firstStart.getString("turnKind"));

            JSONObject handoff = semantic(scenario, web, new JSONObject()
                    .put("type", "stream_handoff")
                    .put("conversation_id", CONVERSATION_ID)
                    .put("turn_id", "pro-turn-1"));
            assertPhase("THINKING", handoff);
            assertPhase("THINKING", socketEncoded(scenario, web, "pro-turn-1", "data: [DONE]\n\n"));

            JSONObject emptyFinalMessage = new JSONObject()
                    .put("type", "message_start")
                    .put("message", finalAssistant("pro-message-1", ""));
            JSONObject firstVisibleAnswer = socketEvent(scenario, web, "pro-turn-1", emptyFinalMessage);
            assertPhase("ANSWERING", firstVisibleAnswer);
            assertTrue(firstVisibleAnswer.getBoolean("sawVisibleAnswer"));
            assertFalse(firstVisibleAnswer.getBoolean("sawAssistantFinalText"));

            JSONObject firstText = socketEvent(scenario, web, "pro-turn-1", new JSONObject()
                    .put("p", "/message/content/parts/0")
                    .put("v", "첫 Pro 답변"));
            assertPhase("ANSWERING", firstText);
            assertTrue(firstText.getBoolean("sawAssistantFinalText"));

            JSONObject firstOuterDone = socketOuterDone(scenario, web, "pro-turn-1");
            assertPhase("ANSWERING", firstOuterDone);
            assertFalse(firstOuterDone.getBoolean("sawStreamComplete"));

            JSONObject firstComplete = socketEvent(scenario, web, "pro-turn-1", new JSONObject()
                    .put("type", "message_stream_complete"));
            assertPhase("COMPLETE", firstComplete);
            assertPhase("COMPLETE", socketOuterDone(scenario, web, "pro-turn-1"));

            JSONObject followupStart = request(scenario, web, "POST",
                    "/backend-api/f/conversation");
            assertPhase("THINKING", followupStart);
            assertEquals(2, followupStart.getInt("turnSequence"));
            assertEquals("FOLLOWUP_TURN", followupStart.getString("turnKind"));
            assertPhase("THINKING", socketEncoded(scenario, web, "pro-turn-2", "data: [DONE]\n\n"));

            JSONObject followupVisibleAnswer = socketEvent(scenario, web, "pro-turn-2", new JSONObject()
                    .put("type", "message_start")
                    .put("message", finalAssistant("pro-message-2", "후속 Pro 답변")));
            assertPhase("ANSWERING", followupVisibleAnswer);
            assertTrue(followupVisibleAnswer.getBoolean("sawVisibleAnswer"));
            assertTrue(followupVisibleAnswer.getBoolean("sawAssistantFinalText"));

            JSONObject followupComplete = socketEvent(scenario, web, "pro-turn-2", new JSONObject()
                    .put("type", "message_stream_complete"));
            assertPhase("COMPLETE", followupComplete);
        }
    }

    private static JSONObject marker(String marker, String event) throws Exception {
        return new JSONObject()
                .put("type", "message_marker")
                .put("marker", marker)
                .put("event", event)
                .put("conversation_id", CONVERSATION_ID);
    }

    private static JSONObject finalAssistant(String id, String text) throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("author", new JSONObject().put("role", "assistant"))
                .put("channel", "final")
                .put("content", new JSONObject().put("parts",
                        text.isEmpty() ? new org.json.JSONArray() : new org.json.JSONArray().put(text)));
    }

    private static JSONObject request(ActivityScenario<SelfRunNewActivity> scenario,
                                      AtomicReference<WebView> web, String method, String path)
            throws Exception {
        return state(scenario, web, "window.__selfRunTurnProtocol.observeRequest("
                + JSONObject.quote(method) + "," + JSONObject.quote(ORIGIN.substring(0, ORIGIN.length() - 1) + path)
                + ")");
    }

    private static JSONObject semantic(ActivityScenario<SelfRunNewActivity> scenario,
                                       AtomicReference<WebView> web, JSONObject event) throws Exception {
        String encoded = "data: " + event + "\n\n";
        return state(scenario, web, "window.__selfRunTurnProtocol.observeSseText("
                + JSONObject.quote(encoded) + ",'fixture',{})");
    }

    private static JSONObject socketEvent(ActivityScenario<SelfRunNewActivity> scenario,
                                          AtomicReference<WebView> web, String turnId,
                                          JSONObject event) throws Exception {
        return socketEncoded(scenario, web, turnId, "data: " + event + "\n\n");
    }

    private static JSONObject socketEncoded(ActivityScenario<SelfRunNewActivity> scenario,
                                            AtomicReference<WebView> web, String turnId,
                                            String encodedItem) throws Exception {
        JSONObject payload = new JSONObject()
                .put("type", "stream-item")
                .put("conversation_id", CONVERSATION_ID)
                .put("turn_id", turnId)
                .put("encoded_item", encodedItem);
        return socketFrame(scenario, web, payload);
    }

    private static JSONObject socketOuterDone(ActivityScenario<SelfRunNewActivity> scenario,
                                              AtomicReference<WebView> web, String turnId)
            throws Exception {
        return socketFrame(scenario, web, new JSONObject()
                .put("type", "done")
                .put("conversation_id", CONVERSATION_ID)
                .put("turn_id", turnId));
    }

    private static JSONObject socketFrame(ActivityScenario<SelfRunNewActivity> scenario,
                                          AtomicReference<WebView> web, JSONObject payload)
            throws Exception {
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
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(ORIGIN,
                    "<!doctype html><html><body>turn protocol fixture</body></html>",
                    "text/html", "UTF-8", null);
        });
        assertTrue("Turn protocol fixture did not load", loaded.await(15, TimeUnit.SECONDS));
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
        assertTrue("Turn protocol WebView script timed out", complete.await(15, TimeUnit.SECONDS));
        return result.get();
    }
}
