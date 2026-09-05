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

    @Test public void chatStringSocketInheritsOuterContextAndCompletesOnceAfterEarlyBoundary() throws Exception {
        try (Fixture f = new Fixture()) {
            f.start("turn-a");
            assertEquals("true", f.text("String(window.__selfRunWorkTurnProtocolIngress.handlesTransport())"));

            f.socketString(f.outer("turn-a", marker()));
            f.eventually("ANSWERING");
            assertEquals(0, f.callbacks.get());

            f.socketString(f.outer("turn-a", complete()));
            f.eventually("COMPLETE");
            f.awaitCallbacks(1);

            f.socketString(f.outer("turn-a", complete()));
            SystemClock.sleep(100);
            assertEquals(1, f.callbacks.get());
            assertEquals("1", f.text("String(window.fixtureLogs.filter(x=>x.stage==='completion_dispatch').length)"));
        }
    }

    @Test public void chatBlobArrayBufferAndViewSocketFramesDecode() throws Exception {
        try (Fixture f = new Fixture()) {
            f.start("turn-binary");
            f.socketExpression("new Blob([" + JSONObject.quote(f.outer("turn-binary", marker())) + "])" );
            f.eventually("ANSWERING");
            f.socketExpression("new TextEncoder().encode(" + JSONObject.quote(f.outer("turn-binary", complete())) + ").buffer");
            f.eventually("COMPLETE");
            f.awaitCallbacks(1);
            assertTrue(f.diagnostics().getInt("binaryDecoded") >= 2);

            f.startNewBoundTurn("turn-view");
            f.socketExpression("new TextEncoder().encode(" + JSONObject.quote(f.outer("turn-view", marker())) + ")");
            f.eventually("ANSWERING");
            f.socketString(f.outer("turn-view", complete()));
            f.eventually("COMPLETE");
            f.awaitCallbacks(2);
            assertTrue(f.diagnostics().getInt("binaryDecoded") >= 3);
        }
    }

    @Test public void chatWorkerAndSharedWorkerUseSharedDecoder() throws Exception {
        try (Fixture f = new Fixture()) {
            f.start("turn-worker");
            f.newWorker();
            f.workerString(f.outer("turn-worker", marker()));
            f.eventually("ANSWERING");
            f.workerString(f.outer("turn-worker", complete()));
            f.eventually("COMPLETE");
            f.awaitCallbacks(1);
            assertTrue(f.diagnostics().getInt("workerMessages") >= 2);

            f.startNewBoundTurn("turn-shared");
            f.newSharedWorker();
            f.sharedWorkerString(f.outer("turn-shared", marker()));
            f.eventually("ANSWERING");
            f.sharedWorkerString(f.outer("turn-shared", complete()));
            f.eventually("COMPLETE");
            f.awaitCallbacks(2);
            assertTrue(f.diagnostics().getInt("sharedWorkerMessages") >= 2);
        }
    }

    @Test public void chatServiceWorkerAndTransferredPortUsePassiveSharedIngress() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals("true", f.text("String(!!window.fixtureServiceWorker&&typeof window.fixtureServiceWorker.dispatchEvent==='function')"));
            f.start("turn-sw");
            f.serviceWorkerWithPort(f.outer("turn-sw", marker()));
            f.eventually("ANSWERING");
            f.serviceWorkerPortString(f.outer("turn-sw", complete()));
            f.eventually("COMPLETE");
            f.awaitCallbacks(1);
            JSONObject d = f.diagnostics();
            assertTrue(d.getInt("serviceWorkerMessages") >= 1);
            assertTrue(d.getInt("serviceWorkerPortMessages") >= 1);
        }
    }

    @Test public void staleOuterContextIsRejectedAndChatIdleDoesNotClaimTransport() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals("false", f.text("String(window.__selfRunWorkTurnProtocolIngress.handlesTransport())"));
            f.socketString(f.outer("idle-turn", marker()));
            assertEquals("IDLE", f.state().getString("phase"));

            f.start("old-turn");
            String oldRequest = f.state().getString("requestIdentity");
            f.post("current-turn");
            assertNotEquals(oldRequest, f.state().getString("requestIdentity"));

            f.socketString(f.outer("old-turn", marker()));
            f.socketString(f.outer("old-turn", complete()));
            SystemClock.sleep(100);
            assertEquals("THINKING", f.state().getString("phase"));
            assertEquals(0, f.callbacks.get());

            f.socketString(f.outer("current-turn", marker()));
            f.eventually("ANSWERING");
            f.socketString(f.outer("current-turn", complete()));
            f.eventually("COMPLETE");
            f.awaitCallbacks(1);
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
                        "<!doctype html><html><body>chat transport ingress fixture</body></html>",
                        "text/html", "UTF-8", null);
            });
            assertTrue("fixture load", loaded.await(15, TimeUnit.SECONDS));
            eval("sessionStorage.clear();window.fixtureLogs=[];window.selfRunTurnLog={postMessage:x=>window.fixtureLogs.push(JSON.parse(x))};"
                    + "window.__selfRunRequestProfileEngine={target:()=>({runId:'fixture-run',mode:'chat'})};"
                    + "window.fixtureResponse='';window.fixtureSocket=null;window.fixtureWorker=null;window.fixtureSharedWorker=null;"
                    + "class FWS extends EventTarget{constructor(url){super();this.url=url;window.fixtureSocket=this;}emit(data){this.dispatchEvent(new MessageEvent('message',{data:data}));}}"
                    + "FWS.CONNECTING=0;FWS.OPEN=1;FWS.CLOSING=2;FWS.CLOSED=3;window.WebSocket=FWS;"
                    + "class FW extends EventTarget{constructor(url){super();this.url=url;window.fixtureWorker=this;}emit(data){this.dispatchEvent(new MessageEvent('message',{data:data}));}}window.Worker=FW;"
                    + "class FP extends EventTarget{start(){}emit(data){this.dispatchEvent(new MessageEvent('message',{data:data}));}}"
                    + "class FSW{constructor(url){this.url=url;this.port=new FP();window.fixtureSharedWorker=this;}}window.SharedWorker=FSW;"
                    + "if(!navigator.serviceWorker){try{Object.defineProperty(navigator,'serviceWorker',{value:new EventTarget(),configurable:true});}catch(_){}}"
                    + "window.fixtureServiceWorker=navigator.serviceWorker;window.fixtureServicePort=null;window.fixtureServicePeer=null;"
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

        void startNewBoundTurn(String turn) throws Exception {
            assertEquals("COMPLETE", state().getString("phase"));
            assertEquals("true", text("String(window.__selfRunTurnProtocol.bindTurn('fixture-run','fixture-token-" + turn + "'))"));
            assertEquals("true", text("String(window.__selfRunTurnProtocol.armCompletion('fixture-run','fixture-token-" + turn + "'))"));
            post(turn);
        }

        void post(String turn) throws Exception {
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
                    assertEquals(0, callbacks.get());
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

        void socketString(String frame) throws Exception {
            eval("window.fixtureSocket.emit(" + JSONObject.quote(frame) + ");");
        }

        void socketExpression(String expression) throws Exception {
            eval("window.fixtureSocket.emit(" + expression + ");");
        }

        void newWorker() throws Exception {
            eval("new Worker('fixture-worker.js');");
        }

        void workerString(String frame) throws Exception {
            eval("window.fixtureWorker.emit(" + JSONObject.quote(frame) + ");");
        }

        void newSharedWorker() throws Exception {
            eval("new SharedWorker('fixture-shared-worker.js');");
        }

        void sharedWorkerString(String frame) throws Exception {
            eval("window.fixtureSharedWorker.port.emit(" + JSONObject.quote(frame) + ");");
        }

        void serviceWorkerWithPort(String frame) throws Exception {
            eval("(()=>{const c=new MessageChannel();window.fixtureServicePort=c.port1;window.fixtureServicePeer=c.port2;"
                    + "window.fixtureServiceWorker.dispatchEvent(new MessageEvent('message',{data:"
                    + JSONObject.quote(frame) + ",ports:[c.port1]}));})()");
        }

        void serviceWorkerPortString(String frame) throws Exception {
            eval("window.fixtureServicePeer.postMessage(" + JSONObject.quote(frame) + ");");
        }

        JSONObject diagnostics() throws Exception {
            return new JSONObject(text("JSON.stringify(window.__selfRunWorkTurnProtocolIngress.diagnostics())"));
        }

        JSONObject state() throws Exception {
            return new JSONObject(text("JSON.stringify(window.__selfRunTurnProtocol.snapshot())"));
        }

        void eventually(String expected) throws Exception {
            long deadline = SystemClock.uptimeMillis() + 5000;
            JSONObject last = null;
            while (SystemClock.uptimeMillis() < deadline) {
                last = state();
                if (expected.equals(last.getString("phase"))) return;
                SystemClock.sleep(20);
            }
            fail("expected phase=" + expected + "; actual=" + last);
        }

        void awaitCallbacks(int count) throws Exception {
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (callbacks.get() != count && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20);
            assertEquals(count, callbacks.get());
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
