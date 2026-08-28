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

/** Reproduces the 2026-08-28 desktop Chrome picker: slider role without ARIA value metadata. */
@RunWith(AndroidJUnit4.class)
public final class CurrentComposerDetentPickerAndroidTest {
    private static final String PROJECT_URL = "https://chatgpt.com/g/g-p-detent-test";
    private static final String CONVERSATION_URL = "https://chatgpt.com/c/detent-work-test";

    @Test public void chatHighUsesPerformanceDetentWhenSliderHasNoValueAttributes() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, PROJECT_URL, chatFixture());
            String runId = "SR-DETENT-CHAT-HIGH";
            scenario.onActivity(activity -> assertTrue(ChatReasoningPreferenceStore.save(
                    activity, runId, ChatReasoningPreferenceStore.HIGH)));
            String script = SelfRunDom.prepareInitialContext(PROJECT_URL, SelfRunStore.MODE_CHAT, runId);

            JSONObject result = runUntilReady(scenario, web, script, 20);
            assertEquals(result.toString(), "READY", result.getString("status"));
            assertEquals("high", result.getJSONObject("diagnostics").getString("observed"));
            assertEquals("1", read(scenario, web, "String(window.detentClicks)"));
            assertEquals("High", read(scenario, web, "document.getElementById('reasoning').textContent"));
            assertEquals("false", read(scenario, web,
                    "String(document.getElementById('slider').hasAttribute('aria-valuenow'))"));
            assertEquals("false", read(scenario, web,
                    "String(document.getElementById('slider').hasAttribute('aria-valuetext'))"));
        }
    }

    @Test public void workHighUsesSixStepPerformanceDetentWithoutAriaValue() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, CONVERSATION_URL, workFixture());
            String script = WorkPreferenceDom.reasoningForConversation(CONVERSATION_URL, "high");

            JSONObject result = runUntilReady(scenario, web, script, 20);
            assertEquals(result.toString(), "READY", result.getString("status"));
            JSONObject diagnostics = result.getJSONObject("diagnostics");
            assertEquals("high", diagnostics.getString("observed"));
            assertEquals("high", diagnostics.getString("currentReasoning"));
            assertEquals("1", read(scenario, web, "String(window.detentClicks)"));
            assertTrue(read(scenario, web, "document.getElementById('effort').textContent").contains("High"));
            assertEquals("false", read(scenario, web,
                    "String(document.getElementById('slider').hasAttribute('aria-valuenow'))"));
        }
    }

    private static String chatFixture() {
        return """
                <!doctype html><html><head><style>
                [hidden]{display:none!important}body{min-height:800px}form{margin-top:500px;min-height:100px}
                #menu{position:absolute;width:280px}.track{display:flex;align-items:center;justify-content:space-between;width:236px;height:28px;position:relative}
                .detent{display:inline-block;width:8px;height:8px}.knob{display:block;position:absolute;width:28px;height:28px;left:208px;top:0}
                </style></head><body>
                <div><button id="chat" role="radio" aria-checked="true" data-state="on" data-tpp-toggle-value="chatgpt">Chat</button>
                <button id="work" role="radio" aria-checked="false" data-state="off" data-tpp-toggle-value="work">Work</button></div>
                <form><textarea id="prompt-textarea"></textarea><button id="reasoning" type="button" aria-haspopup="menu" aria-expanded="false">Extra High</button></form>
                <div id="menu" role="menu" hidden>
                  <div id="model-row" role="menuitem" aria-label="Select model">Extra High</div>
                  <div id="performance" role="menuitem" aria-label="Performance"><span class="track"><span id="points">
                    <span class="detent" data-index="0"></span><span class="detent" data-index="1"></span><span class="detent" data-index="2"></span><span class="detent" data-index="3"></span>
                  </span><span><span id="slider" class="knob" role="slider"></span></span></span></div>
                </div>
                <script>
                window.detentClicks=0;const root=document.getElementById('reasoning'),menu=document.getElementById('menu'),row=document.getElementById('model-row'),perf=document.getElementById('performance'),slider=document.getElementById('slider');
                const labels=['Instant','Medium','High','Extra High'];let current=3;
                const update=()=>{root.textContent=labels[current];row.textContent=labels[current];slider.style.left=(current*208/3)+'px';};
                root.onclick=()=>{const opening=root.getAttribute('aria-expanded')!=='true';root.setAttribute('aria-expanded',String(opening));menu.hidden=!opening;};
                perf.addEventListener('click',event=>{const point=event.target.closest?.('[data-index]');if(!point)return;window.detentClicks++;current=Number(point.dataset.index);update();});
                update();
                </script></body></html>
                """;
    }

    private static String workFixture() {
        return """
                <!doctype html><html><head><style>
                [hidden]{display:none!important}body{min-height:800px}form{margin-top:500px;min-height:100px}
                #menu{position:absolute;width:280px}.track{display:flex;align-items:center;justify-content:space-between;width:236px;height:28px;position:relative}
                .detent{display:inline-block;width:8px;height:8px}.knob{display:block;position:absolute;width:28px;height:28px;left:125px;top:0}
                </style></head><body>
                <form><textarea id="prompt-textarea"></textarea><button id="effort" type="button" aria-haspopup="menu" aria-expanded="false">5.6 Sol Extra High</button></form>
                <div id="menu" role="menu" hidden>
                  <div id="model-row" role="menuitem" aria-label="Select model">5.6 Sol Extra High</div>
                  <div id="performance" role="menuitem" aria-label="Performance"><span class="track"><span id="points">
                    <span class="detent" data-index="0"></span><span class="detent" data-index="1"></span><span class="detent" data-index="2"></span><span class="detent" data-index="3"></span><span class="detent" data-index="4"></span><span class="detent" data-index="5"></span>
                  </span><span><span id="slider" class="knob" role="slider"></span></span></span></div>
                </div>
                <script>
                window.detentClicks=0;const root=document.getElementById('effort'),menu=document.getElementById('menu'),row=document.getElementById('model-row'),perf=document.getElementById('performance'),slider=document.getElementById('slider');
                const labels=['Light','Medium','High','Extra High','Max','Ultra'];let current=3;
                const update=()=>{root.textContent='5.6 Sol '+labels[current];row.textContent='5.6 Sol '+labels[current];slider.style.left=(current*208/5)+'px';};
                root.onclick=()=>{const opening=root.getAttribute('aria-expanded')!=='true';root.setAttribute('aria-expanded',String(opening));menu.hidden=!opening;};
                perf.addEventListener('click',event=>{const point=event.target.closest?.('[data-index]');if(!point)return;window.detentClicks++;current=Number(point.dataset.index);update();});
                update();
                </script></body></html>
                """;
    }

    private static JSONObject runUntilReady(ActivityScenario<SelfRunNewActivity> scenario,
                                            AtomicReference<WebView> web, String script,
                                            int maxAttempts) throws Exception {
        JSONObject result = null;
        for (int i = 0; i < maxAttempts; i++) {
            result = evaluate(scenario, web, script);
            if ("READY".equals(result.getString("status"))) return result;
            assertEquals(result.toString(), "UI_WAIT", result.getString("status"));
        }
        assertNotNull(result);
        return result;
    }

    private static void load(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web,
                             String url, String html) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView ignored, String finishedUrl) {
                    if (url.equals(finishedUrl)) loaded.countDown();
                }
            });
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null);
        });
        assertTrue("detent fixture did not load", loaded.await(15, TimeUnit.SECONDS));
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
