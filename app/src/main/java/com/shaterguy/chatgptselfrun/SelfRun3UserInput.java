package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

/** Reads the existing user next-input store without binding V3 to V2 submission-lock semantics. */
final class SelfRun3UserInput {
    private static final String PREFS = "selfrun_drive_user_next_input";
    private static final String RUN_ID = "runId";
    private static final String TEXT = "text";
    private static final String REVISION = "revision";

    static final class Snapshot {
        final String text;
        final long revision;
        Snapshot(String text, long revision) {
            this.text = text == null ? "" : text;
            this.revision = Math.max(0L, revision);
        }
    }

    static Snapshot snapshot(Context context, String runId) {
        synchronized (UserNextInputStore.class) {
            SharedPreferences p = prefs(context);
            if (runId == null || !runId.equals(p.getString(RUN_ID, ""))) return new Snapshot("", 0L);
            return new Snapshot(p.getString(TEXT, ""), p.getLong(REVISION, 0L));
        }
    }

    static boolean consumeIfRevision(Context context, String runId, long revision) {
        synchronized (UserNextInputStore.class) {
            SharedPreferences p = prefs(context);
            if (runId == null || !runId.equals(p.getString(RUN_ID, ""))) return true;
            if (p.getLong(REVISION, 0L) != revision) return false;
            return p.edit().putString(TEXT, "").putLong(REVISION, revision + 1L).commit();
        }
    }

    static SelfRun3Engine.State commit(Context context, SelfRunStore store,
                                       SelfRun3Ledger ledger, SelfRun3Engine.State current) {
        synchronized (SelfRunStore.RUN_STATE_LOCK) {
            synchronized (UserNextInputStore.class) {
                if (!current.taskId().equals(store.runId())) throw new IllegalStateException("STALE_INPUT_TASK");
                org.json.JSONObject result = SelfRun3Engine.object(current.text("result"));
                Snapshot latest = snapshot(context, current.taskId());
                boolean lateInput = "DONE".equals(result.optString("status"))
                        && latest.revision > current.time("inputRevision") && !latest.text.isEmpty();
                org.json.JSONObject payload = new org.json.JSONObject();
                if (lateInput) {
                    SelfRun3Engine.put(payload, "lateInput", true);
                    SelfRun3Engine.put(payload, "lateInputRevision", latest.revision);
                }
                SelfRun3Engine.State after = ledger.apply(new SelfRun3Engine.Event(
                        current.turnId() + ":commit:" + result.optString("status"),
                        SelfRun3Engine.Kind.COMMIT, current.taskId(), current.turnId(), payload));
                if (!SelfRun3Engine.isBranch(current) && !"REPAIR".equals(current.text("executionKind"))
                        && current.time("inputRevision") >= 0L) {
                    if (!consumeIfRevision(context, current.taskId(), current.time("inputRevision"))
                            && snapshot(context, current.taskId()).revision == current.time("inputRevision")) {
                        throw new IllegalStateException("INPUT_CONSUME_COMMIT_FAILED");
                    }
                }
                // Same monitor as save(): an accepted revision is included above, or save sees inactive.
                if (after.stage() == SelfRun3Engine.Stage.DONE) store.setActive(false);
                return after;
            }
        }
    }


    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private SelfRun3UserInput() {}
}
