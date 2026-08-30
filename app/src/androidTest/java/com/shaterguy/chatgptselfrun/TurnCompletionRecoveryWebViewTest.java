package com.shaterguy.chatgptselfrun;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** WebView regressions for local STOP divergence and safe server-state resynchronization. */
@RunWith(AndroidJUnit4.class)
public final class TurnCompletionRecoveryWebViewTest {
    private static final String CONVERSATION_URL =
  "https://chatgpt.com/g/g-p-test/c/conversation123";

    @Test public void stopWithoutANewUserTurnCannotConfirmContinuationSubmission() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
  Fixture loaded = loadFixture(scenario, fixture(false));
  String markerId = "stale-stop-marker";
  String markerKey = "selfrun-drive:verified-continuation:" + markerId;
  assertEquals("ok", read(scenario, loaded.web,
          "(()=>{localStorage.setItem(" + SelfRunScript.quote(markerKey)
                  + ",JSON.stringify({state:'clicked',baselineUserCount:1,clickedAt:1}));return 'ok';})()"));
  JSONObject pending = evaluate(scenario, loaded.web,
          SelfRunContinuationDom.prepareDriveTurn(CONVERSATION_URL, "CONTINUE", markerId));
  assertEquals(SelfRunContinuationDom.SUBMISSION_PENDING, pending.getString("status"));
  assertTrue(pending.getString("detail").contains("stopOnly=1"));
  assertEquals("2", read(scenario, loaded.web,
          "(()=>{const e=document.createElement('div');e.setAttribute('data-message-author-role','user');e.textContent='second user';document.getElementById('turns').appendChild(e);return String(document.querySelectorAll('[data-message-author-role=user]').length);})()"));
  JSONObject confirmed = evaluate(scenario, loaded.web,
          SelfRunContinuationDom.prepareDriveTurn(CONVERSATION_URL, "CONTINUE", markerId));
  assertEquals("SUBMISSION_CONFIRMED", confirmed.getString("status"));
        }
    }

    @Test public void staticAssistantWithStopRequestsResyncAndNeverCompletes() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
  Fixture loaded = loadFixture(scenario, fixture(true));
  JSONObject armed = evaluate(scenario, loaded.web,
          SelfRunContinuationDom.observeTurnCompletion(
                  CONVERSATION_URL, "SR-RESYNC", "resync-token", 100L, true, 250L));
  assertEquals("OBSERVER_ARMED", armed.getString("status"));
  assertTrue("same-conversation resync callback not observed",
          awaitCallback(loaded.callbacks, SelfRunContinuationDom.TURN_RESYNC_HOST, 4_000L));
  assertFalse(hasCallback(loaded.callbacks, SelfRunContinuationDom.TURN_COMPLETION_HOST));
  assertEquals("1", read(scenario, loaded.web,
          "String(document.querySelectorAll('[data-testid=stop-button]').length)"));
        }
    }

    @Test public void completionRequiresTheResynchronizedWebViewToBecomeIdle() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
  Fixture loaded = loadFixture(scenario, fixture(true));
  evaluate(scenario, loaded.web,
          SelfRunContinuationDom.observeTurnCompletion(
                  CONVERSATION_URL, "SR-IDLE", "idle-token", 100L, true, 200L));
  assertTrue(awaitCallback(loaded.callbacks, SelfRunContinuationDom.TURN_RESYNC_HOST, 4_000L));
  assertEquals("idle", read(scenario, loaded.web,
          "(()=>{document.querySelector('[data-testid=stop-button]').remove();const b=document.createElement('button');b.type='submit';b.setAttribute('data-testid','send-button');b.setAttribute('aria-label','Send');b.textContent='send';document.querySelector('form').appendChild(b);return 'idle';})()"));
  JSONObject rearmed = evaluate(scenario, loaded.web,
          SelfRunContinuationDom.observeTurnCompletion(
                  CONVERSATION_URL, "SR-IDLE", "idle-token", 100L, true, 2_000L));
  assertEquals("OBSERVER_ARMED", rearmed.getString("status"));
  assertTrue("completion callback not observed after internal idle",
          awaitCallback(loaded.callbacks, SelfRunContinuationDom.TURN_COMPLETION_HOST, 4_000L));
        }
    }

    @Test public void assistantProgressFingerprintChangesWithoutExposingAnswerText() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
  Fixture loaded = loadFixture(scenario, fixture(true));
  JSONObject first = evaluate(scenario, loaded.web,
          SelfRunContinuationDom.observeTurnCompletion(
                  CONVERSATION_URL, "SR-PROGRESS", "progress-token", 5_000L, true, 10_000L));
  assertEquals("OBSERVER_ARMED", first.getString("status"));
  String before = read(scenario, loaded.web,
          "window.__selfRunDriveTurnObserver.progressSignature");
  assertEquals("updated", read(scenario, loaded.web,
          "(()=>{document.querySelector('[data-message-author-role=assistant]').textContent='changed assistant output';return 'updated';})()"));
  Thread.sleep(150L);
  String after = read(scenario, loaded.web,
          "window.__selfRunDriveTurnObserver.progressSignature");
  assertFalse(before.equals(after));
  assertFalse(first.toString().contains("initial assistant output"));
  assertFalse(first.toString().contains("changed assistant output"));
        }
    }

    private static Fixture loadFixture(ActivityScenario<SelfRunNewActivity> scenario, String html) throws Exception {
        AtomicReference<WebView> web = new AtomicReference<>();
        ConcurrentLinkedQueue<String> callbacks = new ConcurrentLinkedQueue<>();
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
  WebView view = new WebView(activity);
  view.getSettings().setJavaScriptEnabled(true);
  view.getSettings().setDomStorageEnabled(true);
  view.setWebViewClient(new WebViewClient() {
      @Override public void onPageFinished(WebView ignored, String url) {
          if (url != null && url.startsWith("https://")) loaded.countDown();
      }
      @Override public boolean shouldOverrideUrlLoading(WebView ignored, WebResourceRequest request) {
          return capture(callbacks, request.getUrl());
      }
      @SuppressWarnings("deprecation")
      @Override public boolean shouldOverrideUrlLoading(WebView ignored, String url) {
          return capture(callbacks, Uri.parse(url));
      }
  });
  activity.setContentView(view);
  web.set(view);
  view.loadDataWithBaseURL(CONVERSATION_URL, html, "text/html", "UTF-8", null);
        });
        assertTrue("fixture did not load", loaded.await(15, TimeUnit.SECONDS));
        return new Fixture(web, callbacks);
    }

    private static boolean capture(ConcurrentLinkedQueue<String> callbacks, Uri uri) {
        if (SelfRunContinuationDom.TURN_COMPLETION_SCHEME.equals(uri.getScheme())) {
  callbacks.add(uri.toString());
  return true;
        }
        return false;
    }

    private static JSONObject evaluate(ActivityScenario<SelfRunNewActivity> scenario,
                             AtomicReference<WebView> web, String script) throws Exception {
        return new JSONObject(read(scenario, web, script));
    }

    private static String read(ActivityScenario<SelfRunNewActivity> scenario,
                     AtomicReference<WebView> web, String expression) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<String> raw = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(expression, value -> {
  raw.set(value); complete.countDown();
        }));
        assertTrue("WebView read timed out", complete.await(15, TimeUnit.SECONDS));
        return String.valueOf(new JSONTokener(raw.get()).nextValue());
    }

    private static boolean hasCallback(ConcurrentLinkedQueue<String> callbacks, String host) {
        for (String raw : callbacks) if (host.equals(Uri.parse(raw).getHost())) return true;
        return false;
    }

    private static boolean awaitCallback(ConcurrentLinkedQueue<String> callbacks, String host,
                               long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
  if (hasCallback(callbacks, host)) return true;
  Thread.sleep(40L);
        }
        return hasCallback(callbacks, host);
    }

    private static String fixture(boolean withAssistant) {
        String assistant = withAssistant
      ? "<article><div data-message-author-role='assistant'>initial assistant output</div></article>"
      : "";
        return "<!doctype html><html><body><main>"
      + "<div id='turns'><div data-message-author-role='user'>first user</div>" + assistant + "</div>"
      + "<form data-type='unified-composer'>"
      + "<div id='prompt-textarea' contenteditable='true' data-lexical-editor='true'><p><br></p></div>"
      + "<button type='button' data-testid='stop-button' aria-label='Stop generating'>stop</button>"
      + "</form></main></body></html>";
    }

    private record Fixture(AtomicReference<WebView> web,
                 ConcurrentLinkedQueue<String> callbacks) {}
}
