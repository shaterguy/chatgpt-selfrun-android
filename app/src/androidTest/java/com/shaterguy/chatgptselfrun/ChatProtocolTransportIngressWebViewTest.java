package com.shaterguy.chatgptselfrun;

import android.os.SystemClock;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Actual-hook regression coverage for shared CHAT response transport ingress. */
@RunWith(AndroidJUnit4.class)
public final class ChatProtocolTransportIngressWebViewTest {
    private static final String ORIGIN = "https://chatgpt.com/c/fixture-conversation";
    private static final String CONVERSATION = "fixture-conversation";

    @Test public void stringWebSocketInheritsOuterContextAndCompletesOnce() throws Exception {
        try (Fixture f = new Fixture()) {
            f.start("turn-string");
            assertEquals("true", f.text("String(window.__selfRunWorkTurnProtocolIngress.handlesTransport())"));
            f.socket(f.outer("turn-string", marker()));
            f.phase("ANSWERING");
            assertEquals(0, f.callbacks.get());
            f.socket(f.outer("turn-string", complete()));
            f.phase("COMPLETE");
            f.callbacks(1);
            f.socket(f.outer("turn-string", complete()));
            SystemClock.sleep(100);
            assertEquals(1, f.callbacks.get());
        }
    }

    @Test public void blobArrayBufferAndArrayBufferViewDecode() throws Exception {
        try (Fixture f = new Fixture()) {
            f.start("turn-binary");
            f.socketExpr("new Blob([" + JSONObject.quote(f.outer("turn-binary", marker())) + "])" );
            f.phase("ANSWERING");
            f.socketExpr("new TextEncoder().encode(" + JSONObject.quote(f.outer("turn-binary", complete())) + ").buffer");
            f.phase("COMPLETE");
            f.callbacks(1);
            assertTrue(f.diag().getInt("binaryDecoded") >= 2);
        }
        try (Fixture f = new Fixture()) {
            f.start("turn-view");
            f.socketExpr("new TextEncoder().encode(" + JSONObject.quote(f.outer("turn-view", marker())) + ")");
            f.phase("ANSWERING");
            f.socket(f.outer("turn-view", complete()));
            f.phase("COMPLETE");
            f.callbacks(1);
            assertTrue(f.diag().getInt("binaryDecoded") >= 1);
        }
    }

    @Test public void workerAndSharedWorkerUseSharedDecoder() throws Exception {
        try (Fixture f = new Fixture()) {
            f.start("turn-worker");
            f.eval("new Worker('fixture-worker.js');");
            f.eval("window.fixtureWorker.emit(" + JSONObject.quote(f.outer("turn-worker", marker())) + ");");
            f.phase("ANSWERING");
            f.eval("window.fixtureWorker.emit(" + JSONObject.quote(f.outer("turn-worker", complete())) + ");");
            f.phase("COMPLETE");
            f.callbacks(1);
            assertTrue(f.diag().getInt("workerMessages") >= 2);
        }
        try (Fixture f = new Fixture()) {
            f.start("turn-shared");
            f.eval("new SharedWorker('fixture-shared-worker.js');");
            f.eval("window.fixtureSharedWorker.port.emit(" + JSONObject.quote(f.outer("turn-shared", marker())) + ");");
            f.phase("ANSWERING");
            f.eval("window.fixtureSharedWorker.port.emit(" + JSONObject.quote(f.outer("turn-shared", complete())) + ");");
            f.phase("COMPLETE");
            f.callbacks(1);
            assertTrue(f.diag().getInt("sharedWorkerMessages") >= 2);
        }
    }

