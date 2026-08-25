package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;

/** Durable predecessor -> successor claim and lineage journal. Raw user requirements are never copied here. */
final class SelfRunRolloverCoordinator {
    static final String PHASE_ROLLED_OVER = "ROLLED_OVER";
    static final String RESULT_STARTED = "STARTED";
    static final String RESULT_ALREADY_STARTED = "ALREADY_STARTED";
    static final String RESULT_LOOP_GUARD = "LOOP_GUARD";
    static final String RESULT_FAILED = "FAILED";

    private static final String PREFS = "selfrun_drive_rollover";
    private static final String CURRENT = "currentClaim";
    private static final String LINEAGE_PREFIX = "lineage:";
    private static final String FAILURE_PREFIX = "localFailures:";
    private static final String STORE_PREFS = "selfrun_drive";

    static final class Result {
        final String status;
        final String successorRunId;
        final String cause;
        Result(String status, String successorRunId, String cause) {
            this.status = status;
            this.successorRunId = successorRunId == null ? "" : successorRunId;
            this.cause = cause == null ? "" : cause;
        }
        boolean started() { return RESULT_STARTED.equals(status) || RESULT_ALREADY_STARTED.equals(status); }
    }

    private final Context app;
    private final SharedPreferences prefs;

    SelfRunRolloverCoordinator(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized boolean hasPendingClaim() {
        return claim() != null;
    }

    synchronized Result beginOrResume(SelfRunStore store, String rawCause) {
        if (store == null || store.runId().isEmpty()) return failed(rawCause);
        JSONObject existing = claim();
        if (existing != null) {
            String pred = existing.optString("predecessorRunId");
            String succ = existing.optString("successorRunId");
            if (!store.runId().equals(pred) && !store.runId().equals(succ)) return failed(rawCause);
            return startClaimedSuccessor(store, existing);
        }

        String cause = SelfRunRolloverPolicy.normalizeCause(rawCause);
        String predecessorRunId = store.runId();
        String priorCauses = lineageCauses(predecessorRunId);
        if (SelfRunRolloverPolicy.containsCause(priorCauses, cause)) {
            return new Result(RESULT_LOOP_GUARD, "", cause);
        }
        if (!SelfRunRolloverPolicy.knownConversation(store.conversationUrl())
                || !DriveApiClient.validFileId(store.jobFolderId())
                || !DriveApiClient.validFileId(store.turnDocumentId())) {
            return failed(cause);
        }

        String model = store.pendingModel();
        String reasoning = store.pendingReasoning();
        if (SelfRunStore.MODE_WORK.equals(store.mode()) && !SelfRunProtocol.validWorkProfile(model, reasoning)) {
            return failed(cause);
        }
        String successorRunId = SelfRunRunId.create();
        JSONObject next = new JSONObject();
        try {
            next.put("predecessorRunId", predecessorRunId);
            next.put("successorRunId", successorRunId);
            next.put("predecessorJobFolderId", store.jobFolderId());
            next.put("predecessorTurnDocumentId", store.turnDocumentId());
            next.put("predecessorOriginalRequirementStored", SelfRunSignalTransport.isSignalDocumentRun(app, predecessorRunId));
            next.put("projectUrl", store.projectUrl());
            next.put("mode", store.mode());
            next.put("model", model);
            next.put("reasoning", reasoning);
            next.put("chatReasoning", ChatReasoningPreferenceStore.selectionForRun(app, predecessorRunId));
            next.put("cause", cause);
            next.put("priorCauses", priorCauses);
            next.put("claimedAt", System.currentTimeMillis());
        } catch (Exception ignored) {
            return failed(cause);
        }
        if (!prefs.edit().putString(CURRENT, next.toString()).commit()) return failed(cause);
        return startClaimedSuccessor(store, next);
    }

    synchronized Result resumePending(SelfRunStore store) {
        JSONObject existing = claim();
        if (existing == null || store == null) return failed("");
        String pred = existing.optString("predecessorRunId");
        String succ = existing.optString("successorRunId");
        if (store.userStopped() && store.runId().equals(pred)) {
            prefs.edit().remove(CURRENT).commit();
            return failed(existing.optString("cause"));
        }
        if (!store.runId().equals(pred) && !store.runId().equals(succ)) return failed(existing.optString("cause"));
        return startClaimedSuccessor(store, existing);
    }

    private Result startClaimedSuccessor(SelfRunStore store, JSONObject state) {
        String predecessorRunId = state.optString("predecessorRunId");
        String successorRunId = state.optString("successorRunId");
        String cause = state.optString("cause");
        if (!SelfRunProtocolRules.validRunId(predecessorRunId)
                || !SelfRunProtocolRules.validRunId(successorRunId)) return failed(cause);

        if (successorRunId.equals(store.runId())) {
            if (!persistLineage(state)) return failed(cause);
            clearLocalFailures(successorRunId);
            if (!prefs.edit().remove(CURRENT).commit()) return failed(cause);
            return new Result(RESULT_ALREADY_STARTED, successorRunId, cause);
        }
        if (!predecessorRunId.equals(store.runId())) return failed(cause);

        String requirement = store.requirement();
        if (!SelfRunOriginalRequirement.valid(requirement)) return failed(cause);
        if (!markPredecessorTerminal(store, successorRunId, cause)) return failed(cause);

        String chatReasoning = state.optString("chatReasoning", ChatReasoningPreferenceStore.KEEP);
        if (!ChatReasoningPreferenceStore.save(app, successorRunId, chatReasoning)) return failed(cause);
        if (!SelfRunSignalTransport.mark(app, successorRunId)) return failed(cause);
        try {
            store.start(successorRunId, state.optString("mode"), state.optString("projectUrl"),
                    requirement, new ArrayList<>());
            if (SelfRunStore.MODE_WORK.equals(store.mode())) {
                String model = state.optString("model");
                String reasoning = state.optString("reasoning");
                if (!SelfRunProtocol.validWorkProfile(model, reasoning)) return failed(cause);
                store.setPendingModel(model);
                store.setPendingReasoning(reasoning);
            }
        } catch (RuntimeException error) {
            return failed(cause);
        }
        if (!persistLineage(state)) return failed(cause);
        clearLocalFailures(successorRunId);
        if (!prefs.edit().remove(CURRENT).commit()) return failed(cause);
        return new Result(RESULT_STARTED, successorRunId, cause);
    }

    private boolean markPredecessorTerminal(SelfRunStore store, String successorRunId, String cause) {
        synchronized (SelfRunStore.RUN_STATE_LOCK) {
            if (!store.active() && PHASE_ROLLED_OVER.equals(store.phase())) return true;
            SharedPreferences run = app.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE);
            boolean committed = run.edit()
                    .putBoolean("active", false)
                    .putBoolean("paused", false)
                    .putBoolean("userStopped", false)
                    .putString("phase", PHASE_ROLLED_OVER)
                    .putString("status", "자동 승계됨 · successor=" + successorRunId)
                    .putString("lastErrorCode", "ROLLED_OVER_" + cause)
                    .putString("lastErrorMessage", "현재 conversation 진행 불능으로 새 Run에 자동 승계됨")
                    .putLong("phaseStartedAt", System.currentTimeMillis())
                    .putBoolean("terminalSideEffectPending", false)
                    .commit();
            if (!committed) return false;
            return new SelfRunHistoryStore(app).sync(store);
        }
    }

