package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.Intent;
import android.os.Process;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.List;

final class SelfRunAc06RecoveryRunner {
    private SelfRunAc06RecoveryRunner() {}

    static JSONObject processSeed(Activity activity) throws Exception {
        SelfRunStore store = SelfRunAc06Support.seed(activity, SelfRunAc06Support.PROCESS_RUN_ID, true);
        store.setRole("VERIFIER");
        store.setPendingModel("terra");
        store.setPendingReasoning("xhigh");
        store.setTurn(6);
        store.setAssistantBaselineKey("ac06-baseline");
        store.setLastSignal("[SELF_RUN_USER_ACTION_REQUIRED " + SelfRunAc06Support.PROCESS_RUN_ID + " PROCESS_TEST]");
        SelfRunAc06Support.settle();
        SelfRunAc06Counter.reset();
        SelfRunAc06Support.invokePause(store, "process-boundary");
        JSONObject out = SelfRunAc06Support.stateSnapshot(store);
        out.put("pid", Process.myPid());
        out.put("counters", SelfRunAc06Counter.snapshot());
        return out;
    }

    static JSONObject processVerify(Activity activity) throws Exception {
        SelfRunAc06Counter.reset();
        SelfRunStore store = new SelfRunStore(activity);
        boolean recovered = SelfRunAc06Support.PROCESS_RUN_ID.equals(store.runId())
                && SelfRunStore.MODE_CHAT.equals(store.mode())
                && SelfRunAc06Support.CONVERSATION_URL.equals(store.conversationUrl())
                && SelfRunStore.PHASE_PAUSED.equals(store.phase())
                && SelfRunStore.PHASE_SEND_CONTINUE.equals(store.pauseResumePhase())
                && store.paused()
                && "VERIFIER".equals(store.role())
                && "terra".equals(store.pendingModel())
                && "xhigh".equals(store.pendingReasoning())
                && store.turn() == 6
                && "ac06-baseline".equals(store.assistantBaselineKey())
                && store.lastSignal().startsWith("[SELF_RUN_USER_ACTION_REQUIRED " + SelfRunAc06Support.PROCESS_RUN_ID);
        JSONObject history = new SelfRunHistoryStore(activity).get(SelfRunAc06Support.PROCESS_RUN_ID);
        boolean historyRecovered = history != null
                && SelfRunStore.PHASE_PAUSED.equals(history.optString("phase"))
                && history.optBoolean("paused")
                && SelfRunAc06Support.CONVERSATION_URL.equals(history.optString("conversationUrl"));
        SelfRunAc06Support.startSelfRunService(activity);
        boolean serviceStarted = SelfRunAc06Support.waitFor(() -> SelfRunAc06Bridge.service != null, 8_000L);
        SelfRunStore serviceStore = SelfRunAc06Bridge.store();
        boolean serviceRecovered = serviceStarted && serviceStore != null
                && SelfRunAc06Support.PROCESS_RUN_ID.equals(serviceStore.runId())
                && SelfRunStore.PHASE_PAUSED.equals(serviceStore.phase())
                && serviceStore.paused()
                && SelfRunAc06Support.CONVERSATION_URL.equals(serviceStore.conversationUrl());
        Thread.sleep(800L);
        JSONObject out = SelfRunAc06Support.stateSnapshot(store);
        out.put("pid", Process.myPid());
        out.put("recovered", recovered);
        out.put("history_recovered", historyRecovered);
        out.put("service_started", serviceStarted);
        out.put("service_recovered", serviceRecovered);
        out.put("counters", SelfRunAc06Counter.snapshot());
        return out;
    }

    static JSONObject rendererRecovery(Activity activity) throws Exception {
        String runId = "AC06-RENDERER";
        SelfRunAc06Support.seed(activity, runId, false);
        SelfRunAc06Support.settle();
        SelfRunAc06Support.startSelfRunService(activity);
        SelfRunAc06Support.require(SelfRunAc06Support.waitFor(() -> SelfRunAc06Bridge.service != null, 8_000L),
                "service_start_timeout");
        SelfRunAc06Support.require(SelfRunAc06Support.waitFor(() -> SelfRunAc06Bridge.webView() != null, 10_000L),
                "webview_start_timeout");
        WebView oldView = SelfRunAc06Bridge.webView();
        int oldId = System.identityHashCode(oldView);
        int oldGeneration = SelfRunAc06Bridge.generation();
        try { SelfRunAc06Support.evaluate(activity, oldView, "1"); } catch (Throwable ignored) {}
        SelfRunAc06Counter.reset();
        SelfRunAc06Support.onMain(activity, () -> {
            oldView.loadUrl("chrome://crash");
            return null;
        });
        boolean replaced = SelfRunAc06Support.waitFor(() -> {
            WebView current = SelfRunAc06Bridge.webView();
            return current != null && System.identityHashCode(current) != oldId;
        }, 20_000L);
        Thread.sleep(3_500L);
        SelfRunRunLog inspection = new SelfRunRunLog(activity);
        List<String> lines = inspection.readDebug(runId, 2_000);
        int rendererGone = SelfRunAc06Support.countEvent(lines, "RENDERER_GONE");
        int launches = SelfRunAc06Support.countEvent(lines, "WEBVIEW_LAUNCH");
        int newId = SelfRunAc06Bridge.webView() == null ? -1 : System.identityHashCode(SelfRunAc06Bridge.webView());
        int newGeneration = SelfRunAc06Bridge.generation();
        JSONObject out = new JSONObject();
        out.put("pid", Process.myPid());
        out.put("renderer_recovered", replaced && rendererGone >= 1 && newId != oldId);
        out.put("renderer_gone_logged", rendererGone >= 1);
        out.put("old_webview_id", oldId);
        out.put("new_webview_id", newId);
        out.put("old_generation", oldGeneration);
        out.put("new_generation", newGeneration);
        out.put("renderer_gone_events", rendererGone);
        out.put("webview_launch_events", launches);
        out.put("log_file_lines", lines.size());
        out.put("counters", SelfRunAc06Counter.snapshot());
        activity.stopService(new Intent(activity, SelfRunService.class));
        return out;
    }
}
