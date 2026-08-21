package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

/** Durable current-run selection plus per-run bootstrap evidence. */
final class ChatReasoningPreferenceStore {
    static final String KEEP = "keep";
    static final String INSTANT = "instant";
    static final String MEDIUM = "medium";
    static final String HIGH = "high";
    static final String EXTRA_HIGH = "xhigh";
    static final String PRO = "pro";

    private static final String PREFS = "selfrun_drive_chat_reasoning";
    private static final String KEY_RUN_ID = "runId";
    private static final String KEY_SELECTION = "selection";
    private static volatile SharedPreferences preferences;
    private static volatile Context appContext;

    private ChatReasoningPreferenceStore() {}

    static void initialize(Context context) {
        if (context == null || (preferences != null && appContext != null)) return;
        synchronized (ChatReasoningPreferenceStore.class) {
  if (appContext == null) appContext = context.getApplicationContext();
  if (preferences == null) preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    static boolean save(Context context, String runId, String selection) {
        initialize(context);
        SharedPreferences current = preferences;
        if (current == null || runId == null || runId.isEmpty()) return false;
        String normalized = normalize(selection);
        if (!BootstrapRunStateStore.startRun(context, runId, normalized)) return false;
        return current.edit().putString(KEY_RUN_ID, runId)
      .putString(KEY_SELECTION, normalized).commit();
    }

    static String selectionForRun(String runId) {
        Context context = appContext;
        if (context != null) {
  String durable = BootstrapRunStateStore.requested(context, runId);
  if (!durable.isEmpty()) return normalize(durable);
        }
        SharedPreferences current = preferences;
        if (current == null || runId == null || !runId.equals(current.getString(KEY_RUN_ID, ""))) {
  return KEEP;
        }
        return normalize(current.getString(KEY_SELECTION, KEEP));
    }

    static String summary(Context context, String runId, String phase, String lastErrorCode) {
        initialize(context);
        return BootstrapRunStateStore.summary(context, runId);
    }

    static boolean shouldApply(String selection) {
        return !KEEP.equals(normalize(selection));
    }

    static int ordinal(String selection) {
        return switch (normalize(selection)) {
  case INSTANT -> 0;
  case MEDIUM -> 1;
  case HIGH -> 2;
  case EXTRA_HIGH -> 3;
  case PRO -> 4;
  default -> -1;
        };
    }

    static String label(String selection) {
        return switch (normalize(selection)) {
  case INSTANT -> "Instant";
  case MEDIUM -> "Medium";
  case HIGH -> "High";
  case EXTRA_HIGH -> "Extra High";
  case PRO -> "Pro";
  default -> "현재 Chat 설정 유지";
        };
    }

    static String normalize(String selection) {
        if (selection == null) return KEEP;
        return switch (selection) {
  case INSTANT, MEDIUM, HIGH, EXTRA_HIGH, PRO -> selection;
  default -> KEEP;
        };
    }
}
