#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

BUILD=app/build.gradle
MANIFEST=app/src/main/AndroidManifest.xml
SERVICE=app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java
STORE=app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunStore.java
API=app/src/main/java/com/shaterguy/chatgptselfrun/DriveApiClient.java
AUTH=app/src/main/java/com/shaterguy/chatgptselfrun/DriveAuthorization.java
DOM=app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunDom.java
LOG=app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunRunLog.java
PROTOCOL=app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunProtocol.java

grep -Fq "applicationId 'com.shaterguy.chatgptselfrun.drive'" "$BUILD"
grep -Fq 'versionCode 1000003' "$BUILD"
grep -Fq "versionName '1.0.0-dev3'" "$BUILD"
grep -Fq "implementation 'com.google.android.gms:play-services-auth:21.6.0'" "$BUILD"
! grep -Fq "applicationId 'com.shaterguy.chatgptselfrun'" "$BUILD"
grep -Fq '<string name="app_name">SelfRun Drive</string>' app/src/main/res/values/strings.xml
! grep -Fq 'android:sharedUserId' "$MANIFEST"

while IFS= read -r authority; do
  [[ "$authority" == '${applicationId}.fileprovider' || "$authority" == '${applicationId}.'* ]]
done < <(sed -n 's/.*android:authorities="\([^"]*\)".*/\1/p' "$MANIFEST")

for action in RUN PAUSE RESUME; do
  grep -Fq "BuildConfig.APPLICATION_ID + \".${action}\"" "$SERVICE"
  ! grep -Fq "com.shaterguy.chatgptselfrun.${action}" app/src/main/java/com/shaterguy/chatgptselfrun/*.java
done

grep -Fq 'https://www.googleapis.com/auth/drive.file' "$AUTH"
! grep -Fq 'https://www.googleapis.com/auth/drive"' "$AUTH"
grep -Fq 'PICKER_OAUTH_TRIGGER' "$AUTH"
grep -Fq 'PICKER_ALLOW_FOLDER_SELECTION' "$AUTH"
grep -Fq 'setOptOutIncludingGrantedScopes(true)' "$AUTH"
grep -Fq 'getAccountPermissionId' "$API"
grep -Fq 'setInstanceFollowRedirects(false)' "$API"
grep -Fq 'ALLOWED_HOSTS' "$API"
grep -Fq 'new JSONArray().put(parentId)' "$API"
grep -Fq 'generateFolderId' "$API"
grep -Fq '.put("id", folderId)' "$API"
grep -Fq 'OutcomeUnknownException' "$API"
! grep -Fq 'recoverAmbiguousCreate' "$API"
# Native Docs create has no pre-generated ID. Recovery may list only the bound Job folder,
# then must validate the exact job/appProperties identity and reject multiple matches.
grep -Fq 'Metadata findSingleTurnDocument' "$API"
grep -Fq "' in parents and trashed = false and mimeType = '" "$API"
grep -Fq 'pageSize=10' "$API"
grep -Fq '&q=' "$API"
grep -Fq '!jobId.equals(candidate.name)' "$API"
grep -Fq '!parentId.equals(candidate.parentId)' "$API"
grep -Fq 'candidate.appProperties.optString("job_id")' "$API"
grep -Fq 'candidate.appProperties.optString("selfrun_kind")' "$API"
grep -Fq 'multiple turn documents found for one SelfRun job' "$API"
grep -Fq '.put("protocol_version", "1")' "$API"
grep -Fq '.put("client_id", "selfrun_drive_android")' "$API"
grep -Fq '.put("created_by", "selfrun_drive_android")' "$API"

! grep -Fq 'WAIT_DRIVE_DISCOVERY' "$SERVICE"
! grep -Fq 'WAIT_ASSISTANT' "$SERVICE"
! grep -Fq 'SelfRunDom.observeAssistant' "$SERVICE"
grep -Fq 'getPollMetadata(accessToken, snapshot.turnDocumentId)' "$SERVICE"
grep -Fq 'CONTINUATION_GUARD_MS = 45_000L' "$SERVICE"
grep -Fq 'store.markSubmissionStarted(beforeCount)' "$SERVICE"
grep -Fq 'checkDriveTurnSubmitted' "$SERVICE"
grep -Fq 'SUBMISSION_RETRY_MS = 5 * 60_000L' "$SERVICE"
grep -Fq 'submissionRetryRunnable' "$SERVICE"
! grep -Fq 'SUBMISSION_CONFIRMATION_TIMEOUT' "$SERVICE"
! grep -Fq 'enterPreservedPause("SUBMISSION_AMBIGUOUS"' "$SERVICE"
grep -Fq 'return SelfRunProtocol.continuation(store.runId());' "$SERVICE"
if grep -Fq 'SELF_RUN_DRIVE_COMMIT_ID=' "$SERVICE"; then exit 1; fi
grep -Fq 'releaseWakeLock();' "$SERVICE"
grep -Fq 'DRIVE_DOCUMENT_CREATE_RESULT_PENDING' "$SERVICE"
grep -Fq 'drive.findSingleTurnDocument' "$SERVICE"
grep -Fq 'DRIVE_PROTOCOL_TURN_RECHECK' "$SERVICE"
grep -Fq 'DRIVE_COMMIT_RECHECK' "$SERVICE"
grep -Fq 'resetPendingForDriveReplay' "$SERVICE"
grep -Fq 'private static boolean isWebAutomationPhase' "$SERVICE"
grep -Fq 'handler.removeCallbacks(driveRetryRunnable)' "$SERVICE"
grep -Fq 'webView.onResume()' "$SERVICE"
grep -Fq 'synchronized (SelfRunStore.RUN_STATE_LOCK)' "$SERVICE"
grep -Fq 'driveOperationRunId.equals(store.runId())' "$SERVICE"

grep -Fq 'selfrun_drive' "$STORE"
grep -Fq 'selfrun-drive' "$LOG"
grep -Fq 'sendDriveInitial' "$DOM"
grep -Fq 'SELF_RUN_CLIENT=DRIVE_V1' "$PROTOCOL"
grep -Fq 'DRIVE_TURN_DOCUMENT_ID=' "$PROTOCOL"
! grep -Fq 'ANDROID_APPLICATION_ID=' "$PROTOCOL"
! grep -Fq 'DRIVE_PROTOCOL_VERSION=' "$PROTOCOL"
! grep -Fq 'DRIVE_RUNS_BASE_FOLDER_ID=' "$PROTOCOL"
! grep -Fq 'DRIVE_JOB_FOLDER_ID=' "$PROTOCOL"
! grep -Fq 'DRIVE_TURN_DOCUMENT_URL=' "$PROTOCOL"
grep -Fq 'NEXT|DONE|USER_ACTION_REQUIRED|PAUSE' "$PROTOCOL"
if grep -Fq 'SELF_RUN_ERROR' "$PROTOCOL"; then exit 1; fi

echo 'SelfRun Drive static policy checks passed.'
