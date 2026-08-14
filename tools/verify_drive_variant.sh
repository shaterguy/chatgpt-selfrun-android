#!/usr/bin/env bash
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"
BUILD=app/build.gradle
SRC=app/src/main/java/com/shaterguy/chatgptselfrun
SERVICE=$SRC/SelfRunService.java
STORE=$SRC/SelfRunStore.java
PROTOCOL=$SRC/SelfRunProtocol.java
PARSER=$SRC/DriveCommitParser.java
ACTIVITY=$SRC/SelfRunNewActivity.java
API=$SRC/DriveApiClient.java
NOTIFICATION=$SRC/NotificationHelper.java

grep -Fq "applicationId 'com.shaterguy.chatgptselfrun.drive'" "$BUILD"
grep -Fq 'selfRunDriveVersionCode = 1000006' "$BUILD"
grep -Fq "selfRunDriveVersionName = '1.1.0-dev2'" "$BUILD"
grep -Fq 'MODE_VALUES = {SelfRunStore.MODE_CHAT, SelfRunStore.MODE_WORK}' "$ACTIVITY"
grep -Fq 'setMinLines(8)' "$ACTIVITY"
grep -Fq 'setVerticalScrollBarEnabled(false)' "$ACTIVITY"
grep -Fq 'descendantTopWithinScrollContent' "$ACTIVITY"
grep -Fq 'outer.getPaddingBottom()' "$ACTIVITY"
grep -Fq 'outer.scrollTo(' "$ACTIVITY"
grep -Fq 'addTextChangedListener' "$ACTIVITY"
if grep -Fq 'setMaxLines(24)' "$ACTIVITY"; then
  echo 'command editor must grow with content instead of owning a bounded nested vertical scroll' >&2
  exit 1
fi
if grep -Fq 'configureNestedCommandScrolling' "$ACTIVITY"; then
  echo 'nested command scrolling is forbidden; outer ScrollView owns vertical scrolling' >&2
  exit 1
fi
if grep -Fq 'requestRectangleOnScreen' "$ACTIVITY"; then
  echo 'generic descendant visibility requests are insufficient for the IME-reserved ScrollView viewport' >&2
  exit 1
fi
if grep -Fq 'getLocationOnScreen' "$ACTIVITY" || grep -Fq 'WindowInsets' "$ACTIVITY"; then
  echo 'command visibility must be computed in ScrollView content coordinates, not mixed screen/inset coordinates' >&2
  exit 1
fi
if grep -Fq -- '-editor.getScrollY()' "$ACTIVITY"; then
  echo 'command caret content coordinates must not pre-subtract editor scroll state' >&2
  exit 1
fi
if grep -Fq 'Math.min(editor.getHeight()' "$ACTIVITY"; then
  echo 'caret coordinates must not be clamped to the editor viewport' >&2
  exit 1
fi
grep -Fq 'RUN_SUFFIX_LENGTH = 6' "$ACTIVITY"
grep -Fq 'TimeZone.getTimeZone("Asia/Seoul")' "$ACTIVITY"
! grep -Fq 'UUID.randomUUID' "$ACTIVITY"
grep -Fq 'SELF_RUN_COMMAND_RECEIVED' "$PROTOCOL"
grep -Fq 'driveContinuation' "$PROTOCOL"
grep -Fq 'kstTimestamp' "$PROTOCOL"
grep -Fq 'DriveSignalParser.scan' "$SERVICE"
grep -Fq 'driveSignalCursor' "$STORE"
grep -Fq 'PHASE_RESUME_BASELINE' "$STORE"
grep -Fq 'beginManualResumeOverride' "$SERVICE"
grep -Fq 'baselineManualResume' "$STORE"
grep -Fq 'CONTINUATION_GUARD_MS = 45_000L' "$SERVICE"
grep -Fq 'SUBMISSION_RETRY_MS = 5 * 60_000L' "$SERVICE"
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
if grep -Fq 'startForegroundCompat();' <<<"$TRANSITION_BLOCK"; then
  echo 'routine transitions must not repost the foreground notification' >&2
  exit 1
fi
COMMAND_BLOCK="$(grep -F 'private void commandSubmitted' "$SERVICE")"
if grep -Fq 'startForegroundCompat();' <<<"$COMMAND_BLOCK"; then
  echo 'command submission must not repost the foreground notification' >&2
  exit 1
fi
FG_POST_COUNT="$(grep -o 'startForegroundCompat();' "$SERVICE" | wc -l | tr -d ' ')"
[[ "$FG_POST_COUNT" == '4' ]]
echo 'SelfRun Drive v1.1.0-dev2 policy checks passed.'
