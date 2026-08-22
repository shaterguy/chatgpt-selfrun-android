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

/** Exercises the persistent desktop Work advanced-menu state in Android WebView. */
@RunWith(AndroidJUnit4.class)
public final class WorkAdvancedMenuAndroidTest {
    private static final String CONVERSATION_URL = "https://chatgpt.com/c/conversation123";

    @Test public void workEntersAdvancedOnceThenKeepsModelAndReasoningMenuFlow() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web);

            JSONObject modelReady = runToReady(scenario, web,
                    WorkPreferenceDom.modelForConversation(CONVERSATION_URL, "sol"));
            assertEquals("sol", modelReady.getJSONObject("diagnostics").getString("current"));
            assertEquals("1", read(scenario, web, "String(window.advancedClicks)"));
            assertEquals("1", read(scenario, web, "String(window.modelRowClicks)"));
            assertEquals("1", read(scenario, web, "String(window.modelOptionClicks)"));

            JSONObject reasoningReady = runToReady(scenario, web,
                    WorkPreferenceDom.reasoningForConversation(CONVERSATION_URL, "max"));
            assertEquals("max", reasoningReady.getJSONObject("diagnostics").getString("current"));
            assertEquals("1", read(scenario, web, "String(window.advancedClicks)"));
            assertEquals("1", read(scenario, web, "String(window.reasoningRowClicks)"));
            assertEquals("1", read(scenario, web, "String(window.reasoningOptionClicks)"));
            assertEquals("0", read(scenario, web, "String(window.sliderEvents)"));
            assertEquals("0", read(scenario, web, "String(window.resetClicks)"));

            JSONObject alreadySelected = runToReady(scenario, web,
                    WorkPreferenceDom.reasoningForConversation(CONVERSATION_URL, "max"));
            assertEquals("already-selected",
                    alreadySelected.getJSONObject("diagnostics").getString("action"));
            assertEquals("2", read(scenario, web, "String(window.triggerClicks)"));
            assertEquals("1", read(scenario, web, "String(window.advancedClicks)"));
            assertEquals("1", read(scenario, web, "String(window.reasoningRowClicks)"));
            assertEquals("1", read(scenario, web, "String(window.reasoningOptionClicks)"));
        }
    }

    private static JSONObject runToReady(ActivityScenario<SelfRunNewActivity> scenario,
                                         AtomicReference<WebView> web, String script) throws Exception {
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
        assertTrue("Work advanced fixture did not load", loaded.await(15, TimeUnit.SECONDS));
        assertNotNull("Android System WebView must be available", WebView.getCurrentWebViewPackage());
    }

    private static String fixture() {
        return """
                <!doctype html><html><head><style>
                [hidden]{display:none!important}body{min-height:800px}form{margin-top:500px}
                #compact-menu,#advanced-menu,#model-submenu,#reasoning-submenu{position:absolute;width:300px;min-height:100px}
                </style></head><body>
                <form><textarea id="prompt-textarea"></textarea>
                  <button id="trigger" type="button" aria-haspopup="menu" aria-expanded="false">GPT-5.6 Terra High</button>
                </form>
                <div id="compact-menu" role="menu" hidden>
                  <div role="menuitem" aria-label="Performance"><div id="slider" role="slider" aria-hidden="true" tabindex="-1" aria-valuemin="0" aria-valuemax="3" aria-valuenow="3"></div></div>
                  <button id="advanced" type="button" role="menuitem" aria-label="Show advanced options">Advanced</button>
                  <button type="button" role="menuitem">Model GPT-5.6 Terra</button>
                  <button type="button" role="menuitem">Reasoning level High</button>
                </div>
                <div id="advanced-menu" role="menu" hidden>
                  <button type="button" role="menuitem" aria-label="Show fewer options">Advanced</button>
                  <button id="reset" type="button" role="menuitem">Reset to default</button>
                  <button id="model-row" type="button" role="menuitem" aria-haspopup="menu">Model GPT-5.6 Terra</button>
                  <button id="reasoning-row" type="button" role="menuitem" aria-haspopup="menu">Reasoning level High</button>
                  <button type="button" role="menuitem">Speed Standard</button>
                </div>
                <div id="model-submenu" role="menu" hidden>
                  <button id="sol" type="button" role="menuitemradio" aria-checked="false">GPT-5.6 Sol</button>
                  <button type="button" role="menuitemradio" aria-checked="true">GPT-5.6 Terra</button>
                  <button type="button" role="menuitemradio" aria-checked="false">GPT-5.6 Luna</button>
                </div>
                <div id="reasoning-submenu" role="menu" hidden>
                  <button type="button" role="menuitemradio" aria-checked="false">Medium</button>
                  <button type="button" role="menuitemradio" aria-checked="true">High</button>
                  <button id="maximum" type="button" role="menuitemradio" aria-checked="false">Maximum</button>
                </div>
                <script>
                window.advancedEnabled=false;window.triggerClicks=0;window.advancedClicks=0;window.modelRowClicks=0;window.reasoningRowClicks=0;window.modelOptionClicks=0;window.reasoningOptionClicks=0;window.sliderEvents=0;window.resetClicks=0;
                const trigger=document.getElementById('trigger'),compact=document.getElementById('compact-menu'),advancedMenu=document.getElementById('advanced-menu'),advanced=document.getElementById('advanced'),modelRow=document.getElementById('model-row'),reasoningRow=document.getElementById('reasoning-row'),modelSubmenu=document.getElementById('model-submenu'),reasoningSubmenu=document.getElementById('reasoning-submenu'),slider=document.getElementById('slider');
                const closeAll=()=>{compact.hidden=true;advancedMenu.hidden=true;modelSubmenu.hidden=true;reasoningSubmenu.hidden=true;trigger.setAttribute('aria-expanded','false');};
                trigger.onclick=()=>{window.triggerClicks++;const opening=trigger.getAttribute('aria-expanded')!=='true';closeAll();if(opening){(window.advancedEnabled?advancedMenu:compact).hidden=false;trigger.setAttribute('aria-expanded','true');}};
                advanced.onclick=()=>{window.advancedClicks++;window.advancedEnabled=true;compact.hidden=true;advancedMenu.hidden=false;};
                modelRow.onclick=()=>{window.modelRowClicks++;modelSubmenu.hidden=false;};
                reasoningRow.onclick=()=>{window.reasoningRowClicks++;reasoningSubmenu.hidden=false;};
                document.getElementById('sol').onclick=event=>{window.modelOptionClicks++;for(const option of modelSubmenu.querySelectorAll('[role=menuitemradio]'))option.setAttribute('aria-checked','false');event.currentTarget.setAttribute('aria-checked','true');trigger.textContent=trigger.textContent.replace('Terra','Sol');modelRow.textContent='Model GPT-5.6 Sol';closeAll();};
                document.getElementById('maximum').onclick=event=>{window.reasoningOptionClicks++;for(const option of reasoningSubmenu.querySelectorAll('[role=menuitemradio]'))option.setAttribute('aria-checked','false');event.currentTarget.setAttribute('aria-checked','true');trigger.textContent=trigger.textContent.replace('High','Maximum');reasoningRow.textContent='Reasoning level Maximum';closeAll();};
                document.getElementById('reset').onclick=()=>window.resetClicks++;
                for(const type of ['input','change','keydown','pointerdown','mousedown','click'])slider.addEventListener(type,()=>window.sliderEvents++);
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
