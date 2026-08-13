package com.shaterguy.chatgptselfrun;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SelfRunProtocol {
    enum Type { NEXT, DONE, USER_ACTION, PAUSE, ERROR, NONE }

    static final class Signal {
        final Type type;
        final String raw;
        final String runId;
        final String role;
        final String model;
        final String reasoning;
        final String actionId;

        Signal(Type type, String raw, String runId, String role, String model,
               String reasoning, String actionId) {
            this.type = type;
            this.raw = raw;
            this.runId = runId;
            this.role = role;
            this.model = model;
            this.reasoning = reasoning;
            this.actionId = actionId;
        }
    }

    private static final Pattern BRACKET = Pattern.compile("\\[SELF_RUN_(NEXT|DONE|USER_ACTION_REQUIRED|PAUSE|ERROR)\\s+([^\\]]+)]");
    private SelfRunProtocol() {}

    static Signal parseLatest(String assistantText, String expectedRunId, String mode) {
        if (assistantText == null) return none();
        Matcher matcher = BRACKET.matcher(assistantText);
        Signal last = none();
        while (matcher.find()) {
            String kind = matcher.group(1);
            String payload = matcher.group(2).trim();
            String[] parts = payload.split("\\s+");
            if (parts.length == 0 || !expectedRunId.equals(parts[0])) continue;
            String raw = matcher.group(0);
            if ("DONE".equals(kind)) {
                last = new Signal(Type.DONE, raw, parts[0], "", "", "", "");
            } else if ("USER_ACTION_REQUIRED".equals(kind)) {
                String action = parts.length > 1 ? parts[1] : "ACTION";
                if (!safeCode(action)) continue;
                last = new Signal(Type.USER_ACTION, raw, parts[0], "", "", "", action);
            } else if ("PAUSE".equals(kind)) {
                last = new Signal(Type.PAUSE, raw, parts[0], value(payload, "ROLE"), "", "", "");
            } else if ("ERROR".equals(kind)) {
                String reason = value(payload, "REASON");
                if (!safeCode(reason)) continue;
                last = new Signal(Type.ERROR, raw, parts[0], "", "", "", reason);
            } else {
                String role = value(payload, "ROLE").toUpperCase(Locale.ROOT);
                String model = value(payload, "MODEL").toLowerCase(Locale.ROOT);
                String reasoning = value(payload, "REASONING").toLowerCase(Locale.ROOT);
                if (SelfRunStore.MODE_CHAT.equals(mode)) {
                    model = "";
                    reasoning = "";
                } else if (!validWorkProfile(model, reasoning)) {
                    continue;
                }
                if (role.isEmpty()) role = "BUILDER";
                if (!safeCode(role)) continue;
                last = new Signal(Type.NEXT, raw, parts[0], role, model, reasoning, "");
            }
        }
        return last;
    }

    static boolean validWorkProfile(String model, String reasoning) {
        if (!("sol".equals(model) || "terra".equals(model) || "luna".equals(model))) return false;
        if (!("high".equals(reasoning) || "xhigh".equals(reasoning)
                || "max".equals(reasoning) || "ultra".equals(reasoning))) return false;
        return !"luna".equals(model) || "max".equals(reasoning) || "ultra".equals(reasoning);
    }

    static String bootstrap(String runId, String mode, String requirement) {
        return "[SELF_RUN_BOOTSTRAP 0.1.0 " + runId + " MODE=" + mode + "]\n\n" + requirement.trim();
    }

    static String bootstrapDrive(String runId, String mode, String requirement, String baseFolderId,
                                 String jobFolderId, String documentId, String documentUrl,
                                 int expectedTurn) {
        return "[SELF_RUN_BOOTSTRAP 0.1.0 " + runId + " MODE=" + mode + "]\n"
                + "SELF_RUN_CLIENT=DRIVE_V1\n"
                + "ANDROID_APPLICATION_ID=" + BuildConfig.APPLICATION_ID + "\n"
                + "DRIVE_PROTOCOL_VERSION=1\n"
                + "DRIVE_JOB_ID=" + runId + "\n"
                + "DRIVE_RUNS_BASE_FOLDER_ID=" + baseFolderId + "\n"
                + "DRIVE_JOB_FOLDER_ID=" + jobFolderId + "\n"
                + "DRIVE_TURN_DOCUMENT_ID=" + documentId + "\n"
                + "DRIVE_TURN_DOCUMENT_URL=" + documentUrl + "\n"
                + "DRIVE_EXPECTED_TURN=" + expectedTurn + "\n\n"
                + "Drive V1 실행 계약:\n"
                + "- 전달된 정확한 DRIVE_TURN_DOCUMENT_ID 또는 URL만 사용한다.\n"
                + "- Job 폴더나 실행턴 문서를 생성하거나 이름/Job ID로 Drive를 검색하지 않는다.\n"
                + "- 초기 블록 JOB_ID를 DRIVE_JOB_ID와 대조하고 접근 직후 SESSION_BOUND를 기록한다.\n"
                + "- 실제 주요 단계 전이만 갱신한다.\n"
                + "- 턴 마지막에 commit 작성, 동일 문서 readback, 동일 SelfRun 신호 답변 출력 순서로 종료한다.\n\n"
                + requirement.trim();
    }

    static String continuation(String runId) {
        return "[SELF_RUN_CONTINUE " + runId + "]";
    }

    static String signalRecovery(String runId) {
        return "[SELF_RUN_SIGNAL_RECOVERY " + runId + "]";
    }

    private static String value(String payload, String key) {
        Matcher matcher = Pattern.compile("(?:^|\\s)" + key + "=([^\\s]+)", Pattern.CASE_INSENSITIVE).matcher(payload);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static Signal none() {
        return new Signal(Type.NONE, "", "", "", "", "", "");
    }

    private static boolean safeCode(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,80}");
    }
}
