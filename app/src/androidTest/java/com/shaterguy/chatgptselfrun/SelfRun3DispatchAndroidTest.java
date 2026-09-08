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
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Regression: SelfRun 3.1 dispatch observation must never block the actual ChatGPT POST. */
@RunWith(AndroidJUnit4.class)
public final class SelfRun3DispatchAndroidTest {
    private static final String ORIGIN = "https://chatgpt.com/";

    @Test public void canonicalPostIsObservedOnceAndAlwaysPassedThrough() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<HeadlessWebViewHost> host = new AtomicReference<>();
            AtomicReference<WebView> web = new AtomicReference<>();
            AtomicReference<JSONObject> report = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            try {
                scenario.onActivity(activity -> {
                    HeadlessWebViewHost h = HeadlessWebViewHost.create(activity);
                    host.set(h);
                    web.set(h.webView());
                    h.webView().getSettings().setJavaScriptEnabled(true);
                    h.webView().getSettings().setDomStorageEnabled(true);
                    WebViewCompat.addWebMessageListener(h.webView(), "fixtureResult", Set.of("https://chatgpt.com"),
                            (view, message, origin, main, reply) -> {
                                if (!main || message.getType() != WebMessageCompat.TYPE_STRING) return;
                                try { report.set(new JSONObject(message.getData())); } catch (Exception ignored) {}
                                completed.countDown();
                            });
                });
                load(scenario, web);
                evaluate(scenario, web, """
                        window.fixtureFetches=0;window.fixtureEvents=[];
                        window.selfRun3Dispatch={postMessage:raw=>fixtureEvents.push(JSON.parse(raw))};
                        window.fetch=(input,init)=>{fixtureFetches++;return Promise.resolve({ok:true});};
                        """);
                evaluate(scenario, web, SelfRun3DispatchScript.documentStartScript());
                evaluate(scenario, web, """
                        (async()=>{
                          const body=(turn,request,conversation)=>JSON.stringify({
                            conversation_id:conversation,
                            messages:[{author:{role:'user'},content:{parts:[turn+' '+request]}}]
                          });
                          const post=text=>fetch('/backend-api/f/conversation',{method:'POST',body:text});
                          const out={};
                          out.armed=window.__selfRun3Dispatch.arm('task','turn-1','request-1');
                          await post(body('turn-1','wrong'));
                          await new Promise(r=>setTimeout(r,20));
                          out.afterWrongFetches=fixtureFetches;out.afterWrongSignals=fixtureEvents.length;
                          await post(body('turn-1','request-1','existing'));
                          await new Promise(r=>setTimeout(r,20));
                          out.afterExistingFetches=fixtureFetches;out.afterExistingSignals=fixtureEvents.length;
                          history.replaceState({},'', '/c/optimistic-route');
                          await post(body('turn-1','request-1'));
                          await new Promise(r=>setTimeout(r,50));
                          out.afterMatchFetches=fixtureFetches;out.afterMatchSignals=fixtureEvents.length;
                          out.event=fixtureEvents[0]||{};
                          await post(body('turn-1','request-1'));
                          await new Promise(r=>setTimeout(r,20));
                          out.afterRepeatFetches=fixtureFetches;out.afterRepeatSignals=fixtureEvents.length;
                          window.fixtureResult.postMessage(JSON.stringify(out));
                        })();
                        """);
                assertTrue("dispatch fixture timed out", completed.await(15, TimeUnit.SECONDS));
                JSONObject out = report.get();
                assertNotNull(out);
                assertTrue(out.getBoolean("armed"));
                assertEquals(1, out.getInt("afterWrongFetches"));
                assertEquals(0, out.getInt("afterWrongSignals"));
                assertEquals(2, out.getInt("afterExistingFetches"));
                assertEquals(0, out.getInt("afterExistingSignals"));
                assertEquals(3, out.getInt("afterMatchFetches"));
                assertEquals(1, out.getInt("afterMatchSignals"));
                assertEquals(4, out.getInt("afterRepeatFetches"));
                assertEquals(1, out.getInt("afterRepeatSignals"));
                JSONObject event = out.getJSONObject("event");
                assertEquals("task", event.getString("runId"));
                assertEquals("turn-1", event.getString("turnId"));
                assertEquals("request-1", event.getString("turnToken"));
                assertEquals("canonical_post", event.getString("source"));
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
            web.get().loadDataWithBaseURL(ORIGIN, "<!doctype html><html><body>fixture</body></html>",
                    "text/html", "UTF-8", null);
        });
        assertTrue("fixture navigation timed out", loaded.await(15, TimeUnit.SECONDS));
    }

    private static void evaluate(ActivityScenario<SelfRunNewActivity> scenario,
                                 AtomicReference<WebView> web, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        scenario.onActivity(activity -> web.get().evaluateJavascript(script, value -> done.countDown()));
        assertTrue("fixture JavaScript timed out", done.await(15, TimeUnit.SECONDS));
    }
}
