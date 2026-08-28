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
import static org.junit.Assert.assertTrue;

/** Regression for fresh rich-text ChatGPT composer scaffolds used by SelfRun 2.0. */
@RunWith(AndroidJUnit4.class)
public final class RichComposerBootstrapWebViewTest {
    private static final String PROJECT_URL = "https://chatgpt.com/g/g-p-test";
    private static final String PROMPT = "SELF_RUN_RICH_COMPOSER_BOOTSTRAP";

    @Test public void emptyRichComposerScaffoldSurvivesPreparationAndSubmits() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            CountDownLatch loaded = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                WebView view = new WebView(activity);
                view.getSettings().setJavaScriptEnabled(true);
                view.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView ignored, String url) {
                        loaded.countDown();
                    }
                });
                activity.setContentView(view);
                web.set(view);
                view.loadDataWithBaseURL(PROJECT_URL, fixture(), "text/html", "UTF-8", null);
            });
            assertTrue("Rich composer fixture did not load", loaded.await(15, TimeUnit.SECONDS));

            JSONObject first = evaluate(scenario, web,
                    SelfRunContinuationDom.prepareBootstrap(PROJECT_URL, PROMPT, "rich-bootstrap"));
            assertEquals("COMPOSER_CLEARING", first.getString("status"));
            assertEquals("false", read(scenario, web, "String(window.editorBroken)"));
            assertEquals("1", read(scenario, web, "String(document.querySelectorAll('#prompt-textarea > p').length)"));

            JSONObject prepared = first;
            for (int attempt = 0; attempt < 5; attempt++) {
                prepared = evaluate(scenario, web,
                        SelfRunContinuationDom.prepareBootstrap(PROJECT_URL, PROMPT, "rich-bootstrap"));
                if ("READY_TO_SUBMIT".equals(prepared.getString("status"))) break;
            }
            assertEquals("READY_TO_SUBMIT", prepared.getString("status"));
            assertEquals("false", read(scenario, web, "String(window.editorBroken)"));
            assertEquals(PROMPT, read(scenario, web,
                    "document.getElementById('prompt-textarea').innerText"));

            JSONObject dispatched = evaluate(scenario, web,
                    SelfRunContinuationDom.clickPreparedBootstrap(
                            PROJECT_URL, PROMPT, "rich-bootstrap", "SR-RICH", "rich-token", 5000L));
            assertEquals("COMPOSER_INPUTTING", dispatched.getString("status"));
            assertTrue(dispatched.getString("detail").contains("dispatch=BOOTSTRAP_CLICKED"));
            assertEquals("1", read(scenario, web, "String(window.submitCount)"));

            evaluate(scenario, web,
                    "(()=>{history.replaceState({},'', '/g/g-p-test/c/conversation123');return JSON.stringify({status:'ROUTE_CREATED'});})()");
            JSONObject confirmed = evaluate(scenario, web,
                    SelfRunContinuationDom.prepareBootstrap(PROJECT_URL, PROMPT, "rich-bootstrap"));
            assertEquals("SUBMISSION_CONFIRMED", confirmed.getString("status"));
        }
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

    private static String fixture() {
        return "<!doctype html><html><head><style>body{margin:20px}#prompt-textarea{min-height:48px;border:1px solid #999}button{display:block;margin:8px}</style></head>"
                + "<body><main><form><div id='prompt-textarea' contenteditable='true' data-lexical-editor='true'><p><br></p></div>"
                + "<button type='submit' data-testid='send-button' aria-label='Send'>Send</button></form></main>"
                + "<script>window.editorBroken=false;window.submitCount=0;const editor=document.getElementById('prompt-textarea');const form=document.querySelector('form');"
                + "const nativeExec=document.execCommand.bind(document);document.execCommand=function(command,show,value){"
                + "if(command==='delete'&&!editor.innerText.trim())return false;"
                + "if(command==='insertText'){if(window.editorBroken)return false;const p=editor.querySelector('p');if(!p)return false;p.textContent=String(value||'');return true;}"
                + "return nativeExec(command,show,value);};"
                + "editor.addEventListener('input',()=>{if(!editor.querySelector('p'))window.editorBroken=true;if(window.editorBroken)editor.innerHTML='<p><br></p>';});"
                + "form.addEventListener('submit',event=>{event.preventDefault();window.submitCount++;});</script></body></html>";
    }
}
