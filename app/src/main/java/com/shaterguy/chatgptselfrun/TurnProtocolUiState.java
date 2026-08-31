package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

/** Small UI-facing projection of the latest active ChatGPT response state. */
final class TurnProtocolUiState {
    private static final String PREFS = "selfrun_turn_protocol_ui";
    private static final String KEY_RUN_ID = "runId";
    private static final String KEY_STAGE = "stage";
    private static final String KEY_PHASE = "phase";
    private static final String KEY_UPDATED_AT = "updatedAt";

    static final class Snapshot {
        final boolean present;
        final String stage;
        final String phase;
        final long updatedAt;

        Snapshot(boolean present, String stage, String phase, long updatedAt) {
            this.present = present;
            this.stage = safe(stage);
            this.phase = safe(phase);
            this.updatedAt = Math.max(0L, updatedAt);
        }

        String headline() { return headlineFor(stage, phase); }
    }

    private TurnProtocolUiState() {}

    static void record(Context context, String runId, String stage, String phase) {
        if (context == null || runId == null || runId.isEmpty() || !validPhase(phase)) return;
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_RUN_ID, runId)
                .putString(KEY_STAGE, safe(stage))
                .putString(KEY_PHASE, safe(phase))
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    static Snapshot read(Context context, String runId) {
        if (context == null || runId == null || runId.isEmpty()) return empty();
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!runId.equals(prefs.getString(KEY_RUN_ID, ""))) return empty();
        String phase = prefs.getString(KEY_PHASE, "");
        if (!validPhase(phase)) return empty();
        return new Snapshot(true, prefs.getString(KEY_STAGE, ""), phase,
                prefs.getLong(KEY_UPDATED_AT, 0L));
    }

    static String headlineFor(String stage, String phase) {
        if ("completion_ignored".equals(stage) && "THINKING".equals(phase)) {
            return "답변 시작 대기 중";
        }
        return switch (safe(phase)) {
            case "THINKING" -> "추론 중";
            case "ANSWERING" -> "답변 생성 중";
            case "COMPLETE" -> "답변 완료 · 차기 턴 대기";
            case "ERROR" -> "응답 상태 오류";
            default -> "";
        };
    }

    static String pillFor(String headline) {
        String value = safe(headline);
        if (value.equals("추론 중")) return "추론";
        if (value.equals("답변 시작 대기 중")) return "전환";
        if (value.equals("답변 생성 중")) return "답변";
        if (value.equals("답변 완료 · 차기 턴 대기")) return "대기";
        if (value.contains("오류")) return "오류";
        if (value.contains("일시정지")) return "정지";
        if (value.contains("완료") || value.contains("종료")) return "완료";
        if (value.contains("전송")) return "전송";
        if (value.contains("설정")) return "설정";
        if (value.contains("준비")) return "준비";
        return "실행";
    }

    private static boolean validPhase(String phase) {
        return "IDLE".equals(phase) || "THINKING".equals(phase) || "ANSWERING".equals(phase)
                || "COMPLETE".equals(phase) || "ERROR".equals(phase);
    }

    private static Snapshot empty() { return new Snapshot(false, "", "", 0L); }
    private static String safe(String value) { return value == null ? "" : value; }
}
