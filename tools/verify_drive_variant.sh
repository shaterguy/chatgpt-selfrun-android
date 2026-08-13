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
grep -Fq 'versionCode 1000001' "$BUILD"
grep -Fq "versionName '1.0.0-dev1'" "$BUILD"
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
! grep -Fq 'pageSize=' "$API"
! grep -Fq '&q=' "$API"
grep -Fq '.put("protocol_version", "1")' "$API"
grep -Fq '.put("client_id", "selfrun_drive_android")' "$API"
grep -Fq '.put("created_by", "selfrun_drive_android")' "$API"

! grep -Fq 'WAIT_DRIVE_DISCOVERY' "$SERVICE"
! grep -Fq 'WAIT_ASSISTANT' "$SERVICE"
! grep -Fq 'SelfRunDom.observeAssistant' "$SERVICE"
grep -Fq 'getPollMetadata(accessToken, snapshot.turnDocumentId)' "$SERVICE"
grep -Fq 'CONTINUATION_GUARD_MS = 120_000L' "$SERVICE"
grep -Fq 'store.markSubmissionStarted()' "$SERVICE"
grep -Fq 'checkDriveTurnSubmitted' "$SERVICE"
grep -Fq 'SELF_RUN_DRIVE_COMMIT_ID=' "$SERVICE"
grep -Fq 'releaseWakeLock();' "$SERVICE"
grep -Fq 'DRIVE_DOCUMENT_CREATE_RESULT_UNKNOWN' "$SERVICE"
grep -Fq 'private static boolean isWebAutomationPhase' "$SERVICE"
grep -Fq 'handler.removeCallbacks(driveRetryRunnable)' "$SERVICE"
grep -Fq 'webView.onResume()' "$SERVICE"
grep -Fq 'synchronized (SelfRunStore.RUN_STATE_LOCK)' "$SERVICE"
grep -Fq 'driveOperationRunId.equals(store.runId())' "$SERVICE"

grep -Fq 'selfrun_drive' "$STORE"
grep -Fq 'selfrun-drive' "$LOG"
grep -Fq 'sendDriveInitial' "$DOM"
grep -Fq 'SELF_RUN_CLIENT=DRIVE_V1' "$PROTOCOL"
grep -Fq 'PAUSE|ERROR' "$PROTOCOL"

echo 'SelfRun Drive static policy checks passed.'
