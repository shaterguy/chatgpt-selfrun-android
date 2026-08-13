package com.shaterguy.chatgptselfrun;

import android.net.Uri;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SelfRunAssistantObserverInstrumentationTest {
    @Test
    public void streamingCharacterDataCanEndThroughQuietCompletionProbe() throws Exception {
        String quiet = runQuietScenario(
                "<article data-message-author-role='user'>u</article>"
                        + "<article data-message-author-role='assistant' data-is-streaming='true'><span id='t'>A</span></article>",
                "document.querySelector('#t').firstChild.data='AB'",
                "document.querySelector('[data-message-author-role=assistant]').removeAttribute('data-is-streaming')",
                false);
        assertTrue(quiet, quiet.contains("\"streaming\":false"));
    }

    @Test
    public void characterDataOnlyMutationArmsQuietCompletionAfterStreamingSignalDisappearsUnobserved() throws Exception {
        String quiet = runQuietScenario(
                "<article data-message-author-role='user'>u</article>"
                        + "<article data-message-author-role='assistant'><span id='t'>A</span></article>"
                        + "<button id='stop' data-testid='stop-button'>Stop</button>",
                "document.querySelector('#stop').style.display='none';"
                        + "document.querySelector('#t').firstChild.data='AB'",
                "void 0",
                false);
        assertTrue(quiet, quiet.contains("\"streaming\":false"));
    }

    @Test
    public void childInsertionAndClassRemovalCanEndThroughQuietCompletionProbe() throws Exception {
        String quiet = runQuietScenario(
                "<article data-message-author-role='user'>u</article>"
                        + "<article data-message-author-role='assistant' aria-busy='true' class='streaming'><span>A</span></article>",
                "const a=document.querySelector('[data-message-author-role=assistant]');"
                        + "a.removeAttribute('aria-busy');a.classList.remove('streaming');"
                        + "const s=document.createElement('span');s.textContent=' done';a.appendChild(s)",
                "void 0",
                false);
        assertTrue(quiet, quiet.contains("\"streaming\":false"));
    }

    @Test
    public void quietProbeSeesCompleteEvenWhenFinalMutationIsNotDelivered() throws Exception {
        String quiet = runQuietScenario(
                "<article data-message-author-role='user'>u</article>"
                        + "<article data-message-author-role='assistant' data-is-streaming='true'><span id='t'>A</span></article>",
                "document.querySelector('#t').firstChild.data='AB'",
                "window.__chatgptSelfRunDomObserver.observer.disconnect();"
                        + "document.querySelector('[data-message-author-role=assistant]').removeAttribute('data-is-streaming')",
                true);
        assertTrue(quiet, quiet.contains("\"streaming\":false"));
    }

    @Test
    public void loadingSpinnerClassAloneDoesNotMeanStreaming() throws Exception {
        String quiet = runQuietScenario(
                "<article data-message-author-role='user'>u</article>"
                        + "<article data-message-author-role='assistant'><span id='t' class='loading spinner'>finished</span></article>",
                "document.querySelector('#t').firstChild.data='finished!'",
                "void 0",
                false);
        assertTrue(quiet, quiet.contains("\"streaming\":false"));
    }

    private String runQuietScenario(String body, String firstMutation, String secondMutation,
            boolean disconnectBeforeSecond) throws Exception {
        AtomicReference<WebView> webRef = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WebView webView = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) { loaded.countDown(); }
            });
            webView.loadDataWithBaseURL("https://chatgpt.com/c/test", "<html><body>" + body + "</body></html>",
                    "text/html", "UTF-8", null);
            webRef.set(webView);
        });
        assertTrue("test page did not load", loaded.await(5, TimeUnit.SECONDS));
        WebView webView = webRef.get();
        assertNotNull(webView);

        String token = UUID.randomUUID().toString();
        String lease = UUID.randomUUID().toString();
        LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        CountDownLatch installed = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                webView.evaluateJavascript(SelfRunDomObserver.install(token, lease, "run", 1, 1), raw -> {
                    WebMessagePort[] channel = webView.createWebMessageChannel();
                    channel[0].setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
                        @Override public void onMessage(WebMessagePort port, WebMessage message) {
                            messages.offer(message == null ? "" : message.getData());
                        }
                    });
                    webView.postWebMessage(new WebMessage(token, new WebMessagePort[]{channel[1]}),
                            Uri.parse("https://chatgpt.com"));
                    installed.countDown();
                }));
        assertTrue("observer install callback missing", installed.await(3, TimeUnit.SECONDS));
        waitForPrefix(messages, "ready|", 3_000L);

        evaluate(webView, firstMutation);
        Thread.sleep(100L);
        if (disconnectBeforeSecond) evaluate(webView, secondMutation);
        else evaluate(webView, secondMutation);
        String quiet = waitForPrefix(messages, "quiet|", 3_000L);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(webView::destroy);
        return quiet;
    }

    private static void evaluate(WebView webView, String source) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                webView.evaluateJavascript("(() => {" + source + "; return 'OK';})()", raw -> latch.countDown()));
        assertTrue("mutation evaluation callback missing", latch.await(3, TimeUnit.SECONDS));
    }

    private static String waitForPrefix(LinkedBlockingQueue<String> messages, String prefix, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            long remaining = deadline - System.nanoTime();
            String value = messages.poll(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)), TimeUnit.MILLISECONDS);
            if (value == null) continue;
            if (value.startsWith(prefix)) return value;
        }
        throw new AssertionError("message prefix not received: " + prefix);
    }
}
