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

/** Reproduces the dev8 mode-stage regression and the current direct-option picker. */
@RunWith(AndroidJUnit4.class)
public final class BootstrapStageAndDirectPickerAndroidTest {
    private static final String PROJECT_URL = "https://chatgpt.com/g/g-p-test";

    @Test public void confirmedChatStageSurvivesPickerRenderAndAppliesInstantDirectly() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, fixture(false));
            String runId = "SR-MODE-LATCH-DIRECT";
            scenario.onActivity(activity -> assertTrue(ChatReasoningPreferenceStore.save(
                    activity, runId, ChatReasoningPreferenceStore.INSTANT)));
            String script = SelfRunDom.prepareInitialContext(PROJECT_URL, SelfRunStore.MODE_CHAT, runId);

            JSONObject menuOpened = evaluate(scenario, web, script);
            assertEquals("UI_WAIT", menuOpened.getString("status"));
            assertEquals("1", read(scenario, web, "String(window.modelMenuClicks)"));
            assertEquals("0", read(scenario, web, "String(window.chatClicks)"));

            JSONObject optionClicked = evaluate(scenario, web, script);
            assertEquals("UI_WAIT", optionClicked.getString("status"));
            assertEquals("direct-option-click",
                    optionClicked.getJSONObject("diagnostics").getString("action"));
            assertEquals("1", read(scenario, web, "String(window.instantClicks)"));
            assertEquals("0", read(scenario, web, "String(window.chatClicks)"));

            JSONObject ready = evaluate(scenario, web, script);
            assertEquals("READY", ready.getString("status"));
            assertEquals("instant", ready.getJSONObject("diagnostics").getString("observed"));
            assertEquals("1", read(scenario, web, "String(window.modelMenuClicks)"));
            assertEquals("1", read(scenario, web, "String(window.instantClicks)"));
            assertEquals("0", read(scenario, web, "String(window.chatClicks)"));
            assertEquals("MODE_CONFIRMED", read(scenario, web,
                    "(()=>{const k='chatgpt-selfrun:bootstrap-stage:" + runId
                            + "';const v=localStorage.getItem(k)||sessionStorage.getItem(k);return JSON.parse(v).stage;})()"));
        }
    }

    @Test public void workToChatTransitionClicksExactlyOnceBeforeDirectPicker() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, fixture(true));
            String runId = "SR-WORK-TO-CHAT-DIRECT";
            scenario.onActivity(activity -> assertTrue(ChatReasoningPreferenceStore.save(
                    activity, runId, ChatReasoningPreferenceStore.INSTANT)));
            String script = SelfRunDom.prepareInitialContext(PROJECT_URL, SelfRunStore.MODE_CHAT, runId);

            JSONObject modeClicked = evaluate(scenario, web, script);
            assertEquals("UI_WAIT", modeClicked.getString("status"));
            assertEquals("1", read(scenario, web, "String(window.chatClicks)"));
            assertEquals("0", read(scenario, web, "String(window.modelMenuClicks)"));

            JSONObject result = null;
            for (int attempt = 0; attempt < 6; attempt++) {
                result = evaluate(scenario, web, script);
                if ("READY".equals(result.getString("status"))) break;
                assertEquals("UI_WAIT", result.getString("status"));
            }
            assertNotNull(result);
            assertEquals("READY", result.getString("status"));
            assertEquals("1", read(scenario, web, "String(window.chatClicks)"));
            assertEquals("1", read(scenario, web, "String(window.modelMenuClicks)"));
            assertEquals("1", read(scenario, web, "String(window.instantClicks)"));
        }
    }

    private static String fixture(boolean initialWork) {
        return """
                <!doctype html><html><body>
                <div id="mode-group">
                  <button id="chat"><span id="chat-state" aria-selected="__CHAT__">Chat</span></button>
                  <button id="work"><span id="work-state" aria-selected="__WORK__">Work</span></button>
                </div>
                <button id="model" aria-haspopup="menu" aria-expanded="false">Model</button>
                <textarea id="prompt-textarea"></textarea>
                <div id="model-popup" role="menu" hidden>
                  <button id="instant" role="menuitemradio" aria-checked="false">Instant</button>
                  <button id="medium" role="menuitemradio" aria-checked="false">Medium</button>
                  <button id="high" role="menuitemradio" aria-checked="false">High</button>
                </div>
                <script>
                window.chatClicks=0;window.modelMenuClicks=0;window.instantClicks=0;
                const chat=document.getElementById('chat'),work=document.getElementById('work');
                const chatState=document.getElementById('chat-state'),workState=document.getElementById('work-state');
                const model=document.getElementById('model'),popup=document.getElementById('model-popup');
                chat.onclick=()=>{window.chatClicks++;chatState.setAttribute('aria-selected','true');workState.setAttribute('aria-selected','false');};
                work.onclick=()=>{chatState.setAttribute('aria-selected','false');workState.setAttribute('aria-selected','true');};
                model.onclick=()=>{window.modelMenuClicks++;const opening=popup.hidden;popup.hidden=!opening;model.setAttribute('aria-expanded',opening?'true':'false');if(opening){chatState.removeAttribute('aria-selected');workState.removeAttribute('aria-selected');}};
                document.getElementById('instant').onclick=event=>{window.instantClicks++;for(const option of popup.querySelectorAll('[role=menuitemradio]'))option.setAttribute('aria-checked','false');event.currentTarget.setAttribute('aria-checked','true');model.textContent='Instant';model.setAttribute('aria-expanded','false');popup.hidden=true;};
                </script></body></html>
                """.replace("__CHAT__", initialWork ? "false" : "true")
                .replace("__WORK__", initialWork ? "true" : "false");
    }

    private static void load(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web,
                             String html) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView ignored, String url) {
                    if (PROJECT_URL.equals(url)) loaded.countDown();
                }
            });
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(PROJECT_URL, html, "text/html", "UTF-8", null);
        });
        assertTrue("Bootstrap stage fixture did not load", loaded.await(15, TimeUnit.SECONDS));
        assertNotNull("Android System WebView must be available", WebView.getCurrentWebViewPackage());
    }

    private static JSONObject evaluate(ActivityScenario<SelfRunNewActivity> scenario,
                                       AtomicReference<WebView> web, String script) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<String> raw = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(script, value -> {
            raw.set(value);complete.countDown();
        }));
        assertTrue("WebView script timed out", complete.await(15, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(raw.get()).nextValue();
        return new JSONObject(String.valueOf(decoded));
    }

    private static String read(ActivityScenario<SelfRunNewActivity> scenario,
                               AtomicReference<WebView> web, String expression) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<String> raw = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(expression, value -> {
            raw.set(value);complete.countDown();
        }));
        assertTrue("WebView read timed out", complete.await(15, TimeUnit.SECONDS));
        return String.valueOf(new JSONTokener(raw.get()).nextValue());
    }
}
