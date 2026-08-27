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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Executes the immediate steering DOM contract against real Android WebView fixtures. */
@RunWith(AndroidJUnit4.class)
public final class UserImmediateInputDomWebViewTest {
    private static final String CONVERSATION_URL = "https://chatgpt.com/c/conversation123";
    private static final String INPUT = "change direction now";

    @Test public void activeResponseWithEnabledSendClicksExactlyOnce() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadFixture(scenario, web, true, true);

            JSONObject prepared = evaluate(scenario, web,
                    UserImmediateInputDom.prepare(CONVERSATION_URL, INPUT, "send-once"));
            assertEquals(UserImmediateInputDom.PREPARED, prepared.getString("status"));
            assertEquals(INPUT, read(scenario, web, "document.getElementById('prompt-textarea').value"));

            JSONObject resolved = evaluate(scenario, web,
                    UserImmediateInputDom.resolve(CONVERSATION_URL, INPUT, "send-once"));
            assertEquals(UserImmediateInputDom.SEND_READY, resolved.getString("status"));

            JSONObject clicked = evaluate(scenario, web,
                    UserImmediateInputDom.click(CONVERSATION_URL, INPUT, "send-once"));
            assertEquals(UserImmediateInputDom.SENT, clicked.getString("status"));
            assertEquals("1", read(scenario, web, "String(window.sendClicks)"));

            JSONObject repeated = evaluate(scenario, web,
                    UserImmediateInputDom.click(CONVERSATION_URL, INPUT, "send-once"));
            assertEquals(UserImmediateInputDom.SENT, repeated.getString("status"));
            assertEquals("1", read(scenario, web, "String(window.sendClicks)"));
        }
    }

    @Test public void activeResponseWithoutEnabledSendClearsOwnTextAndDefers() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadFixture(scenario, web, true, false);

            JSONObject prepared = evaluate(scenario, web,
                    UserImmediateInputDom.prepare(CONVERSATION_URL, INPUT, "no-send"));
            assertEquals(UserImmediateInputDom.PREPARED, prepared.getString("status"));

            JSONObject resolved = evaluate(scenario, web,
                    UserImmediateInputDom.resolve(CONVERSATION_URL, INPUT, "no-send"));
            assertEquals(UserImmediateInputDom.DEFERRED, resolved.getString("status"));
            assertEquals("", read(scenario, web, "document.getElementById('prompt-textarea').value"));
            assertEquals("0", read(scenario, web, "String(window.sendClicks)"));
        }
    }

    @Test public void responseFinishRaceNeverUsesIdleSendAsImmediateSteering() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadFixture(scenario, web, true, true);

            JSONObject prepared = evaluate(scenario, web,
                    UserImmediateInputDom.prepare(CONVERSATION_URL, INPUT, "finish-race"));
            assertEquals(UserImmediateInputDom.PREPARED, prepared.getString("status"));

            evaluate(scenario, web, "(()=>{document.getElementById('stop').remove();"
                    + "document.getElementById('send').style.display='';return JSON.stringify({status:'FINISHED'});})()");
            JSONObject resolved = evaluate(scenario, web,
                    UserImmediateInputDom.resolve(CONVERSATION_URL, INPUT, "finish-race"));
            assertEquals(UserImmediateInputDom.DEFERRED, resolved.getString("status"));
            assertEquals("", read(scenario, web, "document.getElementById('prompt-textarea').value"));
            assertEquals("0", read(scenario, web, "String(window.sendClicks)"));
        }
    }

    @Test public void differentExistingComposerTextIsPreservedAndDeferred() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadFixture(scenario, web, true, true);
            evaluate(scenario, web, "(()=>{document.getElementById('prompt-textarea').value='keep this draft';return JSON.stringify({status:'SEEDED'});})()");

            JSONObject prepared = evaluate(scenario, web,
                    UserImmediateInputDom.prepare(CONVERSATION_URL, INPUT, "preserve-draft"));
            assertEquals(UserImmediateInputDom.DEFERRED, prepared.getString("status"));
            assertEquals("keep this draft", read(scenario, web, "document.getElementById('prompt-textarea').value"));
            assertEquals("0", read(scenario, web, "String(window.sendClicks)"));
        }
    }

    private static void loadFixture(ActivityScenario<SelfRunNewActivity> scenario,
                                    AtomicReference<WebView> web, boolean stopVisible,
                                    boolean enableSendOnInput) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            view.getSettings().setJavaScriptEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView v, String url) {
                    loaded.countDown();
                }
            });
            web.set(view);
            String stop = stopVisible
                    ? "<button id='stop' type='button' data-testid='stop-stream-action' aria-label='Stop streaming'>Stop</button>"
                    : "";
            String listener = enableSendOnInput
                    ? "document.getElementById('prompt-textarea').addEventListener('input',()=>{document.getElementById('send').style.display='';});"
                    : "";
            String html = "<!doctype html><html><body><main><div id='shell'><form id='composer'>"
                    + "<textarea id='prompt-textarea'></textarea>" + stop
                    + "<button id='send' type='submit' data-testid='send-button' aria-label='Send' style='display:none'>Send</button>"
                    + "</form></div></main><script>window.sendClicks=0;"
                    + "document.getElementById('composer').addEventListener('submit',e=>e.preventDefault());"
                    + "document.getElementById('send').addEventListener('click',e=>{e.preventDefault();window.sendClicks++;});"
                    + listener + "</script></body></html>";
            view.loadDataWithBaseURL(CONVERSATION_URL, html, "text/html", "UTF-8", null);
        });
        assertTrue("fixture did not load", loaded.await(5, TimeUnit.SECONDS));
        assertNotNull(web.get());
    }

    private static JSONObject evaluate(ActivityScenario<SelfRunNewActivity> scenario,
                                       AtomicReference<WebView> web, String script) throws Exception {
        AtomicReference<String> raw = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        scenario.onActivity(activity -> web.get().evaluateJavascript(script, value -> {
            raw.set(value);
            done.countDown();
        }));
        assertTrue("evaluateJavascript callback missing", done.await(5, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(raw.get()).nextValue();
        String json = decoded instanceof String ? (String) decoded : String.valueOf(decoded);
        return new JSONObject(json);
    }

    private static String read(ActivityScenario<SelfRunNewActivity> scenario,
                               AtomicReference<WebView> web, String expression) throws Exception {
        AtomicReference<String> raw = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        scenario.onActivity(activity -> web.get().evaluateJavascript(expression, value -> {
            raw.set(value);
            done.countDown();
        }));
        assertTrue("read callback missing", done.await(5, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(raw.get()).nextValue();
        return decoded == null || decoded == JSONObject.NULL ? "" : String.valueOf(decoded);
    }
}
