package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SelfRunContinuationHandshakeDev4InstrumentedTest {
    private static final String CONVERSATION = "conversation123";
    private static final String URL = "https://chatgpt.com/g/g-p-test/c/" + CONVERSATION;
    private static final String CONTINUE = "[SELF_RUN_CONTINUE SR-20260816-DEV4]";
    private WebView webView;

    @Before public void setUp() throws Exception {
        assertTrue("DOCUMENT_START_SCRIPT must be supported by the test WebView",
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));
        Context context = ApplicationProvider.getApplicationContext();
        CountDownLatch loaded = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            webView = new WebView(context);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            WebViewCompat.addDocumentStartJavaScript(webView, mockBackendScript(),
                    Set.of("https://chatgpt.com"));
            WebViewConfig.applyAutomation(webView);
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) { loaded.countDown(); }
            });
            webView.loadDataWithBaseURL(URL, pageHtml(), "text/html", "UTF-8", null);
        });
        assertTrue("test WebView did not load", loaded.await(15, TimeUnit.SECONDS));
        assertTrue(eval("window.__selfRunDriveParentGuardInstalled===true").contains("true"));
        assertTrue(eval("window." + SelfRunNetworkGuard.LIVENESS_FN + "()===true").contains("true"));
    }

    @After public void tearDown() {
        if (webView == null) return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { webView.destroy(); } catch (Throwable ignored) {}
        });
    }

    @Test public void firstSecondAndFiveConsecutiveContinuationsReachSubmitted() throws Exception {
        for (int i = 1; i <= 5; i++) {
            String marker = "cycle-" + i;
            String canonical = "latest-" + i;
            eval("window.__canonicalError=false;window.__canonicalNode=" + js(canonical)
                    + ";window.__postThrow=false;window.__dropSend=false;'ready'");
            stage(marker);
            String click = eval(SelfRunDom.clickPreparedDriveTurn(URL, CONTINUE, marker));
            assertTrue(click.contains("UI_WAIT"));
            assertTrue(click.contains("GUARD_ARMED"));
            waitFor(resultState(marker, "forwarded"), 10_000L);
            String submitted = eval(SelfRunDom.prepareDriveTurn(URL, CONTINUE, marker));
            assertTrue("cycle " + i + " did not converge to SUBMITTED: " + submitted,
                    submitted.contains("SUBMITTED"));
            assertTrue(eval("window.__sentBodies[window.__sentBodies.length-1].parent_message_id==="
                    + js(canonical)).contains("true"));
        }
        assertTrue(eval("window.__sentBodies.length===5").contains("true"));
    }

    @Test public void hookReplacementFailsClosedBeforeSecondClick() throws Exception {
        stage("first");
        eval("window.__canonicalNode='latest-first';window.__dropSend=false;'ready'");
        eval(SelfRunDom.clickPreparedDriveTurn(URL, CONTINUE, "first"));
        waitFor(resultState("first", "forwarded"), 10_000L);
        assertTrue(eval(SelfRunDom.prepareDriveTurn(URL, CONTINUE, "first")).contains("SUBMITTED"));

        stage("second");
        eval("window.__guardFetchBeforeLoss=window.fetch;window.fetch=async function(){return new Response('{}',{status:200});};'replaced'");
        assertTrue(eval("window." + SelfRunNetworkGuard.LIVENESS_FN + "()===false").contains("true"));
        String before = eval("String(window.__clickCount)");
        String failed = eval(SelfRunDom.clickPreparedDriveTurn(URL, CONTINUE, "second"));
        String after = eval("String(window.__clickCount)");
        assertTrue(failed.contains("PARENT_GUARD_FAILED"));
        assertTrue(failed.contains("HOOK_LOST"));
        assertTrue("send button must not be clicked when hook is lost", before.equals(after));
    }

    @Test public void payloadEndpointAndCanonicalFailuresNeverForwardStaleRequest() throws Exception {
        arm("payload", CONTINUE);
        eval("window.fetch('/backend-api/f/conversation',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({conversation_id:'"
                + CONVERSATION + "',parent_message_id:'stale',messages:[{content:{parts:['different']}}]})}).catch(()=>{});'sent'");
        waitFor(resultCode("payload", "PAYLOAD_MISMATCH"), 10_000L);
        assertTrue(eval("window.__sentBodies.length===0").contains("true"));
        assertTrue(eval(SelfRunDom.prepareDriveTurn(URL, CONTINUE, "payload")).contains("PARENT_GUARD_FAILED"));

        arm("endpoint", CONTINUE);
        eval("window.fetch('/backend-api/new-conversation-path',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({conversation_id:'"
                + CONVERSATION + "',parent_message_id:'stale',messages:[{content:{parts:[" + js(CONTINUE) + "]}}]})}).catch(()=>{});'sent'");
        waitFor(resultCode("endpoint", "ENDPOINT_MISMATCH"), 10_000L);
        assertTrue(eval("window.__sentBodies.length===0").contains("true"));

        eval("window.__canonicalError=true;'error-on'");
        arm("canonical", CONTINUE);
        sendDirect(CONTINUE);
        waitFor(resultCode("canonical", "CANONICAL_HTTP_500"), 10_000L);
        assertTrue(eval("window.__sentBodies.length===0").contains("true"));
    }

    @Test public void failedResultAndNoPostAfterClickAreTerminal() throws Exception {
        eval("window.__dropSend=true;'drop-on'");
        stage("no-post");
        String click = eval(SelfRunDom.clickPreparedDriveTurn(URL, CONTINUE, "no-post"));
        assertTrue(click.contains("GUARD_ARMED"));
        eval("(()=>{const k='" + SelfRunNetworkGuard.ARM_KEY
                + "';const a=JSON.parse(sessionStorage.getItem(k));a.expiresAt=Date.now()-1;sessionStorage.setItem(k,JSON.stringify(a));return 'expired';})()");
        String noPost = eval(SelfRunDom.prepareDriveTurn(URL, CONTINUE, "no-post"));
        assertTrue(noPost.contains("PARENT_GUARD_FAILED"));
        assertTrue(noPost.contains("NO_POST_AFTER_CLICK"));
        assertFalse(noPost.contains("READY_TO_SUBMIT"));

        eval("sessionStorage.setItem('" + SelfRunNetworkGuard.RESULT_KEY
                + "',JSON.stringify({markerId:'failed-result',state:'failed',code:'PAYLOAD_MISMATCH',at:Date.now()}));"
                + "window." + SelfRunNetworkGuard.MEMORY_RESULT
                + "={markerId:'failed-result',state:'failed',code:'PAYLOAD_MISMATCH',at:Date.now()};'failed'");
        String failed = eval(SelfRunDom.prepareDriveTurn(URL, CONTINUE, "failed-result"));
        assertTrue(failed.contains("PARENT_GUARD_FAILED"));
        assertTrue(failed.contains("PAYLOAD_MISMATCH"));
        assertFalse(failed.contains("UI_WAIT"));
    }

    @Test public void forwardingFailureIsExplicitAndFailClosed() throws Exception {
        eval("window.__postThrow=true;window.__canonicalError=false;window.__canonicalNode='latest';'ready'");
        arm("forward", CONTINUE);
        sendDirect(CONTINUE);
        waitFor(resultCode("forward", "FORWARD_FAILED"), 10_000L);
        assertTrue(eval("window.__sentBodies.length===0").contains("true"));
    }

    private void stage(String marker) throws Exception {
        String first = eval(SelfRunDom.prepareDriveTurn(URL, CONTINUE, marker));
        if (first.contains("READY_TO_SUBMIT")) return;
        assertTrue("unexpected first prepare result: " + first, first.contains("UI_WAIT"));
        String second = eval(SelfRunDom.prepareDriveTurn(URL, CONTINUE, marker));
        assertTrue("continuation did not reach READY_TO_SUBMIT: " + second,
                second.contains("READY_TO_SUBMIT"));
    }

    private void arm(String marker, String expected) throws Exception {
        eval("sessionStorage.removeItem('" + SelfRunNetworkGuard.RESULT_KEY + "');window."
                + SelfRunNetworkGuard.MEMORY_RESULT + "=null;sessionStorage.setItem('"
                + SelfRunNetworkGuard.ARM_KEY + "',JSON.stringify({markerId:" + js(marker)
                + ",conversationId:'" + CONVERSATION + "',expected:" + js(expected)
                + ",armedAt:Date.now(),expiresAt:Date.now()+30000}));'armed'");
    }

    private void sendDirect(String text) throws Exception {
        eval("window.fetch('/backend-api/f/conversation',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({conversation_id:'"
                + CONVERSATION + "',parent_message_id:'stale',messages:[{content:{parts:["
                + js(text) + "]}}]})}).catch(()=>{});'sent'");
    }

    private String resultState(String marker, String state) {
        return "(()=>{try{const r=window." + SelfRunNetworkGuard.MEMORY_RESULT
                + "||JSON.parse(sessionStorage.getItem('" + SelfRunNetworkGuard.RESULT_KEY
                + "')||'{}');return r.markerId===" + js(marker) + "&&r.state===" + js(state)
                + "}catch(_){return false}})()";
    }

    private String resultCode(String marker, String code) {
        return "(()=>{try{const r=window." + SelfRunNetworkGuard.MEMORY_RESULT
                + "||JSON.parse(sessionStorage.getItem('" + SelfRunNetworkGuard.RESULT_KEY
                + "')||'{}');return r.markerId===" + js(marker) + "&&r.state==='failed'&&r.code==="
                + js(code) + "}catch(_){return false}})()";
    }

    private void waitFor(String expression, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (eval(expression).contains("true")) return;
            Thread.sleep(100L);
        }
        throw new AssertionError("condition not reached: " + expression);
    }

    private String eval(String script) throws Exception {
        AtomicReference<String> value = new AtomicReference<>("");
        CountDownLatch done = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                webView.evaluateJavascript(script, raw -> { value.set(raw == null ? "" : raw); done.countDown(); }));
        assertTrue("evaluateJavascript timed out", done.await(10, TimeUnit.SECONDS));
        return value.get();
    }

    private static String js(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String pageHtml() {
        return """
                <!doctype html><html><body>
                <textarea id="prompt-textarea" style="display:block;width:400px;height:80px"></textarea>
                <button data-testid="send-button" style="display:block" onclick="window.__clickCount++;window.__buttonSend()">Send</button>
                <script>
                  window.__clickCount=0;
                  window.__dropSend=false;
                  window.__buttonSend=()=>{
                    if(window.__dropSend)return;
                    const text=document.getElementById('prompt-textarea').value;
                    window.fetch('/backend-api/f/conversation',{
                      method:'POST',headers:{'Content-Type':'application/json'},
                      body:JSON.stringify({conversation_id:'conversation123',parent_message_id:'stale-parent',
                        messages:[{author:{role:'user'},content:{parts:[text]}}]})
                    }).catch(()=>{});
                  };
                </script>
                </body></html>
                """;
    }

    private static String mockBackendScript() {
        return """
                (()=>{
                  window.__canonicalNode='stale-tip';
                  window.__canonicalError=false;
                  window.__postThrow=false;
                  window.__sentBodies=[];
                  window.fetch=async function(input,init){
                    const request=(typeof Request!=='undefined'&&input instanceof Request)?input:null;
                    const url=new URL(request?request.url:String(input),location.href);
                    const method=String(init?.method||request?.method||'GET').toUpperCase();
                    if(method==='GET'&&url.pathname.startsWith('/backend-api/conversation/')){
                      if(window.__canonicalError)return new Response('{}',{status:500});
                      const node=String(window.__canonicalNode||'');
                      const graph={current_node:node,mapping:{}};
                      graph.mapping[node]={id:node,parent:null,message:{status:'finished_successfully'}};
                      return new Response(JSON.stringify(graph),{status:200,headers:{'Content-Type':'application/json'}});
                    }
                    if(method==='POST'){
                      if(window.__postThrow)throw new Error('mock forwarding failure');
                      let body=init?.body;
                      if(body==null&&request)body=await request.clone().text();
                      window.__sentBodies.push(JSON.parse(String(body||'{}')));
                      return new Response('{}',{status:200,headers:{'Content-Type':'application/json'}});
                    }
                    return new Response('{}',{status:200,headers:{'Content-Type':'application/json'}});
                  };
                })();
                """;
    }
}
