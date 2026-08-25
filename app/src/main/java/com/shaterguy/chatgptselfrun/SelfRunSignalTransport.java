package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Durable marker for runs whose turn document is an immutable requirement record, not a signal log. */
final class SelfRunSignalTransport {
    private static final String PREFS = "selfrun_drive_signal_transport";
    private static final String KEY_RUNS = "signalDocumentRuns";

    private SelfRunSignalTransport() {}

    static boolean mark(Context context, String runId) {
        if (context == null || !SelfRunProtocolRules.validRunId(runId)) return false;
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        HashSet<String> next = new HashSet<>(prefs.getStringSet(KEY_RUNS, java.util.Collections.emptySet()));
        next.add(runId);
        return prefs.edit().putStringSet(KEY_RUNS, next).commit();
    }

    static boolean isSignalDocumentRun(Context context, String runId) {
        if (context == null || runId == null || runId.isEmpty()) return false;
        Set<String> runs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_RUNS, java.util.Collections.emptySet());
        return runs != null && runs.contains(runId);
    }
}
