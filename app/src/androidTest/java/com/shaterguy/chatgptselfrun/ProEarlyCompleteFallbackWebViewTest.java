package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
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

/** Regression for Pro streams that report semantic stream completion before the final answer appears. */
@RunWith(AndroidJUnit4.class)
public final class ProEarlyCompleteFallbackWebViewTest {
    private static final String ORIGIN = "https://chatgpt.com/";

    @Test public void earlyStreamCompleteKeepsDomFallbackAndLaterAnswerCanFinish() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web);
            evaluateRaw(scenario, web, ChatGptTurnProtocolScript.documentStartScript());
            evaluateRaw(scenario, web,
                    "window.__disconnectCount=0;"
                            + "window.__selfRunDriveTurnObserver={token:'fixture-token',fired:false,timer:0,"
                            + "observer:{disconnect(){window.__disconnectCount++;}}};'ready';");

            JSONObject start = state(scenario, web,
                    "window.__selfRunTurnProtocol.observeRequest('POST','https://chatgpt.com/backend-api/f/conversation')");
            assertEquals("THINKING", start.getString("phase"));

            JSONObject earlyComplete = semantic(scenario, web, new JSONObject()
                    .put("type", "message_stream_complete"));
            assertEquals("THINKING", earlyComplete.getString("phase"));
            assertTrue(earlyComplete.getBoolean("sawStreamComplete"));
            assertFalse(readBoolean(scenario, web, "window.__selfRunDriveTurnObserver.fired"));
            assertEquals(0, readInt(scenario, web, "window.__disconnectCount"));

            JSONObject finalMessage = new JSONObject()
                    .put("type", "message_start")
                    .put("message", new JSONObject()
                            .put("id", "pro-final")
                            .put("author", new JSONObject().put("role", "assistant"))
                            .put("channel", "final")
                            .put("content", new JSONObject().put("parts", new JSONArray().put("Pro 답변"))));
            JSONObject answering = semantic(scenario, web, finalMessage);
            assertEquals("ANSWERING", answering.getString("phase"));
            assertTrue(answering.getBoolean("sawVisibleAnswer"));

            JSONObject complete = semantic(scenario, web, new JSONObject()
                    .put("type", "message_stream_complete"));
            assertEquals("COMPLETE", complete.getString("phase"));
        }
    }

    private static JSONObject semantic(ActivityScenario<SelfRunNewActivity> scenario,
                                       AtomicReference<WebView> web, JSONObject event) throws Exception {
        return state(scenario, web, "window.__selfRunTurnProtocol.observeSseText("
                + JSONObject.quote("data: " + event + "\n\n") + ",'pro-early',{})");
    }

    private static JSONObject state(ActivityScenario<SelfRunNewActivity> scenario,
                                    AtomicReference<WebView> web, String expression) throws Exception {
        String raw = evaluateRaw(scenario, web, "JSON.stringify(" + expression + ")");
        Object decoded = new JSONTokener(raw).nextValue();
        return new JSONObject(String.valueOf(decoded));
    }

    private static boolean readBoolean(ActivityScenario<SelfRunNewActivity> scenario,
                                       AtomicReference<WebView> web, String expression) throws Exception {
        return Boolean.parseBoolean(String.valueOf(new JSONTokener(
                evaluateRaw(scenario, web, expression)).nextValue()));
    }

    private static int readInt(ActivityScenario<SelfRunNewActivity> scenario,
                               AtomicReference<WebView> web, String expression) throws Exception {
        Object decoded = new JSONTokener(evaluateRaw(scenario, web, expression)).nextValue();
        return Integer.parseInt(String.valueOf(decoded));
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
                    "<!doctype html><html><body>pro early complete fixture</body></html>",
                    "text/html", "UTF-8", null);
        });
        assertTrue("Pro early-complete fixture did not load", loaded.await(15, TimeUnit.SECONDS));
    }

    private static String evaluateRaw(ActivityScenario<SelfRunNewActivity> scenario,
                                      AtomicReference<WebView> web, String script) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(script, value -> {
            result.set(value);
            complete.countDown();
        }));
        assertTrue("Pro early-complete WebView script timed out", complete.await(15, TimeUnit.SECONDS));
        return result.get();
    }
}
