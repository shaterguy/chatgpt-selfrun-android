package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** End-to-end local WebView fixture for the 3.1 first-message transport. */
@RunWith(AndroidJUnit4.class)
public final class SelfRun31FirstConversationAndroidTest {
    private static final String ORIGIN = "https://chatgpt.com/";
    private static final String TURN = "turn-first";
    private static final String REQUEST = "request-first";
    private static final String PROMPT = "TASK_ID=task-first\nTURN_ID=" + TURN + "\nREQUEST_ID=" + REQUEST + "\nfirst conversation fixture";

    @Test public void freshComposerSubmitsExactlyOnceThenSurfaceCanDetach() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<HeadlessWebViewHost> host = new AtomicReference<>();
            AtomicReference<WebView> web = new AtomicReference<>();
            AtomicReference<JSONObject> event = new AtomicReference<>();
            CountDownLatch observed = new CountDownLatch(1);
            String marker = "fixture-first-" + System.nanoTime();
            try {
                scenario.onActivity(activity -> {
                    HeadlessWebViewHost h = HeadlessWebViewHost.create(activity);
                    host.set(h);
                    web.set(h.webView());
                    h.webView().getSettings().setJavaScriptEnabled(true);
                    h.webView().getSettings().setDomStorageEnabled(true);
                    WebViewCompat.addWebMessageListener(h.webView(), "selfRun3Dispatch", Set.of("https://chatgpt.com"),
                            (view, message, origin, main, reply) -> {
                                if (!main || message.getType() != WebMessageCompat.TYPE_STRING) return;
                                try { event.set(new JSONObject(message.getData())); } catch (Exception ignored) {}
                                observed.countDown();
                            });
                });
                load(scenario, web);
                evaluate(scenario, web, SelfRun3DispatchScript.documentStartScript());

                String status = "";
                for (int i = 0; i < 12; i++) {
                    status = status(evaluate(scenario, web,
                            SelfRun3BootstrapTransport.prepare(ORIGIN, PROMPT, marker)));
                    if (SelfRun3BootstrapTransport.READY_TO_SUBMIT.equals(status)) break;
                    Thread.sleep(30L);
                }
                assertEquals(SelfRun3BootstrapTransport.READY_TO_SUBMIT, status);
                assertEquals(PROMPT, evaluateValue(scenario, web,
                        "document.querySelector('#prompt-textarea').value"));

                assertEquals("true", evaluateValue(scenario, web,
                        "String(" + SelfRun3DispatchScript.arm("task-first", TURN, REQUEST) + ")"));
                String submitStatus = status(evaluate(scenario, web,
                        SelfRun3BootstrapTransport.submit(ORIGIN, PROMPT, marker)));
                assertEquals(SelfRun3BootstrapTransport.SUBMISSION_PENDING, submitStatus);
                assertTrue("canonical first POST not observed", observed.await(5, TimeUnit.SECONDS));
                assertEquals("1", evaluateValue(scenario, web, "String(window.fixturePosts.length)"));
                assertEquals(PROMPT, evaluateValue(scenario, web, "window.fixturePosts[0]"));
                JSONObject e = event.get();
                assertNotNull(e);
                assertEquals("task-first", e.getString("runId"));
                assertEquals(TURN, e.getString("turnId"));
                assertEquals(REQUEST, e.getString("turnToken"));
                assertEquals("canonical_post", e.getString("source"));
                assertEquals("/c/fixture-conversation", evaluateValue(scenario, web, "location.pathname"));

                WebView original = web.get();
                scenario.onActivity(activity -> {
                    assertTrue(host.get().hasDetachableOutput());
                    host.get().detachOutput();
                    assertFalse(host.get().isOutputAttached());
                    assertSame(original, host.get().webView());
                    host.get().attachOutput();
                    assertTrue(host.get().isOutputAttached());
                    assertSame(original, host.get().webView());
                });
            } finally {
                scenario.onActivity(activity -> { if (host.get() != null) host.get().destroy(); });
            }
        }
    }

    private static void load(ActivityScenario<SelfRunNewActivity> scenario,
                             AtomicReference<WebView> web) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            web.get().setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) { loaded.countDown(); }
            });
            String html = """
                    <!doctype html><html><body>
                    <form id='composer'>
                      <textarea id='prompt-textarea' data-testid='prompt-textarea'></textarea>
                      <button type='submit' data-testid='send-button' aria-label='Send'>Send</button>
                    </form>
                    <script>
                      window.fixturePosts=[];
                      window.fetch=(input,init)=>{try{
                        const body=JSON.parse(String(init?.body||''));
                        const last=body.messages[body.messages.length-1];
                        const prompt=last.content.parts[0];
                        fixturePosts.push(prompt);
                      }catch(_){}return Promise.resolve({ok:true});};
                      document.querySelector('#composer').addEventListener('submit',e=>{
                        e.preventDefault();
                        const prompt=document.querySelector('#prompt-textarea').value;
                        fetch('/backend-api/f/conversation',{method:'POST',body:JSON.stringify({
                          messages:[{author:{role:'user'},content:{parts:[prompt]}}]
                        })});
                        history.replaceState({},'', '/c/fixture-conversation');
                      });
                    </script>
                    </body></html>
                    """;
            web.get().loadDataWithBaseURL(ORIGIN, html, "text/html", "UTF-8", null);
        });
        assertTrue("fixture navigation timed out", loaded.await(15, TimeUnit.SECONDS));
    }

    private static String status(String raw) throws Exception {
        Object outer = new JSONTokener(raw == null ? "null" : raw).nextValue();
        JSONObject json = outer instanceof String ? new JSONObject((String) outer) : (JSONObject) outer;
        return json.optString("status");
    }

    private static String evaluateValue(ActivityScenario<SelfRunNewActivity> scenario,
                                        AtomicReference<WebView> web, String script) throws Exception {
        String raw = evaluate(scenario, web, script);
        Object value = new JSONTokener(raw == null ? "null" : raw).nextValue();
        return value == null || value == JSONObject.NULL ? "" : String.valueOf(value);
    }

    private static String evaluate(ActivityScenario<SelfRunNewActivity> scenario,
                                   AtomicReference<WebView> web, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> raw = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(script, value -> {
            raw.set(value); done.countDown();
        }));
        assertTrue("fixture JavaScript timed out", done.await(15, TimeUnit.SECONDS));
        return raw.get();
    }
}
