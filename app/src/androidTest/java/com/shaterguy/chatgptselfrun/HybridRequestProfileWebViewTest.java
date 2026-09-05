package com.shaterguy.chatgptselfrun;

import android.content.SharedPreferences;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Executes both HYBRID directions against real WebView fetch request bodies. */
@RunWith(AndroidJUnit4.class)
public final class HybridRequestProfileWebViewTest {
    private static final String CHAT_URL = "https://chatgpt.com/";
    private static final String WORK_TO_CHAT_RUN = "SR-20260905-103318-W2CHAT";
    private static final String CHAT_TO_WORK_RUN = "SR-20260905-103318-C2WORK";
    private static final String RECOVERY_RUN = "SR-20260905-103318-RECOVR";
    private static final String MESSAGES = """
            [{"id":"message-bootstrap","author":{"role":"user"},"content":{"content_type":"text","parts":["[SELF_RUN_BOOTSTRAP 0.2.0 SR-20260905-103318-W2CHAT MODE=HYBRID]"]}},{"id":"message-continue","author":{"role":"user"},"content":{"content_type":"text","parts":["[SELF_RUN_CONTINUE SR-20260905-103318-W2CHAT]"]}}]
            """.trim();

    @Test public void bothDirectionsBindEachFetchToNativeStageAndPreserveDataPlane() throws Exception {
        AtomicReference<ProfileRegistry.Profile> work = new AtomicReference<>();
        AtomicReference<ProfileRegistry.Profile> chat = new AtomicReference<>();
        AtomicBoolean removeCaptured = new AtomicBoolean(false);
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            scenario.onActivity(activity -> {
                ProfileRegistry.initialize(activity);
                ProfileRegistry.CapturedProfile captured = ProfileRegistry.parseCaptured("""
                        {"mode":"work","operations":[
                          {"op":"SET","path":"model","value":"gpt-5.6-luna-wm"},
                          {"op":"SET","path":"thinking_effort","value":"standard"},
                          {"op":"SET","path":"conversation_origin","value":"tpp"},
                          {"op":"SET","path":"service_tier","value":"standard"}
                        ]}
                        """);
                ProfileRegistry.RegisterResult result =
                        ProfileRegistry.registerCaptured(captured, "luna", "standard");
                removeCaptured.set(ProfileRegistry.RegisterResult.ADDED.equals(result.status));
                work.set(result.profile);
                chat.set(ProfileRegistry.resolveChat("xhigh"));
                assertTrue(work.get() != null);
                assertTrue(chat.get() != null);
            });

            HybridRunProfileStore.Endpoint workEndpoint =
                    HybridRunProfileStore.Endpoint.fromProfile(work.get());
            HybridRunProfileStore.Endpoint chatEndpoint =
                    HybridRunProfileStore.Endpoint.fromProfile(chat.get());

            JSONArray workToChat = runDirection(scenario,
                    new HybridRunProfileStore.Selection(WORK_TO_CHAT_RUN,
                            HybridRunProfileStore.STAGE_BOOTSTRAP, workEndpoint, chatEndpoint));
            assertWork(body(workToChat, "bootstrap"));
            assertChat(body(workToChat, "continuation"));
            assertPreserved(workToChat);

            JSONArray chatToWork = runDirection(scenario,
                    new HybridRunProfileStore.Selection(CHAT_TO_WORK_RUN,
                            HybridRunProfileStore.STAGE_BOOTSTRAP, chatEndpoint, workEndpoint));
            assertChat(body(chatToWork, "bootstrap"));
            assertWork(body(chatToWork, "continuation"));
            assertPreserved(chatToWork);
        } finally {
            if (removeCaptured.get() && work.get() != null) {
                assertTrue(ProfileRegistry.delete(work.get().fingerprint));
            }
        }
    }

    @Test public void sendContinueWithoutPendingDriveSignalRestoresContinuationStage() {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            scenario.onActivity(activity -> {
                ProfileRegistry.initialize(activity);
                ProfileRegistry.Profile workProfile = ProfileRegistry.resolveWork("sol", "xhigh");
                ProfileRegistry.Profile chatProfile = ProfileRegistry.resolveChat("xhigh");
                assertTrue(workProfile != null);
                assertTrue(chatProfile != null);
                HybridRunProfileStore.Endpoint work =
                        HybridRunProfileStore.Endpoint.fromProfile(workProfile);
                HybridRunProfileStore.Endpoint chat =
                        HybridRunProfileStore.Endpoint.fromProfile(chatProfile);
                assertTrue(HybridRunProfileStore.startRun(activity, RECOVERY_RUN, work, chat));

                SharedPreferences state =
                        activity.getSharedPreferences("selfrun_drive", android.content.Context.MODE_PRIVATE);
                String oldRun = state.getString("runId", "");
                String oldMode = state.getString("mode", "");
                String oldPhase = state.getString("phase", "");
                String oldSignal = state.getString("pendingDriveSignalType", "");
                boolean hadRun = state.contains("runId"), hadMode = state.contains("mode");
                boolean hadPhase = state.contains("phase"), hadSignal = state.contains("pendingDriveSignalType");
                try {
                    assertTrue(state.edit()
                            .putString("runId", RECOVERY_RUN)
                            .putString("mode", HybridRunProfileStore.MODE_HYBRID)
                            .putString("phase", SelfRunStore.PHASE_SEND_CONTINUE)
                            .putString("pendingDriveSignalType", "")
                            .commit());
                    HybridRunProfileStore.Selection restored =
                            HybridRunProfileStore.currentSelection();
                    assertTrue(restored.valid());
                    assertTrue(restored.continuationStage());
                } finally {
                    SharedPreferences.Editor restore = state.edit();
                    restoreString(restore, "runId", hadRun, oldRun);
                    restoreString(restore, "mode", hadMode, oldMode);
                    restoreString(restore, "phase", hadPhase, oldPhase);
                    restoreString(restore, "pendingDriveSignalType", hadSignal, oldSignal);
                    assertTrue(restore.commit());
                }
            });
        }
    }

    private static JSONArray runDirection(ActivityScenario<SelfRunNewActivity> scenario,
                                          HybridRunProfileStore.Selection selection) throws Exception {
        AtomicReference<WebView> web = loadFixture(scenario);
        read(scenario, web, RequestProfileScript.documentStartScript());
        read(scenario, web, HybridRequestProfileScript.documentStartScript(selection));
        assertEquals("started", read(scenario, web, requestPair()));
        for (int attempt = 0; attempt < 100; attempt++) {
            if ("true".equals(read(scenario, web, "String(window.hybridPairDone)"))) break;
            Thread.sleep(50L);
        }
        assertEquals("true", read(scenario, web, "String(window.hybridPairDone)"));
        assertEquals("", read(scenario, web, "String(window.hybridPairError||'')"));
        String records = read(scenario, web,
                "JSON.stringify(window.records.map(record=>({url:new URL(record.url).pathname,body:JSON.parse(record.body)})))");
        JSONArray result = new JSONArray(records);
        assertEquals(2, result.length());
        return result;
    }

    private static JSONObject body(JSONArray records, String opaque) throws Exception {
        for (int i = 0; i < records.length(); i++) {
            JSONObject body = records.getJSONObject(i).getJSONObject("body");
            if (opaque.equals(body.getString("opaque"))) return body;
        }
        throw new AssertionError("missing record " + opaque);
    }

    private static void assertWork(JSONObject body) throws Exception {
        assertEquals("gpt-5.6-luna-wm", body.getString("model"));
        assertEquals("standard", body.getString("thinking_effort"));
        assertEquals("tpp", body.getString("conversation_origin"));
        assertEquals("standard", body.getString("service_tier"));
    }

    private static void assertChat(JSONObject body) throws Exception {
        assertEquals("gpt-5-6-thinking", body.getString("model"));
        assertEquals("max", body.getString("thinking_effort"));
        assertFalse(body.has("conversation_origin"));
        assertFalse(body.has("service_tier"));
    }

    private static void assertPreserved(JSONArray records) throws Exception {
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            JSONObject body = record.getJSONObject("body");
            assertEquals("/backend-api/f/conversation", record.getString("url"));
            assertEquals("next", body.getString("action"));
            assertEquals("conversation-fixed", body.getString("conversation_id"));
            assertEquals("parent-fixed", body.getString("parent_message_id"));
            assertEquals(new JSONArray(MESSAGES).toString(), body.getJSONArray("messages").toString());
            assertEquals("primary_assistant",
                    body.getJSONObject("conversation_mode").getString("kind"));
            assertEquals("message-fixed", body.getString("message_id"));
            assertEquals("preserve-me", body.getJSONObject("custom").getString("value"));
        }
    }

    private static String requestPair() {
        return """
                (()=>{window.hybridPairDone=false;window.hybridPairError='';
                const base={action:'next',conversation_id:'conversation-fixed',parent_message_id:'parent-fixed',
                  message_id:'message-fixed',messages:__MESSAGES__,
                  conversation_mode:{kind:'primary_assistant'},custom:{value:'preserve-me'},
                  model:'source-model',thinking_effort:'source-effort',
                  conversation_origin:'source-origin',service_tier:'source-tier'};
                const send=opaque=>fetch('/backend-api/f/conversation',{method:'POST',
                  headers:{'Content-Type':'application/json'},body:JSON.stringify({...base,opaque})});
                (async()=>{
                  const first=send('bootstrap');
                  window.__selfRunHybridProfileBridge.selectStage('continuation');
                  const second=send('continuation');
                  await Promise.all([first,second]);
                  window.hybridPairDone=true;
                })().catch(error=>{window.hybridPairError=String(error?.message||error);window.hybridPairDone=true;});
                return 'started';})()
                """.replace("__MESSAGES__", MESSAGES);
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
        assertTrue("HYBRID fixture did not load", loaded.await(15, TimeUnit.SECONDS));
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

    private static void restoreString(SharedPreferences.Editor editor, String key,
                                      boolean existed, String value) {
        if (existed) editor.putString(key, value);
        else editor.remove(key);
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
