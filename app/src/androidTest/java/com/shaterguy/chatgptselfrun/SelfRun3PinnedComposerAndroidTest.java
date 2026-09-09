package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public final class SelfRun3PinnedComposerAndroidTest {
    private static final String URL = "https://chatgpt.com/c/selfrun-v3-pin-fixture";

    @Test public void detachedWaitCanReusePinnedComposerWhenFreshDiscoveryDisappears() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AtomicReference<HeadlessWebViewHost> hostRef = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            HeadlessWebViewHost host = HeadlessWebViewHost.create(context);
            hostRef.set(host);
            WebView web = host.webView();
            web.getSettings().setJavaScriptEnabled(true);
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) { loaded.countDown(); }
            });
            web.loadDataWithBaseURL(URL, """
                    <!doctype html><html><body><main><div id="shadow-host"></div></main>
                    <script>
                    window.protocolPhase='THINKING';
                    window.turnToken='turn-one';
                    window.__selfRunTurnProtocol={snapshot:()=>({phase:window.protocolPhase,turnToken:window.turnToken})};
                    const host=document.getElementById('shadow-host');
                    const root=host.attachShadow({mode:'open'});
                    const form=document.createElement('form');
                    const editor=document.createElement('textarea');
                    editor.id='prompt-textarea';
                    editor.setAttribute('aria-label','Message');
                    form.appendChild(editor);
                    root.appendChild(form);
                    window.formSubmitCount=0;
                    form.addEventListener('submit',event=>{event.preventDefault();window.formSubmitCount++;});
                    </script></body></html>
                    """, "text/html", "UTF-8", null);
        });

        assertTrue("pinned-composer fixture did not load", loaded.await(15, TimeUnit.SECONDS));
        HeadlessWebViewHost host = hostRef.get();
        assertNotNull(host);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertTrue(host.hasDetachableOutput());
                assertTrue(host.isOutputAttached());
                assertTrue(host.detachOutputWhenComposerReady());
            });
            waitUntilDetached(host);
            assertFalse(isOutputAttached(host));

            assertEquals("1", readPage(host.webView(),
                    "(()=>{const s=window.__selfRunV3PinnedComposer;return s?.element?.isConnected?'1':'0';})()"));

            assertEquals("0", readPage(host.webView(), """
                    (()=>{
                      const host=document.getElementById('shadow-host');
                      Object.defineProperty(host,'shadowRoot',{configurable:true,value:null});
                      window.protocolPhase='IDLE';
                      window.turnToken='turn-two';
                      return String(document.querySelectorAll('textarea,[contenteditable],[role="textbox"]').length);
                    })()
                    """));

            String freshOnlyProbe = "(()=>{const saved=window.__selfRunV3PinnedComposer;"
                    + "delete window.__selfRunV3PinnedComposer;const fresh=Boolean("
                    + SelfRun3ComposerTransport.composerReadyExpression()
                    + ");window.__selfRunV3PinnedComposer=saved;return String(fresh);})()";
            assertEquals("false", readPage(host.webView(), freshOnlyProbe));
            assertEquals("true", readPage(host.webView(), SelfRun3ComposerTransport.composerReadyExpression()));

            JSONObject clearing = new JSONObject(readPage(host.webView(),
                    SelfRun3ComposerTransport.prepareContinuation(URL, "pinned continuation")));
            assertEquals(SelfRun3ComposerTransport.COMPOSER_CLEARING, clearing.getString("status"));

            JSONObject inputting = new JSONObject(readPage(host.webView(),
                    SelfRun3ComposerTransport.prepareContinuation(URL, "pinned continuation")));
            assertEquals(SelfRun3ComposerTransport.COMPOSER_INPUTTING, inputting.getString("status"));

            JSONObject prepared = new JSONObject(readPage(host.webView(),
                    SelfRun3ComposerTransport.prepareContinuation(URL, "pinned continuation")));
            assertEquals(SelfRun3ComposerTransport.READY_TO_SUBMIT, prepared.getString("status"));
            assertEquals("pinned continuation", readPage(host.webView(),
                    "String(window.__selfRunV3PinnedComposer.element.value)"));

            JSONObject submitted = new JSONObject(readPage(host.webView(),
                    SelfRun3ComposerTransport.submitContinuation(URL, "pinned continuation")));
            assertEquals(SelfRun3ComposerTransport.SUBMISSION_PENDING, submitted.getString("status"));
            assertEquals("1", readPage(host.webView(), "String(window.formSubmitCount)"));
            assertFalse("continuation input must not reattach the rendering surface", isOutputAttached(host));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(host::destroy);
        }
    }

    private static void waitUntilDetached(HeadlessWebViewHost host) throws Exception {
        long deadline = System.currentTimeMillis() + 6_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!isOutputAttached(host)) return;
            Thread.sleep(100L);
        }
        fail("generation surface did not detach after composer pin");
    }

    private static boolean isOutputAttached(HeadlessWebViewHost host) {
        AtomicReference<Boolean> attached = new AtomicReference<>(true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> attached.set(host.isOutputAttached()));
        return attached.get();
    }

    private static String readPage(WebView view, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> raw = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                view.evaluateJavascript(script, value -> { raw.set(value); done.countDown(); }));
        assertTrue("WebView evaluation timed out", done.await(15, TimeUnit.SECONDS));
        assertNotNull(raw.get());
        Object decoded = new JSONTokener(raw.get()).nextValue();
        return String.valueOf(decoded);
    }
}
