package com.shaterguy.chatgptselfrun;

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
import static org.junit.Assert.assertTrue;

/** Diagnostic-only coverage for BOOTSTRAP -> CONTINUE 1 -> CONTINUE 2 Chat profile routing. */
@RunWith(AndroidJUnit4.class)
public final class ChatSplitReasoningMultiContinuationWebViewTest {
    private static final String CHAT_URL = "https://chatgpt.com/";

    @Test public void splitBootstrapAndContinuationReasoningSurvivesSecondContinuation() throws Exception {
        assertSequence("SR-DIAG-SPLIT",
                ChatReasoningPreferenceStore.MEDIUM,
                ChatReasoningPreferenceStore.EXTRA_HIGH,
                new String[]{"standard", "max", "max"});
    }

    @Test public void sameBootstrapAndContinuationReasoningSurvivesSecondContinuation() throws Exception {
        assertSequence("SR-DIAG-SAME",
                ChatReasoningPreferenceStore.EXTRA_HIGH,
                ChatReasoningPreferenceStore.EXTRA_HIGH,
                new String[]{"max", "max", "max"});
    }

    private static void assertSequence(String runId, String bootstrapReasoning,
                                       String continuationReasoning, String[] expectedEfforts) throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<Boolean> saved = new AtomicReference<>(false);
            scenario.onActivity(activity -> saved.set(ChatReasoningPreferenceStore.save(
                    activity, runId, bootstrapReasoning, continuationReasoning)));
            assertTrue(saved.get());

            AtomicReference<WebView> web = loadFixture(scenario);
            read(scenario, web, RequestProfileScript.documentStartScript());
            String target = read(scenario, web,
                    "(()=>{" + RequestProfileScript.beginTarget("chat", runId)
                            + RequestProfileScript.setChatProfiles(bootstrapReasoning, continuationReasoning)
                            + "return JSON.stringify(window.__selfRunRequestProfileEngine.target());})()");
            assertTrue(target.contains("\"bootstrapReasoning\":\"" + bootstrapReasoning + "\""));
            assertTrue(target.contains("\"continuationReasoning\":\"" + continuationReasoning + "\""));

            assertEquals("started", read(scenario, web, requestSequence(runId)));
            for (int attempt = 0; attempt < 100; attempt++) {
                if ("true".equals(read(scenario, web, "String(window.profileSequenceDone)"))) break;
                Thread.sleep(50L);
            }

            assertEquals("true", read(scenario, web, "String(window.profileSequenceDone)"));
            assertEquals("", read(scenario, web, "String(window.profileSequenceError||'')"));
            assertEquals("3", read(scenario, web, "String(window.records.length)"));

            for (int i = 0; i < expectedEfforts.length; i++) {
                assertEquals("gpt-5-6-thinking", bodyValue(scenario, web, i, "model"));
                assertEquals(expectedEfforts[i], bodyValue(scenario, web, i, "thinking_effort"));
            }
            assertEquals("bootstrap", bodyValue(scenario, web, 0, "opaque"));
            assertEquals("continue-1", bodyValue(scenario, web, 1, "opaque"));
            assertEquals("continue-2", bodyValue(scenario, web, 2, "opaque"));

            String diagnostics = read(scenario, web,
                    "JSON.stringify(window.__selfRunRequestProfileEngine.diagnostics())");
            assertTrue(diagnostics.contains("\"reasoning\":\"" + continuationReasoning + "\""));
            assertEquals("alive", read(scenario, web, "(()=>{const x=1+1;return x===2?'alive':'dead';})()"));
        }
    }

    private static String bodyValue(ActivityScenario<SelfRunNewActivity> scenario,
                                    AtomicReference<WebView> web, int index, String field) throws Exception {
        return read(scenario, web, "String(JSON.parse(window.records[" + index + "].body)["
                + SelfRunScript.quote(field) + "]||'')");
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
            view.loadDataWithBaseURL(CHAT_URL, fixture(), "text/html", "UTF-8", null);
        });
        assertTrue("Profile fixture did not load", loaded.await(15, TimeUnit.SECONDS));
        return web;
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
        Object decoded = new JSONTokener(raw.get()).nextValue();
        return String.valueOf(decoded);
    }

    private static String requestSequence(String runId) {
        String bootstrap = "[SELF_RUN_BOOTSTRAP 0.2.0 " + runId + " MODE=CHAT]";
        String continuation = "[SELF_RUN_CONTINUE " + runId + "]";
        return """
                (()=>{window.profileSequenceDone=false;window.profileSequenceError='';
                const send=async(message,opaque)=>{
                  const response=await fetch('/backend-api/conversation',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:'next',messages:[{author:{role:'user'},content:{content_type:'text',parts:[message]}}],opaque})});
                  await response.text();
                };
                (async()=>{
                  await send('__BOOTSTRAP__','bootstrap');
                  await send('__CONTINUE__','continue-1');
                  await send('__CONTINUE__','continue-2');
                  window.profileSequenceDone=true;
                })().catch(error=>{window.profileSequenceError=String(error?.message||error);window.profileSequenceDone=true;});
                return 'started';})()
                """.replace("__BOOTSTRAP__", bootstrap).replace("__CONTINUE__", continuation);
    }

    private static String fixture() {
        return """
                <!doctype html><html><body><script>
                window.records=[];
                window.fetch=async function(input,init){
                  const request=input instanceof Request?new Request(input,init):new Request(input,init);
                  const body=await request.clone().text();
                  window.records.push({url:request.url,body});
                  return new Response('{}',{status:200,headers:{'Content-Type':'application/json'}});
                };
                </script></body></html>
                """;
    }
}
