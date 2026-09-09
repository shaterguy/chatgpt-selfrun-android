package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

/** Pins the single active SelfRun 3 task lineage. */
final class SelfRun3RunMarker {
    private static final String PREFS = "selfrun3_run_marker";
    private static final String RUN_ID = "runId";
    private static final String CONTRACT = "contract";

    static boolean mark(Context context, String runId) {
        if (context == null || !SelfRun3Engine.validId(runId)) return false;
        return prefs(context).edit().putString(RUN_ID, runId)
                .putString(CONTRACT, SelfRun3Protocol.CONTRACT_VERSION).commit();
    }

    static boolean current(Context context, String runId) {
        if (context == null || runId == null || runId.isEmpty()) return false;
        SharedPreferences p = prefs(context);
        return runId.equals(p.getString(RUN_ID, ""))
                && SelfRun3Protocol.CONTRACT_VERSION.equals(p.getString(CONTRACT, ""));
    }

    static void clearIfCurrent(Context context, String runId) {
        if (current(context, runId)) prefs(context).edit().clear().commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private SelfRun3RunMarker() {}
}
