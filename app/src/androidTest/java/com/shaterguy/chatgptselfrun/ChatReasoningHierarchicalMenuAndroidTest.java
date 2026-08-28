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

/** Reproduces the current Chat reasoning popover: current text/model row plus a direct slider. */
@RunWith(AndroidJUnit4.class)
public final class ChatReasoningHierarchicalMenuAndroidTest {
    private static final String PROJECT_URL = "https://chatgpt.com/g/g-p-test";

    @Test public void koreanCurrentSliderAppliesExtraHighWithoutAdvanced() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web);
            String runId = "SR-CURRENT-SLIDER-KO";
            scenario.onActivity(activity -> assertTrue(ChatReasoningPreferenceStore.save(
                    activity, runId, ChatReasoningPreferenceStore.EXTRA_HIGH)));

            JSONObject ready = runToReady(scenario, web,
                    SelfRunDom.prepareInitialContext(PROJECT_URL, SelfRunStore.MODE_CHAT, runId));

            assertEquals("xhigh", ready.getJSONObject("diagnostics").getString("observed"));
            assertEquals("slider-model-popover", ready.getJSONObject("diagnostics").getString("strategy"));
            assertEquals("3", read(scenario, web,
                    "document.getElementById('slider').getAttribute('aria-valuenow')"));
            assertEquals("매우 높음 >", read(scenario, web,
                    "document.getElementById('reasoning-summary').textContent"));
            assertEquals("1", read(scenario, web, "String(window.sliderKeydowns)"));
            assertEquals("0", read(scenario, web, "String(window.modelMenuClicks)"));
        }
    }

    @Test public void currentModelMenuThenProSliderAppliesProExtended() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web);
            String runId = "SR-CURRENT-PRO-EXTENDED";
            scenario.onActivity(activity -> assertTrue(ChatReasoningPreferenceStore.save(
                    activity, runId, ChatReasoningPreferenceStore.PRO_EXTENDED)));

            JSONObject ready = runToReady(scenario, web,
                    SelfRunDom.prepareInitialContext(PROJECT_URL, SelfRunStore.MODE_CHAT, runId));

            assertEquals("pro_extended", ready.getJSONObject("diagnostics").getString("observed"));
            assertEquals("Pro Extended >", read(scenario, web,
                    "document.getElementById('reasoning-summary').textContent"));
            assertEquals("1", read(scenario, web, "String(window.modelMenuClicks)"));
            assertEquals("1", read(scenario, web, "String(window.proClicks)"));
            assertEquals("1", read(scenario, web, "String(window.sliderKeydowns)"));
        }
    }

    @Test public void keepCapturesCurrentSliderValueWithoutChangingIt() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web);
            String runId = "SR-CAPTURE-CURRENT";
            scenario.onActivity(activity -> assertTrue(ChatReasoningPreferenceStore.save(
                    activity, runId, ChatReasoningPreferenceStore.KEEP)));

            JSONObject ready = runToReady(scenario, web,
                    SelfRunDom.prepareInitialContext(PROJECT_URL, SelfRunStore.MODE_CHAT, runId));

            assertEquals("high", ready.getJSONObject("diagnostics").getString("observed"));
            assertEquals("capture-current", ready.getJSONObject("diagnostics").getString("action"));
            assertEquals("2", read(scenario, web,
                    "document.getElementById('slider').getAttribute('aria-valuenow')"));
            assertEquals("0", read(scenario, web, "String(window.sliderKeydowns)"));
        }
    }

    @Test public void matchingCurrentValueOpensOnlyForReadbackAndDoesNotMoveSlider() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web);
            String runId = "SR-CURRENT-MATCH";
            scenario.onActivity(activity -> assertTrue(ChatReasoningPreferenceStore.save(
                    activity, runId, ChatReasoningPreferenceStore.HIGH)));

            JSONObject ready = runToReady(scenario, web,
                    SelfRunDom.prepareInitialContext(PROJECT_URL, SelfRunStore.MODE_CHAT, runId));

            assertEquals("high", ready.getJSONObject("diagnostics").getString("observed"));
            assertEquals("0", read(scenario, web, "String(window.sliderKeydowns)"));
            assertEquals("2", read(scenario, web, "String(window.triggerClicks)"));
        }
    }

    private static JSONObject runToReady(ActivityScenario<SelfRunNewActivity> scenario,
                                         AtomicReference<WebView> web, String script) throws Exception {
        JSONObject result = null;
        for (int attempt = 0; attempt < 24; attempt++) {
            result = evaluate(scenario, web, script);
            if ("READY".equals(result.getString("status"))) break;
            assertEquals(result.toString(), "UI_WAIT", result.getString("status"));
        }
        assertNotNull(result);
        assertEquals(result.toString(), "READY", result.getString("status"));
        return result;
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
                    if (PROJECT_URL.equals(url)) loaded.countDown();
                }
            });
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(PROJECT_URL, fixture(), "text/html", "UTF-8", null);
        });
        assertTrue("Chat current picker fixture did not load", loaded.await(15, TimeUnit.SECONDS));
        assertNotNull("Android System WebView must be available", WebView.getCurrentWebViewPackage());
    }

    private static String fixture() {
        return """
                <!doctype html><html><head><style>
                [hidden]{display:none!important}body{min-height:800px}form{margin-top:500px}
                #sheet,#model-menu{position:absolute;width:320px;min-height:110px}
                #slider{display:block;width:280px;height:28px}
                </style></head><body>
                <div><button id="chat" aria-selected="true">Chat</button><button id="work" aria-selected="false">Work</button></div>
                <form><textarea id="prompt-textarea"></textarea>
                  <button id="reasoning-trigger" type="button" aria-haspopup="dialog" aria-controls="sheet" aria-expanded="false">추론 수준</button>
                </form>
                <div id="sheet" role="dialog" hidden>
                  <button id="reasoning-summary" type="button" aria-haspopup="menu" aria-expanded="false">높음 &gt;</button>
                  <div id="slider" role="slider" tabindex="0" aria-valuemin="0" aria-valuemax="3" aria-valuenow="2" aria-valuestep="1" aria-valuetext="높음"></div>
                </div>
                <div id="model-menu" role="menu" hidden>
                  <button id="sol" type="button" role="menuitemradio" aria-checked="true">GPT-5.6 Sol</button>
                  <button type="button" role="menuitemradio" aria-checked="false">GPT-5.5</button>
                  <button id="pro" type="button" role="menuitemradio" aria-checked="false">Pro</button>
                </div>
                <script>
                window.triggerClicks=0;window.sliderKeydowns=0;window.modelMenuClicks=0;window.proClicks=0;
                const trigger=document.getElementById('reasoning-trigger'),sheet=document.getElementById('sheet'),summary=document.getElementById('reasoning-summary'),slider=document.getElementById('slider'),modelMenu=document.getElementById('model-menu');
                let proMode=false;
                const standard=['즉시','중간','높음','매우 높음'],pro=['Pro Standard','Pro Extended'];
                const update=()=>{const value=Number(slider.getAttribute('aria-valuenow')),labels=proMode?pro:standard,text=labels[value];slider.setAttribute('aria-valuetext',text);summary.textContent=text+' >';};
                trigger.onclick=()=>{window.triggerClicks++;const opening=sheet.hidden&&modelMenu.hidden;sheet.hidden=!opening;modelMenu.hidden=true;trigger.setAttribute('aria-expanded',opening?'true':'false');summary.setAttribute('aria-expanded','false');};
                summary.onclick=()=>{window.modelMenuClicks++;sheet.hidden=true;modelMenu.hidden=false;summary.setAttribute('aria-expanded','true');};
                document.getElementById('pro').onclick=event=>{window.proClicks++;for(const option of modelMenu.querySelectorAll('[role=menuitemradio]'))option.setAttribute('aria-checked','false');event.currentTarget.setAttribute('aria-checked','true');proMode=true;slider.setAttribute('aria-valuemin','0');slider.setAttribute('aria-valuemax','1');slider.setAttribute('aria-valuestep','1');slider.setAttribute('aria-valuenow','0');modelMenu.hidden=true;sheet.hidden=false;summary.setAttribute('aria-expanded','false');update();};
                document.getElementById('sol').onclick=event=>{for(const option of modelMenu.querySelectorAll('[role=menuitemradio]'))option.setAttribute('aria-checked','false');event.currentTarget.setAttribute('aria-checked','true');proMode=false;slider.setAttribute('aria-valuemin','0');slider.setAttribute('aria-valuemax','3');slider.setAttribute('aria-valuestep','1');slider.setAttribute('aria-valuenow','2');modelMenu.hidden=true;sheet.hidden=false;summary.setAttribute('aria-expanded','false');update();};
                slider.onkeydown=event=>{if(event.key!=='ArrowRight'&&event.key!=='ArrowLeft')return;window.sliderKeydowns++;const min=Number(slider.getAttribute('aria-valuemin')),max=Number(slider.getAttribute('aria-valuemax')),current=Number(slider.getAttribute('aria-valuenow')),next=Math.max(min,Math.min(max,current+(event.key==='ArrowRight'?1:-1)));slider.setAttribute('aria-valuenow',String(next));update();event.preventDefault();};
                document.addEventListener('keydown',event=>{if(event.key==='Escape'&&!modelMenu.hidden){modelMenu.hidden=true;sheet.hidden=false;summary.setAttribute('aria-expanded','false');}});
                update();
                </script></body></html>
                """;
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
}
