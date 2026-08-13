package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class SelfRunAc06MatrixRunner {
    private SelfRunAc06MatrixRunner() {}

    static JSONObject run(Activity activity) throws Exception {
        JSONObject out = new JSONObject();
        out.put("pid", android.os.Process.myPid());
        JSONArray scenarios = new JSONArray();
        scenarios.put(logScenario(activity, "long_response", "DOM_RESULT", 1_000, true));
        scenarios.put(logScenario(activity, "observer_state", "DOM_OBSERVER_STATE", 1_000, true));
        scenarios.put(logScenario(activity, "watchdog", "DOM_WATCHDOG_HEALTH", 1_000, true));
        scenarios.put(logScenario(activity, "stale_callback", "STALE_CALLBACK", 1_000, false));
        scenarios.put(stableNoChange(activity));
        scenarios.put(pauseScenario(activity, "user_action", "[SELF_RUN_USER_ACTION_REQUIRED AC06-PAUSE ACTION]"));
        scenarios.put(pauseScenario(activity, "protocol_pause", "[SELF_RUN_PAUSE AC06-PAUSE REASON=TEST]"));
        scenarios.put(pauseScenario(activity, "manual_pause", "USER_MANUAL"));
        scenarios.put(actualDomMutation(activity));
        scenarios.put(serviceLongResponse(activity));
        out.put("scenarios", scenarios);
        return out;
    }

    private static JSONObject logScenario(Activity activity, String name, String event,
            int count, boolean changingCounter) throws Exception {
        String runId = "AC06-" + name.toUpperCase().replace('-', '_');
        SelfRunStore store = SelfRunAc06Support.seed(activity, runId, true);
        SelfRunRunLog log = new SelfRunRunLog(activity);
        SelfRunAc06Support.settle();
        SelfRunAc06Counter.reset();
        for (int i = 0; i < count; i++) {
            String detail;
            if ("DOM_RESULT".equals(event)) detail = "phase=WAIT_ASSISTANT;status=GENERATING;count=" + i;
            else if ("DOM_OBSERVER_STATE".equals(event)) detail = "count=" + i + ";phase=WAIT_ASSISTANT";
            else if ("DOM_WATCHDOG_HEALTH".equals(event)) detail = "count=" + i + ";observer=alive;suppressed=0";
            else detail = changingCounter ? "count=" + i : "source=observer_message";
            log.record(store, event, detail);
        }
        log.record(store, "AC06_BOUNDARY", name);
        SelfRunAc06Support.settle();
        List<String> lines = log.readDebug(runId, 20_000);
        SelfRunAc06Support.settle();
        JSONObject row = SelfRunAc06Support.row(name, lines.size());
        row.put("logical_events", count);
        row.put("state_valid", SelfRunStore.PHASE_WAIT_ASSISTANT.equals(store.phase())
                && SelfRunAc06Support.CONVERSATION_URL.equals(store.conversationUrl()));
        return row;
    }

    private static JSONObject stableNoChange(Activity activity) throws Exception {
        SelfRunStore store = SelfRunAc06Support.seed(activity, "AC06-STABLE", true);
        store.setStatus("stable");
        SelfRunAc06Support.settle();
        SelfRunAc06Counter.reset();
        for (int i = 0; i < 1_000; i++) store.setStatus("stable");
        SelfRunAc06Support.settle();
        JSONObject row = SelfRunAc06Support.row("stable_no_change", 0);
        row.put("logical_events", 1_000);
        row.put("state_valid", "stable".equals(store.status()));
        return row;
    }

    private static JSONObject pauseScenario(Activity activity, String name, String signal) throws Exception {
        String runId = "AC06-PAUSE";
        SelfRunStore store = SelfRunAc06Support.seed(activity, runId, true);
        store.setLastSignal(signal);
        SelfRunAc06Support.settle();
        SelfRunRunLog log = new SelfRunRunLog(activity);
        SelfRunAc06Counter.reset();
        SelfRunAc06Support.invokePause(store, "pause:" + name);
        log.record(store, "STATE_TRANSITION", "to=PAUSED;reason=" + name);
        log.record(store, "PAUSED", name);
        SelfRunAc06Support.settle();
        SelfRunAc06Support.invokeResume(store, "resume:" + name);
        log.record(store, "UI_RESUME", name);
        log.record(store, "AC06_BOUNDARY", name);
        SelfRunAc06Support.settle();
        List<String> lines = log.readDebug(runId, 2_000);
        SelfRunAc06Support.settle();
        JSONObject row = SelfRunAc06Support.row("pause_" + name, lines.size());
        row.put("state_valid", !store.paused()
                && SelfRunStore.PHASE_SEND_CONTINUE.equals(store.phase())
                && SelfRunAc06Support.CONVERSATION_URL.equals(store.conversationUrl()));
        return row;
    }

    private static JSONObject actualDomMutation(Activity activity) throws Exception {
        String runId = "AC06-DOM-RUNTIME";
        SelfRunStore store = SelfRunAc06Support.seed(activity, runId, true);
        SelfRunRunLog log = new SelfRunRunLog(activity);
        CountDownLatch pageReady = new CountDownLatch(1);
        WebView view = SelfRunAc06Support.onMain(activity, () -> {
            WebView created = new WebView(activity);
            created.getSettings().setJavaScriptEnabled(true);
            created.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView webView, String url) { pageReady.countDown(); }
            });
            created.loadDataWithBaseURL("https://chatgpt.com/",
                    "<html><body><article data-message-author-role='assistant'><span id='t'>start</span></article></body></html>",
                    "text/html", "UTF-8", "https://chatgpt.com/");
            return created;
        });
        SelfRunAc06Support.require(pageReady.await(10, TimeUnit.SECONDS), "dom_page_timeout");

        String token = UUID.randomUUID().toString();
        String lease = "ac06:" + UUID.randomUUID();
        SelfRunAc06Support.evaluate(activity, view,
                SelfRunDomObserver.install(token, lease, runId, 1, 1));
        CountDownLatch bridgeReady = new CountDownLatch(1);
        AtomicInteger stateEvents = new AtomicInteger();
        WebMessagePort[] channel = SelfRunAc06Support.onMain(activity, view::createWebMessageChannel);
        SelfRunAc06Support.onMain(activity, () -> {
            channel[0].setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
                @Override public void onMessage(WebMessagePort port, WebMessage message) {
                    String data = message == null ? "" : message.getData();
                    if (data.startsWith("ready|")) bridgeReady.countDown();
                    else if (data.startsWith("state|")) {
                        int count = stateEvents.incrementAndGet();
                        log.record(store, "DOM_OBSERVER_STATE", "count=" + count + ";phase=" + store.phase());
                    }
                }
            });
            view.postWebMessage(new WebMessage(token, new WebMessagePort[]{channel[1]}),
                    Uri.parse("https://chatgpt.com"));
            return null;
        });
        SelfRunAc06Support.require(bridgeReady.await(10, TimeUnit.SECONDS), "dom_bridge_timeout");
        SelfRunAc06Support.settle();
        SelfRunAc06Counter.reset();
        SelfRunAc06Support.evaluate(activity, view, """
                (() => {
                  const node=document.getElementById('t');
                  const article=document.querySelector('[data-message-author-role="assistant"]');
                  article.setAttribute('data-is-streaming','true');
                  for(let i=0;i<1000;i++) node.textContent='token-'+i;
                  article.setAttribute('data-is-streaming','false');
                  return 'mutated';
                })()
                """);
        Thread.sleep(1_000L);
        log.record(store, "AC06_BOUNDARY", "actual_dom_mutation");
        SelfRunAc06Support.settle();
        List<String> lines = log.readDebug(runId, 2_000);
        try { SelfRunAc06Support.evaluate(activity, view, SelfRunDomObserver.detach(lease)); } catch (Throwable ignored) {}
        SelfRunAc06Support.onMain(activity, () -> {
            try { channel[0].close(); } catch (Throwable ignored) {}
            view.destroy();
            return null;
        });
        JSONObject row = SelfRunAc06Support.row("actual_dom_mutation", lines.size());
        row.put("logical_events", 1_000);
        row.put("dom_state_events", stateEvents.get());
        row.put("state_valid", stateEvents.get() >= 1 && stateEvents.get() <= 4);
        return row;
    }

    private static JSONObject serviceLongResponse(Activity activity) throws Exception {
        String runId = "AC06-SERVICE-DOM";
        SelfRunStore store = SelfRunAc06Support.seed(activity, runId, true);
        SelfRunAc06Support.settle();
        SelfRunAc06Counter.reset();
        SelfRunAc06Support.startSelfRunService(activity);
        SelfRunAc06Support.require(SelfRunAc06Support.waitFor(() -> SelfRunAc06Bridge.service != null, 8_000L),
                "service_dom_start_timeout");
        SelfRunAc06Support.require(SelfRunAc06Support.waitFor(() -> SelfRunAc06Bridge.webView() != null, 10_000L),
                "service_dom_webview_timeout");
        WebView serviceView = SelfRunAc06Bridge.webView();
        int generationBefore = SelfRunAc06Bridge.generation();
        SelfRunAc06Support.onMain(activity, () -> {
            serviceView.loadDataWithBaseURL(SelfRunAc06Support.CONVERSATION_URL,
                    "<html><body><article data-message-author-role='assistant' data-is-streaming='true'><span id='t'>start</span></article></body></html>",
                    "text/html", "UTF-8", SelfRunAc06Support.CONVERSATION_URL);
            return null;
        });
        boolean testPageReady = SelfRunAc06Support.waitFor(() -> SelfRunAc06Support.onMain(activity, () ->
                SelfRunAc06Bridge.generation() > generationBefore
                        && serviceView.getProgress() >= 100
                        && SelfRunAc06Support.CONVERSATION_URL.equals(serviceView.getUrl())), 12_000L);
        if (testPageReady) {
            SelfRunAc06Support.onMain(activity, () -> {
                SelfRunAc06Bridge.ensureObserverNow();
                return null;
            });
        }
        boolean observerReady = testPageReady && SelfRunAc06Support.waitFor(
                () -> SelfRunAc06Bridge.observerPort() != null, 12_000L);
        if (!observerReady) {
            String pageState = SelfRunAc06Support.onMain(activity, () ->
                    "url=" + serviceView.getUrl()
                            + ";progress=" + serviceView.getProgress()
                            + ";generation=" + SelfRunAc06Bridge.generation()
                            + ";recovery=" + SelfRunAc06Bridge.recoveryInProgress()
                            + ";install=" + SelfRunAc06Bridge.observerInstallInFlight()
                            + ";lease=" + SelfRunAc06Bridge.observerLease()
                            + ";testPageReady=" + testPageReady);
            throw new IllegalStateException("service_dom_observer_timeout:" + pageState);
        }
        long beforeEvents = Math.max(0L, SelfRunAc06Bridge.observerEventCount());
        SelfRunAc06Counter.reset();
        SelfRunAc06Support.evaluate(activity, serviceView, """
                (() => {
                  const node=document.getElementById('t');
                  const article=document.querySelector('[data-message-author-role="assistant"]');
                  if(!node||!article)return 'missing';
                  for(let i=0;i<1000;i++) node.textContent='token-'+i;
                  article.setAttribute('data-is-streaming','false');
                  node.textContent='done [SELF_RUN_NEXT AC06-SERVICE-DOM ROLE=BUILDER]';
                  return 'mutated';
                })()
                """);
        SelfRunAc06Support.require(SelfRunAc06Support.waitFor(
                () -> SelfRunAc06Bridge.observerEventCount() > beforeEvents, 8_000L),
                "service_dom_state_event_timeout");
        Thread.sleep(1_000L);
        long afterEvents = SelfRunAc06Bridge.observerEventCount();
        SelfRunRunLog inspection = new SelfRunRunLog(activity);
        List<String> lines = inspection.readDebug(runId, 4_000);
        JSONObject row = SelfRunAc06Support.row("service_long_response", lines.size());
        row.put("logical_events", 1_000);
        row.put("service_dom_state_events", Math.max(0L, afterEvents - beforeEvents));
        row.put("state_valid", store.active() && !store.paused()
                && SelfRunAc06Support.CONVERSATION_URL.equals(store.conversationUrl()));
        activity.stopService(new Intent(activity, SelfRunService.class));
        SelfRunAc06Bridge.service = null;
        Thread.sleep(500L);
        return row;
    }
}
