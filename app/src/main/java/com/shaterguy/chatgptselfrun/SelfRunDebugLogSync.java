package com.shaterguy.chatgptselfrun;

import android.content.Context;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fire-and-forget journal requests. No execution state, timers or service-owned executor. */
final class SelfRunDebugLogSync {
    private static final ExecutorService JOURNAL = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "selfrun-debug-journal"));

    static SelfRunDebugLogArchive archive(Context context) {
        return new SelfRunDebugLogArchive(directory(context));
    }
    private static File directory(Context context) {
        return new File(context.getApplicationContext().getNoBackupFilesDir(), "selfrun-debug-archive");
    }
    static void record(Context context, String taskId, String sanitizedLine) {
        Context app = context.getApplicationContext();
        submit(() -> archive(app).append(taskId, sanitizedLine));
    }
    static void request(Context context, SelfRun3Engine.State state, String trigger) {
        try {
            if (state == null || state.resource("folderId").isEmpty()) return;
            Context app = context.getApplicationContext();
            SelfRunDebugLogArchive.Binding binding = new SelfRunDebugLogArchive.Binding(state.taskId(),
                    state.config().optString("accountId"), state.config().optString("baseFolderId"),
                    state.resource("folderId"));
            String execution = state.turnId() + ":" + state.requestId();
            submit(() -> requestNow(app, binding, trigger, execution));
        } catch (Throwable ignored) { }
    }
    static boolean waitingReady(SelfRun3Engine.State state) {
        return state != null && !state.resource("conversationUrl").isEmpty()
                && state.flag("sendClaimed") && state.flag("dispatchObserved");
    }
    /** Captures the task before the UI can switch tasks; ledger lookup is off the UI/execution queue. */
    static void requestStored(Context context, String taskId, String trigger) {
        Context app = context.getApplicationContext();
        submit(() -> {
            SelfRunDebugLogArchive.Snapshot saved = archive(app).state(taskId);
            if (saved != null) { requestNow(app, saved.binding, trigger, ""); return; }
            try (SelfRun3Ledger ledger = new SelfRun3Ledger(app)) {
                SelfRun3Engine.State state = ledger.load(taskId);
                if (state == null || state.resource("folderId").isEmpty()) return;
                requestNow(app, new SelfRunDebugLogArchive.Binding(taskId, state.config().optString("accountId"),
                        state.config().optString("baseFolderId"), state.resource("folderId")), trigger, state.turnId());
            }
        });
    }
    static void recover(Context context) {
        Context app = context.getApplicationContext();
        submit(() -> {
            File[] states = directory(app).listFiles((dir, name) -> name.startsWith("task-") && name.endsWith(".json"));
            if (states == null) return;
            for (File state : states) {
                try {
                    String task = state.getName().substring(5, state.getName().length() - 5);
                    SelfRunDebugLogArchive.Snapshot s = archive(app).state(task);
                    if (s != null && s.requestedSequence > s.uploadedSequence)
                        SelfRunDebugLogUploadWorker.schedule(app, task);
                } catch (Throwable ignored) { }
            }
        });
    }
    private static void requestNow(Context app, SelfRunDebugLogArchive.Binding binding,
                                   String trigger, String execution) throws Exception {
        if (archive(app).request(binding, trigger, execution)) SelfRunDebugLogUploadWorker.schedule(app, binding.taskId);
    }
    private interface Operation { void run() throws Exception; }
    private static void submit(Operation operation) {
        try { JOURNAL.execute(() -> { try { operation.run(); } catch (Throwable ignored) { } }); }
        catch (Throwable ignored) { }
    }
    private SelfRunDebugLogSync() { }
}