    private boolean persistLineage(JSONObject claim) {
        String successorRunId = claim.optString("successorRunId");
        String causes = SelfRunRolloverPolicy.appendCause(claim.optString("priorCauses"), claim.optString("cause"));
        JSONObject lineage = new JSONObject();
        try {
            lineage.put("runId", successorRunId);
            lineage.put("predecessorRunId", claim.optString("predecessorRunId"));
            lineage.put("predecessorJobFolderId", claim.optString("predecessorJobFolderId"));
            lineage.put("predecessorTurnDocumentId", claim.optString("predecessorTurnDocumentId"));
            lineage.put("predecessorOriginalRequirementStored", claim.optBoolean("predecessorOriginalRequirementStored", false));
            lineage.put("cause", claim.optString("cause"));
            lineage.put("causes", causes);
        } catch (Exception ignored) {
            return false;
        }
        return prefs.edit().putString(LINEAGE_PREFIX + successorRunId, lineage.toString()).commit();
    }

    String bootstrapPrompt(SelfRunStore store) {
        String base = SelfRunProtocol.bootstrapDrive(store.runId(), store.mode(), store.requirement(),
                store.turnDocumentId(), store.jobFolderId(), store.hasAttachments());
        JSONObject lineage = lineage(store.runId());
        if (lineage == null) return base;
        boolean predecessorOriginalStored = lineage.optBoolean("predecessorOriginalRequirementStored", false);
        String metadata = "SELF_RUN_PREDECESSOR_RUN_ID=" + lineage.optString("predecessorRunId") + "\n"
                + "SELF_RUN_PREDECESSOR_JOB_FOLDER_ID=" + lineage.optString("predecessorJobFolderId") + "\n"
                + "SELF_RUN_PREDECESSOR_TURN_DOCUMENT_ID=" + lineage.optString("predecessorTurnDocumentId") + "\n"
                + "SELF_RUN_PREDECESSOR_ORIGINAL_REQUIREMENT_STORED=" + (predecessorOriginalStored ? "1" : "0") + "\n"
                + "SELF_RUN_ROLLOVER_REASON=" + lineage.optString("cause") + "\n";
        int metadataAt = base.indexOf("\n\n이 실행은 SelfRun이다.");
        if (metadataAt < 0) throw new IllegalStateException("bootstrap metadata anchor missing");
        String withMetadata = base.substring(0, metadataAt) + "\n" + metadata + base.substring(metadataAt);
        String requirementMarker = "\n\n[요구사항]\n";
        int requirementAt = withMetadata.indexOf(requirementMarker);
        if (requirementAt < 0) throw new IllegalStateException("bootstrap requirement anchor missing");
        String originalRequirementInstruction = predecessorOriginalStored
                ? "SELF_RUN_PREDECESSOR_TURN_DOCUMENT_ID 문서의 본문을 원래 사용자 요구사항 권위 원본으로 읽는다."
                : "predecessor는 원문 요구사항 저장 기능 도입 전 Run이므로 현재 successor의 [요구사항]과 현재 DRIVE_TURN_DOCUMENT_ID 본문을 원래 사용자 요구사항 권위 원본으로 사용하고 predecessor turn document 본문을 원문 요구사항으로 해석하지 않는다.";
        String handoffInstruction = "\n\n이 Run은 이전 SelfRun 작업의 자동 승계 Run이다. 실질 작업을 시작하기 전에 " + originalRequirementInstruction + " SELF_RUN_PREDECESSOR_JOB_FOLDER_ID 폴더의 관련 실행 문서와 사용 가능한 모든 누적 HANDOFF를 확인한다. 특정 마지막 HANDOFF 하나의 존재를 전제로 하지 말고 실제 외부 상태와 대조하여 완료된 작업, 실제 반영 상태, 미완료 작업과 다음 진행 지점을 판정한 뒤 중복 작업 없이 이어서 수행한다.";
        return withMetadata.substring(0, requirementAt) + handoffInstruction + withMetadata.substring(requirementAt);
    }

    int incrementLocalFailure(String runId) {
        if (!SelfRunProtocolRules.validRunId(runId)) return Integer.MAX_VALUE;
        String key = FAILURE_PREFIX + runId;
        int current = prefs.getInt(key, 0);
        int next = current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current + 1;
        return prefs.edit().putInt(key, next).commit() ? next : Integer.MAX_VALUE;
    }

    void clearLocalFailures(String runId) {
        if (SelfRunProtocolRules.validRunId(runId)) prefs.edit().remove(FAILURE_PREFIX + runId).commit();
    }

    String lineageCauses(String runId) {
        JSONObject value = lineage(runId);
        return value == null ? "" : value.optString("causes");
    }

    private JSONObject lineage(String runId) {
        if (!SelfRunProtocolRules.validRunId(runId)) return null;
        return parse(prefs.getString(LINEAGE_PREFIX + runId, ""));
    }

    private JSONObject claim() {
        return parse(prefs.getString(CURRENT, ""));
    }

    private static JSONObject parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try { return new JSONObject(raw); }
        catch (Exception ignored) { return null; }
    }

    private static Result failed(String cause) {
        return new Result(RESULT_FAILED, "", SelfRunRolloverPolicy.normalizeCause(cause));
    }
}
