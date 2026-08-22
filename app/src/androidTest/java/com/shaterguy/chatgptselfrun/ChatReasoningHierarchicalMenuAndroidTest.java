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

/** Reproduces current reasoning -> slider sheet -> Advanced -> menu option navigation. */
@RunWith(AndroidJUnit4.class)
public final class ChatReasoningHierarchicalMenuAndroidTest {
    private static final String PROJECT_URL = "https://chatgpt.com/g/g-p-test";

    @Test public void koreanAdvancedButtonPathAppliesInstantWithoutSliderMutation() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, koreanFixture());
            String runId = "SR-ADVANCED-KO";
            scenario.onActivity(activity -> assertTrue(ChatReasoningPreferenceStore.save(activity, runId, ChatReasoningPreferenceStore.INSTANT)));
            JSONObject ready = runToReady(scenario, web, SelfRunDom.prepareInitialContext(PROJECT_URL, SelfRunStore.MODE_CHAT, runId));
            assertEquals("instant", ready.getJSONObject("diagnostics").getString("observed"));
            assertEquals("advanced-menu", ready.getJSONObject("diagnostics").getString("strategy"));
            assertEquals("1", read(scenario, web, "String(window.triggerClicks)"));
            assertEquals("1", read(scenario, web, "String(window.advancedClicks)"));
            assertEquals("1", read(scenario, web, "String(window.reasoningClicks)"));
            assertEquals("1", read(scenario, web, "String(window.optionClicks)"));
            assertEquals("0", read(scenario, web, "String(window.sliderEvents)"));
            assertEquals("즉시", read(scenario, web, "document.getElementById('reasoning-trigger').textContent"));
        }
    }

    @Test public void englishAdvancedButtonReplacementMenuAppliesProWithoutSliderMutation() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, englishFixture());
            String runId = "SR-ADVANCED-EN";
            scenario.onActivity(activity -> assertTrue(ChatReasoningPreferenceStore.save(activity, runId, ChatReasoningPreferenceStore.PRO)));
            JSONObject ready = runToReady(scenario, web, SelfRunDom.prepareInitialContext(PROJECT_URL, SelfRunStore.MODE_CHAT, runId));
            assertEquals("pro", ready.getJSONObject("diagnostics").getString("observed"));
            assertEquals("1", read(scenario, web, "String(window.triggerClicks)"));
            assertEquals("1", read(scenario, web, "String(window.advancedClicks)"));
            assertEquals("1", read(scenario, web, "String(window.reasoningClicks)"));
            assertEquals("1", read(scenario, web, "String(window.optionClicks)"));
            assertEquals("0", read(scenario, web, "String(window.inertReasoningClicks)"));
            assertEquals("0", read(scenario, web, "String(window.sliderEvents)"));
            assertEquals("Pro", read(scenario, web, "document.getElementById('reasoning-trigger').textContent"));
        }
    }

    @Test public void matchingCurrentValueSkipsAllReasoningSelectionUi() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, englishFixture());
            String runId = "SR-CURRENT-MATCH";
            scenario.onActivity(activity -> assertTrue(ChatReasoningPreferenceStore.save(
                    activity, runId, ChatReasoningPreferenceStore.EXTRA_HIGH)));
            JSONObject ready = runToReady(scenario, web,
                    SelfRunDom.prepareInitialContext(PROJECT_URL, SelfRunStore.MODE_CHAT, runId));
            assertEquals("already-selected", ready.getJSONObject("diagnostics").getString("action"));
            assertEquals("0", read(scenario, web, "String(window.triggerClicks)"));
            assertEquals("0", read(scenario, web, "String(window.advancedClicks)"));
            assertEquals("0", read(scenario, web, "String(window.reasoningClicks)"));
            assertEquals("0", read(scenario, web, "String(window.optionClicks)"));
            assertEquals("0", read(scenario, web, "String(window.sliderEvents)"));
        }
    }

    private static JSONObject runToReady(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web, String script) throws Exception {
        JSONObject result = null;
        for (int attempt = 0; attempt < 18; attempt++) {
            result = evaluate(scenario, web, script);
            if ("READY".equals(result.getString("status"))) break;
            assertEquals(result.toString(), "UI_WAIT", result.getString("status"));
        }
        assertNotNull(result);
        assertEquals(result.toString(), "READY", result.getString("status"));
        return result;
    }

    private static String koreanFixture() {
        return """
                <!doctype html><html><head><style>[hidden]{display:none!important}body{min-height:800px}form{margin-top:500px}#sheet,#advanced-menu,#submenu{position:absolute;width:280px;min-height:120px}</style></head><body>
                <div><button id="chat" aria-selected="true">Chat</button><button id="work" aria-selected="false">Work</button></div>
                <form><textarea id="prompt-textarea"></textarea><button id="reasoning-trigger" type="button" aria-haspopup="dialog" aria-controls="sheet" aria-expanded="false">매우 높음</button></form>
                <div id="sheet" role="dialog" hidden><input id="slider" type="range" min="0" max="4" value="3" aria-valuetext="매우 높음"><button id="advanced" type="button">고급</button></div>
                <div id="advanced-menu" role="menu" hidden><button type="button" role="menuitem">모델 GPT-5.6 Sol</button><button id="reasoning-row" type="button" role="menuitem" aria-haspopup="menu">추론 수준 매우 높음</button></div>
                <div id="submenu" role="menu" hidden><button id="instant" type="button" role="menuitemradio" aria-checked="false">즉시</button><button type="button" role="menuitemradio" aria-checked="false">중간</button><button type="button" role="menuitemradio" aria-checked="false">높음</button><button type="button" role="menuitemradio" aria-checked="true">매우 높음</button><button type="button" role="menuitemradio" aria-checked="false">Pro</button></div>
                <script>
                window.triggerClicks=0;window.advancedClicks=0;window.reasoningClicks=0;window.optionClicks=0;window.sliderEvents=0;
                const trigger=document.getElementById('reasoning-trigger'),sheet=document.getElementById('sheet'),advanced=document.getElementById('advanced'),menu=document.getElementById('advanced-menu'),row=document.getElementById('reasoning-row'),submenu=document.getElementById('submenu'),slider=document.getElementById('slider');
                trigger.onclick=()=>{window.triggerClicks++;const opening=sheet.hidden;sheet.hidden=!opening;trigger.setAttribute('aria-expanded',opening?'true':'false');};
                advanced.onclick=()=>{window.advancedClicks++;sheet.hidden=true;menu.hidden=false;trigger.setAttribute('aria-expanded','true');};
                row.onclick=()=>{window.reasoningClicks++;submenu.hidden=false;};
                document.getElementById('instant').onclick=event=>{window.optionClicks++;for(const option of submenu.querySelectorAll('[role=menuitemradio]'))option.setAttribute('aria-checked','false');event.currentTarget.setAttribute('aria-checked','true');trigger.textContent='즉시';trigger.setAttribute('aria-expanded','false');sheet.hidden=true;menu.hidden=true;submenu.hidden=true;};
                for(const type of ['input','change','keydown','pointerdown','mousedown','click'])slider.addEventListener(type,()=>window.sliderEvents++);
                </script></body></html>
                """;
    }

    private static String englishFixture() {
        return """
                <!doctype html><html><head><style>[hidden]{display:none!important}body{min-height:800px}form{margin-top:500px}</style></head><body>
                <div><button id="chat" aria-selected="true">Chat</button><button id="work" aria-selected="false">Work</button></div>
                <form><textarea id="prompt-textarea"></textarea><button id="reasoning-trigger" type="button" aria-haspopup="menu" aria-controls="sheet" aria-expanded="false">Extra high</button></form>
                <div id="sheet" role="menu" hidden data-testid="composer-intelligence-picker-content">
                  <div id="simple-view" data-testid="composer-model-picker-slider-simple-view" data-active="true">
                    <div role="menuitem" aria-label="Performance"><div id="slider" role="slider" aria-hidden="true" tabindex="-1" aria-valuemin="0" aria-valuemax="3" aria-valuenow="3"></div></div>
                  </div>
                  <div id="advanced" role="menuitem" aria-label="Show advanced options" aria-expanded="false">Advanced</div>
                  <div id="advanced-view" data-testid="composer-model-picker-slider-advanced-view" data-active="false" inert>
                    <div type="button" role="menuitem">Model GPT-5.6 Sol</div>
                    <div id="reasoning-row" type="button" role="menuitem" aria-haspopup="menu" aria-expanded="false">Reasoning level Extra high</div>
                  </div>
                </div>
                <div id="submenu" role="menu" hidden><button type="button" role="menuitemradio" aria-checked="false">Instant</button><button type="button" role="menuitemradio" aria-checked="false">Medium</button><button type="button" role="menuitemradio" aria-checked="false">High</button><button type="button" role="menuitemradio" aria-checked="true">Extra high</button><button id="pro" type="button" role="menuitemradio" aria-checked="false">Pro</button></div>
                <script>
                window.triggerClicks=0;window.advancedClicks=0;window.reasoningClicks=0;window.inertReasoningClicks=0;window.optionClicks=0;window.sliderEvents=0;
                const trigger=document.getElementById('reasoning-trigger'),sheet=document.getElementById('sheet'),advanced=document.getElementById('advanced'),simpleView=document.getElementById('simple-view'),advancedView=document.getElementById('advanced-view'),row=document.getElementById('reasoning-row'),submenu=document.getElementById('submenu'),slider=document.getElementById('slider');
                trigger.onclick=()=>{window.triggerClicks++;const opening=sheet.hidden;sheet.hidden=!opening;trigger.setAttribute('aria-expanded',opening?'true':'false');};
                advanced.onclick=()=>{window.advancedClicks++;simpleView.dataset.active='false';simpleView.setAttribute('inert','');advancedView.dataset.active='true';advancedView.removeAttribute('inert');advanced.setAttribute('aria-label','Show fewer options');advanced.setAttribute('aria-expanded','true');};
                row.onclick=()=>{if(row.closest('[inert]')){window.inertReasoningClicks++;return;}window.reasoningClicks++;row.setAttribute('aria-expanded','true');submenu.hidden=false;};
                document.getElementById('pro').onclick=event=>{window.optionClicks++;for(const option of submenu.querySelectorAll('[role=menuitemradio]'))option.setAttribute('aria-checked','false');event.currentTarget.setAttribute('aria-checked','true');trigger.textContent='Pro';trigger.setAttribute('aria-expanded','false');sheet.hidden=true;submenu.hidden=true;};
                for(const type of ['input','change','keydown','pointerdown','mousedown','click'])slider.addEventListener(type,()=>window.sliderEvents++);
                </script></body></html>
                """;
    }

    private static void load(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web, String html) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);view.getSettings().setJavaScriptEnabled(true);view.getSettings().setDomStorageEnabled(true);
            view.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView ignored,String url){if(PROJECT_URL.equals(url))loaded.countDown();}});
            activity.setContentView(view);web.set(view);view.loadDataWithBaseURL(PROJECT_URL,html,"text/html","UTF-8",null);
        });
        assertTrue("Advanced reasoning fixture did not load",loaded.await(15,TimeUnit.SECONDS));
        assertNotNull("Android System WebView must be available",WebView.getCurrentWebViewPackage());
    }

    private static JSONObject evaluate(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web, String script) throws Exception {
        CountDownLatch complete=new CountDownLatch(1);AtomicReference<String> raw=new AtomicReference<>();scenario.onActivity(activity->web.get().evaluateJavascript(script,value->{raw.set(value);complete.countDown();}));
        assertTrue("WebView script timed out",complete.await(15,TimeUnit.SECONDS));Object decoded=new JSONTokener(raw.get()).nextValue();return new JSONObject(String.valueOf(decoded));
    }

    private static String read(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web, String expression) throws Exception {
        CountDownLatch complete=new CountDownLatch(1);AtomicReference<String> raw=new AtomicReference<>();scenario.onActivity(activity->web.get().evaluateJavascript(expression,value->{raw.set(value);complete.countDown();}));
        assertTrue("WebView read timed out",complete.await(15,TimeUnit.SECONDS));return String.valueOf(new JSONTokener(raw.get()).nextValue());
    }
}
