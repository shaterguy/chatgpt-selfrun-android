package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;

import java.util.Date;

/** Pure restart policy shared by the history UI and recovery activity. */
final class SelfRunRestartPolicy {
    static final String TEST_APPLICATION_ID = "com.shaterguy.chatgptselfrun.drive.test";

    private SelfRunRestartPolicy() {}

    static boolean restartable(JSONObject item) {
        if (item == null) return false;
        String runId = item.optString("runId", "");
        String phase = item.optString("phase", "");
        String conversationUrl = item.optString("conversationUrl", "");
        if (runId.isEmpty() || conversationUrl.isEmpty() || SelfRunStore.PHASE_DONE.equals(phase)) return false;
        return item.optBoolean("userStopped", false)
                || item.optBoolean("paused", false)
                || SelfRunStore.PHASE_PAUSED.equals(phase);
    }

    static String restartPhase(String mode) {
        return SelfRunStore.MODE_WORK.equals(mode)
                ? SelfRunStore.PHASE_APPLY_PREFS
                : SelfRunStore.PHASE_SEND_CONTINUE;
    }

    static String continuationPrompt(String runId, String replacementDocumentId) {
        String base = "[" + SelfRunProtocol.kstTimestamp(new Date()) + "] "
                + SelfRunProtocol.continuation(runId)
                + "\nCommand Received Record Required";
        if (replacementDocumentId == null || replacementDocumentId.isEmpty()) return base;
        if (!DriveApiClient.validFileId(replacementDocumentId)) {
            throw new IllegalArgumentException("valid replacement turn document id required");
        }
        return base
                + "\nDRIVE_TURN_DOCUMENT_ID=" + replacementDocumentId
                + "\n이전 실행턴 문서가 복구 과정에서 새로 생성되었습니다. 이 CONTINUE부터 위 DRIVE_TURN_DOCUMENT_ID 문서를 현재 Run의 실행턴 문서로 사용하고, 향후 SelfRun Drive signal을 해당 문서에 기록·readback할 것. Bootstrap은 재실행하지 말 것.";
    }
}
