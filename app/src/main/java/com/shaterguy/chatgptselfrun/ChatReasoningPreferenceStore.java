package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

/** Durable run-scoped selection for the one-tap Chat reasoning slider. */
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

    private ChatReasoningPreferenceStore() {}

    static void initialize(Context context) {
        if (context == null || preferences != null) return;
        synchronized (ChatReasoningPreferenceStore.class) {
            if (preferences == null) {
                preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            }
        }
    }

    static boolean save(Context context, String runId, String selection) {
        initialize(context);
        SharedPreferences current = preferences;
        if (current == null || runId == null || runId.isEmpty()) return false;
        return current.edit().putString(KEY_RUN_ID, runId)
                .putString(KEY_SELECTION, normalize(selection)).commit();
    }

    static String selectionForRun(String runId) {
        SharedPreferences current = preferences;
        if (current == null || runId == null || !runId.equals(current.getString(KEY_RUN_ID, ""))) {
            return KEEP;
        }
        return normalize(current.getString(KEY_SELECTION, KEEP));
    }

    static String summary(Context context, String runId, String phase, String lastErrorCode) {
        initialize(context);
        SharedPreferences current = preferences;
        if (current == null || runId == null || runId.isEmpty()
                || !runId.equals(current.getString(KEY_RUN_ID, ""))) {
            return "요청값 기록 없음";
        }
        String selection = normalize(current.getString(KEY_SELECTION, KEEP));
        if (KEEP.equals(selection)) return "현재 Chat 설정 유지";
        String requested = label(selection);
        if (reasoningFailure(lastErrorCode)) {
            return "요청: " + requested + " / 적용: 실패 / 확인: -";
        }
        if (SelfRunStore.PHASE_BOOTSTRAP.equals(phase)) {
            return "요청: " + requested + " / 적용: 진행 중 / 확인: -";
        }
        if (reasoningVerifiedPhase(phase)) {
            return "요청: " + requested + " / 적용: 확인 완료 / 확인: " + requested;
        }
        return "요청: " + requested + " / 적용: 대기 / 확인: -";
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

    private static boolean reasoningFailure(String code) {
        if (code == null) return false;
        return code.startsWith("CHAT_REASONING_") || code.startsWith("CHAT_BOOTSTRAP_");
    }

    private static boolean reasoningVerifiedPhase(String phase) {
        if (phase == null || phase.isEmpty()) return false;
        if (SelfRunStore.PHASE_BOOTSTRAP.equals(phase)
                || SelfRunStore.PHASE_JOB_ID_CREATE.equals(phase)
                || phase.startsWith("DRIVE_")) return false;
        return true;
    }
}
