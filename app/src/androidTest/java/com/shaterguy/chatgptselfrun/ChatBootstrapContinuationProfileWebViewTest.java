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

/** Verifies first-PLAN and continuation Chat profiles against actual outgoing request bodies. */
@RunWith(AndroidJUnit4.class)
public final class ChatBootstrapContinuationProfileWebViewTest {
    private static final String RUN_ID = "SR-BOOTSTRAP-CONTINUATION";
    private static final String CHAT_URL = "https://chatgpt.com/";

    @Test public void bootstrapUsesPlannerProfileAndEveryLaterTurnUsesTaskProfile() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<Boolean> saved = new AtomicReference<>(false);
            scenario.onActivity(activity -> {
                saved.set(ChatReasoningPreferenceStore.save(activity, RUN_ID,
                        ChatReasoningPreferenceStore.MEDIUM,
                        ChatReasoningPreferenceStore.EXTRA_HIGH));
                assertEquals(ChatReasoningPreferenceStore.MEDIUM,
                        ChatReasoningPreferenceStore.selectionForRun(activity, RUN_ID));
                assertEquals(ChatReasoningPreferenceStore.EXTRA_HIGH,
                        ChatReasoningPreferenceStore.continuationSelectionForRun(activity, RUN_ID));
                assertTrue(ChatPickerStateStore.saveObserved(activity, RUN_ID,
                        ChatReasoningPreferenceStore.MEDIUM));
                assertEquals(ChatReasoningPreferenceStore.EXTRA_HIGH,
                        ChatPickerStateStore.effectiveForRun(activity, RUN_ID));
            });
            assertTrue(saved.get());

            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals(ChatReasoningPreferenceStore.MEDIUM,
                        ChatReasoningPreferenceStore.selectionForRun(activity, RUN_ID));
                assertEquals(ChatReasoningPreferenceStore.EXTRA_HIGH,
                        ChatReasoningPreferenceStore.continuationSelectionForRun(activity, RUN_ID));
                assertEquals(ChatReasoningPreferenceStore.EXTRA_HIGH,
                        ChatPickerStateStore.effectiveForRun(activity, RUN_ID));
            });

            AtomicReference<WebView> web = loadFixture(scenario);
            read(scenario, web, RequestProfileScript.documentStartScript());
            String staged = read(scenario, web,
                    "(()=>{" + RequestProfileScript.beginTarget("chat", RUN_ID)
                            + RequestProfileScript.setChatProfiles(
                                    ChatReasoningPreferenceStore.MEDIUM,
                                    ChatReasoningPreferenceStore.EXTRA_HIGH)
                            + "return JSON.stringify(window.__selfRunRequestProfileEngine.target());})()");
            assertTrue(staged.contains("\"bootstrapReasoning\":\"medium\""));
            assertTrue(staged.contains("\"continuationReasoning\":\"xhigh\""));

            assertEquals("started", read(scenario, web, requestSequence()));
            for (int attempt = 0; attempt < 80; attempt++) {
                if ("true".equals(read(scenario, web, "String(window.profileSequenceDone)"))) break;
                Thread.sleep(50L);
            }
            assertEquals("true", read(scenario, web, "String(window.profileSequenceDone)"));
            assertEquals("", read(scenario, web, "String(window.profileSequenceError||'')"));
            assertEquals("3", read(scenario, web, "String(window.records.length)"));

            assertEquals("gpt-5-6-thinking", bodyValue(scenario, web, 0, "model"));
            assertEquals("standard", bodyValue(scenario, web, 0, "thinking_effort"));
            assertEquals("bootstrap", bodyValue(scenario, web, 0, "opaque"));

            assertEquals("gpt-5-6-thinking", bodyValue(scenario, web, 1, "model"));
            assertEquals("max", bodyValue(scenario, web, 1, "thinking_effort"));
            assertEquals("continuation", bodyValue(scenario, web, 1, "opaque"));

            assertEquals("gpt-5-6-thinking", bodyValue(scenario, web, 2, "model"));
            assertEquals("standard", bodyValue(scenario, web, 2, "thinking_effort"));
            assertEquals("bootstrap-retry", bodyValue(scenario, web, 2, "opaque"));
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

    private static String requestSequence() {
        return """
                (()=>{window.profileSequenceDone=false;window.profileSequenceError='';
                const send=async(message,opaque)=>{
                  const response=await fetch('/backend-api/conversation',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:'next',messages:[{author:{role:'user'},content:{content_type:'text',parts:[message]}}],opaque})});
                  await response.text();
                };
                (async()=>{
                  await send('[SELF_RUN_BOOTSTRAP 0.2.0 SR-BOOTSTRAP-CONTINUATION MODE=CHAT]','bootstrap');
                  await send('[SELF_RUN_CONTINUE SR-BOOTSTRAP-CONTINUATION]','continuation');
                  await send('[SELF_RUN_BOOTSTRAP 0.2.0 SR-BOOTSTRAP-CONTINUATION MODE=CHAT]','bootstrap-retry');
                  window.profileSequenceDone=true;
                })().catch(error=>{window.profileSequenceError=String(error?.message||error);window.profileSequenceDone=true;});
                return 'started';})()
                """;
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
