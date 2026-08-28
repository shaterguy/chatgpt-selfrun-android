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

/** Reproduces the mode-stage regression with the current Chat reasoning slider popover. */
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

            JSONObject result = null;
            boolean sawOpen = false;
            boolean sawSlider = false;
            for (int attempt = 0; attempt < 16; attempt++) {
                result = evaluate(scenario, web, script);
                if ("READY".equals(result.getString("status"))) break;
                assertEquals(result.toString(), "UI_WAIT", result.getString("status"));
                JSONObject diagnostics = result.optJSONObject("diagnostics");
                String action = diagnostics == null ? "" : diagnostics.optString("action");
                if ("open-reasoning-popover".equals(action)) sawOpen = true;
                if ("set-slider".equals(action)) sawSlider = true;
            }

            assertNotNull(result);
            assertEquals(result.toString(), "READY", result.getString("status"));
            assertTrue("current Chat picker must open the reasoning popover", sawOpen);
            assertTrue("current Chat picker must move the reasoning slider", sawSlider);
            assertEquals("instant", result.getJSONObject("diagnostics").getString("observed"));
            assertEquals("0", read(scenario, web, "String(window.chatClicks)"));
            assertEquals("2", read(scenario, web, "String(window.sliderKeydowns)"));
            assertEquals("0", read(scenario, web,
                    "String(document.getElementById('slider').getAttribute('aria-valuenow'))"));
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

            JSONObject result = null;
            boolean sawOpen = false;
            boolean sawSlider = false;
            for (int attempt = 0; attempt < 18; attempt++) {
                result = evaluate(scenario, web, script);
                if ("READY".equals(result.getString("status"))) break;
                assertEquals(result.toString(), "UI_WAIT", result.getString("status"));
                JSONObject diagnostics = result.optJSONObject("diagnostics");
                String action = diagnostics == null ? "" : diagnostics.optString("action");
                if ("open-reasoning-popover".equals(action)) sawOpen = true;
                if ("set-slider".equals(action)) sawSlider = true;
            }

            assertNotNull(result);
            assertEquals(result.toString(), "READY", result.getString("status"));
            assertTrue("Chat mode transition must reach the current reasoning popover", sawOpen);
            assertTrue("Chat mode transition must reach the current reasoning slider", sawSlider);
            assertEquals("1", read(scenario, web, "String(window.chatClicks)"));
            assertEquals("2", read(scenario, web, "String(window.sliderKeydowns)"));
            assertEquals("instant", result.getJSONObject("diagnostics").getString("observed"));
        }
    }

    @Test public void chatToWorkTransitionUsesExactToggleAndPointerDown() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, fixture(false));
            String runId = "SR-CHAT-TO-WORK-POINTER";
            String script = SelfRunDom.prepareInitialContext(PROJECT_URL, SelfRunStore.MODE_WORK, runId);

            JSONObject modeClicked = evaluate(scenario, web, script);
            assertEquals("UI_WAIT", modeClicked.getString("status"));
            assertEquals("tpp-toggle", modeClicked.getJSONObject("diagnostics").getString("targetSource"));
            assertEquals("1", read(scenario, web, "String(window.workClicks)"));

            JSONObject ready = evaluate(scenario, web, script);
            assertEquals(ready.toString(), "READY", ready.getString("status"));
            assertEquals("work", ready.getJSONObject("diagnostics").getString("currentMode"));
            assertEquals("1", read(scenario, web, "String(window.workClicks)"));
        }
    }

    private static String fixture(boolean initialWork) {
        return """
                <!doctype html><html><head><style>
                [hidden]{display:none!important}body{min-height:760px}form{margin-top:480px;min-height:100px}
                #reasoning-popup{position:absolute;width:320px;min-height:100px}#slider{display:block;width:280px;height:28px}
                </style></head><body>
                <div id="mode-group">
                  <button id="chat" role="radio" aria-checked="__CHAT__" data-state="__CHATMODESTATE__" data-tpp-toggle-value="chatgpt"><span id="chat-state" aria-selected="__CHAT__">Chat</span></button>
                  <button id="work" role="radio" aria-checked="__WORK__" data-state="__WORKMODESTATE__" data-tpp-toggle-value="work"><span id="work-state" aria-selected="__WORK__">Work</span></button>
                </div>
                <form><textarea id="prompt-textarea"></textarea>
                  <button id="reasoning" type="button" aria-haspopup="dialog" aria-controls="reasoning-popup" aria-expanded="false">High &gt;</button>
                </form>
                <div id="reasoning-popup" role="dialog" hidden>
                  <div id="slider" role="slider" tabindex="0" aria-valuemin="0" aria-valuemax="3" aria-valuenow="2" aria-valuestep="1" aria-valuetext="High"></div>
                </div>
                <script>
                window.chatClicks=0;window.workClicks=0;window.reasoningTriggerClicks=0;window.sliderKeydowns=0;
                const chat=document.getElementById('chat'),work=document.getElementById('work');
                const chatState=document.getElementById('chat-state'),workState=document.getElementById('work-state');
                const reasoning=document.getElementById('reasoning'),popup=document.getElementById('reasoning-popup'),slider=document.getElementById('slider');
                const labels=['Instant','Medium','High','Extra High'];
                function selectMode(selected,other,value){selected.setAttribute('aria-checked','true');selected.dataset.state='on';other.setAttribute('aria-checked','false');other.dataset.state='off';chatState.setAttribute('aria-selected',String(value==='chat'));workState.setAttribute('aria-selected',String(value==='work'));}
                function updateReasoning(){const index=Number(slider.getAttribute('aria-valuenow'));const text=labels[index];slider.setAttribute('aria-valuetext',text);reasoning.textContent=text+' >';}
                chat.onclick=()=>{window.chatClicks++;selectMode(chat,work,'chat');};
                work.onpointerdown=()=>{window.workClicks++;selectMode(work,chat,'work');};
                reasoning.onclick=()=>{window.reasoningTriggerClicks++;const opening=reasoning.getAttribute('aria-expanded')!=='true';popup.hidden=!opening;reasoning.setAttribute('aria-expanded',opening?'true':'false');if(opening){chatState.removeAttribute('aria-selected');workState.removeAttribute('aria-selected');}};
                slider.onkeydown=event=>{if(event.key!=='ArrowRight'&&event.key!=='ArrowLeft')return;window.sliderKeydowns++;const current=Number(slider.getAttribute('aria-valuenow')),next=Math.max(0,Math.min(3,current+(event.key==='ArrowRight'?1:-1)));slider.setAttribute('aria-valuenow',String(next));updateReasoning();event.preventDefault();};
                updateReasoning();
                </script></body></html>
                """.replace("__CHATMODESTATE__", initialWork ? "off" : "on")
                .replace("__WORKMODESTATE__", initialWork ? "on" : "off")
                .replace("__CHAT__", initialWork ? "false" : "true")
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
