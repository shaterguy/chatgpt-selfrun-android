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

grep -Fq "applicationId 'com.shaterguy.chatgptselfrun.drive'" "$BUILD"
grep -Fq 'selfRunDriveVersionCode = 1000004' "$BUILD"
grep -Fq "selfRunDriveVersionName = '1.0.0'" "$BUILD"
grep -Fq 'MODE_VALUES = {SelfRunStore.MODE_CHAT, SelfRunStore.MODE_WORK}' "$ACTIVITY"
grep -Fq 'setMinLines(8)' "$ACTIVITY"
grep -Fq 'setVerticalScrollBarEnabled(false)' "$ACTIVITY"
grep -Fq 'requestRectangleOnScreen(new Rect(left,top,right,bottom),true)' "$ACTIVITY"
grep -Fq 'addTextChangedListener' "$ACTIVITY"
if grep -Fq 'setMaxLines(24)' "$ACTIVITY"; then
  echo 'command editor must grow with content instead of owning a bounded nested vertical scroll' >&2
  exit 1
fi
if grep -Fq 'configureNestedCommandScrolling' "$ACTIVITY"; then
  echo 'nested command scrolling is forbidden; outer ScrollView owns vertical scrolling' >&2
  exit 1
fi
if grep -Fq 'scrollCommandWindowToCaret' "$ACTIVITY"; then
  echo 'manual screen-coordinate outer scrolling is forbidden' >&2
  exit 1
fi
if grep -Fq -- '-editor.getScrollY()' "$ACTIVITY"; then
  echo 'requestRectangleOnScreen caret rectangle must remain in editor content coordinates' >&2
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
echo 'SelfRun Drive v1.0.0 policy checks passed.'
