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

/** Exercises the generated selector on a real Android WebView fixture for an existing general chat. */
@RunWith(AndroidJUnit4.class)
public final class WorkPreferenceDomWebViewTest {
    private static final String CONVERSATION_URL = "https://chatgpt.com/c/conversation123";

    @Test public void modelSelectorCompletesForDialogAndNoPopupComposerSiblingTriggers() throws Exception {
        assertSelection("aria-haspopup=\"dialog\"", false);
        assertSelection("", false);
    }

    @Test public void reasoningSelectorCompletesForDialogAndNoPopupComposerSiblingTriggers() throws Exception {
        assertSelection("aria-haspopup=\"dialog\"", true);
        assertSelection("", true);
    }

    @Test public void v1WorkTargetsPopulateAllScopedV2TargetsWithoutReplacingRecaptures() throws Exception {
        JSONObject profile = new JSONObject();
        JSONObject targets = new JSONObject();
        JSONObject model = new JSONObject().put("testid", "legacy-model");
        JSONObject reasoning = new JSONObject().put("aria", "legacy-reasoning");
        JSONObject scoped = new JSONObject().put("id", "fresh-continuation-model");
        targets.put(WebUiCalibrationStore.PURPOSE_LEGACY_WORK_MODEL, model);
        targets.put(WebUiCalibrationStore.PURPOSE_LEGACY_WORK_REASONING, reasoning);
        targets.put(WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_MODEL, scoped);
        profile.put("version", 1).put("targets", targets);

        assertTrue(WebUiCalibrationStore.migrateLegacyWorkTargets(profile));
        assertEquals(2, profile.getInt("version"));
        assertEquals("v1-work-targets", profile.getString("migratedFrom"));
        assertEquals("legacy-model", targets.getJSONObject(WebUiCalibrationStore.PURPOSE_GENERAL_BOOTSTRAP_WORK_MODEL).getString("testid"));
        assertEquals("fresh-continuation-model", targets.getJSONObject(WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_MODEL).getString("id"));
        assertEquals("legacy-model", targets.getJSONObject(WebUiCalibrationStore.PURPOSE_PROJECT_CONTINUATION_WORK_MODEL).getString("testid"));
        assertEquals("legacy-reasoning", targets.getJSONObject(WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_REASONING).getString("aria"));
        assertEquals("legacy-reasoning", targets.getJSONObject(WebUiCalibrationStore.PURPOSE_PROJECT_BOOTSTRAP_WORK_REASONING).getString("aria"));
    }

    private static void assertSelection(String popupAttribute, boolean reasoning) throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            loadFixture(scenario, web, popupAttribute, reasoning);
            assertNotNull("Android System WebView must be available", WebView.getCurrentWebViewPackage());
            String script = reasoning
                    ? WorkPreferenceDom.reasoningForConversation(CONVERSATION_URL, "xhigh")
                    : WorkPreferenceDom.modelForConversation(CONVERSATION_URL, "terra");

            assertEquals("UI_WAIT", evaluate(scenario, web, script).getString("status"));
            assertEquals("UI_WAIT", evaluate(scenario, web, script).getString("status"));
            assertEquals("UI_WAIT", evaluate(scenario, web, script).getString("status"));
            JSONObject ready = evaluate(scenario, web, script);
            assertEquals("READY", ready.getString("status"));
            assertEquals(reasoning ? "xhigh" : "terra", ready.getJSONObject("diagnostics").getString("current"));
            assertTrue("fixture must update the combined composer control", read(scenario, web,
                    "document.getElementById('trigger').textContent").contains(reasoning ? "xhigh" : "terra"));
        }
    }

    private static void loadFixture(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web,
                                    String popupAttribute, boolean reasoning) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            view.getSettings().setJavaScriptEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView ignored, String url) {
                    if (CONVERSATION_URL.equals(url)) loaded.countDown();
                }
            });
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(CONVERSATION_URL, fixture(popupAttribute, reasoning), "text/html", "UTF-8", null);
        });
        assertTrue("WebView fixture did not load", loaded.await(15, TimeUnit.SECONDS));
    }

    private static JSONObject evaluate(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web,
                                       String script) throws Exception {
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

    private static String read(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web,
                               String expression) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<String> raw = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(expression, value -> {
            raw.set(value);
            complete.countDown();
        }));
        assertTrue("WebView read timed out", complete.await(15, TimeUnit.SECONDS));
        return String.valueOf(new JSONTokener(raw.get()).nextValue());
    }

    private static String fixture(String popupAttribute, boolean reasoning) {
        String current = reasoning ? "medium" : "sol";
        String wanted = reasoning ? "xhigh" : "terra";
        return "<!doctype html><html><head><style>body{margin:20px}form{height:54px}button{display:block;margin:8px}#popup[hidden]{display:none}</style></head>"
                + "<body><form><textarea id='prompt-textarea'></textarea></form>"
                + "<button id='trigger' aria-expanded='false' " + popupAttribute + ">" + current + "</button>"
                + "<div id='popup' role='dialog' hidden><button id='wanted' role='option' aria-selected='false'>" + wanted + "</button></div>"
                + "<script>const trigger=document.getElementById('trigger'),popup=document.getElementById('popup'),wanted=document.getElementById('wanted');"
                + "function toggle(){popup.hidden=!popup.hidden;trigger.setAttribute('aria-expanded',String(!popup.hidden));}"
                + "trigger.addEventListener('pointerdown',toggle);trigger.addEventListener('mousedown',toggle);"
                + "wanted.addEventListener('click',()=>{trigger.textContent=wanted.textContent;wanted.setAttribute('aria-selected','true');});</script>"
                + "</body></html>";
    }
}
