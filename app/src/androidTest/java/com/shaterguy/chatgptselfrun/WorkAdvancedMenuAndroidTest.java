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

/** Exercises the current Work effort popover: combined model/reasoning row plus reasoning slider. */
@RunWith(AndroidJUnit4.class)
public final class WorkAdvancedMenuAndroidTest {
    private static final String CONVERSATION_URL = "https://chatgpt.com/c/conversation123";

    @Test public void workUsesCombinedHeaderForModelAndSliderForReasoningWithoutAdvanced() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web);

            JSONObject modelReady = runToReady(scenario, web,
                    WorkPreferenceDom.modelForConversation(CONVERSATION_URL, "sol"));
            assertEquals("sol", modelReady.getJSONObject("diagnostics").getString("currentModel"));
            assertEquals("1", read(scenario, web, "String(window.modelHeaderClicks)"));
            assertEquals("1", read(scenario, web, "String(window.modelOptionClicks)"));
            assertEquals("0", read(scenario, web, "String(window.advancedClicks)"));

            JSONObject reasoningReady = runToReady(scenario, web,
                    WorkPreferenceDom.reasoningForConversation(CONVERSATION_URL, "max"));
            assertEquals("max", reasoningReady.getJSONObject("diagnostics").getString("currentReasoning"));
            assertEquals("4", read(scenario, web,
                    "document.getElementById('slider').getAttribute('aria-valuenow')"));
            assertEquals("5.6 Sol Maximum >", read(scenario, web,
                    "document.getElementById('combined').textContent"));
            assertEquals("2", read(scenario, web, "String(window.sliderKeydowns)"));
            assertEquals("0", read(scenario, web, "String(window.advancedClicks)"));

            JSONObject alreadySelected = runToReady(scenario, web,
                    WorkPreferenceDom.reasoningForConversation(CONVERSATION_URL, "max"));
            assertEquals("max", alreadySelected.getJSONObject("diagnostics").getString("currentReasoning"));
            assertEquals("2", read(scenario, web, "String(window.sliderKeydowns)"));
        }
    }

    @Test public void workCanMoveFromMaximumToUltraOnTheSameDirectSlider() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web);
            runToReady(scenario, web, WorkPreferenceDom.modelForConversation(CONVERSATION_URL, "sol"));
            runToReady(scenario, web, WorkPreferenceDom.reasoningForConversation(CONVERSATION_URL, "max"));

            JSONObject ready = runToReady(scenario, web,
                    WorkPreferenceDom.reasoningForConversation(CONVERSATION_URL, "ultra"));
            assertEquals("ultra", ready.getJSONObject("diagnostics").getString("currentReasoning"));
            assertEquals("5", read(scenario, web,
                    "document.getElementById('slider').getAttribute('aria-valuenow')"));
            assertEquals("5.6 Sol Ultra >", read(scenario, web,
                    "document.getElementById('combined').textContent"));
        }
    }

    private static JSONObject runToReady(ActivityScenario<SelfRunNewActivity> scenario,
                                         AtomicReference<WebView> web, String script) throws Exception {
        JSONObject result = null;
        for (int attempt = 0; attempt < 28; attempt++) {
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
                    if (CONVERSATION_URL.equals(url)) loaded.countDown();
                }
            });
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(CONVERSATION_URL, fixture(), "text/html", "UTF-8", null);
        });
        assertTrue("Work current effort fixture did not load", loaded.await(15, TimeUnit.SECONDS));
        assertNotNull("Android System WebView must be available", WebView.getCurrentWebViewPackage());
    }

    private static String fixture() {
        return """
                <!doctype html><html><head><style>
                [hidden]{display:none!important}body{min-height:800px}form{margin-top:500px}
                #effort-popup,#model-menu{position:absolute;width:320px;min-height:120px}
                #slider{display:block;width:280px;height:28px}
                </style></head><body>
                <form><textarea id="prompt-textarea"></textarea>
                  <button id="effort" type="button" aria-haspopup="dialog" aria-controls="effort-popup" aria-expanded="false">Select effort</button>
                </form>
                <div id="effort-popup" role="dialog" hidden>
                  <button id="combined" type="button" aria-haspopup="menu" aria-expanded="false">5.6 Terra High &gt;</button>
                  <div id="slider" role="slider" tabindex="0" aria-valuemin="0" aria-valuemax="5" aria-valuenow="2" aria-valuestep="1" aria-valuetext="High"></div>
                </div>
                <div id="model-menu" role="menu" hidden>
                  <button type="button" role="menuitem">Default</button>
                  <button id="sol" type="button" role="menuitemradio" aria-checked="false">5.6 Sol</button>
                  <button id="terra" type="button" role="menuitemradio" aria-checked="true">5.6 Terra</button>
                  <button type="button" role="menuitemradio" aria-checked="false">5.6 Luna</button>
                  <button type="button" role="menuitemradio" aria-checked="false">5.5</button>
                </div>
                <script>
                window.rootClicks=0;window.modelHeaderClicks=0;window.modelOptionClicks=0;window.sliderKeydowns=0;window.advancedClicks=0;
                const effort=document.getElementById('effort'),popup=document.getElementById('effort-popup'),combined=document.getElementById('combined'),slider=document.getElementById('slider'),modelMenu=document.getElementById('model-menu');
                let model='Terra';const labels=['Light','Medium','High','Extra High','Maximum','Ultra'];
                const update=()=>{const index=Number(slider.getAttribute('aria-valuenow')),reasoning=labels[index];slider.setAttribute('aria-valuetext',reasoning);combined.textContent='5.6 '+model+' '+reasoning+' >';};
                const closeAll=()=>{popup.hidden=true;modelMenu.hidden=true;combined.setAttribute('aria-expanded','false');effort.setAttribute('aria-expanded','false');};
                effort.onclick=()=>{window.rootClicks++;const opening=effort.getAttribute('aria-expanded')!=='true';closeAll();if(opening){popup.hidden=false;effort.setAttribute('aria-expanded','true');}};
                combined.onclick=()=>{window.modelHeaderClicks++;popup.hidden=true;modelMenu.hidden=false;combined.setAttribute('aria-expanded','true');};
                document.getElementById('sol').onclick=event=>{window.modelOptionClicks++;for(const option of modelMenu.querySelectorAll('[role=menuitemradio]'))option.setAttribute('aria-checked','false');event.currentTarget.setAttribute('aria-checked','true');model='Sol';modelMenu.hidden=true;popup.hidden=false;combined.setAttribute('aria-expanded','false');update();};
                document.getElementById('terra').onclick=event=>{for(const option of modelMenu.querySelectorAll('[role=menuitemradio]'))option.setAttribute('aria-checked','false');event.currentTarget.setAttribute('aria-checked','true');model='Terra';modelMenu.hidden=true;popup.hidden=false;combined.setAttribute('aria-expanded','false');update();};
                slider.onkeydown=event=>{if(event.key!=='ArrowRight'&&event.key!=='ArrowLeft')return;window.sliderKeydowns++;const current=Number(slider.getAttribute('aria-valuenow')),next=Math.max(0,Math.min(5,current+(event.key==='ArrowRight'?1:-1)));slider.setAttribute('aria-valuenow',String(next));update();event.preventDefault();};
                document.addEventListener('keydown',event=>{if(event.key==='Escape'&&!modelMenu.hidden){modelMenu.hidden=true;popup.hidden=false;combined.setAttribute('aria-expanded','false');}});
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
