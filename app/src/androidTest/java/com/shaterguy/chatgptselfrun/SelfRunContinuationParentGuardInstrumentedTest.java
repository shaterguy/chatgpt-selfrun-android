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

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SelfRunContinuationParentGuardInstrumentedTest {
    private static final String CONVERSATION = "conversation123";
    private static final String CONTINUE = "[SELF_RUN_CONTINUE SR-20260815-TEST01]";
    private WebView webView;

    @Before public void setUp() throws Exception {
        assertTrue("DOCUMENT_START_SCRIPT must be supported by the test WebView",
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));
        Context context = ApplicationProvider.getApplicationContext();
        CountDownLatch loaded = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            webView = new WebView(context);
            webView.getSettings().setJavaScriptEnabled(true);
            WebViewCompat.addDocumentStartJavaScript(webView, mockBackendScript(),
                    Set.of("https://chatgpt.com"));
            WebViewConfig.applyAutomation(webView);
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) { loaded.countDown(); }
            });
            webView.loadDataWithBaseURL(
                    "https://chatgpt.com/g/g-p-test/c/" + CONVERSATION,
                    pageHtml(), "text/html", "UTF-8", null);
        });
        assertTrue("test WebView did not load", loaded.await(15, TimeUnit.SECONDS));
        assertTrue(eval("window.__selfRunDriveParentGuardInstalled===true").contains("true"));
    }

    @After public void tearDown() {
        if (webView == null) return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { webView.destroy(); } catch (Throwable ignored) {}
        });
    }

    @Test public void canonicalParentWinsForNoExtraOneExtraAndManyExtraTurns() throws Exception {
        assertParentRewrite("m0", "stale-tip", "stale-tip");
        assertParentRewrite("m1", "stale-tip", "latest-after-one-dialogue");
        assertParentRewrite("m2", "old-before-many-dialogues", "latest-after-many-dialogues");
    }

    @Test public void canonicalLookupFailureBlocksTheStaleConversationPost() throws Exception {
        eval("window.__sentBodies=[];window.__canonicalError=true;sessionStorage.setItem('"
                + SelfRunNetworkGuard.ARM_KEY + "',JSON.stringify({markerId:'mf',conversationId:'"
                + CONVERSATION + "',expected:" + js(CONTINUE)
                + ",armedAt:Date.now(),expiresAt:Date.now()+30000}));"
                + "window.__sendContinue('stale-parent'," + js(CONTINUE) + ");'armed'");
        waitFor("(()=>{try{return JSON.parse(sessionStorage.getItem('"
                + SelfRunNetworkGuard.RESULT_KEY + "')||'{}').state==='failed'}catch(_){return false}})()", 10_000L);
        assertTrue(eval("window.__sentBodies.length===0").contains("true"));
        assertTrue(eval("(()=>{try{return JSON.parse(sessionStorage.getItem('"
                + SelfRunNetworkGuard.RESULT_KEY + "')||'{}').markerId==='mf'}catch(_){return false}})()")
                .contains("true"));
    }

    private void assertParentRewrite(String marker, String pageParent, String canonicalParent) throws Exception {
        eval("window.__sentBodies=[];window.__canonicalError=false;window.__canonicalNode="
                + js(canonicalParent) + ";sessionStorage.removeItem('" + SelfRunNetworkGuard.RESULT_KEY + "');"
                + "sessionStorage.setItem('" + SelfRunNetworkGuard.ARM_KEY + "',JSON.stringify({markerId:"
                + js(marker) + ",conversationId:'" + CONVERSATION + "',expected:" + js(CONTINUE)
                + ",armedAt:Date.now(),expiresAt:Date.now()+30000}));"
                + "window.__sendContinue(" + js(pageParent) + "," + js(CONTINUE) + ");'armed'");
        waitFor("window.__sentBodies.length===1", 10_000L);
        assertTrue(eval("window.__sentBodies[0].parent_message_id===" + js(canonicalParent)).contains("true"));
        assertTrue(eval("window.__sentBodies[0].conversation_id==='" + CONVERSATION + "'").contains("true"));
        assertTrue(eval("(()=>{try{const r=JSON.parse(sessionStorage.getItem('"
                + SelfRunNetworkGuard.RESULT_KEY + "')||'{}');return r.state==='forwarded'&&r.markerId==="
                + js(marker) + "}catch(_){return false}})()").contains("true"));
    }

    private void waitFor(String expression, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (eval(expression).contains("true")) return;
            Thread.sleep(100L);
        }
        throw new AssertionError("condition not reached: " + expression + " last="
                + eval("JSON.stringify({sent:window.__sentBodies,result:sessionStorage.getItem('"
                + SelfRunNetworkGuard.RESULT_KEY + "')})"));
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
                <script>
                  window.__canonicalNode='stale-tip';
                  window.__canonicalError=false;
                  window.__sentBodies=[];
                  const pageFetch=window.fetch;
                  window.__sendContinue=(parent,text)=>pageFetch('/backend-api/f/conversation',{
                    method:'POST',headers:{'Content-Type':'application/json'},
                    body:JSON.stringify({conversation_id:'conversation123',parent_message_id:parent,
                      messages:[{author:{role:'user'},content:{parts:[text]}}]})
                  }).catch(error=>{window.__lastSendError=String(error?.message||error)});
                </script>
                </body></html>
                """;
    }

    private static String mockBackendScript() {
        return """
                (()=>{
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
                    if(method==='POST'&&(url.pathname==='/backend-api/f/conversation'||url.pathname==='/backend-api/conversation')){
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
