package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.common.api.ApiException;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/** Restarts a historical stopped run without replaying its bootstrap. */
public final class SelfRunRestartActivity extends Activity {
    static final String EXTRA_RUN_ID = "runId";
    private static final int REQUEST_AUTHORIZATION = 5201;
    private static final String RESTART_PREFS = "selfrun_drive_restart";
    private static final String PROCESS_INSTANCE_ID = Long.toHexString(System.currentTimeMillis())
            + ":" + Long.toHexString(System.nanoTime())
            + ":" + Long.toHexString(ThreadLocalRandom.current().nextLong());

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SelfRunStore store;
    private SelfRunHistoryStore history;
    private JSONObject snapshot;
    private TextView stagePill;
    private TextView statusHeadline;
    private TextView status;
    private TextView progress;
    private TextView targetSummary;
    private Button closeButton;
    private String runId = "";
    private String claimToken = "";
    private boolean recoveryStarted;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SelfRunStore(this);
        history = new SelfRunHistoryStore(this);
        runId = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_RUN_ID);
        if (runId == null) runId = "";
        render();
        snapshot = history.get(runId);
        try { snapshot = LegacyRunModeMigration.normalizedSnapshot(this, snapshot); }
        catch (RuntimeException invalid) { failure("이전 실행 모드를 확인할 수 없습니다. 저장된 프로필을 확인하세요."); return; }
        updateTargetSummary();
        if (!SelfRunRestartPolicy.restartable(snapshot)) {
            failure("재시작할 수 있는 중지 작업이 아닙니다.");
            return;
        }
        if (!SelfRunStore.canCaptureConversationUrl(snapshot.optString("projectUrl"), snapshot.optString("conversationUrl"))) {
            failure("등록된 대화방 주소를 안전하게 확인할 수 없습니다.");
            return;
        }
        if (!claimRestart()) return;
        requestAuthorization();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = Ui.page(this);
        scroll.addView(page);

        page.addView(Ui.topBar(this, "작업 재시작", "", null));

        stagePill = Ui.muted(this, "");
        stagePill.setVisibility(android.view.View.GONE);
        statusHeadline = Ui.headline(this, "중지 작업 확인 중");
        status = Ui.body(this, "작업 이력과 Drive 리소스를 확인하고 있습니다.");
        progress = Ui.body(this, progressText(0));
        progress.setTextIsSelectable(false);
        page.addView(Ui.card(this,
                stagePill,
                statusHeadline,
                status,
                Ui.divider(this),
                progress));

        page.addView(Ui.section(this, "작업"));
        targetSummary = Ui.muted(this, "Run ID  " + empty(runId));
        page.addView(targetSummary);

        closeButton = Ui.outlinedButton(this, "재시작 취소", v -> cancelBeforeRecovery());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeParams.topMargin = Ui.dp(this, 18);
        page.addView(closeButton, closeParams);

        Ui.setContent(this, scroll);
    }

    private void updateTargetSummary() {
        if (targetSummary == null) return;
        if (snapshot == null) {
            targetSummary.setText("Run ID  " + empty(runId) + "\n저장된 작업 정보를 찾지 못했습니다.");
            return;
        }
        targetSummary.setText(missionPreview(snapshot.optString("requirement", ""))
                + "\n" + snapshot.optString("mode", "-") + " · " + snapshot.optInt("turn", 0) + "턴");
    }

    private void showRecoveryStage(String badge, String headline, String message, int step) {
        runOnUiThread(() -> {
            if (stagePill != null) stagePill.setText(badge);
            if (statusHeadline != null) statusHeadline.setText(headline);
            if (status != null) status.setText(message);
            if (progress != null) progress.setText(progressText(step));
            if (closeButton != null) closeButton.setEnabled(!recoveryStarted);
        });
    }

    private static String progressText(int currentStep) {
        String[] steps = {"작업 확인", "Drive 계정", "리소스 복구", "다음 턴 준비"};
        if (currentStep < 0) return "복구를 완료하지 못했습니다.";
        int index = Math.min(currentStep, steps.length - 1);
        return (index + 1) + " / " + steps.length + " · " + steps[index];
    }

    private static String missionPreview(String value) {
        if (value == null || value.trim().isEmpty()) return "요청 내용 없음";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }

    private static String empty(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private boolean claimRestart() {
        synchronized (SelfRunStore.RUN_STATE_LOCK) {
            if (store.active()) {
                failure("현재 실행 중인 SelfRun이 있어 과거 작업으로 전환할 수 없습니다.");
                return false;
            }
            SharedPreferences lock = getSharedPreferences(RESTART_PREFS, MODE_PRIVATE);
            String existingToken = lock.getString("claimToken", "");
            String existingProcess = lock.getString("claimProcessId", "");
            if (SelfRunRestartPolicy.processClaimConflicts(existingToken, existingProcess, PROCESS_INSTANCE_ID)) {
                failure("다른 재시작 요청이 이미 처리 중입니다.");
                return false;
            }
            long now = System.currentTimeMillis();
            claimToken = runId + ":" + now + ":" + Long.toHexString(System.nanoTime());
            SharedPreferences.Editor claim = lock.edit()
                    .putString("claimRunId", runId)
                    .putString("claimToken", claimToken)
                    .putString("claimProcessId", PROCESS_INSTANCE_ID)
                    .putLong("claimedAt", now);
            if (!runId.equals(lock.getString("reservedRunId", ""))) {
                claim.remove("reservedFolderId").remove("reservedRunId");
            }
            boolean committed = claim.commit();
            if (!committed) {
                claimToken = "";
                failure("재시작 상태를 저장하지 못했습니다.");
                return false;
            }
            return true;
        }
    }

    private void requestAuthorization() {
        showRecoveryStage("AUTH", "Drive 계정 확인", "기존 작업과 같은 Drive 계정인지 확인하고 있습니다.", 1);
        DriveAuthorization.requestSilently(this, new DriveAuthorization.Callback() {
            @Override public void onAuthorized(AuthorizationResult result) { startRecovery(result); }
            @Override public void onResolutionRequired(PendingIntent pendingIntent) {
                try {
                    startIntentSenderForResult(pendingIntent.getIntentSender(), REQUEST_AUTHORIZATION,
                            null, 0, 0, 0);
                } catch (Exception error) {
                    failure("Drive 권한 확인 화면을 열지 못했습니다.");
                }
            }
            @Override public void onFailure(Throwable error) {
                failure("Drive 권한을 확인하지 못했습니다.");
            }
        });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_AUTHORIZATION) return;
        if (resultCode != RESULT_OK || data == null) {
            failure("Drive 권한 확인이 취소되었습니다.");
            return;
        }
        try {
            startRecovery(DriveAuthorization.fromIntent(this, data));
        } catch (ApiException error) {
            failure("Drive 권한 결과를 확인하지 못했습니다.");
        }
    }

    private void startRecovery(AuthorizationResult result) {
        if (recoveryStarted) return;
        String accessToken = DriveAuthorization.accessToken(result);
        if (accessToken.isEmpty()) {
            failure("Drive 액세스 토큰을 얻지 못했습니다.");
            return;
        }
        recoveryStarted = true;
        showRecoveryStage("RECOVERING", "실행 리소스 복구", "기존 작업과 Drive 리소스를 확인하고 필요한 경우 안전하게 복구합니다.", 2);
        io.execute(() -> recover(accessToken));
    }

    private void recover(String accessToken) {
        try {
            requireClaimOwnership();
            DriveApiClient api = new DriveApiClient();
            String expectedAccount = snapshot.optString("driveAccountId", "");
            String actualAccount = api.getAccountPermissionId(accessToken);
            if (expectedAccount.isEmpty() || !expectedAccount.equals(actualAccount)) {
                throw new IllegalStateException("historical Drive account mismatch");
            }

            String baseFolderId = snapshot.optString("runBaseFolderId", "");
            if (baseFolderId.isEmpty()) baseFolderId = snapshot.optString("driveRunsBaseFolderId", "");
            if (!DriveApiClient.validFileId(baseFolderId)) {
                throw new IllegalStateException("historical Runs base folder is missing");
            }
            DriveApiClient.Metadata base = api.getMetadata(accessToken, baseFolderId);
            verifyBaseFolder(base, baseFolderId);

            DriveApiClient.Metadata jobFolder = reusableMetadata(api, accessToken,
                    snapshot.optString("jobFolderId", ""));
            boolean folderRecreated = jobFolder == null;
            if (jobFolder == null) jobFolder = createOrRecoverJobFolder(api, accessToken, baseFolderId);
            else verifyJobFolder(jobFolder, baseFolderId);

            String oldDocumentId = snapshot.optString("turnDocumentId", "");
            DriveApiClient.Metadata document = folderRecreated ? null
                    : reusableMetadata(api, accessToken, oldDocumentId);
            if (document != null) verifyTurnDocument(document, jobFolder.id);
            if (document == null) document = createOrRecoverTurnDocument(api, accessToken, jobFolder.id);
            verifyTurnDocument(document, jobFolder.id);

            String mode = snapshot.optString("mode", SelfRunStore.MODE_CHAT);
            int stoppedCursor = Math.max(0, snapshot.optInt("driveSignalCursor", 0));
            String body = api.readDocumentText(accessToken, document.id);
            DriveSignalParser.Scan baseline = DriveSignalParser.scan(body, runId, stoppedCursor, mode);
            DriveSignalParser.Event restartCompletion = SelfRunRestartPolicy.restartCompletion(baseline, snapshot);
            boolean documentChanged = !document.id.equals(oldDocumentId);
            String prompt = SelfRunRestartPolicy.continuationPrompt(runId,
                    documentChanged ? document.id : "");
            showRecoveryStage("RESTORING", "실행 상태 복원", "Drive 문서와 signal 기준선을 확인했습니다. CONTINUE 상태를 복원합니다.", 2);
            restoreRun(baseFolderId, actualAccount, jobFolder, document, baseline, restartCompletion, prompt);
            runOnUiThread(this::startRecoveredService);
        } catch (Throwable error) {
            failure("작업 재시작 준비에 실패했습니다. 기존 작업과 Drive 리소스는 변경하지 않았습니다.");
        }
    }

    private DriveApiClient.Metadata reusableMetadata(DriveApiClient api, String token, String id) throws Exception {
        if (!DriveApiClient.validFileId(id)) return null;
        try {
            DriveApiClient.Metadata metadata = api.getMetadata(token, id);
            return metadata.trashed ? null : metadata;
        } catch (DriveApiClient.ApiException error) {
            if (error.status == 404) return null;
            throw error;
        }
    }

    private DriveApiClient.Metadata createOrRecoverJobFolder(DriveApiClient api, String token,
                                                               String baseFolderId) throws Exception {
        requireClaimOwnership();
        SharedPreferences lock = getSharedPreferences(RESTART_PREFS, MODE_PRIVATE);
        String folderId = runId.equals(lock.getString("reservedRunId", ""))
                ? lock.getString("reservedFolderId", "") : "";
        if (!DriveApiClient.validFileId(folderId)) {
            folderId = api.generateFolderId(token);
            if (!lock.edit().putString("reservedFolderId", folderId)
                    .putString("reservedRunId", runId).commit()) {
                throw new IllegalStateException("reserved restart folder id was not persisted");
            }
        }
        DriveApiClient.Metadata existing = reusableMetadata(api, token, folderId);
        if (existing != null) {
            verifyJobFolder(existing, baseFolderId);
            return existing;
        }
        try {
            requireClaimOwnership();
            DriveApiClient.Metadata created = api.createJobFolder(token, folderId, runId, baseFolderId);
            verifyJobFolder(created, baseFolderId);
        } catch (Throwable createError) {
            DriveApiClient.Metadata recovered = reusableMetadata(api, token, folderId);
            if (recovered == null) throw createError;
            verifyJobFolder(recovered, baseFolderId);
        }
        DriveApiClient.Metadata readback = api.getMetadata(token, folderId);
        verifyJobFolder(readback, baseFolderId);
        return readback;
    }

    private DriveApiClient.Metadata createOrRecoverTurnDocument(DriveApiClient api, String token,
                                                                  String jobFolderId) throws Exception {
        requireClaimOwnership();
        DriveApiClient.Metadata recovered = api.findSingleTurnDocument(token, runId, jobFolderId);
        if (recovered != null) {
            verifyTurnDocument(recovered, jobFolderId);
            return recovered;
        }
        try {
            requireClaimOwnership();
            DriveApiClient.Metadata created = api.createTurnDocument(token, runId, jobFolderId);
            verifyTurnDocument(created, jobFolderId);
            DriveApiClient.Metadata readback = api.getMetadata(token, created.id);
            verifyTurnDocument(readback, jobFolderId);
            return readback;
        } catch (DriveApiClient.OutcomeUnknownException unknown) {
            DriveApiClient.Metadata afterUnknown = api.findSingleTurnDocument(token, runId, jobFolderId);
            if (afterUnknown == null) throw unknown;
            verifyTurnDocument(afterUnknown, jobFolderId);
            return afterUnknown;
        }
    }

    private void verifyBaseFolder(DriveApiClient.Metadata metadata, String expectedId) {
        if (metadata.trashed || metadata.shared || !metadata.isAppAuthorized || !metadata.canAddChildren
                || !expectedId.equals(metadata.id) || !DriveApiClient.MIME_FOLDER.equals(metadata.mimeType)) {
            throw new IllegalStateException("historical Runs base folder is not writable");
        }
    }

    private void verifyJobFolder(DriveApiClient.Metadata metadata, String baseFolderId) {
        if (metadata.trashed || metadata.shared || !metadata.isAppAuthorized || !metadata.canAddChildren
                || !runId.equals(metadata.name) || !DriveApiClient.MIME_FOLDER.equals(metadata.mimeType)
                || !baseFolderId.equals(metadata.parentId)
                || !runId.equals(metadata.appProperties.optString("job_id"))
                || !"job_folder".equals(metadata.appProperties.optString("selfrun_kind"))) {
            throw new IllegalStateException("historical job folder metadata mismatch");
        }
    }

    private void verifyTurnDocument(DriveApiClient.Metadata metadata, String jobFolderId) {
        if (metadata.trashed || metadata.shared || !metadata.isAppAuthorized
                || !runId.equals(metadata.name) || !DriveApiClient.MIME_DOCUMENT.equals(metadata.mimeType)
                || !jobFolderId.equals(metadata.parentId)
                || !runId.equals(metadata.appProperties.optString("job_id"))
                || !"turn_document".equals(metadata.appProperties.optString("selfrun_kind"))) {
            throw new IllegalStateException("historical turn document metadata mismatch");
        }
    }

    private void restoreRun(String baseFolderId, String accountId,
                            DriveApiClient.Metadata jobFolder, DriveApiClient.Metadata document,
                            DriveSignalParser.Scan baseline, DriveSignalParser.Event restartCompletion,
                            String prompt) {
        synchronized (SelfRunStore.RUN_STATE_LOCK) {
            requireClaimOwnership();
            if (store.active()) throw new IllegalStateException("another SelfRun became active during restart");
            String mode = snapshot.optString("mode", SelfRunStore.MODE_CHAT);
            String model = snapshot.optString("pendingModel", "");
            String reasoning = snapshot.optString("pendingReasoning", "");
            if (SelfRunStore.MODE_WORK.equals(mode) && restartCompletion != null) {
                DriveSignalParser.WorkProfile profile = DriveSignalParser.workProfile(restartCompletion.raw);
                if (profile.valid) {
                    model = profile.model;
                    reasoning = profile.reasoning;
                }
            }
            if (SelfRunStore.MODE_WORK.equals(mode) && !SelfRunProtocol.validWorkProfile(model, reasoning)) {
                model = "sol";
                reasoning = "xhigh";
            }
            if (!SelfRunStore.MODE_WORK.equals(mode)) {
                model = "";
                reasoning = "";
            }
            long now = System.currentTimeMillis();
            long createdAt = snapshot.optLong("createdAt", now);
            DriveSignalParser.Event latest = baseline.latest;
            String pendingRaw = restartCompletion == null ? "" : restartCompletion.raw;
            String pendingTimestamp = restartCompletion == null ? "" : restartCompletion.timestamp;
            String pendingType = restartCompletion == null ? "" : restartCompletion.type.name();
            SharedPreferences prefs = getSharedPreferences("selfrun_drive", MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit()
                    .putString("runId", runId)
                    .putLong("createdAt", createdAt > 0L ? createdAt : now)
                    .putLong("phaseStartedAt", now)
                    .putString("mode", mode)
                    .putBoolean("legacyChatSelectionPending:" + runId, snapshot.optBoolean("legacyChatSelectionPending", false))
                    .putString("projectUrl", snapshot.optString("projectUrl", ""))
                    .putString("requirement", snapshot.optString("requirement", ""))
                    .putString("conversationUrl", snapshot.optString("conversationUrl", ""))
                    .putString("phase", SelfRunRestartPolicy.restartPhase(mode))
                    .putString("status", "과거 중지 작업 재시작 · CONTINUE 준비")
                    .putString("pendingModel", model)
                    .putString("pendingReasoning", reasoning)
                    .putString("lastErrorCode", "")
                    .putString("lastErrorMessage", "")
                    .putString("runDriveAccountId", accountId)
                    .putString("runBaseFolderId", baseFolderId)
                    .putString("jobFolderId", jobFolder.id)
                    .putString("turnDocumentId", document.id)
                    .putString("turnDocumentUrl", documentUrl(document.id))
                    .putString("attachmentsJson", "[]")
                    .putString("attachmentGrantCleanupJson", "[]")
                    .putInt("turn", Math.max(0, snapshot.optInt("turn", 0)))
                    .putString("lastSeenDriveVersion", document.version)
                    .putString("lastSeenModifiedTime", document.modifiedTime)
                    .putInt("driveSignalCursor", baseline.totalCount)
                    .putString("lastDriveSignalRaw", latest == null ? "" : latest.raw)
                    .putString("lastDriveSignalTimestamp", latest == null ? "" : latest.timestamp)
                    .putString("lastDriveSignalType", latest == null ? "" : latest.type.name())
                    .putString("pendingDriveSignalRaw", pendingRaw)
                    .putString("pendingDriveSignalTimestamp", pendingTimestamp)
                    .putString("pendingDriveSignalType", pendingType)
                    .putLong("commitDetectedAt", restartCompletion == null ? 0L : now)
                    .putString("activeCommandPrompt", prompt)
                    .putString("activeCommandKind", SelfRunStore.RETRY_CONTINUE)
                    .putInt("commandAttempt", 0)
                    .putBoolean("awaitingCommandAck", false)
                    .putString("submissionRetryKind", "")
                    .putString("submissionRetryReason", "")
                    .putLong("submissionRetryDueAt", 0L)
                    .putInt("submissionRetryAttempt", 0)
                    .putBoolean("submissionRetryReady", false)
                    .putString("creationStage", SelfRunStore.CREATION_DOCUMENT_CREATED)
                    .putString("pausedFromPhase", "")
                    .putLong("pausedFromPhaseStartedAt", 0L)
                    .putBoolean("resumeNeedsContinuation", false)
                    .putBoolean("terminalSideEffectPending", false)
                    .putString("terminalSideEffectType", "")
                    .putString("terminalSideEffectRunId", "")
                    .putString("terminalSideEffectCommitId", "")
                    .putBoolean("active", true)
                    .putBoolean("paused", false)
                    .putBoolean("userStopped", false)
                    .remove("lastSignal")
                    .remove("driveProtocolVersion")
                    .remove("expectedTurn")
                    .remove("lastConsumedEventSeq")
                    .remove("lastCommittedAt")
                    .remove("pendingEventSeq")
                    .remove("pendingTurn")
                    .remove("pendingSignalRaw")
                    .remove("pendingCommitId")
                    .remove("submissionState")
                    .remove("submissionStartedAt")
                    .remove("submissionBaselineCount")
                    .remove("lastSubmittedCommitId")
                    .remove("bootstrapSubmittedAt")
                    .remove("bootstrapSubmissionState");
            String role = snapshot.optString("role", "");
            if (role.isEmpty()) editor.remove("role"); else editor.putString("role", role);
            if (!editor.commit()) throw new IllegalStateException("historical run state was not persisted");
            LegacyRunModeMigration.persistCurrentChatSelection(this, runId);
            history.sync(new SelfRunStore(this));
        }
    }

    private void startRecoveredService() {
        try {
            requireClaimOwnership();
            showRecoveryStage("READY", "CONTINUE 준비 완료", "기존 대화방에서 SelfRun을 이어갈 준비가 완료되었습니다.", 3);
            Intent service = new Intent(this, SelfRunService.class).setAction(SelfRunService.ACTION_RUN);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
            releaseClaim(true);
            showRecoveryStage("RESUMED", "재시작 완료", "기존 대화방에 CONTINUE를 전송합니다.", 4);
            Toast.makeText(this, "중지 작업을 재시작했습니다.", Toast.LENGTH_LONG).show();
            finish();
        } catch (Throwable error) {
            synchronized (SelfRunStore.RUN_STATE_LOCK) {
                getSharedPreferences("selfrun_drive", MODE_PRIVATE).edit()
                        .putBoolean("active", false)
                        .putBoolean("userStopped", true)
                        .putString("phase", SelfRunStore.PHASE_IDLE)
                        .putString("status", "재시작 서비스 시작 실패")
                        .commit();
                history.sync(new SelfRunStore(this));
            }
            failure("재시작 서비스 시작에 실패했습니다.");
        }
    }

    private static String documentUrl(String documentId) {
        return new Uri.Builder().scheme("https").authority("docs.google.com")
                .appendPath("document").appendPath("d").appendPath(documentId).appendPath("edit")
                .build().toString();
    }

    private void releaseClaim(boolean clearReservation) {
        if (claimToken.isEmpty()) return;
        synchronized (SelfRunStore.RUN_STATE_LOCK) {
            SharedPreferences lock = getSharedPreferences(RESTART_PREFS, MODE_PRIVATE);
            if (claimToken.equals(lock.getString("claimToken", ""))) {
                SharedPreferences.Editor editor = lock.edit()
                        .remove("claimRunId").remove("claimToken")
                        .remove("claimProcessId").remove("claimedAt");
                if (clearReservation) editor.remove("reservedFolderId").remove("reservedRunId");
                editor.commit();
            }
        }
        claimToken = "";
    }

    private void failure(String message) {
        releaseClaim(false);
        runOnUiThread(() -> {
            recoveryStarted = false;
            showRecoveryStage("FAILED", "재시작을 완료하지 못했습니다", message, -1);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void requireClaimOwnership() {
        if (claimToken.isEmpty()) throw new IllegalStateException("restart claim is missing");
        SharedPreferences lock = getSharedPreferences(RESTART_PREFS, MODE_PRIVATE);
        if (!claimToken.equals(lock.getString("claimToken", ""))
                || !PROCESS_INSTANCE_ID.equals(lock.getString("claimProcessId", ""))) {
            throw new IllegalStateException("restart claim ownership changed");
        }
    }

    private void cancelBeforeRecovery() {
        if (recoveryStarted) {
            Toast.makeText(this, "Drive 복구가 진행 중일 때는 재시작 화면을 닫을 수 없습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        releaseClaim(false);
        finish();
    }

    @Override public void onBackPressed() {
        if (recoveryStarted) {
            Toast.makeText(this, "Drive 복구가 진행 중일 때는 뒤로 갈 수 없습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        releaseClaim(false);
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (recoveryStarted) io.shutdown();
        else io.shutdownNow();
        super.onDestroy();
    }
}
