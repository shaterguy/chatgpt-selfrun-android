package com.shaterguy.chatgptselfrun;

import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Runtime WebView regression for the no-STOP assistant completion fallback and bounded watchdog. */
@RunWith(AndroidJUnit4.class)
public final class TurnCompletionDomFallbackWebViewTest {
    private static final String ORIGIN = "https://chatgpt.com/g/g-p-selfrun-dom/c/domfallback123";
    private static final String RUN_ID = "SR-DOM-FALLBACK";
    private static final String TOKEN = "dom-fallback-token";

    @Test public void staleThinkingAndNoStopCompleteOnlyAfterNewAssistantFinalUi() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            AtomicReference<String> callback = new AtomicReference<>("");
            load(scenario, web, callback);

            evaluateRaw(scenario, web, TurnCompletionDomFallbackScript.documentStartScript(200L));
            installProtocolFixture(scenario, web);

            assertEquals("old-appended", evaluate(scenario, web, appendTurn("old user", "old assistant", true)));
            assertEquals("armed", evaluate(scenario, web, arm(TOKEN)));

            Thread.sleep(350L);
            assertEquals("", callback.get());
            assertFalse(Boolean.parseBoolean(evaluate(scenario, web,
                    "String(window.__selfRunDomAssistantFallback.diagnostics().assistantBound)")));

            assertEquals("new-appended", evaluate(scenario, web, appendTurn("new user", "new assistant", true)));
            waitForCallback(callback, "selfrun-drive://turn-completed?", 2_000L);

            String completion = callback.get();
            assertTrue(completion.contains("run=" + RUN_ID));
            assertTrue(completion.contains("token=" + TOKEN));
            assertTrue(completion.contains("source=dom_assistant_final_ui"));
        }
    }

    @Test public void ambiguousIdleEmitsRebindProbeAndRecoverInOrder() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            AtomicReference<String> callback = new AtomicReference<>("");
            load(scenario, web, callback);

            evaluateRaw(scenario, web, TurnCompletionDomFallbackScript.documentStartScript(50L, 100L, 250L, 500L));
            installProtocolFixture(scenario, web);
            assertEquals("old-appended", evaluate(scenario, web, appendTurn("old user", "old assistant", true)));
            String token = TOKEN + "-watch";
            assertEquals("armed", evaluate(scenario, web, arm(token)));
            assertEquals("new-appended", evaluate(scenario, web, appendTurn("new user", "new assistant", false)));

            waitForCallback(callback, "selfrun-drive://turn-watchdog-rebind?", 1_500L);
            assertTrue(callback.get().contains("token=" + token));
            callback.set("");

            waitForCallback(callback, "selfrun-drive://turn-watchdog-probe?", 1_500L);
            assertTrue(callback.get().contains("token=" + token));
            callback.set("");

            waitForCallback(callback, "selfrun-drive://turn-watchdog-recover?", 2_000L);
            assertTrue(callback.get().contains("token=" + token));
            assertTrue(Boolean.parseBoolean(evaluate(scenario, web,
                    "String(window.__selfRunDomAssistantFallback.diagnostics().ambiguous)")));
        }
    }

    private static void installProtocolFixture(ActivityScenario<SelfRunNewActivity> scenario,
                                               AtomicReference<WebView> web) throws Exception {
        evaluateRaw(scenario, web,
                "window.__selfRunRequestProfileEngine={target:()=>({runId:'" + RUN_ID + "'})};"
                        + "window.__selfRunTurnProtocol={diagnostics:()=>({phase:'THINKING',completionDispatched:false})};'ready';");
    }

    private static String arm(String token) {
        return "(()=>{window.__selfRunDriveTurnObserver={token:'" + token
                + "',fired:false,timer:0,observer:{disconnect(){}}};return 'armed';})()";
    }

    private static String appendTurn(String userText, String assistantText, boolean finalAction) {
        String result = userText.startsWith("old") ? "old-appended" : "new-appended";
        return "(()=>{const main=document.querySelector('main'),form=main.querySelector('form');"
                + "const user=document.createElement('div');user.setAttribute('data-message-author-role','user');user.textContent="
                + SelfRunScript.quote(userText) + ";"
                + "const article=document.createElement('article');article.setAttribute('data-testid','conversation-turn-fixture');"
                + "const assistant=document.createElement('div');assistant.setAttribute('data-message-author-role','assistant');assistant.textContent="
                + SelfRunScript.quote(assistantText) + ";article.appendChild(assistant);"
                + (finalAction
                ? "const action=document.createElement('button');action.setAttribute('data-testid','copy-turn');action.setAttribute('aria-label','Copy');action.textContent='Copy';article.appendChild(action);"
                : "")
                + "main.insertBefore(user,form);main.insertBefore(article,form);return '" + result + "';})()";
    }

    private static void waitForCallback(AtomicReference<String> callback, String prefix, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (callback.get().startsWith(prefix)) return;
            Thread.sleep(25L);
        }
        assertTrue("expected callback not observed: " + prefix + " actual=" + callback.get(),
                callback.get().startsWith(prefix));
    }

    private static void load(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web,
                             AtomicReference<String> callback) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView ignored, String url) {
                    if (url != null && url.startsWith("https://chatgpt.com/")) loaded.countDown();
                }

                @Override public boolean shouldOverrideUrlLoading(WebView ignored, WebResourceRequest request) {
                    String url = request == null || request.getUrl() == null ? "" : request.getUrl().toString();
                    if (url.startsWith("selfrun-drive://")) { callback.set(url); return true; }
                    return false;
                }

                @SuppressWarnings("deprecation")
                @Override public boolean shouldOverrideUrlLoading(WebView ignored, String url) {
                    if (url != null && url.startsWith("selfrun-drive://")) { callback.set(url); return true; }
                    return false;
                }
            });
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(ORIGIN, fixture(), "text/html", "UTF-8", null);
        });
        assertTrue("DOM fallback fixture did not load", loaded.await(15, TimeUnit.SECONDS));
    }

    private static String fixture() {
        return "<!doctype html><html><head><style>button{display:block}</style></head><body><main><form>"
                + "<div id='prompt-textarea' contenteditable='true' data-lexical-editor='true'><p><br></p></div>"
                + "<button type='submit' data-testid='send-button' aria-label='Send'>Send</button>"
                + "</form></main></body></html>";
    }

    private static String evaluate(ActivityScenario<SelfRunNewActivity> scenario,
                                   AtomicReference<WebView> web, String script) throws Exception {
        Object decoded = new JSONTokener(evaluateRaw(scenario, web, script)).nextValue();
        return String.valueOf(decoded);
    }

    private static String evaluateRaw(ActivityScenario<SelfRunNewActivity> scenario,
                                      AtomicReference<WebView> web, String script) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(script, value -> {
            result.set(value);
            complete.countDown();
        }));
        assertTrue("DOM fallback WebView script timed out", complete.await(15, TimeUnit.SECONDS));
        return result.get();
    }
}
