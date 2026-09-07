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
 * V3 transport regression: bootstrap plus two continuations must use a freshly rebuilt composer
 * inside an open shadow root, without calibrated IDs, test IDs, send buttons, or layout visibility.
 * Each physical submit is observed by the V3 canonical POST/response protocol before the next turn.
 */
@RunWith(AndroidJUnit4.class)
public final class SelfRun3ComposerTransportWebViewTest {
    private static final String RUN_ID = "SR-V3-COMPOSER-3TURN";
    private static final String PROJECT_ID = "g-p-6a582c824ba08191ac7e74e9bad721fc";
    private static final String SLUGGED_PROJECT_ID = PROJECT_ID + "-vibe-coding";
    private static final String PROJECT_URL = "https://chatgpt.com/g/" + SLUGGED_PROJECT_ID + "/project";
    private static final String CONVERSATION_URL =
            "https://chatgpt.com/g/" + SLUGGED_PROJECT_ID + "/c/conversation123";

    @Test public void bootstrapThenTwoContinuationsUseFreshShadowComposerAndCanonicalProtocol()
            throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario =
                     ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = loadFixture(scenario);
            read(scenario, web, ChatGptTurnProtocolScript.documentStartScript());

            assertShadowComposer(scenario, web);
            runTurn(scenario, web, "turn-1", "turn-one", true, 1);
            assertEquals("/g/" + SLUGGED_PROJECT_ID + "/c/conversation123",
                    read(scenario, web, "location.pathname"));
            assertShadowComposer(scenario, web);

            runTurn(scenario, web, "turn-2", "turn-two", false, 2);
            assertShadowComposer(scenario, web);

            runTurn(scenario, web, "turn-3", "turn-three", false, 3);
            assertShadowComposer(scenario, web);

            assertEquals("turn-one|turn-two|turn-three",
                    read(scenario, web, "window.sent.join('|')"));
            assertEquals("3", read(scenario, web, "String(window.canonicalPosts.length)"));
            assertEquals("POST|POST|POST", read(scenario, web,
                    "window.canonicalPosts.map(x=>x.method).join('|')"));
            assertEquals("COMPLETE", read(scenario, web,
                    "window.__selfRunTurnProtocol.snapshot().phase"));
        }
    }

    private static void runTurn(ActivityScenario<SelfRunNewActivity> scenario,
                                AtomicReference<WebView> web,
                                String token,
                                String prompt,
                                boolean initial,
                                int expectedCount) throws Exception {
        String prepare = initial
                ? SelfRun3ComposerTransport.prepareInitial(PROJECT_URL, prompt)
                : SelfRun3ComposerTransport.prepareContinuation(CONVERSATION_URL, prompt);
        JSONObject prepared = prepare(scenario, web, prepare);
        assertEquals(SelfRun3ComposerTransport.READY_TO_SUBMIT, prepared.optString("status"));

        String action = initial
                ? SelfRun3ComposerTransport.submitInitial(PROJECT_URL, prompt)
                : SelfRun3ComposerTransport.submitContinuation(CONVERSATION_URL, prompt);
        JSONObject dispatched = evaluate(scenario, web,
                ChatGptTurnProtocolScript.bindTurnAndThen(RUN_ID, token, action));
        assertEquals(SelfRun3ComposerTransport.SUBMISSION_PENDING,
                dispatched.optString("status"));

        await(scenario, web, "String(window.submitCount)", String.valueOf(expectedCount));
        await(scenario, web, "window.__selfRunTurnProtocol.snapshot().phase", "COMPLETE");
        assertEquals(token, read(scenario, web,
                "window.__selfRunTurnProtocol.snapshot().turnToken"));
    }

    private static JSONObject prepare(ActivityScenario<SelfRunNewActivity> scenario,
                                      AtomicReference<WebView> web,
                                      String script) throws Exception {
        JSONObject last = null;
        for (int i = 0; i < 8; i++) {
            last = evaluate(scenario, web, script);
            if (SelfRun3ComposerTransport.READY_TO_SUBMIT.equals(last.optString("status"))) {
                return last;
            }
            Thread.sleep(50L);
        }
        assertEquals(SelfRun3ComposerTransport.READY_TO_SUBMIT,
                last == null ? "" : last.optString("status"));
        return last;
    }

    private static void assertShadowComposer(ActivityScenario<SelfRunNewActivity> scenario,
                                             AtomicReference<WebView> web) throws Exception {
        assertEquals("true", read(scenario, web,
                "String(window.currentEditor.getRootNode() instanceof ShadowRoot)"));
        assertEquals("", read(scenario, web,
                "String(window.currentEditor.id||'')"));
        assertEquals("", read(scenario, web,
                "String(window.currentEditor.getAttribute('data-testid')||'')"));
        assertEquals("0", read(scenario, web,
                "String(window.currentForm.querySelectorAll('button,[role=button]').length)"));
    }

    private static void await(ActivityScenario<SelfRunNewActivity> scenario,
                              AtomicReference<WebView> web,
                              String expression,
                              String expected) throws Exception {
        for (int i = 0; i < 60; i++) {
            if (expected.equals(read(scenario, web, expression))) return;
            Thread.sleep(50L);
        }
        assertEquals(expected, read(scenario, web, expression));
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
        assertTrue("V3 shadow composer fixture did not load",
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
                <!doctype html><html><body><main><div id="composer-host"></div></main><script>
                window.submitCount=0;window.sent=[];window.canonicalPosts=[];
                window.fetch=async(input,init={})=>{
                  const url=typeof input==='string'?input:String(input?.url||'');
                  const method=String(init.method||(input&&input.method)||'GET').toUpperCase();
                  window.canonicalPosts.push({url,method,body:String(init.body||'')});
                  const sse='data: '+JSON.stringify({type:'message_stream_complete'})+'\\n\\n';
                  return new Response(sse,{status:200,headers:{'Content-Type':'text/event-stream'}});
                };
                const host=document.getElementById('composer-host');
                const shadow=host.attachShadow({mode:'open'});
                window.rebuildComposer=()=>{
                  shadow.replaceChildren();
                  const form=document.createElement('form');
                  const editor=document.createElement('div');
                  editor.setAttribute('contenteditable','true');
                  editor.setAttribute('role','textbox');
                  editor.setAttribute('aria-multiline','true');
                  editor.setAttribute('aria-label','Message');
                  editor.appendChild(document.createElement('p')).appendChild(document.createElement('br'));
                  form.appendChild(editor);shadow.appendChild(form);
                  window.currentForm=form;window.currentEditor=editor;
                  form.addEventListener('submit',event=>{
                    event.preventDefault();
                    const text=String(editor.innerText||editor.textContent||'').trim();
                    window.sent.push(text);window.submitCount++;
                    if(window.submitCount===1)history.replaceState({},'',
                      '/g/__SLUGGED_PROJECT_ID__/c/conversation123');
                    fetch('/backend-api/f/conversation',{
                      method:'POST',headers:{'Content-Type':'application/json'},
                      body:JSON.stringify({action:'next',messages:[{content:{parts:[text]}}]})
                    });
                    window.rebuildComposer();
                  });
                };
                window.rebuildComposer();
                </script></body></html>
                """.replace("__SLUGGED_PROJECT_ID__", SLUGGED_PROJECT_ID);
    }
}
