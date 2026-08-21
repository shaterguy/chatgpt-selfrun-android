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

/** Verifies delayed Chat model-menu rendering without using wall-clock sleeps. */
@RunWith(AndroidJUnit4.class)
public final class ChatReasoningDelayedDomWebViewTest {
    private static final String PROJECT_URL = "https://chatgpt.com/g/g-p-test";

    @Test public void delayedTriggerGetsAnIndependentSliderReadinessWindow() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, delayedTriggerFixture());
            String script = chatReasoningScript(ChatReasoningPreferenceStore.PRO, "SR-DELAYED-TRIGGER");

            JSONObject waitingForTrigger = evaluate(scenario, web, script);
            assertEquals("UI_WAIT", waitingForTrigger.getString("status"));
            assertEquals("wait-trigger", waitingForTrigger.getJSONObject("diagnostics").getString("action"));

            setNow(scenario, web, 19_000L);
            assertEquals("installed", read(scenario, web, "window.installTrigger()"));
            JSONObject opened = evaluate(scenario, web, script);
            assertEquals("UI_WAIT", opened.getString("status"));
            assertEquals("open-menu", opened.getJSONObject("diagnostics").getString("action"));

            setNow(scenario, web, 40_000L);
            JSONObject stillWaiting = evaluate(scenario, web, script);
            assertEquals("UI_WAIT", stillWaiting.getString("status"));
            assertEquals("wait-slider", stillWaiting.getJSONObject("diagnostics").getString("action"));
            assertEquals(21_000L, stillWaiting.getJSONObject("diagnostics").getLong("sliderWaitElapsedMs"));

            setNow(scenario, web, 42_000L);
            assertEquals("installed", read(scenario, web, "window.installSlider()"));
            JSONObject ready = runToReady(scenario, web, script);
            assertEquals("4", read(scenario, web, "document.getElementById('slider').value"));
            assertEquals("1", read(scenario, web, "String(window.menuOpenClicks)"));
            assertEquals("1", read(scenario, web, "String(window.menuCloseClicks)"));
            assertTrue(ready.getJSONObject("diagnostics").getLong("sliderWaitElapsedMs") < 24_000L);
        }
    }

    @Test public void lostFirstClickRetriesOnlyTheCurrentReplacementTrigger() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, replacementTriggerFixture());
            String script = chatReasoningScript(ChatReasoningPreferenceStore.HIGH, "SR-REPLACED-TRIGGER");

            JSONObject firstClick = evaluate(scenario, web, script);
            assertEquals("UI_WAIT", firstClick.getString("status"));
            assertEquals("open-menu", firstClick.getJSONObject("diagnostics").getString("action"));
            assertEquals("1", read(scenario, web, "String(window.firstClicks)"));
            assertEquals("0", read(scenario, web, "String(window.retryClicks)"));

            setNow(scenario, web, 7_000L);
            JSONObject retry = evaluate(scenario, web, script);
            assertEquals("UI_WAIT", retry.getString("status"));
            assertEquals("open-menu-retry", retry.getJSONObject("diagnostics").getString("action"));
            assertEquals(2, retry.getJSONObject("diagnostics").getInt("menuClickAttempts"));
            assertEquals("1", read(scenario, web, "String(window.retryClicks)"));

            JSONObject ready = runToReady(scenario, web, script);
            assertEquals("2", read(scenario, web, "document.getElementById('slider').value"));
            assertEquals(2, ready.getJSONObject("diagnostics").getInt("menuClickAttempts"));
            assertEquals("1", read(scenario, web, "String(window.menuCloseClicks)"));
        }
    }

    @Test public void missingSliderFailsOnlyAfterThePostClickReadinessWindow() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, missingSliderFixture());
            String script = chatReasoningScript(ChatReasoningPreferenceStore.HIGH, "SR-SLIDER-TIMEOUT");

            JSONObject opened = evaluate(scenario, web, script);
            assertEquals("UI_WAIT", opened.getString("status"));
            assertEquals("open-menu", opened.getJSONObject("diagnostics").getString("action"));

            setNow(scenario, web, 23_900L);
            JSONObject beforeDeadline = evaluate(scenario, web, script);
            assertEquals("UI_WAIT", beforeDeadline.getString("status"));
            assertEquals(22_900L, beforeDeadline.getJSONObject("diagnostics").getLong("sliderWaitElapsedMs"));

            setNow(scenario, web, 25_200L);
            JSONObject timedOut = evaluate(scenario, web, script);
            assertEquals("CHAT_REASONING_SLIDER_NOT_FOUND", timedOut.getString("status"));
            assertTrue(timedOut.getJSONObject("diagnostics").getLong("sliderWaitElapsedMs") >= 24_000L);
            assertEquals(1, timedOut.getJSONObject("diagnostics").getInt("menuClickAttempts"));
        }
    }

    private static JSONObject runToReady(ActivityScenario<SelfRunNewActivity> scenario,
                                         AtomicReference<WebView> web, String script) throws Exception {
        JSONObject result = null;
        for (int attempt = 0; attempt < 16; attempt++) {
            result = evaluate(scenario, web, script);
            if ("READY".equals(result.getString("status"))) break;
            assertEquals("UI_WAIT", result.getString("status"));
        }
        assertNotNull(result);
        assertEquals("READY", result.getString("status"));
        return result;
    }

    private static void load(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web,
                             String html) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            view.getSettings().setJavaScriptEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView ignored, String url) {
                    if (PROJECT_URL.equals(url)) loaded.countDown();
                }
            });
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(PROJECT_URL, html, "text/html", "UTF-8", null);
        });
        assertTrue("Delayed Chat reasoning WebView fixture did not load", loaded.await(15, TimeUnit.SECONDS));
        assertNotNull("Android System WebView must be available", WebView.getCurrentWebViewPackage());
    }

    private static JSONObject evaluate(ActivityScenario<SelfRunNewActivity> scenario,
                                       AtomicReference<WebView> web, String script) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<String> raw = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(script, value -> {
            raw.set(value);
            complete.countDown();
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
            raw.set(value);
            complete.countDown();
        }));
        assertTrue("WebView read timed out", complete.await(15, TimeUnit.SECONDS));
        return String.valueOf(new JSONTokener(raw.get()).nextValue());
    }

    private static void setNow(ActivityScenario<SelfRunNewActivity> scenario,
                               AtomicReference<WebView> web, long now) throws Exception {
        assertEquals(String.valueOf(now), read(scenario, web,
                "(()=>{window.testNow=" + now + ";return String(window.testNow);})()"));
    }

    private static String chatReasoningScript(String selection, String runId) {
        return "(()=>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,diagnostics});"
                + "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;const exactText=s=>String(s??'').replace(/\\s+/g,' ').trim().toLowerCase();"
                + "const labelOf=e=>exactText(e?.innerText||'')||exactText(e?.getAttribute?.('aria-label')||'');"
                + ChatReasoningDom.inline(selection, runId)
                + "return result('READY','Delayed Chat reasoning fixture ready');})()";
    }

    private static String delayedTriggerFixture() {
        return """
                <!doctype html><html><head><style>
                body{margin:0}header{height:64px;padding:8px}main{min-height:720px;display:flex;align-items:flex-end}
                form{height:72px;width:100%}#reasoning-menu[hidden]{display:none}#reasoning-menu{width:320px;min-height:72px}
                </style></head><body><header id="header"></header>
                <main><form><textarea id="prompt-textarea"></textarea></form></main>
                <div id="reasoning-menu" role="menu" hidden><div id="levels">
                <span data-level="instant">Instant</span><span data-level="medium">Medium</span><span data-level="high">High</span>
                <span data-level="xhigh">Extra High</span><span data-level="pro">Pro</span></div></div>
                <script>
                window.testNow=1000;Date.now=()=>window.testNow;window.menuOpenClicks=0;window.menuCloseClicks=0;
                const header=document.getElementById('header'),menu=document.getElementById('reasoning-menu');
                window.installTrigger=()=>{if(document.getElementById('trigger'))return'existing';const trigger=document.createElement('button');
                trigger.id='trigger';trigger.type='button';trigger.setAttribute('aria-haspopup','menu');trigger.setAttribute('aria-controls','reasoning-menu');trigger.setAttribute('aria-expanded','false');trigger.textContent='Instant';
                trigger.addEventListener('click',()=>{if(menu.hidden){menu.hidden=false;window.menuOpenClicks++;trigger.setAttribute('aria-expanded','true');}else{menu.hidden=true;window.menuCloseClicks++;trigger.setAttribute('aria-expanded','false');}});header.appendChild(trigger);return'installed';};
                window.installSlider=()=>{if(document.getElementById('slider'))return'existing';const slider=document.createElement('input');slider.id='slider';slider.type='range';slider.min='0';slider.max='4';slider.step='1';slider.value='0';slider.setAttribute('aria-valuetext','Instant');
                const labels=['Instant','Medium','High','Extra High','Pro'];const update=()=>{const value=Math.max(0,Math.min(4,Math.round(Number(slider.value))));const text=labels[value];slider.setAttribute('aria-valuetext',text);const trigger=document.getElementById('trigger');if(trigger)trigger.textContent=text;};
                slider.addEventListener('input',update);slider.addEventListener('change',update);menu.appendChild(slider);return'installed';};
                </script></body></html>
                """;
    }

    private static String replacementTriggerFixture() {
        return """
                <!doctype html><html><head><style>
                body{margin:0}header{height:64px;padding:8px}main{min-height:720px;display:flex;align-items:flex-end}
                form{height:72px;width:100%}#reasoning-menu[hidden]{display:none}#reasoning-menu{width:320px;min-height:72px}
                </style></head><body><header id="header"><button id="trigger" type="button" aria-haspopup="menu" aria-controls="reasoning-menu" aria-expanded="false">Instant</button></header>
                <main><form><textarea id="prompt-textarea"></textarea></form></main>
                <div id="reasoning-menu" role="menu" hidden><div id="levels">
                <span data-level="instant">Instant</span><span data-level="medium">Medium</span><span data-level="high">High</span>
                <span data-level="xhigh">Extra High</span><span data-level="pro">Pro</span></div></div>
                <script>
                window.testNow=1000;Date.now=()=>window.testNow;window.firstClicks=0;window.retryClicks=0;window.menuCloseClicks=0;
                const header=document.getElementById('header'),menu=document.getElementById('reasoning-menu');
                function installSlider(){if(document.getElementById('slider'))return;const slider=document.createElement('input');slider.id='slider';slider.type='range';slider.min='0';slider.max='4';slider.step='1';slider.value='0';slider.setAttribute('aria-valuetext','Instant');
                const labels=['Instant','Medium','High','Extra High','Pro'];const update=()=>{const value=Math.max(0,Math.min(4,Math.round(Number(slider.value))));const text=labels[value];slider.setAttribute('aria-valuetext',text);const trigger=document.getElementById('trigger');if(trigger)trigger.textContent=text;};slider.addEventListener('input',update);slider.addEventListener('change',update);menu.appendChild(slider);}
                const first=document.getElementById('trigger');first.addEventListener('click',()=>{window.firstClicks++;first.remove();const replacement=document.createElement('button');replacement.id='trigger';replacement.type='button';replacement.setAttribute('aria-haspopup','menu');replacement.setAttribute('aria-controls','reasoning-menu');replacement.setAttribute('aria-expanded','false');replacement.textContent='Instant';
                replacement.addEventListener('click',()=>{if(menu.hidden){window.retryClicks++;menu.hidden=false;replacement.setAttribute('aria-expanded','true');installSlider();}else{menu.hidden=true;window.menuCloseClicks++;replacement.setAttribute('aria-expanded','false');}});header.appendChild(replacement);});
                </script></body></html>
                """;
    }

    private static String missingSliderFixture() {
        return """
                <!doctype html><html><head><style>
                body{margin:0}header{height:64px;padding:8px}main{min-height:720px;display:flex;align-items:flex-end}
                form{height:72px;width:100%}#reasoning-menu[hidden]{display:none}#reasoning-menu{width:320px;min-height:72px}
                </style></head><body><header><button id="trigger" type="button" aria-haspopup="menu" aria-controls="reasoning-menu" aria-expanded="false">Instant</button></header>
                <main><form><textarea id="prompt-textarea"></textarea></form></main>
                <div id="reasoning-menu" role="menu" hidden><span data-level="instant">Instant</span><span data-level="high">High</span></div>
                <script>
                window.testNow=1000;Date.now=()=>window.testNow;const trigger=document.getElementById('trigger'),menu=document.getElementById('reasoning-menu');
                trigger.addEventListener('click',()=>{if(menu.hidden){menu.hidden=false;trigger.setAttribute('aria-expanded','true');}else{menu.hidden=true;trigger.setAttribute('aria-expanded','false');}});
                </script></body></html>
                """;
    }
}
