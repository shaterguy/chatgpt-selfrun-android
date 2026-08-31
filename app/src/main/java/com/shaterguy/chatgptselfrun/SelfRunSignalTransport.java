package com.shaterguy.chatgptselfrun;

import android.content.Context;

import java.util.HashSet;
import java.util.Set;

/** Durable per-run marker that selects one-signal-per-Google-Doc transport. */
final class SelfRunSignalTransport {
    private static final String PREFS = "selfrun_drive_signal_transport";
    private static final String KEY_RUNS = "runs";

    private SelfRunSignalTransport() {}

    static void mark(Context context, String runId) {
        String value = runId == null ? "" : runId.trim();
        if (context == null || !SelfRunProtocolRules.validRunId(value)) {
            throw new IllegalArgumentException("valid SelfRun id required for signal transport marker");
        }
        synchronized (SelfRunSignalTransport.class) {
            Set<String> current = new HashSet<>(context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getStringSet(KEY_RUNS, Set.of()));
            if (current.add(value)) {
                boolean committed = context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putStringSet(KEY_RUNS, current).commit();
                if (!committed) throw new IllegalStateException("signal transport marker persistence failed");
            }
        }
    }

    static boolean isSignalDocumentRun(Context context, String runId) {
        String value = runId == null ? "" : runId.trim();
        if (context == null || value.isEmpty()) return false;
        boolean enabled = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_RUNS, Set.of()).contains(value);
        if (enabled) DriveSignalDocumentIdentity.activate(context, value);
        return enabled;
    }
}
