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

/** Covers existing-chat WORK selection with the current composer effort popover. */
@RunWith(AndroidJUnit4.class)
public final class WorkPreferenceHeaderContinuationAndroidTest {
    private static final String CONVERSATION_URL = "https://chatgpt.com/c/work-header-continuation";

    @Test public void composerEffortTriggerWinsAfterStaleCalibrationIsRejected() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, modelFixture());
            evaluate(scenario, web, "(()=>{const profile={version:2,targets:{GENERAL_CONTINUATION_WORK_MODEL:{id:'stale',tag:'button'}}};localStorage.setItem('"
                    + WebUiCalibrationStore.STORAGE_KEY + "',JSON.stringify(profile));return JSON.stringify({status:'READY'});})()");

            assertEquals("true", read(scenario, web,
                    "String(document.getElementById('prompt-textarea').getBoundingClientRect().top-document.getElementById('stale').getBoundingClientRect().bottom>260)"));
            assertEquals("true", read(scenario, web,
                    "String(document.querySelector('form').contains(document.getElementById('effort')))"));

            JSONObject result = null;
            for (int attempt = 0; attempt < 18; attempt++) {
                result = evaluate(scenario, web,
                        WorkPreferenceDom.modelForConversation(CONVERSATION_URL, "luna"));
                if ("READY".equals(result.getString("status"))) break;
                assertEquals(result.toString(), "UI_WAIT", result.getString("status"));
            }

            assertNotNull(result);
            assertEquals(result.toString(), "READY", result.getString("status"));
            JSONObject diagnostics = result.getJSONObject("diagnostics");
            assertEquals("luna", diagnostics.getString("observed"));
            assertEquals("luna", diagnostics.getString("currentModel"));
            assertEquals("0", read(scenario, web, "String(window.staleClicks)"));
            assertEquals("2", read(scenario, web, "String(window.effortClicks)"));
            assertEquals("1", read(scenario, web, "String(window.modelHeaderClicks)"));
            assertEquals("1", read(scenario, web, "String(window.optionClicks)"));
            assertTrue(read(scenario, web, "document.getElementById('combined').textContent").contains("Luna"));
        }
    }

    @Test public void missingModelSelectorTerminatesInsteadOfWaitingForever() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, missingSelectorFixture());
            evaluate(scenario, web,
                    "(()=>{const key='selfrun-drive:work-preference-current:model:GENERAL_CONTINUATION_WORK_MODEL:'+location.pathname;sessionStorage.setItem(key,JSON.stringify({startedAt:Date.now()-27000,requested:'luna',attempts:31,rootClicks:0,advancedClicks:0,rowClicks:0,optionClicks:0,sliderMoves:0,closeAttempts:0,pending:false,pendingLevel:'',pendingDirection:0,pendingTarget:null,pendingStrategy:'',pendingWaits:0,verified:'',lastAction:'',lastActionAt:0}));return JSON.stringify({status:'READY'});})()");

            JSONObject result = evaluate(scenario, web,
                    WorkPreferenceDom.modelForConversation(CONVERSATION_URL, "luna"));
            assertEquals("WORK_MODEL_SELECTION_TIMEOUT", result.getString("status"));
            assertTrue(result.getJSONObject("diagnostics").getLong("elapsedMs") >= 26_000L);
        }
    }

    private static String modelFixture() {
        return """
                <!doctype html><html><head><style>
                [hidden]{display:none!important}body{margin:0;min-height:1000px}header{height:72px;padding:8px}
                main{margin-top:650px}form{height:100px}button{display:block;margin:6px}
                #settings,#model-menu{position:absolute;width:320px;min-height:120px}#slider{display:block;width:280px;height:28px}
                </style></head><body>
                <header><button id="stale" type="button" aria-haspopup="dialog">Account</button></header>
                <main><form><textarea id="prompt-textarea"></textarea>
                  <button id="effort" type="button" aria-haspopup="dialog" aria-controls="settings" aria-expanded="false">Select effort</button>
                </form></main>
                <div id="settings" role="dialog" hidden>
                  <button id="combined" type="button" aria-haspopup="menu" aria-expanded="false">5.6 Sol High &gt;</button>
                  <div id="slider" role="slider" tabindex="0" aria-valuemin="0" aria-valuemax="5" aria-valuenow="2" aria-valuestep="1" aria-valuetext="High"></div>
                </div>
                <div id="model-menu" role="menu" hidden>
                  <button type="button" role="menuitem">Default</button>
                  <button id="sol" type="button" role="menuitemradio" aria-checked="true">5.6 Sol</button>
                  <button type="button" role="menuitemradio" aria-checked="false">5.6 Terra</button>
                  <button id="luna" type="button" role="menuitemradio" aria-checked="false">5.6 Luna</button>
                </div>
                <script>
                window.staleClicks=0;window.effortClicks=0;window.modelHeaderClicks=0;window.optionClicks=0;
                const stale=document.getElementById('stale'),effort=document.getElementById('effort'),settings=document.getElementById('settings'),combined=document.getElementById('combined'),menu=document.getElementById('model-menu');
                let model='Sol';
                const update=()=>{combined.textContent='5.6 '+model+' High >';};
                const closeAll=()=>{settings.hidden=true;menu.hidden=true;combined.setAttribute('aria-expanded','false');effort.setAttribute('aria-expanded','false');};
                stale.onclick=()=>window.staleClicks++;
                effort.onclick=()=>{window.effortClicks++;const opening=effort.getAttribute('aria-expanded')!=='true';closeAll();if(opening){settings.hidden=false;effort.setAttribute('aria-expanded','true');}};
                combined.onclick=()=>{window.modelHeaderClicks++;settings.hidden=true;menu.hidden=false;combined.setAttribute('aria-expanded','true');};
                document.getElementById('luna').onclick=event=>{window.optionClicks++;for(const option of menu.querySelectorAll('[role=menuitemradio]'))option.setAttribute('aria-checked','false');event.currentTarget.setAttribute('aria-checked','true');model='Luna';menu.hidden=true;settings.hidden=false;combined.setAttribute('aria-expanded','false');update();};
                update();
                </script></body></html>
                """;
    }

    private static String missingSelectorFixture() {
        return "<!doctype html><html><head><style>body{min-height:800px}main{margin-top:600px}</style></head>"
                + "<body><main><form><textarea id='prompt-textarea'></textarea></form></main></body></html>";
    }

    private static void load(ActivityScenario<SelfRunNewActivity> scenario,
                             AtomicReference<WebView> web, String html) throws Exception {
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
            view.loadDataWithBaseURL(CONVERSATION_URL, html, "text/html", "UTF-8", null);
        });
        assertTrue("WORK effort fixture did not load", loaded.await(15, TimeUnit.SECONDS));
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
