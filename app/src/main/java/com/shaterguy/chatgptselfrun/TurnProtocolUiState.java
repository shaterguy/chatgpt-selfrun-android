package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

/** Small UI-facing projection of the latest active ChatGPT response state. */
final class TurnProtocolUiState {
    static final String DETECTOR_PROTOCOL_PRIMARY = "PROTOCOL_PRIMARY";
    static final String DETECTOR_DOM_FALLBACK_ONLY = "DOM_FALLBACK_ONLY";

    private static final String PREFS = "selfrun_turn_protocol_ui";
    private static final String KEY_RUN_ID = "runId";
    private static final String KEY_STAGE = "stage";
    private static final String KEY_PHASE = "phase";
    private static final String KEY_DETECTOR = "detector";
    private static final String KEY_UPDATED_AT = "updatedAt";

    static final class Snapshot {
        final boolean present;
        final String stage;
        final String phase;
        final String detector;
        final long updatedAt;

        Snapshot(boolean present, String stage, String phase, String detector, long updatedAt) {
            this.present = present;
            this.stage = safe(stage);
            this.phase = safe(phase);
            this.detector = safe(detector);
            this.updatedAt = Math.max(0L, updatedAt);
        }

        String headline() {
            String base = headlineFor(stage, phase);
            if (DETECTOR_PROTOCOL_PRIMARY.equals(detector)) {
                return base.isEmpty() ? "응답 감지 중 · 프로토콜 우선 / DOM fallback 병행"
                        : base + " · 응답 프로토콜";
            }
            if (DETECTOR_DOM_FALLBACK_ONLY.equals(detector)) {
                return base.isEmpty() ? "응답 감지 중 · DOM fallback" : base + " · DOM fallback";
            }
            return base;
        }
    }

    private TurnProtocolUiState() {}

    static void recordDetector(Context context, String runId, String detector) {
        if (context == null || runId == null || runId.isEmpty() || !validDetector(detector)) return;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = prefs.edit();
        if (!runId.equals(prefs.getString(KEY_RUN_ID, ""))) {
            edit.putString(KEY_STAGE, "").putString(KEY_PHASE, "IDLE");
        }
        edit.putString(KEY_RUN_ID, runId)
                .putString(KEY_DETECTOR, detector)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    static void record(Context context, String runId, String stage, String phase) {
        if (context == null || runId == null || runId.isEmpty() || !validPhase(phase)) return;
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_RUN_ID, runId)
                .putString(KEY_STAGE, safe(stage))
                .putString(KEY_PHASE, safe(phase))
                .putString(KEY_DETECTOR, DETECTOR_PROTOCOL_PRIMARY)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    static Snapshot read(Context context, String runId) {
        if (context == null || runId == null || runId.isEmpty()) return empty();
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!runId.equals(prefs.getString(KEY_RUN_ID, ""))) return empty();
        String detector = prefs.getString(KEY_DETECTOR, "");
        String phase = prefs.getString(KEY_PHASE, "IDLE");
        if (!validPhase(phase)) phase = "IDLE";
        if (!validDetector(detector) && "IDLE".equals(phase)) return empty();
        return new Snapshot(true, prefs.getString(KEY_STAGE, ""), phase, detector,
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

    static String detectorHeadline(String detector) {
        if (DETECTOR_PROTOCOL_PRIMARY.equals(detector)) {
            return "응답 감지 중 · 프로토콜 우선 / DOM fallback 병행";
        }
        if (DETECTOR_DOM_FALLBACK_ONLY.equals(detector)) return "응답 감지 중 · DOM fallback";
        return "";
    }

    static String pillFor(String headline) {
        String value = safe(headline);
        if (value.equals("추론 중") || value.startsWith("추론 중 ·")) return "추론";
        if (value.equals("답변 시작 대기 중") || value.startsWith("답변 시작 대기 중 ·")) return "전환";
        if (value.equals("답변 생성 중") || value.startsWith("답변 생성 중 ·")) return "답변";
        if (value.equals("답변 완료 · 차기 턴 대기") || value.startsWith("답변 완료 · 차기 턴 대기 ·")) return "대기";
        if (value.contains("오류")) return "오류";
        if (value.contains("일시정지")) return "정지";
        if (value.contains("감지")) return "감지";
        if (value.contains("완료") || value.contains("종료")) return "완료";
        if (value.contains("전송")) return "전송";
        if (value.contains("설정")) return "설정";
        if (value.contains("준비")) return "준비";
        return "실행";
    }

    private static boolean validDetector(String detector) {
        return DETECTOR_PROTOCOL_PRIMARY.equals(detector) || DETECTOR_DOM_FALLBACK_ONLY.equals(detector);
    }

    private static boolean validPhase(String phase) {
        return "IDLE".equals(phase) || "THINKING".equals(phase) || "ANSWERING".equals(phase)
                || "COMPLETE".equals(phase) || "ERROR".equals(phase);
    }

    private static Snapshot empty() { return new Snapshot(false, "", "", "", 0L); }
    private static String safe(String value) { return value == null ? "" : value; }
}
