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

/**
 * V3 transport regression: one bootstrap plus two continuations must survive a brand-new
 * composer node after every turn, including a fixed-position editor whose offsetParent is null.
 */
@RunWith(AndroidJUnit4.class)
public final class SelfRun3ComposerTransportWebViewTest {
    private static final String PROJECT_ID = "g-p-6a582c824ba08191ac7e74e9bad721fc";
    private static final String SLUGGED_PROJECT_ID = PROJECT_ID + "-vibe-coding";
    private static final String PROJECT_URL = "https://chatgpt.com/g/" + SLUGGED_PROJECT_ID + "/project";
    private static final String CONVERSATION_URL =
            "https://chatgpt.com/g/" + SLUGGED_PROJECT_ID + "/c/conversation123";

    @Test public void bootstrapThenTwoContinuationsUseFreshCapabilityDiscoveredComposer() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario =
                     ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = loadFixture(scenario);

            assertEquals("true", read(scenario, web,
                    "String(window.currentEditor.offsetParent===null)"));

            prepare(scenario, web,
                    SelfRun3ComposerTransport.prepareInitial(PROJECT_URL, "turn-one"));
            assertPending(evaluate(scenario, web,
                    SelfRun3ComposerTransport.submitInitial(PROJECT_URL, "turn-one")));
            awaitCount(scenario, web, 1);
            assertEquals("/g/" + SLUGGED_PROJECT_ID + "/c/conversation123",
                    read(scenario, web, "location.pathname"));
            assertEquals("true", read(scenario, web,
                    "String(window.currentEditor.offsetParent===null)"));

            prepare(scenario, web,
                    SelfRun3ComposerTransport.prepareContinuation(CONVERSATION_URL, "turn-two"));
            assertPending(evaluate(scenario, web,
                    SelfRun3ComposerTransport.submitContinuation(CONVERSATION_URL, "turn-two")));
            awaitCount(scenario, web, 2);
            assertEquals("true", read(scenario, web,
                    "String(window.currentEditor.offsetParent===null)"));

            prepare(scenario, web,
                    SelfRun3ComposerTransport.prepareContinuation(CONVERSATION_URL, "turn-three"));
            assertPending(evaluate(scenario, web,
                    SelfRun3ComposerTransport.submitContinuation(CONVERSATION_URL, "turn-three")));
            awaitCount(scenario, web, 3);

            assertEquals("turn-one|turn-two|turn-three",
                    read(scenario, web, "window.sent.join('|')"));
            assertEquals("3", read(scenario, web, "String(window.canonicalPosts.length)"));
            assertEquals("POST|POST|POST", read(scenario, web,
                    "window.canonicalPosts.map(x=>x.method).join('|')"));
        }
    }

    private static void prepare(ActivityScenario<SelfRunNewActivity> scenario,
                                AtomicReference<WebView> web,
                                String script) throws Exception {
        JSONObject last = null;
        for (int i = 0; i < 8; i++) {
            last = evaluate(scenario, web, script);
            if (SelfRun3ComposerTransport.READY_TO_SUBMIT.equals(last.optString("status"))) return;
            Thread.sleep(50L);
        }
        assertEquals(SelfRun3ComposerTransport.READY_TO_SUBMIT,
                last == null ? "" : last.optString("status"));
    }

    private static void assertPending(JSONObject result) {
        assertEquals(SelfRun3ComposerTransport.SUBMISSION_PENDING, result.optString("status"));
    }

    private static void awaitCount(ActivityScenario<SelfRunNewActivity> scenario,
                                   AtomicReference<WebView> web,
                                   int count) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (String.valueOf(count).equals(
                    read(scenario, web, "String(window.submitCount)"))) return;
            Thread.sleep(50L);
        }
        assertEquals(String.valueOf(count),
                read(scenario, web, "String(window.submitCount)"));
    }

    private static AtomicReference<WebView> loadFixture(
            ActivityScenario<SelfRunNewActivity> scenario) throws Exception {
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
        assertTrue("V3 composer transport fixture did not load",
                loaded.await(15, TimeUnit.SECONDS));
        return web;
    }

    private static JSONObject evaluate(ActivityScenario<SelfRunNewActivity> scenario,
                                       AtomicReference<WebView> web,
                                       String script) throws Exception {
        return new JSONObject(read(scenario, web, script));
    }

    private static String read(ActivityScenario<SelfRunNewActivity> scenario,
                               AtomicReference<WebView> web,
                               String expression) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<String> raw = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(expression, value -> {
            raw.set(value);
            complete.countDown();
        }));
        assertTrue("WebView read timed out", complete.await(15, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(raw.get()).nextValue();
        return String.valueOf(decoded);
    }

    private static String fixture() {
        return """
                <!doctype html><html><body><main>
                <form id="composer-form"></form>
                </main><script>
                window.submitCount=0;window.sent=[];window.canonicalPosts=[];
                const form=document.getElementById('composer-form');
                window.fetch=async(input,init={})=>{
                  const url=typeof input==='string'?input:String(input?.url||'');
                  const method=String(init.method||(input&&input.method)||'GET').toUpperCase();
                  window.canonicalPosts.push({url,method,body:String(init.body||'')});
                  return new Response('{}',{status:200,headers:{'Content-Type':'application/json'}});
                };
                window.rebuildComposer=()=>{
                  form.replaceChildren();
                  const editor=document.createElement('div');
                  editor.setAttribute('contenteditable','true');
                  editor.setAttribute('role','textbox');
                  editor.setAttribute('aria-label','Message ChatGPT');
                  editor.style.position='fixed';
                  editor.style.left='12px';
                  editor.style.bottom='12px';
                  editor.style.minWidth='200px';
                  editor.appendChild(document.createElement('p')).appendChild(document.createElement('br'));
                  const send=document.createElement('button');
                  send.type='submit';send.setAttribute('aria-label','Send message');
                  send.textContent='Send';
                  form.append(editor,send);window.currentEditor=editor;
                };
                form.addEventListener('submit',event=>{
                  event.preventDefault();
                  const text=String(window.currentEditor.innerText||window.currentEditor.textContent||'').trim();
                  window.sent.push(text);window.submitCount++;
                  fetch('/backend-api/f/conversation',{
                    method:'POST',headers:{'Content-Type':'application/json'},
                    body:JSON.stringify({action:'next',messages:[{content:{parts:[text]}}]})
                  });
                  if(window.submitCount===1)history.replaceState({},'',
                    '/g/__SLUGGED_PROJECT_ID__/c/conversation123');
                  window.rebuildComposer();
                });
                window.rebuildComposer();
                </script></body></html>
                """.replace("__SLUGGED_PROJECT_ID__", SLUGGED_PROJECT_ID);
    }
}
