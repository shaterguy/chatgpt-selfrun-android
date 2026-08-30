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

/** Executes captured Chat, Work, and Pro ordering inside Android WebView. */
@RunWith(AndroidJUnit4.class)
public final class TurnProtocolStateWebViewTest {
    private static final String ORIGIN = "https://chatgpt.com/";
    private static final String CONVERSATION_ID = "fixture-conversation";

    @Test public void chatAndWorkDelegateToTheSingleDomCompletionOwner() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web);
            install(scenario, web);
            installFakeObserver(scenario, web);

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

            assertPhase("THINKING", semantic(scenario, web, marker("user_visible_token", "first")));
            JSONObject chatAnswering = semantic(scenario, web, marker("final_channel_token", "first"));
            assertPhase("ANSWERING", chatAnswering);
            assertTrue(chatAnswering.getBoolean("sawVisibleAnswer"));
            assertPhase("ANSWERING", semantic(scenario, web, marker("last_token", "last")));

            JSONObject chatComplete = semantic(scenario, web, new JSONObject()
                    .put("type", "message_stream_complete")
                    .put("conversation_id", CONVERSATION_ID));
            assertPhase("COMPLETE", chatComplete);
            assertTrue(chatComplete.getBoolean("completionDelegated"));
            assertEquals(1, readInt(scenario, web, "window.__delegateCount"));
            assertFalse(readBoolean(scenario, web, "window.__selfRunDriveTurnObserver.fired"));
            assertTrue(readBoolean(scenario, web,
                    "window.__selfRunDriveTurnObserver.allowIdleBaseline"));

            assertPhase("COMPLETE", socketOuterDone(scenario, web, "chat-turn-1"));
            assertEquals(1, readInt(scenario, web, "window.__delegateCount"));
            assertPhase("COMPLETE", semantic(scenario, web, new JSONObject()
                    .put("type", "message_stream_complete")
                    .put("conversation_id", CONVERSATION_ID)));
            assertEquals(1, readInt(scenario, web, "window.__delegateCount"));

            installFakeObserver(scenario, web);
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
            assertPhase("ANSWERING", socketOuterDone(scenario, web, "work-turn-2"));
            assertEquals(0, readInt(scenario, web, "window.__delegateCount"));

            JSONObject workComplete = socketEvent(scenario, web, "work-turn-2", new JSONObject()
                    .put("type", "message_stream_complete"));
            assertPhase("COMPLETE", workComplete);
            assertEquals(1, readInt(scenario, web, "window.__delegateCount"));
        }
    }

    @Test public void proEarlyCompleteCannotDisableFallbackAndNextPostStillAdvances() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web);
            install(scenario, web);
            installFakeObserver(scenario, web);

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

            JSONObject earlyComplete = socketEvent(scenario, web, "pro-turn-1", new JSONObject()
                    .put("type", "message_stream_complete"));
            assertPhase("THINKING", earlyComplete);
            assertEquals("completion_without_final_answer_evidence",
                    earlyComplete.getString("lastError"));
            assertFalse(readBoolean(scenario, web, "window.__selfRunDriveTurnObserver.fired"));
            assertEquals(0, readInt(scenario, web, "window.__delegateCount"));
            assertTrue(readBoolean(scenario, web,
                    "window.__selfRunDriveTurnObserver===window.__observerReference"));

            JSONObject followupStart = request(scenario, web, "POST",
                    "/backend-api/f/conversation");
            assertPhase("THINKING", followupStart);
            assertEquals(2, followupStart.getInt("turnSequence"));
            assertEquals("FOLLOWUP_TURN", followupStart.getString("turnKind"));

            installFakeObserver(scenario, web);
            assertPhase("THINKING", socketEncoded(scenario, web, "pro-turn-2", "data: [DONE]\n\n"));
            JSONObject visibleAnswer = socketEvent(scenario, web, "pro-turn-2", new JSONObject()
                    .put("type", "message_start")
                    .put("message", finalAssistant("pro-message-2", "Pro 후속 답변")));
            assertPhase("ANSWERING", visibleAnswer);
            assertTrue(visibleAnswer.getBoolean("sawVisibleAnswer"));
            assertTrue(visibleAnswer.getBoolean("sawAssistantFinalText"));
            assertEquals(0, readInt(scenario, web, "window.__delegateCount"));

            JSONObject followupComplete = socketEvent(scenario, web, "pro-turn-2", new JSONObject()
                    .put("type", "message_stream_complete"));
            assertPhase("COMPLETE", followupComplete);
            assertTrue(followupComplete.getBoolean("completionDelegated"));
            assertEquals(1, readInt(scenario, web, "window.__delegateCount"));
            assertFalse(readBoolean(scenario, web, "window.__selfRunDriveTurnObserver.fired"));
            assertPhase("COMPLETE", socketOuterDone(scenario, web, "pro-turn-2"));
            assertEquals(1, readInt(scenario, web, "window.__delegateCount"));
        }
    }

    private static void installFakeObserver(ActivityScenario<SelfRunNewActivity> scenario,
                                            AtomicReference<WebView> web) throws Exception {
        evaluateRaw(scenario, web,
                "window.__delegateCount=0;window.__selfRunDriveTurnObserver={"
                        + "token:'fixture-token',fired:false,allowIdleBaseline:false,"
                        + "evaluate:function(){window.__delegateCount++;}};"
                        + "window.__observerReference=window.__selfRunDriveTurnObserver;");
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
                        text.isEmpty() ? new org.json.JSONArray()
                                : new org.json.JSONArray().put(text)));
    }

    private static JSONObject request(ActivityScenario<SelfRunNewActivity> scenario,
                                      AtomicReference<WebView> web, String method, String path)
            throws Exception {
        return state(scenario, web, "window.__selfRunTurnProtocol.observeRequest("
                + JSONObject.quote(method) + ","
                + JSONObject.quote(ORIGIN.substring(0, ORIGIN.length() - 1) + path) + ")");
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

    private static int readInt(ActivityScenario<SelfRunNewActivity> scenario,
                               AtomicReference<WebView> web, String expression) throws Exception {
        return ((Number) new JSONTokener(evaluateRaw(scenario, web, expression)).nextValue()).intValue();
    }

    private static boolean readBoolean(ActivityScenario<SelfRunNewActivity> scenario,
                                       AtomicReference<WebView> web, String expression) throws Exception {
        return (Boolean) new JSONTokener(evaluateRaw(scenario, web, expression)).nextValue();
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
