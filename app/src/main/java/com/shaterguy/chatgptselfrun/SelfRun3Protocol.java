package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;

/** Compact SelfRun 3 envelope. Canonical AI semantics live in the Drive SKILL. */
final class SelfRun3Protocol {
    static final String CONTRACT_VERSION = "3.0.0";
    static final String SELF_RUN_SKILL_DOCUMENT_ID = "1qPTSmJG8GpXMSyIGm6SIpgx6-LtWCBGVW3WUpoKj9fs";

    static String receipt(SelfRun3Engine.State s) {
        return "[SELF_RUN_3_RESULT " + s.turnId() + "]";
    }

    static String prompt(SelfRun3Engine.State s, String userInput) {
        JSONObject config = s.config();
        StringBuilder out = baseEnvelope(s, false);
        if ("WORK".equals(config.optString("mode"))) {
            appendWorkProfile(out, config);
            if ("PLAN".equals(s.text("phase"))) {
                out.append("WORK_PROFILE_CHOICES=").append(compactWorkChoices()).append('\n');
            }
        }
        if (!s.text("nextInput").isEmpty()) {
            out.append("\n[이전 턴에서 확정된 다음 입력]\n").append(s.text("nextInput")).append('\n');
        }
        if (userInput != null && !userInput.isEmpty()) {
            out.append("\n[사용자 추가 지시 원문]\n").append(userInput).append('\n');
        }
        return out.toString();
    }

    static String repair(SelfRun3Engine.State s, String nextRequestId) {
        StringBuilder out = baseEnvelope(s, true);
        JSONObject config = s.config();
        if ("WORK".equals(config.optString("mode"))) appendWorkProfile(out, config);
        return out.toString();
    }

    private static StringBuilder baseEnvelope(SelfRun3Engine.State s, boolean repair) {
        JSONObject config = s.config();
        StringBuilder out = new StringBuilder();
        out.append("[SELF_RUN_V3 ").append(CONTRACT_VERSION);
        if (repair) out.append(" RESULT_REPAIR=1");
        out.append("]\n")
                .append("SELF_RUN_SKILL_DOCUMENT_ID=").append(SELF_RUN_SKILL_DOCUMENT_ID).append('\n')
                .append("TASK_ID=").append(s.taskId()).append('\n')
                .append("TURN_ID=").append(s.turnId()).append('\n')
                .append("TURN=").append(s.turn()).append('\n')
                .append("PHASE=").append(s.text("phase")).append('\n')
                .append("MODE=").append(config.optString("mode")).append('\n')
                .append("RESULT_DOCUMENT_ID=").append(s.resource("resultDocumentId")).append('\n')
                .append("REQUIREMENT_DOCUMENT_ID=").append(s.resource("requirementDocumentId")).append('\n')
                .append("PREVIOUS_RESULT_DOCUMENT_ID=").append(s.text("previousResultDocumentId")).append('\n')
                .append("FOLDER_ID=").append(s.resource("folderId")).append('\n')
                .append("RECEIPT=").append(receipt(s)).append('\n');
        return out;
    }

    private static void appendWorkProfile(StringBuilder out, JSONObject config) {
        JSONObject profile = new JSONObject();
        SelfRun3Engine.put(profile, "model", config.optString("model"));
        SelfRun3Engine.put(profile, "reasoning", config.optString("reasoning"));
        out.append("WORK_PROFILE=").append(profile).append('\n');
    }

    private static String compactWorkChoices() {
        JSONArray choices = new JSONArray();
        for (ProfileRegistry.Profile profile : ProfileRegistry.listWork()) {
            JSONObject item = new JSONObject();
            SelfRun3Engine.put(item, "model", profile.signalModel);
            SelfRun3Engine.put(item, "reasoning", profile.signalReasoning);
            choices.put(item);
        }
        return choices.toString();
    }

    private SelfRun3Protocol() {}
}
