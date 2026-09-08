package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Executes the actual dispatch script with an inert network fixture, without ChatGPT credentials. */
@RunWith(AndroidJUnit4.class)
public final class SelfRun3DispatchAndroidTest {
    private static final String ORIGIN = "https://chatgpt.com/";
    @Test public void oneShotCanonicalDispatchReadsOnlyIdentityAndHostIsReusable() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<HeadlessWebViewHost> host = new AtomicReference<>();
            AtomicReference<WebView> web = new AtomicReference<>();
            AtomicReference<JSONObject> report = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            try {
                scenario.onActivity(activity -> {
                    HeadlessWebViewHost h = HeadlessWebViewHost.create(activity, false);
                    host.set(h); web.set(h.webView());
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
                evaluate(scenario, web, fixturePrelude());
                evaluate(scenario, web, SelfRun3DispatchScript.documentStartScript());
                evaluate(scenario, web, """
                        (async()=>{
                          const out={};
                          const signals=()=>fixtureEvents.filter(e=>e.stage==='turn_request').length;
                          const body=(turn,request,conversation)=>JSON.stringify({
                            conversation_id:conversation,
                            messages:[{author:{role:'user'},content:{parts:[turn+' '+request]}}]
                          });
                          const post=(path,text)=>fetch('https://chatgpt.com'+path,{method:'POST',body:text});
                          try{
                            window.__selfRun3Dispatch.arm('task','turn-1','request-1');
                            await post('/backend-api/f/conversation/prepare',body('turn-1','request-1'));
                            out.noncanonicalSignals=signals();
                            try{await post('/backend-api/f/conversation',body('turn-1','wrong'));}catch(_){}
                            out.wrongSignals=signals();
                            try{await post('/backend-api/f/conversation',body('turn-1','request-1','existing'));}catch(_){}
                            out.existingSignals=signals();
                            const before=fixtureFetches;
                            await post('/backend-api/f/conversation',body('turn-1','request-1'));
                            await fixtureIdentityDone;
                            out.matchSignals=signals();
                            out.nativeMatchCalls=fixtureFetches-before;
                            out.event=fixtureEvents.find(e=>e.stage==='turn_request');
                            out.identity=fixtureEvents.find(e=>e.stage==='conversation_identity');
                            try{await post('/backend-api/f/conversation',body('turn-1','request-1'));}catch(_){}
                            out.repeatSignals=signals();
                            out.nativeCallsAfterRepeat=fixtureFetches-before;
                            out.responseReads=fixtureResponseReads;
                            out.readerCancels=fixtureReaderCancels;
                          }catch(error){out.error=String(error);}
                          window.fixtureResult.postMessage(JSON.stringify(out));
                        })();
                        """);
                assertTrue("dispatch fixture timed out", completed.await(15, TimeUnit.SECONDS));
                JSONObject result = report.get();
                assertNotNull(result);
                assertFalse(result.toString(), result.has("error"));
                assertEquals(0, result.getInt("noncanonicalSignals"));
                assertEquals(0, result.getInt("wrongSignals"));
                assertEquals(0, result.getInt("existingSignals"));
                assertEquals(1, result.getInt("matchSignals"));
                assertEquals(1, result.getInt("nativeMatchCalls"));
                assertEquals(1, result.getInt("repeatSignals"));
                assertEquals(1, result.getInt("nativeCallsAfterRepeat"));
                assertEquals(1, result.getInt("responseReads"));
                assertEquals(1, result.getInt("readerCancels"));
                assertEquals("fixture-conversation", result.getJSONObject("identity").getString("conversationId"));
                JSONObject event = result.getJSONObject("event");
                assertEquals("task", event.getString("runId"));
                assertEquals("request-1", event.getString("turnToken"));
                assertEquals("canonical_post", event.getString("source"));

                evaluate(scenario, web, "history.replaceState({},'', '/c/existing');");
                String rejected = evaluate(scenario, web,
                        SelfRun3ComposerTransport.prepareBootstrap(ORIGIN, "new prompt", "fixture"));
                assertEquals("TARGET_ERROR", new JSONObject(
                        String.valueOf(new JSONTokener(rejected).nextValue())).getString("status"));

                WebView original = web.get();
                scenario.onActivity(activity -> {
                    assertTrue("emulator must provide detachable output", host.get().hasDetachableOutput());
                    host.get().detachOutput();
                    assertFalse(host.get().isOutputAttached());
                    host.get().attachOutput();
                    assertTrue(host.get().isOutputAttached());
                    assertSame(original, host.get().webView());
                });
                load(scenario, web);
                assertSame(original, web.get());
                String fresh = evaluate(scenario, web, "String(location.pathname==='/'&&!window.__selfRun3Dispatch)");
                assertEquals("true", new JSONTokener(fresh).nextValue());
                scenario.onActivity(activity -> {
                    host.get().detachOutput();
                    assertFalse(host.get().isOutputAttached());
                    assertSame(original, host.get().webView());
                });
            } finally {
                scenario.onActivity(activity -> { if (host.get() != null) host.get().destroy(); });
            }
        }
    }
    private static String fixturePrelude() {
        return """
                window.fixtureEvents=[];window.fixtureFetches=0;window.fixtureResponseReads=0;
                window.fixtureReaderCancels=0;
                window.fixtureIdentityDone=new Promise(resolve=>window.fixtureIdentityResolve=resolve);
                window.selfRun3Dispatch={postMessage:raw=>{
                  const event=JSON.parse(raw);fixtureEvents.push(event);
                  if(event.stage==='conversation_identity')fixtureIdentityResolve();
                }};
                window.fetch=()=>{fixtureFetches++;return Promise.resolve({
                  clone(){return {body:{getReader:()=>({
                    read(){fixtureResponseReads++;
                      if(fixtureResponseReads>1)throw new Error('continued response observation');
                      return Promise.resolve({done:false,value:new TextEncoder().encode(
                        'data: '+JSON.stringify({conversation_id:'fixture-conversation'})+String.fromCharCode(10,10)
                      )});
                    },
                    cancel(){fixtureReaderCancels++;return Promise.resolve();}
                  })}};},
                  text(){throw new Error('full response text accessed');}
                });};
                """;
    }
    private static void load(ActivityScenario<SelfRunNewActivity> scenario,
                             AtomicReference<WebView> web) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            web.get().setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) { loaded.countDown(); }
            });
            web.get().loadDataWithBaseURL(ORIGIN, "<!doctype html><html><body>dispatch fixture</body></html>",
                    "text/html", "UTF-8", null);
        });
        assertTrue("fixture navigation timed out", loaded.await(15, TimeUnit.SECONDS));
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
