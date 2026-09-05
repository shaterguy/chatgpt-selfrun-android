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

/** Synthetic protocol fixtures: exercise actual fetch/socket hooks, not the logged user payload. */
@RunWith(AndroidJUnit4.class)
public final class ProCompactDeltaWebViewTest {
    private static final String ORIGIN="https://chatgpt.com/c/fixture-conversation";
    private static final String CONVERSATION="fixture-conversation";

    @Test public void proCompactSocketAnswerCompletesOnceAfterEarlyBoundary() throws Exception {
        try(Fixture f=new Fixture()) {
            f.start("work-a");
            f.send("work-a",replaceMessage("answer-a","assistant","final"));
            f.send("work-a",new JSONObject().put("p","/message/content/parts/0").put("o","append").put("v"," "));
            f.expect("THINKING");
            f.send("work-a",new JSONObject().put("v","실제 최종 답변"));
            f.expect("ANSWERING");
            assertEquals(0,f.callbacks.get());
            f.encoded("work-a","data: [DONE]\n\n");
            f.outerDone("work-a");
            f.expect("ANSWERING");
            assertFalse(f.state().toString().contains("실제 최종 답변"));
            f.send("work-a",complete());
            f.expect("COMPLETE");
            f.awaitCallbacks(1);
            f.send("work-a",complete());
            f.outerDone("work-a");
            assertEquals(1,f.callbacks.get());
            assertEquals("1",f.text("String(window.fixtureLogs.filter(x=>x.stage==='complete').length)"));
            assertEquals("1",f.text("String(window.fixtureLogs.filter(x=>x.stage==='completion_dispatch').length)"));
        }
    }

    @Test public void nonFinalRolesMetadataAndForeignMessagesCannotUnlockCompletion() throws Exception {
        try(Fixture f=new Fixture()) {
            f.start("work-a");
            String[][] identities={{"user","final"},{"tool","final"},{"assistant","analysis"},{"assistant","commentary"}};
            for(int i=0;i<identities.length;i++) {
                f.send("work-a",replaceMessage("not-final-"+i,identities[i][0],identities[i][1]));
                f.send("work-a",new JSONObject().put("p","/message/content/parts/0").put("v","not a final answer"));
                f.send("work-a",complete());
                f.expect("THINKING");
            }
            f.send("work-a",replaceMessage("answer-a","assistant","final"));
            f.send("work-a",new JSONObject().put("p","/message/metadata/note").put("v","metadata"));
            f.send("work-a",new JSONObject().put("v","more metadata"));
            f.send("work-a",new JSONObject().put("p","/message/content/parts/0").put("message_id","foreign-message").put("v","foreign"));
            f.send("work-a",complete());
            f.expect("THINKING");
            assertFalse(f.state().getBoolean("sawAssistantFinalText"));
            assertEquals(0,f.callbacks.get());
        }
    }

    @Test public void nestedRelativePatchAndPartsArrayStartTheFinalAnswer() throws Exception {
        try(Fixture f=new Fixture()) {
            f.start("work-a");
            f.send("work-a",replaceMessage("answer-a","assistant","analysis"));
            JSONArray operations=new JSONArray()
                    .put(new JSONObject().put("p","/channel").put("o","replace").put("v","final"))
                    .put(new JSONObject().put("p","/content/parts").put("o","replace").put("v",new JSONArray().put("배열 응답")));
            f.send("work-a",new JSONObject().put("p","/message").put("o","patch").put("v",operations));
            f.expect("ANSWERING");
            f.send("work-a",complete());
            f.expect("COMPLETE");
            f.awaitCallbacks(1);
        }
    }

    @Test public void replacementRequestCannotInheritCursorOrOldSocketFrames() throws Exception {
        try(Fixture f=new Fixture()) {
            f.start("work-a");
            f.send("work-a",replaceMessage("old-answer","assistant","final"));
            f.send("work-a",new JSONObject().put("p","/message/content/parts/0").put("v"," "));
            String oldRequest=f.state().getString("requestIdentity");
            f.start("work-b");
            assertNotEquals(oldRequest,f.state().getString("requestIdentity"));
            assertEquals("",f.state().getString("lastDeltaPath"));
            f.send("work-a",new JSONObject().put("v","old answer"));
            f.send("work-a",complete());
            f.send("work-b",replaceMessage("new-answer","assistant","final"));
            f.send("work-b",new JSONObject().put("v","unbound content"));
            f.expect("THINKING");
            assertFalse(f.state().getBoolean("sawAssistantFinalText"));
            f.send("work-b",new JSONObject().put("p","/message/content/parts").put("v",new JSONArray().put("new answer")));
            f.expect("ANSWERING");
            f.send("work-b",complete());
            f.expect("COMPLETE");
            f.awaitCallbacks(1);
        }
    }

