#!/usr/bin/env bash
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"
BUILD=app/build.gradle
MANIFEST=app/src/main/AndroidManifest.xml
SRC=app/src/main/java/com/shaterguy/chatgptselfrun
SERVICE=$SRC/SelfRunService.java
STORE=$SRC/SelfRunStore.java
PROTOCOL=$SRC/SelfRunProtocol.java
PARSER=$SRC/DriveCommitParser.java
CONTINUE_DOM=$SRC/SelfRunContinuationDom.java
ACTIVITY=$SRC/SelfRunNewActivity.java
HISTORY=$SRC/SelfRunHistoryActivity.java
RESTART=$SRC/SelfRunRestartActivity.java
RESTART_POLICY=$SRC/SelfRunRestartPolicy.java
SETUP=$SRC/DriveSetupActivity.java
AUTH=$SRC/DriveAuthorization.java
API=$SRC/DriveApiClient.java
NOTIFICATION=$SRC/NotificationHelper.java
TEST_DERIVE=tools/derive_test_signing_identity.py
TEST_SIGN=tools/sign_test.sh

grep -Fq "applicationId 'com.shaterguy.chatgptselfrun.drive'" "$BUILD"
grep -Fq "applicationIdSuffix '.test'" "$BUILD"
grep -Fq "selfRunAppLabel: 'SelfRun Drive TEST'" "$BUILD"
grep -Fq 'selfRunDriveVersionCode = 1000060' "$BUILD"
grep -Fq "selfRunDriveVersionName = '1.4.1-dev6'" "$BUILD"
grep -Fq 'android:label="${selfRunAppLabel}"' "$MANIFEST"
grep -Fq '.SelfRunRestartActivity" android:exported="false"' "$MANIFEST"
grep -Fq 'TEST_APPLICATION_ID = "com.shaterguy.chatgptselfrun.drive.test"' "$RESTART_POLICY"
grep -Fq 'Ui.button(this, "중지 작업 재시작"' "$HISTORY"
grep -Fq 'SelfRunProtocol.continuation(runId)' "$RESTART_POLICY"
grep -Fq 'DRIVE_TURN_DOCUMENT_ID=' "$RESTART_POLICY"
! grep -Fq 'SELF_RUN_BOOTSTRAP' "$RESTART_POLICY"
grep -Fq 'DriveAuthorization.requestSilently' "$RESTART"
grep -Fq 'driveAccountId' "$RESTART"
grep -Fq 'findSingleTurnDocument' "$RESTART"
grep -Fq 'PHASE_SEND_CONTINUE' "$RESTART_POLICY"
grep -Fq 'chatgpt-selfrun-test-signing-v1|' "$TEST_DERIVE"
grep -Fq '2c95a5644a0ef2959eaecf10460e300fe2ee7a4ebcede685a82a52634c22e86e' "$TEST_SIGN"
VERSION_CODE="$(sed -n 's/.*selfRunDriveVersionCode = \([0-9][0-9]*\).*/\1/p' "$BUILD" | head -1)"
VERSION_NAME="$(sed -n "s/.*selfRunDriveVersionName = '\([^']*\)'.*/\1/p" "$BUILD" | head -1)"
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]]
[[ "$VERSION_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-(dev|rc)[0-9]+)?$ ]]
grep -Fq 'SELF_RUN_SKILL_DOCUMENT_ID = "1qPTSmJG8GpXMSyIGm6SIpgx6-LtWCBGVW3WUpoKj9fs"' "$PROTOCOL"
grep -Fq '"SELF_RUN_SKILL_DOCUMENT_ID="+SELF_RUN_SKILL_DOCUMENT_ID' "$PROTOCOL"
! grep -Fq 'Vibe Coding' "$PROTOCOL"
grep -Fq '/GPT/Self Run/Runs/' "$SETUP"
! grep -Fq '/GPT/Project/Vibe Coding/00_System/SelfRun/Runs/' "$SETUP"
grep -Fq 'DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"' "$AUTH"
! grep -Fq '"https://www.googleapis.com/auth/drive";' "$AUTH"
! grep -Fq '"https://www.googleapis.com/auth/drive.readonly";' "$AUTH"
grep -Fq 'MODE_VALUES = {SelfRunStore.MODE_CHAT, SelfRunStore.MODE_WORK}' "$ACTIVITY"
grep -Fq 'setMinLines(8)' "$ACTIVITY"
grep -Fq 'setVerticalScrollBarEnabled(false)' "$ACTIVITY"
grep -Fq 'descendantTopWithinScrollContent' "$ACTIVITY"
grep -Fq 'outer.getPaddingBottom()' "$ACTIVITY"
grep -Fq 'outer.scrollTo(' "$ACTIVITY"
grep -Fq 'addTextChangedListener' "$ACTIVITY"
if grep -Fq 'setMaxLines(24)' "$ACTIVITY"; then echo 'command editor must grow with content instead of owning a bounded nested vertical scroll' >&2; exit 1; fi
if grep -Fq 'configureNestedCommandScrolling' "$ACTIVITY"; then echo 'nested command scrolling is forbidden; outer ScrollView owns vertical scrolling' >&2; exit 1; fi
if grep -Fq 'requestRectangleOnScreen' "$ACTIVITY"; then echo 'generic descendant visibility requests are insufficient for the IME-reserved ScrollView viewport' >&2; exit 1; fi
if grep -Fq 'getLocationOnScreen' "$ACTIVITY" || grep -Fq 'WindowInsets' "$ACTIVITY"; then echo 'command visibility must be computed in ScrollView content coordinates, not mixed screen/inset coordinates' >&2; exit 1; fi
if grep -Fq -- '-editor.getScrollY()' "$ACTIVITY"; then echo 'command caret content coordinates must not pre-subtract editor scroll state' >&2; exit 1; fi
if grep -Fq 'Math.min(editor.getHeight()' "$ACTIVITY"; then echo 'caret coordinates must not be clamped to the editor viewport' >&2; exit 1; fi
grep -Fq 'RUN_SUFFIX_LENGTH = 6' "$ACTIVITY"
grep -Fq 'TimeZone.getTimeZone("Asia/Seoul")' "$ACTIVITY"
! grep -Fq 'UUID.randomUUID' "$ACTIVITY"
grep -Fq 'driveContinuation' "$PROTOCOL"
grep -Fq 'kstTimestamp' "$PROTOCOL"
grep -Fq 'DriveSignalParser.scan' "$SERVICE"
grep -Fq 'driveSignalCursor' "$STORE"
grep -Fq 'PHASE_RESUME_BASELINE' "$STORE"
grep -Fq 'beginManualResumeOverride' "$SERVICE"
grep -Fq 'baselineManualResume' "$STORE"
grep -Fq 'CONTINUATION_VERIFY_INTERVAL_MS = 250L' "$SERVICE"
grep -Fq 'CONTINUATION_FAILURE_MS = 2_500L' "$SERVICE"
! grep -Fq 'CONTINUATION_GUARD_MS' "$SERVICE"
! grep -Fq 'BOOTSTRAP_COMMAND_ACK_RETRY_MS' "$SERVICE"
! grep -Fq 'prepareCommandRetry' "$SERVICE"
! grep -Fq 'markCommandSubmitted' "$STORE"
! grep -Fq 'guardRunnable' "$SERVICE"
! grep -Fq 'scheduleGuard()' "$SERVICE"
! grep -Fq 'guardElapsed()' "$SERVICE"
grep -Fq 'SelfRunContinuationDom.buttonState' "$SERVICE"
grep -Fq 'SelfRunContinuationDom.verifyDriveTurnSubmission' "$SERVICE"
grep -Fq 'SelfRunContinuationDom.verifyBootstrapSubmission' "$SERVICE"
grep -Fq 'PHASE_WAIT_INTERNAL_SEND' "$STORE"
grep -Fq 'command_received_ack=unused' "$SERVICE"
grep -Fq 'migrateLegacyContinuationAckWait' "$STORE"
! grep -Fq 'String ph=RETRY_BOOTSTRAP.equals(k)?PHASE_BOOTSTRAP_SEND:PHASE_SEND_CONTINUE' "$STORE"
grep -Fq 'store.phaseStartedAt()' "$SERVICE"
grep -Fq 'SEND_ENABLED' "$CONTINUE_DOM"
grep -Fq 'STOP' "$CONTINUE_DOM"
grep -Fq 'SEND_DISABLED' "$CONTINUE_DOM"
grep -Fq 'UNKNOWN' "$CONTINUE_DOM"
grep -Fq 'CONTINUE_CLICKED' "$CONTINUE_DOM"
grep -Fq 'SUBMISSION_CONFIRMED' "$CONTINUE_DOM"
grep -Fq 'SUBMISSION_FAILED' "$CONTINUE_DOM"
grep -Fq 'data-message-author-role' "$CONTINUE_DOM"
grep -Fq 'scheduleDrivePoll(0L)' "$SERVICE"
! grep -Fq 'DriveCommitParser' "$SERVICE"
! grep -Fq 'DriveInitialDocument' "$SERVICE"
! grep -Fq 'checkDriveTurnSubmitted' "$SERVICE"
! grep -Fq 'checkDriveInitialSubmitted' "$SERVICE"
! grep -Fq 'SUBMISSION_CONFIRMATION_GRACE_MS' "$SERVICE"
! grep -Fq 'EVENT_SEQ' "$PARSER"
! grep -Fq 'PROTOCOL_VERSION' "$PARSER"
! grep -Fq 'COMMIT_KIND' "$PARSER"
! grep -Fq 'SIGNAL_BEGIN' "$PARSER"
! grep -Fq 'SELF_RUN_DRIVE_COMMIT_V1' "$PARSER"
grep -Fq 'findSingleTurnDocument' "$API"
grep -Fq '.put("job_id", name)' "$API"
grep -Fq '.put("selfrun_kind", kind)' "$API"
! grep -Fq '.put("protocol_version"' "$API"
! grep -Fq '.put("client_id"' "$API"
! grep -Fq '.put("created_by"' "$API"
grep -Fq 'RUNNING_CHANNEL = "selfrun-drive-running-v2"' "$NOTIFICATION"
grep -Fq 'ALERT_CHANNEL = "selfrun-drive-alerts-v2"' "$NOTIFICATION"
grep -Fq 'NotificationManager.IMPORTANCE_LOW' "$NOTIFICATION"
grep -Fq 'running.setSound(null, null)' "$NOTIFICATION"
grep -Fq 'running.enableVibration(false)' "$NOTIFICATION"
grep -Fq '.setContentText("SelfRun 작업 중")' "$NOTIFICATION"
grep -Fq 'NotificationManager.IMPORTANCE_HIGH' "$NOTIFICATION"
grep -Fq 'static Notification active(Context context)' "$NOTIFICATION"
! grep -Fq 'runtimeStatus' "$NOTIFICATION"
! grep -Fq 'maybeNotifyPause' "$NOTIFICATION"
! grep -Fq 'SystemClock' "$NOTIFICATION"
grep -Fq 'NotificationHelper.notifyUser(this, "일시정지", store.status())' "$SERVICE"
grep -Fq 'case "PAUSED"->finishPersistedTerminalPause("DRIVE_PAUSED","일시정지"' "$SERVICE"
grep -Fq 'case "USER_ACTION_REQUIRED"->finishPersistedTerminalPause("DRIVE_USER_ACTION_REQUIRED","확인 필요"' "$SERVICE"
TRANSITION_BLOCK="$(sed -n '/private void transition/,/private void pauseError/p' "$SERVICE")"
if grep -Fq 'startForegroundCompat();' <<<"$TRANSITION_BLOCK"; then echo 'routine transitions must not repost the foreground notification' >&2; exit 1; fi
BOOTSTRAP_BLOCK="$(sed -n '/private void bootstrapSubmitted/,/private String commandPrompt/p' "$SERVICE")"
if grep -Fq 'startForegroundCompat();' <<<"$BOOTSTRAP_BLOCK"; then echo 'command submission must not repost the foreground notification' >&2; exit 1; fi
FG_POST_COUNT="$(grep -o 'startForegroundCompat();' "$SERVICE" | wc -l | tr -d ' ')"
[[ "$FG_POST_COUNT" == '4' ]]
echo "SelfRun Drive ${VERSION_NAME} policy checks passed (versionCode=${VERSION_CODE})."
