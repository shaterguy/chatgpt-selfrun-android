package com.shaterguy.chatgptselfrun;

import android.os.SystemClock;
import android.webkit.WebResourceRequest;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Regression coverage for markerless Pro selection before response transport handoff. */
@RunWith(AndroidJUnit4.class)
public final class ProRequestProfilePreclassificationWebViewTest {
    private static final String ORIGIN="https://chatgpt.com/c/pro-preclassification";
    private static final String CONVERSATION="pro-preclassification";
    private static final String RUN="fixture-run";

    @Test public void proBootstrapOwnsTransportBeforeMarkerlessEarlyBoundary() throws Exception {
        try(Fixture f=new Fixture()) {
            f.fetch("[2026.09.06 | 14:44:25] [SELF_RUN_BOOTSTRAP 0.2.0 "+RUN+"]");
            JSONObject early=f.awaitStreamComplete();
            assertEquals("PRO",early.getString("detectorLane"));
            assertEquals("THINKING",early.getString("phase"));
            assertTrue(early.getBoolean("sawStreamHandoff"));
            assertTrue(early.getBoolean("sawStreamComplete"));
            assertFalse(early.getBoolean("sawAssistantFinalText"));
            assertEquals(0,f.callbacks.get());
            JSONObject diag=f.diag();
            assertEquals(1,diag.getInt("requestProfileHints"));
            assertEquals(0,diag.getInt("requestHintMisses"));
            assertTrue(diag.getBoolean("ownsCurrentTurn"));

            f.sendFinal("pro-turn","최종 Pro 답변");
            f.awaitPhase("COMPLETE");
            f.awaitCallbacks(1);
        }
    }

    @Test public void nonProContinuationKeepsNormalChatCompletionPolicy() throws Exception {
        try(Fixture f=new Fixture()) {
            f.fetch("[2026.09.06 | 14:45:25] [SELF_RUN_CONTINUE "+RUN+"]");
            f.awaitPhase("COMPLETE");
            JSONObject state=f.state();
            assertEquals("CHAT",state.getString("detectorLane"));
            assertTrue(state.getBoolean("sawStreamComplete"));
            assertFalse(state.getBoolean("sawStreamHandoff"));
            assertEquals(0,f.diag().getInt("requestProfileHints"));
            f.awaitCallbacks(1);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ActivityScenario<SelfRunNewActivity> scenario=ActivityScenario.launch(SelfRunNewActivity.class);
        final AtomicReference<WebView> web=new AtomicReference<>();
        final AtomicInteger callbacks=new AtomicInteger();

        Fixture() throws Exception {
            CountDownLatch loaded=new CountDownLatch(1);
            scenario.onActivity(activity->{
                WebView view=new WebView(activity);
                view.getSettings().setJavaScriptEnabled(true);
                view.getSettings().setDomStorageEnabled(true);
                view.setWebViewClient(new WebViewClient(){
                    @Override public void onPageFinished(WebView ignored,String url){
                        if(url!=null&&url.startsWith(ORIGIN))loaded.countDown();
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                        if("selfrun-drive".equals(request.getUrl().getScheme())){callbacks.incrementAndGet();return true;}
                        return false;
                    }
                });
                activity.setContentView(view);web.set(view);
                view.loadDataWithBaseURL(ORIGIN,"<!doctype html><html><body>pro request profile fixture</body></html>","text/html","UTF-8",null);
            });
            assertTrue("fixture load",loaded.await(15,TimeUnit.SECONDS));
            eval("sessionStorage.clear();window.fixtureLogs=[];window.selfRunTurnLog={postMessage:x=>window.fixtureLogs.push(JSON.parse(x))};"
                    +"window.__selfRunRequestProfileEngine={target:()=>({runId:'"+RUN+"',mode:'chat',reasoning:'pro',bootstrapReasoning:'pro',continuationReasoning:'instant'})};"
                    +"window.fetch=()=>Promise.resolve(new Response('data: {\\\"type\\\":\\\"message_stream_complete\\\"}\\n\\n',{status:200,headers:{'Content-Type':'text/event-stream'}}));"
                    +"window.WebSocket=class extends EventTarget{constructor(){super();}};window.Worker=undefined;window.SharedWorker=undefined;");
            eval(ChatGptTurnProtocolScript.documentStartScript());
            assertEquals("true",text("String(window.__selfRunTurnProtocol.bindTurn('"+RUN+"','fixture-token'))"));
            assertEquals("true",text("String(window.__selfRunTurnProtocol.armCompletion('"+RUN+"','fixture-token'))"));
            eval(ProTurnProtocolIngressScript.documentStartScript());
        }

        void fetch(String prompt) throws Exception {
            JSONObject body=new JSONObject().put("model","gpt-5-6-thinking").put("messages",new JSONArray().put(
                    new JSONObject().put("author",new JSONObject().put("role","user"))
                            .put("content",new JSONObject().put("parts",new JSONArray().put(prompt)))));
            eval("void fetch('https://chatgpt.com/backend-api/f/conversation',{method:'POST',headers:{'Content-Type':'application/json'},body:"
                    +JSONObject.quote(body.toString())+"});");
        }

        JSONObject awaitStreamComplete() throws Exception {
            long deadline=SystemClock.uptimeMillis()+10000;
            while(SystemClock.uptimeMillis()<deadline){JSONObject state=state();if(state.getBoolean("sawStreamComplete"))return state;SystemClock.sleep(20);}
            fail("stream complete not observed: "+state());return state();
        }

        void sendFinal(String turn,String answer) throws Exception {
            JSONObject message=new JSONObject().put("id","pro-final")
                    .put("author",new JSONObject().put("role","assistant"))
                    .put("channel","final").put("content",new JSONObject().put("parts",new JSONArray().put(answer)));
            JSONObject start=new JSONObject().put("type","message_start").put("message",message);
            JSONObject complete=new JSONObject().put("type","message_stream_complete");
            String encoded="data: "+start+"\n\ndata: "+complete+"\n\n";
            JSONObject item=new JSONObject().put("type","stream-item").put("conversation_id",CONVERSATION)
                    .put("turn_id",turn).put("encoded_item",encoded);
            JSONObject frame=new JSONObject().put("payload",new JSONObject().put("payload",item));
            eval("void window.__selfRunProTurnProtocolIngress.observeTransportData("+JSONObject.quote(frame.toString())+");");
        }

        JSONObject state() throws Exception {return new JSONObject(text("JSON.stringify(window.__selfRunTurnProtocol.snapshot())"));}
        JSONObject diag() throws Exception {return new JSONObject(text("JSON.stringify(window.__selfRunProTurnProtocolIngress.diagnostics())"));}
        void awaitPhase(String phase) throws Exception {
            long deadline=SystemClock.uptimeMillis()+10000;
            while(SystemClock.uptimeMillis()<deadline){if(phase.equals(state().getString("phase")))return;SystemClock.sleep(20);}
            fail("phase "+phase+" not reached: "+state());
        }
        void awaitCallbacks(int count) throws Exception {
            long deadline=SystemClock.uptimeMillis()+5000;
            while(callbacks.get()!=count&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(20);
            assertEquals(count,callbacks.get());
        }
        String text(String script) throws Exception {return String.valueOf(new JSONTokener(eval(script)).nextValue());}
        String eval(String script) throws Exception {
            CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();
            scenario.onActivity(activity->web.get().evaluateJavascript(script,value->{result.set(value);done.countDown();}));
            assertTrue("script result",done.await(15,TimeUnit.SECONDS));return result.get();
        }
        @Override public void close(){scenario.close();}
    }
}