    private static JSONObject replaceMessage(String id,String role,String channel) throws Exception {
        JSONObject message=new JSONObject().put("id",id).put("author",new JSONObject().put("role",role))
                .put("channel",channel).put("content",new JSONObject().put("parts",new JSONArray().put("")));
        return new JSONObject().put("p","/message").put("o","replace").put("v",message);
    }

    private static JSONObject complete() throws Exception {
        return new JSONObject().put("type","message_stream_complete");
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
                view.loadDataWithBaseURL(ORIGIN,"<!doctype html><html><body>Pro compact protocol fixture</body></html>","text/html","UTF-8",null);
            });
            assertTrue("fixture load",loaded.await(15,TimeUnit.SECONDS));
            eval("sessionStorage.clear();window.fixtureLogs=[];window.selfRunTurnLog={postMessage:x=>window.fixtureLogs.push(JSON.parse(x))};"
                    +"window.__selfRunRequestProfileEngine={target:()=>({runId:'fixture-run',mode:'chat'})};"
                    +"window.WebSocket=class extends EventTarget{constructor(){super();}};"
                    +"window.fetch=()=>Promise.resolve(new Response(window.fixtureResponse,{status:200,headers:{'Content-Type':'text/event-stream'}}));");
            eval(ChatGptTurnProtocolScript.documentStartScript());
            assertEquals("true",text("String(window.__selfRunTurnProtocol.bindTurn('fixture-run','fixture-token'))"));
            assertEquals("true",text("String(window.__selfRunTurnProtocol.armCompletion('fixture-run','fixture-token'))"));
            eval("window.fixtureSocket=new WebSocket('wss://chatgpt.com/fixture');");
        }

        void start(String turn) throws Exception {
            JSONObject handoff=new JSONObject().put("type","stream_handoff").put("conversation_id",CONVERSATION).put("turn_id",turn);
            String stream="data: "+handoff+"\n\ndata: "+complete()+"\n\n";
            eval("window.fixtureResponse="+JSONObject.quote(stream)+";void fetch('https://chatgpt.com/backend-api/f/conversation',{method:'POST'});");
            long deadline=SystemClock.uptimeMillis()+10000;
            while(SystemClock.uptimeMillis()<deadline){
                JSONObject s=state();
                if(turn.equals(s.getString("currentWorkTurnId"))&&s.getBoolean("sawStreamComplete")){
                    assertEquals("THINKING",s.getString("phase"));
                    assertTrue(s.getBoolean("sawStreamHandoff"));return;
                }
                SystemClock.sleep(20);
            }
            fail("canonical fetch handoff/early boundary not observed: "+state());
        }

        void send(String turn,JSONObject event) throws Exception {encoded(turn,"data: "+event+"\n\n");}
        void encoded(String turn,String encoded) throws Exception {
            JSONObject item=new JSONObject().put("type","stream-item").put("conversation_id",CONVERSATION)
                    .put("turn_id",turn).put("encoded_item",encoded);
            frame(item);
        }
        void outerDone(String turn) throws Exception {
            frame(new JSONObject().put("type","done").put("conversation_id",CONVERSATION).put("turn_id",turn));
        }
        void frame(JSONObject item) throws Exception {
            String raw=new JSONObject().put("payload",new JSONObject().put("payload",item)).toString();
            eval("window.fixtureSocket.dispatchEvent(new MessageEvent('message',{data:"+JSONObject.quote(raw)+"}));");
        }
        JSONObject state() throws Exception {return new JSONObject(text("JSON.stringify(window.__selfRunTurnProtocol.snapshot())"));}
        void expect(String phase) throws Exception {assertEquals(phase,state().getString("phase"));}
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