    @Test public void serviceWorkerAndTransferredPortUsePassiveSharedIngress() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals("true", f.text("String(!!window.fixtureServiceWorker&&typeof window.fixtureServiceWorker.dispatchEvent==='function')"));
            f.start("turn-sw");
            f.eval("(()=>{const c=new MessageChannel();window.fixtureServicePeer=c.port2;"
                    + "window.fixtureServiceWorker.dispatchEvent(new MessageEvent('message',{data:"
                    + JSONObject.quote(f.outer("turn-sw", marker())) + ",ports:[c.port1]}));})()");
            f.phase("ANSWERING");
            f.eval("window.fixtureServicePeer.postMessage(" + JSONObject.quote(f.outer("turn-sw", complete())) + ");");
            f.phase("COMPLETE");
            f.callbacks(1);
            JSONObject d = f.diag();
            assertTrue(d.getInt("serviceWorkerMessages") >= 1);
            assertTrue(d.getInt("serviceWorkerPortMessages") >= 1);
        }
    }

    @Test public void staleOuterContextRejectedAndIdleDoesNotClaimTransport() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals("false", f.text("String(window.__selfRunWorkTurnProtocolIngress.handlesTransport())"));
            f.socket(f.outer("idle-turn", marker()));
            assertEquals("IDLE", f.state().getString("phase"));

            f.start("old-turn");
            String oldRequest = f.state().getString("requestIdentity");
            f.post("current-turn");
            assertNotEquals(oldRequest, f.state().getString("requestIdentity"));
            f.socket(f.outer("old-turn", marker()));
            f.socket(f.outer("old-turn", complete()));
            SystemClock.sleep(100);
            assertEquals("THINKING", f.state().getString("phase"));
            assertEquals(0, f.callbacks.get());
            f.socket(f.outer("current-turn", marker()));
            f.phase("ANSWERING");
            f.socket(f.outer("current-turn", complete()));
            f.phase("COMPLETE");
            f.callbacks(1);
        }
    }

    private static JSONObject marker() throws Exception {
        return new JSONObject().put("type", "message_marker")
                .put("marker", "final_channel_token").put("event", "first");
    }

    private static JSONObject complete() throws Exception {
        return new JSONObject().put("type", "message_stream_complete");
    }

    private static final class Fixture implements AutoCloseable {
        final ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class);
        final AtomicReference<WebView> web = new AtomicReference<>();
        final AtomicInteger callbacks = new AtomicInteger();

        Fixture() throws Exception {
            CountDownLatch loaded = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                WebView view = new WebView(activity);
                view.getSettings().setJavaScriptEnabled(true);
                view.getSettings().setDomStorageEnabled(true);
                view.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView ignored, String url) {
                        if (url != null && url.startsWith(ORIGIN)) loaded.countDown();
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        if ("selfrun-drive".equals(request.getUrl().getScheme())) {
                            callbacks.incrementAndGet();
                            return true;
                        }
                        return false;
                    }
                });
                activity.setContentView(view);
                web.set(view);
                view.loadDataWithBaseURL(ORIGIN,
                        "<!doctype html><html><body>chat transport fixture</body></html>",
                        "text/html", "UTF-8", null);
            });
            assertTrue("fixture load", loaded.await(15, TimeUnit.SECONDS));
            eval("sessionStorage.clear();window.fixtureLogs=[];window.selfRunTurnLog={postMessage:x=>window.fixtureLogs.push(JSON.parse(x))};"
                    + "window.__selfRunRequestProfileEngine={target:()=>({runId:'fixture-run',mode:'chat'})};"
                    + "window.fixtureResponse='';window.fixtureSocket=null;window.fixtureWorker=null;window.fixtureSharedWorker=null;"
                    + "class FWS extends EventTarget{constructor(url){super();window.fixtureSocket=this;}emit(data){this.dispatchEvent(new MessageEvent('message',{data:data}));}}"
                    + "FWS.CONNECTING=0;FWS.OPEN=1;FWS.CLOSING=2;FWS.CLOSED=3;window.WebSocket=FWS;"
                    + "class FW extends EventTarget{constructor(url){super();window.fixtureWorker=this;}emit(data){this.dispatchEvent(new MessageEvent('message',{data:data}));}}window.Worker=FW;"
                    + "class FP extends EventTarget{start(){}emit(data){this.dispatchEvent(new MessageEvent('message',{data:data}));}}"
                    + "class FSW{constructor(url){this.port=new FP();window.fixtureSharedWorker=this;}}window.SharedWorker=FSW;"
                    + "if(!navigator.serviceWorker){try{Object.defineProperty(navigator,'serviceWorker',{value:new EventTarget(),configurable:true});}catch(_){}}"
                    + "window.fixtureServiceWorker=navigator.serviceWorker;window.fixtureServicePeer=null;"
                    + "window.fetch=()=>Promise.resolve(new Response(window.fixtureResponse,{status:200,headers:{'Content-Type':'text/event-stream'}}));");
            eval(ChatGptTurnProtocolScript.documentStartScript());
            eval(WorkTurnProtocolIngressScript.documentStartScript());
            eval(WorkProtocolTransportCaptureScript.documentStartScript());
            assertEquals("true", text("String(window.__selfRunTurnProtocol.bindTurn('fixture-run','fixture-token'))"));
            assertEquals("true", text("String(window.__selfRunTurnProtocol.armCompletion('fixture-run','fixture-token'))"));
            eval("window.fixtureSocket=new WebSocket('wss://chatgpt.com/fixture');");
        }

        void start(String turn) throws Exception {
            assertEquals("IDLE", state().getString("phase"));
            post(turn);
        }

        void post(String turn) throws Exception {
            int callbacksBefore = callbacks.get();
            JSONObject handoff = new JSONObject().put("type", "stream_handoff")
                    .put("conversation_id", CONVERSATION).put("turn_id", turn);
            String stream = "data: " + handoff + "\n\ndata: " + complete() + "\n\n";
            eval("window.fixtureResponse=" + JSONObject.quote(stream)
                    + ";void fetch('https://chatgpt.com/backend-api/f/conversation',{method:'POST'});");
            long deadline = SystemClock.uptimeMillis() + 10000;
            while (SystemClock.uptimeMillis() < deadline) {
                JSONObject s = state();
                if (turn.equals(s.getString("currentWorkTurnId")) && s.getBoolean("sawStreamComplete")) {
                    assertEquals("THINKING", s.getString("phase"));
                    assertTrue(s.getBoolean("sawStreamHandoff"));
                    assertEquals(callbacksBefore, callbacks.get());
                    return;
                }
                SystemClock.sleep(20);
            }
            fail("canonical fetch handoff/early boundary not observed: " + state());
        }

        String outer(String turn, JSONObject semantic) throws Exception {
            JSONObject streamItem = new JSONObject().put("type", "stream-item")
                    .put("encoded_item", "data: " + semantic + "\n\n");
            return new JSONObject().put("conversation_id", CONVERSATION).put("turn_id", turn)
                    .put("payload", new JSONObject().put("payload", streamItem)).toString();
        }

        void socket(String frame) throws Exception {
            eval("window.fixtureSocket.emit(" + JSONObject.quote(frame) + ");");
        }

        void socketExpr(String expression) throws Exception {
            eval("window.fixtureSocket.emit(" + expression + ");");
        }

        JSONObject diag() throws Exception {
            return new JSONObject(text("JSON.stringify(window.__selfRunWorkTurnProtocolIngress.diagnostics())"));
        }

        JSONObject state() throws Exception {
            return new JSONObject(text("JSON.stringify(window.__selfRunTurnProtocol.snapshot())"));
        }

        void phase(String expected) throws Exception {
            long deadline = SystemClock.uptimeMillis() + 5000;
            JSONObject last = null;
            while (SystemClock.uptimeMillis() < deadline) {
                last = state();
                if (expected.equals(last.getString("phase"))) return;
                SystemClock.sleep(20);
            }
            fail("expected phase=" + expected + "; actual=" + last);
        }

        void callbacks(int expected) throws Exception {
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (callbacks.get() != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20);
            assertEquals(expected, callbacks.get());
        }

        String text(String script) throws Exception {
            return String.valueOf(new JSONTokener(eval(script)).nextValue());
        }

        String eval(String script) throws Exception {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>();
            scenario.onActivity(activity -> web.get().evaluateJavascript(script, value -> {
                result.set(value);
                done.countDown();
            }));
            assertTrue("script result", done.await(15, TimeUnit.SECONDS));
            return result.get();
        }

        @Override public void close() {
            scenario.close();
        }
    }
}
