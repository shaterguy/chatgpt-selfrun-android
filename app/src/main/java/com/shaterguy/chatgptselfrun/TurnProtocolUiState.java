package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

/** UI-facing projection of the single protocol-owned response state. */
final class TurnProtocolUiState {
    static final String DETECTOR_PROTOCOL = "PROTOCOL";

    private static final String PREFS = "selfrun_turn_protocol_ui";
    private static final String KEY_RUN_ID = "runId";
    private static final String KEY_TURN_TOKEN = "turnToken";
    private static final String KEY_STAGE = "stage";
    private static final String KEY_PHASE = "phase";
    private static final String KEY_DETECTOR = "detector";
    private static final String KEY_UPDATED_AT = "updatedAt";

    private static volatile String processRunId = "";
    private static volatile String processTurnToken = "";
    private static volatile String processPhase = "IDLE";

    static final class Snapshot {
        final boolean present;
        final String runId;
        final String turnToken;
        final String stage;
        final String phase;
        final String detector;
        final long updatedAt;

        Snapshot(boolean present, String runId, String turnToken, String stage,
                 String phase, String detector, long updatedAt) {
            this.present = present;
            this.runId = safe(runId);
            this.turnToken = safe(turnToken);
            this.stage = safe(stage);
            this.phase = normalizedPhase(phase);
            this.detector = safe(detector);
            this.updatedAt = Math.max(0L, updatedAt);
        }

        boolean activeGenerationFor(String token) {
            return present && activePhase(phase) && !safe(token).isEmpty()
                    && safe(token).equals(turnToken);
        }

        boolean generationStartedFor(String token) {
            return present && startedPhase(phase) && !safe(token).isEmpty()
                    && safe(token).equals(turnToken);
        }

        String headline() {
            String base = headlineFor(stage, phase);
            return DETECTOR_PROTOCOL.equals(detector) && base.isEmpty()
                    ? "응답 감지 중 · 프로토콜" : base;
        }
    }

    private TurnProtocolUiState() {}

    static void recordDetector(Context context, String runId) {
        if (context == null || safe(runId).isEmpty()) return;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean newRun = !runId.equals(prefs.getString(KEY_RUN_ID, ""));
        SharedPreferences.Editor edit = prefs.edit()
                .putString(KEY_RUN_ID, runId)
                .putString(KEY_DETECTOR, DETECTOR_PROTOCOL)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis());
        if (newRun) {
            edit.putString(KEY_TURN_TOKEN, "").putString(KEY_STAGE, "")
                    .putString(KEY_PHASE, "IDLE");
            setProcess(runId, "", "IDLE");
        }
        edit.apply();
    }

    static void record(Context context, String runId, String turnToken,
                       String stage, String phase) {
        if (context == null || safe(runId).isEmpty() || safe(turnToken).isEmpty()
                || !validPhase(phase)) return;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_RUN_ID, runId)
                .putString(KEY_TURN_TOKEN, turnToken)
                .putString(KEY_STAGE, safe(stage))
                .putString(KEY_PHASE, phase)
                .putString(KEY_DETECTOR, DETECTOR_PROTOCOL)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis()).apply();
        setProcess(runId, turnToken, phase);
    }

    static Snapshot read(Context context, String runId) {
        if (context == null || safe(runId).isEmpty()) return empty();
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!runId.equals(prefs.getString(KEY_RUN_ID, ""))) return empty();
        String detector = prefs.getString(KEY_DETECTOR, "");
        String phase = normalizedPhase(prefs.getString(KEY_PHASE, "IDLE"));
        if (!DETECTOR_PROTOCOL.equals(detector) && "IDLE".equals(phase)) return empty();
        String token = prefs.getString(KEY_TURN_TOKEN, "");
        setProcess(runId, token, phase);
        return new Snapshot(true, runId, token, prefs.getString(KEY_STAGE, ""),
                phase, DETECTOR_PROTOCOL, prefs.getLong(KEY_UPDATED_AT, 0L));
    }

    static boolean activeGenerationForCurrentTurn() {
        return !processRunId.isEmpty() && !processTurnToken.isEmpty() && activePhase(processPhase);
    }

    static boolean activeGenerationFor(String turnToken) {
        return !safe(turnToken).isEmpty() && safe(turnToken).equals(processTurnToken)
                && activePhase(processPhase);
    }

    static boolean generationStartedFor(Context context, String runId, String turnToken) {
        return read(context, runId).generationStartedFor(turnToken);
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
        return DETECTOR_PROTOCOL.equals(detector) ? "응답 감지 중 · 프로토콜" : "";
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

    private static void setProcess(String runId, String turnToken, String phase) {
        processRunId = safe(runId);
        processTurnToken = safe(turnToken);
        processPhase = normalizedPhase(phase);
    }

    private static boolean activePhase(String phase) {
        return "THINKING".equals(phase) || "ANSWERING".equals(phase);
    }

    private static boolean startedPhase(String phase) {
        return activePhase(phase) || "COMPLETE".equals(phase);
    }

    private static boolean validPhase(String phase) {
        return "IDLE".equals(phase) || "THINKING".equals(phase) || "ANSWERING".equals(phase)
                || "COMPLETE".equals(phase) || "ERROR".equals(phase);
    }

    private static String normalizedPhase(String phase) {
        return validPhase(phase) ? phase : "IDLE";
    }

    private static Snapshot empty() {
        return new Snapshot(false, "", "", "", "IDLE", "", 0L);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
