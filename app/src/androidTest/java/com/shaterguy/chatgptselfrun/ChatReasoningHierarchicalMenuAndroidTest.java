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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Reproduces the two-level Advanced -> Reasoning level selector used by current Chat. */
@RunWith(AndroidJUnit4.class)
public final class ChatReasoningHierarchicalMenuAndroidTest {
    private static final String PROJECT_URL = "https://chatgpt.com/g/g-p-test";

    @Test public void koreanSideSubmenuAppliesInstantWithoutSliderFallback() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, koreanSideSubmenuFixture());
            String runId = "SR-HIERARCHY-KO";
            scenario.onActivity(activity -> assertTrue(ChatReasoningPreferenceStore.save(
                    activity, runId, ChatReasoningPreferenceStore.INSTANT)));

            JSONObject ready = runToReady(scenario, web,
                    SelfRunDom.prepareInitialContext(PROJECT_URL, SelfRunStore.MODE_CHAT, runId));

            assertEquals("instant", ready.getJSONObject("diagnostics").getString("observed"));
            assertEquals("hierarchical-menu",
                    ready.getJSONObject("diagnostics").getString("strategy"));
            assertFalse(ready.getJSONObject("diagnostics").getBoolean("sliderFound"));
            assertEquals("1", read(scenario, web, "String(window.advancedClicks)"));
            assertEquals("1", read(scenario, web, "String(window.reasoningClicks)"));
            assertEquals("1", read(scenario, web, "String(window.instantClicks)"));
            assertEquals("즉시", read(scenario, web,
                    "document.getElementById('reasoning-trigger').textContent"));
        }
    }

    @Test public void englishReplacementMenuAppliesProWithOneClickPerStage() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, englishReplacementFixture());
            String runId = "SR-HIERARCHY-EN";
            scenario.onActivity(activity -> assertTrue(ChatReasoningPreferenceStore.save(
                    activity, runId, ChatReasoningPreferenceStore.PRO)));

            JSONObject ready = runToReady(scenario, web,
                    SelfRunDom.prepareInitialContext(PROJECT_URL, SelfRunStore.MODE_CHAT, runId));

            assertEquals("pro", ready.getJSONObject("diagnostics").getString("observed"));
            assertEquals("1", read(scenario, web, "String(window.advancedClicks)"));
            assertEquals("1", read(scenario, web, "String(window.reasoningClicks)"));
            assertEquals("1", read(scenario, web, "String(window.proClicks)"));
            assertEquals("Pro", read(scenario, web,
                    "document.getElementById('reasoning-trigger').textContent"));
        }
    }

    private static JSONObject runToReady(ActivityScenario<SelfRunNewActivity> scenario,
                                         AtomicReference<WebView> web, String script) throws Exception {
        JSONObject result = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            result = evaluate(scenario, web, script);
            if ("READY".equals(result.getString("status"))) break;
            assertEquals(result.toString(), "UI_WAIT", result.getString("status"));
        }
        assertNotNull(result);
        assertEquals(result.toString(), "READY", result.getString("status"));
        return result;
    }

    private static String koreanSideSubmenuFixture() {
        return """
                <!doctype html><html><head><style>
                [hidden]{display:none!important}body{min-height:800px}form{margin-top:500px}
                #advanced-menu,#reasoning-submenu{display:block;position:absolute;width:260px;min-height:120px}
                #advanced-menu{left:520px;top:430px}#reasoning-submenu{left:790px;top:500px}
                </style></head><body>
                <div id="mode-group">
                  <button id="chat" aria-selected="true">Chat</button>
                  <button id="work" aria-selected="false">Work</button>
                </div>
                <form><textarea id="prompt-textarea"></textarea>
                  <button id="reasoning-trigger" type="button" aria-haspopup="menu" aria-controls="advanced-menu" aria-expanded="false">매우 높음</button>
                </form>
                <div id="advanced-menu" role="menu" hidden>
                  <div>고급</div>
                  <button type="button" role="menuitem">모델 GPT-5.6 Sol</button>
                  <button id="reasoning-row" type="button" role="menuitem" aria-haspopup="menu" aria-controls="reasoning-submenu" aria-expanded="false">추론 수준 매우 높음</button>
                </div>
                <div id="reasoning-submenu" role="menu" hidden>
                  <button id="instant" type="button" role="menuitemradio" aria-checked="false">즉시</button>
                  <button type="button" role="menuitemradio" aria-checked="false">중간</button>
                  <button type="button" role="menuitemradio" aria-checked="false">높음</button>
                  <button id="xhigh" type="button" role="menuitemradio" aria-checked="true">매우 높음</button>
                  <button type="button" role="menuitemradio" aria-checked="false">Pro</button>
                </div>
                <script>
                window.advancedClicks=0;window.reasoningClicks=0;window.instantClicks=0;
                const trigger=document.getElementById('reasoning-trigger');
                const advanced=document.getElementById('advanced-menu');
                const row=document.getElementById('reasoning-row');
                const submenu=document.getElementById('reasoning-submenu');
                trigger.onclick=()=>{window.advancedClicks++;const opening=advanced.hidden;advanced.hidden=!opening;if(!opening)submenu.hidden=true;trigger.setAttribute('aria-expanded',opening?'true':'false');};
                row.onclick=()=>{window.reasoningClicks++;submenu.hidden=false;row.setAttribute('aria-expanded','true');};
                document.getElementById('instant').onclick=event=>{window.instantClicks++;for(const option of submenu.querySelectorAll('[role=menuitemradio]'))option.setAttribute('aria-checked','false');event.currentTarget.setAttribute('aria-checked','true');trigger.textContent='즉시';trigger.setAttribute('aria-expanded','false');row.setAttribute('aria-expanded','false');advanced.hidden=true;submenu.hidden=true;};
                </script></body></html>
                """;
    }

    private static String englishReplacementFixture() {
        return """
                <!doctype html><html><head><style>
                [hidden]{display:none!important}body{min-height:800px}form{margin-top:500px}
                #advanced-menu{display:block;position:absolute;left:520px;top:430px;width:260px;min-height:120px}
                </style></head><body>
                <div id="mode-group">
                  <button id="chat" aria-selected="true">Chat</button>
                  <button id="work" aria-selected="false">Work</button>
                </div>
                <form><textarea id="prompt-textarea"></textarea>
                  <button id="reasoning-trigger" type="button" aria-haspopup="menu" aria-controls="advanced-menu" aria-expanded="false">Extra high</button>
                </form>
                <div id="advanced-menu" role="menu" hidden>
                  <div>Advanced</div>
                  <button type="button" role="menuitem">Model GPT-5.6 Sol</button>
                  <button id="reasoning-row" type="button" role="menuitem" aria-haspopup="menu" aria-expanded="false">Reasoning level Extra high</button>
                </div>
                <script>
                window.advancedClicks=0;window.reasoningClicks=0;window.proClicks=0;
                const trigger=document.getElementById('reasoning-trigger');
                const menu=document.getElementById('advanced-menu');
                trigger.onclick=()=>{window.advancedClicks++;const opening=menu.hidden;menu.hidden=!opening;trigger.setAttribute('aria-expanded',opening?'true':'false');};
                document.getElementById('reasoning-row').onclick=()=>{window.reasoningClicks++;menu.innerHTML=`
                  <button type="button" role="menuitemradio" aria-checked="false">Instant</button>
                  <button type="button" role="menuitemradio" aria-checked="false">Medium</button>
                  <button type="button" role="menuitemradio" aria-checked="false">High</button>
                  <button type="button" role="menuitemradio" aria-checked="true">Extra high</button>
                  <button id="pro" type="button" role="menuitemradio" aria-checked="false">Pro</button>`;
                  document.getElementById('pro').onclick=event=>{window.proClicks++;for(const option of menu.querySelectorAll('[role=menuitemradio]'))option.setAttribute('aria-checked','false');event.currentTarget.setAttribute('aria-checked','true');trigger.textContent='Pro';trigger.setAttribute('aria-expanded','false');menu.hidden=true;};
                };
                </script></body></html>
                """;
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
        assertTrue("Hierarchical Chat reasoning fixture did not load",
                loaded.await(15, TimeUnit.SECONDS));
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
}
