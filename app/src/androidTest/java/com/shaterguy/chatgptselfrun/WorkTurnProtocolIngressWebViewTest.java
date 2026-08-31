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

/** Exercises the Work-only XHR/WebSocket adapter against the shared protocol state machine. */
@RunWith(AndroidJUnit4.class)
public final class WorkTurnProtocolIngressWebViewTest {
    private static final String ORIGIN = "https://chatgpt.com/";
    private static final String CONVERSATION_ID = "fixture-conversation";
    private static final String WORK_TURN_ID = "fixture-work-turn";

    @Test public void workXhrStartAndNestedDoneReachSharedProtocol() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web);
            prepare(scenario, web, "work");
            install(scenario, web);

            JSONObject started = xhrPost(scenario, web);
            assertEquals("THINKING", started.getString("phase"));
            String identity = started.getString("requestIdentity");
            assertFalse(identity.isEmpty());

            JSONObject marker = new JSONObject().put("type", "message_marker")
                    .put("marker", "final_channel_token").put("event", "first")
                    .put("conversation_id", CONVERSATION_ID);
            JSONObject answering = state(scenario, web,
                    "window.__selfRunTurnProtocol.observeSseText("
                            + JSONObject.quote("data: " + marker + "\n\n")
                            + ",'fixture',{requestIdentity:" + JSONObject.quote(identity) + "})");
            assertEquals("ANSWERING", answering.getString("phase"));
            assertTrue(answering.getBoolean("sawVisibleAnswer"));

            JSONObject done = new JSONObject().put("type", "done")
                    .put("conversation_id", CONVERSATION_ID).put("turn_id", WORK_TURN_ID);
            JSONObject frame = new JSONObject().put("payload", new JSONObject().put("payload", done));
            JSONObject complete = state(scenario, web,
                    "(()=>{window.__selfRunWorkTurnProtocolIngress.observeSocketFrame("
                            + JSONObject.quote(frame.toString())
                            + ");return window.__selfRunTurnProtocol.snapshot();})()");
            assertEquals("COMPLETE", complete.getString("phase"));
            assertTrue(complete.getBoolean("sawStreamComplete"));
        }
    }

    @Test public void chatTargetIgnoresWorkOnlyXhrAndDoneIngress() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web);
            prepare(scenario, web, "chat");
            install(scenario, web);

            assertEquals("IDLE", xhrPost(scenario, web).getString("phase"));

            JSONObject done = new JSONObject().put("type", "done")
                    .put("conversation_id", CONVERSATION_ID).put("turn_id", WORK_TURN_ID);
            JSONObject frame = new JSONObject().put("payload", new JSONObject().put("payload", done));
            JSONObject unchanged = state(scenario, web,
                    "(()=>{window.__selfRunWorkTurnProtocolIngress.observeSocketFrame("
                            + JSONObject.quote(frame.toString())
                            + ");return window.__selfRunTurnProtocol.snapshot();})()");
            assertEquals("IDLE", unchanged.getString("phase"));
            assertFalse(unchanged.getBoolean("sawStreamComplete"));
        }
    }

    private static void prepare(ActivityScenario<SelfRunNewActivity> scenario,
                                AtomicReference<WebView> web, String mode) throws Exception {
        evaluateRaw(scenario, web,
                "window.__selfRunRequestProfileEngine={target:()=>({mode:"
                        + JSONObject.quote(mode) + ",runId:'fixture-run'})};"
                        + "XMLHttpRequest.prototype.open=function(method,url){this.__fixtureOpen=[method,url];};"
                        + "XMLHttpRequest.prototype.send=function(body){this.__fixtureBody=body;};");
    }

    private static void install(ActivityScenario<SelfRunNewActivity> scenario,
                                AtomicReference<WebView> web) throws Exception {
        evaluateRaw(scenario, web, ChatGptTurnProtocolScript.documentStartScript());
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
