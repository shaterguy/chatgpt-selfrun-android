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

/** Covers the existing-chat WORK selector when its semantic trigger is in the header. */
@RunWith(AndroidJUnit4.class)
public final class WorkPreferenceHeaderContinuationAndroidTest {
    private static final String CONVERSATION_URL = "https://chatgpt.com/c/work-header-continuation";

    @Test public void farHeaderModelTriggerWinsAfterStaleCalibrationIsRejected() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, modelFixture());
            evaluate(scenario, web, "(()=>{const profile={version:2,targets:{GENERAL_CONTINUATION_WORK_MODEL:{id:'stale',tag:'button'}}};localStorage.setItem('"
                    + WebUiCalibrationStore.STORAGE_KEY + "',JSON.stringify(profile));return JSON.stringify({status:'READY'});})()");

            assertEquals("true", read(scenario, web,
                    "String(document.getElementById('prompt-textarea').getBoundingClientRect().top-document.getElementById('trigger').getBoundingClientRect().bottom>260)"));

            JSONObject result = null;
            for (int attempt = 0; attempt < 12; attempt++) {
                result = evaluate(scenario, web,
                        WorkPreferenceDom.modelForConversation(CONVERSATION_URL, "luna"));
                if ("READY".equals(result.getString("status"))) break;
                assertEquals(result.toString(), "UI_WAIT", result.getString("status"));
            }

            assertNotNull(result);
            assertEquals(result.toString(), "READY", result.getString("status"));
            JSONObject diagnostics = result.getJSONObject("diagnostics");
            assertEquals("luna", diagnostics.getString("current"));
            assertEquals("heuristic-trigger", diagnostics.getString("source"));
            assertTrue(diagnostics.getBoolean("calibratedTargetFound"));
            assertFalse(diagnostics.getBoolean("calibratedTargetValid"));
            assertEquals("0", read(scenario, web, "String(window.staleClicks)"));
            assertEquals("1", read(scenario, web, "String(window.triggerClicks)"));
            assertEquals("1", read(scenario, web, "String(window.rowClicks)"));
            assertEquals("1", read(scenario, web, "String(window.optionClicks)"));
            assertEquals("Luna", read(scenario, web, "document.getElementById('trigger').textContent"));
        }
    }

    @Test public void missingModelSelectorTerminatesInsteadOfWaitingForever() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, missingSelectorFixture());
            evaluate(scenario, web,
                    "(()=>{const key='selfrun-drive:work-preference:model:GENERAL_CONTINUATION_WORK_MODEL:'+location.pathname;sessionStorage.setItem(key,JSON.stringify({startedAt:Date.now()-21000,requested:'luna',attempts:23,triggerClicks:0,rowClicks:0,optionClicks:0,fallbackClicks:0,closeAttempts:0,pending:false,lastAction:'',lastActionAt:0}));return JSON.stringify({status:'READY'});})()");

            JSONObject result = evaluate(scenario, web,
                    WorkPreferenceDom.modelForConversation(CONVERSATION_URL, "luna"));
            assertEquals("WORK_MODEL_SELECTION_TIMEOUT", result.getString("status"));
            assertTrue(result.getJSONObject("diagnostics").getLong("elapsedMs") >= 20_000L);
        }
    }

    private static String modelFixture() {
        return """
                <!doctype html><html><head><style>
                [hidden]{display:none!important}body{margin:0;min-height:1000px}header{height:72px;padding:8px}
                main{margin-top:650px}form{height:80px}button{display:block;margin:6px}
                </style></head><body>
                <header>
                  <button id="stale" type="button" aria-haspopup="dialog">Account</button>
                  <button id="trigger" type="button" aria-haspopup="dialog" aria-controls="settings" aria-expanded="false">Sol</button>
                </header>
                <main><form><textarea id="prompt-textarea"></textarea></form></main>
                <div id="settings" role="dialog" hidden>
                  <button id="model-row" type="button" role="menuitem">Model Sol</button>
                </div>
                <div id="options" role="menu" hidden>
                  <button type="button" role="menuitemradio" aria-checked="true">Sol</button>
                  <button type="button" role="menuitemradio" aria-checked="false">Terra</button>
                  <button id="luna" type="button" role="menuitemradio" aria-checked="false">Luna</button>
                </div>
                <script>
                window.staleClicks=0;window.triggerClicks=0;window.rowClicks=0;window.optionClicks=0;
                const stale=document.getElementById('stale'),trigger=document.getElementById('trigger'),settings=document.getElementById('settings'),row=document.getElementById('model-row'),options=document.getElementById('options');
                stale.onclick=()=>window.staleClicks++;
                trigger.onclick=()=>{window.triggerClicks++;const opening=settings.hidden;settings.hidden=!opening;trigger.setAttribute('aria-expanded',opening?'true':'false');};
                row.onclick=()=>{window.rowClicks++;settings.hidden=true;options.hidden=false;};
                document.getElementById('luna').onclick=event=>{window.optionClicks++;for(const option of options.querySelectorAll('[role=menuitemradio]'))option.setAttribute('aria-checked','false');event.currentTarget.setAttribute('aria-checked','true');trigger.textContent='Luna';trigger.setAttribute('aria-expanded','false');settings.hidden=true;options.hidden=true;};
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
        assertTrue("WORK header fixture did not load", loaded.await(15, TimeUnit.SECONDS));
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
