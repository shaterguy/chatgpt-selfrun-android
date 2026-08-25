package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

/** Durable per-run readback of the effective Chat model-picker selection. */
final class ChatPickerStateStore {
    private static final String PREFS = "selfrun_drive_chat_picker_state";
    private static final String PREFIX = "run:";

    private ChatPickerStateStore() {}

    static boolean saveObserved(Context context, String runId, String selection) {
        if (context == null || runId == null || runId.isEmpty()) return false;
        String normalized = ChatReasoningPreferenceStore.normalize(selection);
        if (!ChatReasoningPreferenceStore.shouldApply(normalized)) return false;
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREFIX + runId, normalized).commit();
    }

    static String observedForRun(Context context, String runId) {
        if (context == null || runId == null || runId.isEmpty()) return "";
        String stored = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREFIX + runId, "");
        String normalized = ChatReasoningPreferenceStore.normalize(stored);
        return ChatReasoningPreferenceStore.shouldApply(normalized) ? normalized : "";
    }

    static String effectiveForRun(Context context, String runId) {
        String observed = observedForRun(context, runId);
        if (!observed.isEmpty()) return observed;
        String requested = ChatReasoningPreferenceStore.selectionForRun(context, runId);
        return ChatReasoningPreferenceStore.shouldApply(requested)
                ? ChatReasoningPreferenceStore.normalize(requested) : "";
    }
}
