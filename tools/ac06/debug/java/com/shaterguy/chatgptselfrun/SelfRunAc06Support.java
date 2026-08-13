package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class SelfRunAc06Support {
    static final String PROJECT_URL = "https://chatgpt.com/g/g-p-ac06/project";
    static final String CONVERSATION_URL = "https://chatgpt.com/c/ac06-evidence";
    static final String PROCESS_RUN_ID = "AC06-PROCESS";
    static final long SETTLE_MS = 750L;

    private SelfRunAc06Support() {}

    static SelfRunStore seed(Activity activity, String runId, boolean withConversation) throws Exception {
        SelfRunStore store = new SelfRunStore(activity);
        store.clear();
        store.start(runId, SelfRunStore.MODE_CHAT, PROJECT_URL, "ac06-evidence");
        if (withConversation) store.setConversationUrl(CONVERSATION_URL);
        store.setRole("BUILDER");
        store.setPendingModel("sol");
        store.setPendingReasoning("xhigh");
        store.setLastSignal("[SELF_RUN_NEXT " + runId + " ROLE=BUILDER]");
        store.setAssistantBaselineKey("baseline-" + runId);
        store.setPhase(SelfRunStore.PHASE_WAIT_ASSISTANT);
        store.setStatus("stable");
        return store;
    }

    static void invokePause(SelfRunStore store, String status) throws Exception {
        try {
            SelfRunStore.class.getDeclaredMethod("enterPausedState", String.class).invoke(store, status);
        } catch (NoSuchMethodException missing) {
            store.setPaused(true);
            store.setPhase(SelfRunStore.PHASE_PAUSED);
            store.setStatus(status);
        }
    }

    static void invokeResume(SelfRunStore store, String status) throws Exception {
        try {
            SelfRunStore.class.getDeclaredMethod("resumeState", String.class, String.class)
                    .invoke(store, SelfRunStore.PHASE_SEND_CONTINUE, status);
        } catch (NoSuchMethodException missing) {
            store.setPaused(false);
            store.setActive(true);
            store.setUserStopped(false);
            store.clearLastError();
            store.setLastSignal("USER_RESUME");
            store.setPhase(store.conversationUrl().isEmpty()
                    ? SelfRunStore.PHASE_BOOTSTRAP : SelfRunStore.PHASE_SEND_CONTINUE);
            store.setStatus(status);
        }
    }

    static void startSelfRunService(Activity activity) throws Exception {
        onMain(activity, () -> {
            Intent service = new Intent(activity, SelfRunService.class).setAction(SelfRunService.ACTION_RUN);
            if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(service);
            else activity.startService(service);
            return null;
        });
    }

    static JSONObject row(String name, int fileLines) throws Exception {
        JSONObject row = new JSONObject();
        row.put("name", name);
        row.put("log_file_lines", fileLines);
        row.put("counters", SelfRunAc06Counter.snapshot());
        return row;
    }

    static JSONObject stateSnapshot(SelfRunStore store) throws Exception {
        JSONObject out = new JSONObject();
        out.put("run_id", store.runId());
        out.put("mode", store.mode());
        out.put("conversation_url", store.conversationUrl());
        out.put("phase", store.phase());
        out.put("pause_resume_phase", store.pauseResumePhase());
        out.put("paused", store.paused());
        out.put("active", store.active());
        out.put("role", store.role());
        out.put("pending_model", store.pendingModel());
        out.put("pending_reasoning", store.pendingReasoning());
        out.put("turn", store.turn());
        out.put("assistant_baseline", store.assistantBaselineKey());
        out.put("last_signal", store.lastSignal());
        return out;
    }

    static int countEvent(List<String> lines, String expected) {
        int count = 0;
        for (String line : lines) {
            try {
                if (expected.equals(new JSONObject(line).optString("event"))) count++;
            } catch (Throwable ignored) {}
        }
        return count;
    }

    static String evaluate(Activity activity, WebView view, String script) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("");
        onMain(activity, () -> {
            view.evaluateJavascript(script, value -> {
                result.set(value == null ? "" : value);
                latch.countDown();
            });
            return null;
        });
        require(latch.await(10, TimeUnit.SECONDS), "javascript_timeout");
        return result.get();
    }

    static <T> T onMain(Activity activity, Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        activity.runOnUiThread(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    static boolean waitFor(Callable<Boolean> condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.call())) return true;
            Thread.sleep(100L);
        }
        return false;
    }

    static void settle() throws InterruptedException {
        Thread.sleep(SETTLE_MS);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
