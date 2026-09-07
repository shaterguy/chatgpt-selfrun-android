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
        SharedPreferences p = prefs(context);
        if (runId == null || !runId.equals(p.getString(RUN_ID, ""))) return new Snapshot("", 0L);
        return new Snapshot(p.getString(TEXT, ""), p.getLong(REVISION, 0L));
    }

    static boolean consumeIfRevision(Context context, String runId, long revision) {
        SharedPreferences p = prefs(context);
        if (runId == null || !runId.equals(p.getString(RUN_ID, ""))) return true;
        if (p.getLong(REVISION, 0L) != revision) return false;
        return p.edit().putString(TEXT, "").putLong(REVISION, revision + 1L).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private SelfRun3UserInput() {}
}
