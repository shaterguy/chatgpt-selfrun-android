#!/usr/bin/env bash
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"

BUILD=app/build.gradle
MANIFEST=app/src/main/AndroidManifest.xml
SRC=app/src/main/java/com/shaterguy/chatgptselfrun
SERVICE=$SRC/SelfRunService.java
COORD=$SRC/SelfRun3Coordinator.java
ENGINE=$SRC/SelfRun3Engine.java
LEDGER=$SRC/SelfRun3Ledger.java
PROTOCOL=$SRC/SelfRun3Protocol.java
DRIVE=$SRC/SelfRun3DriveAdapter.java
LOOKUP=$SRC/SelfRun3DriveLookup.java
WEB=$SRC/SelfRun3WebAdapter.java
POWER=$SRC/SelfRun3PowerPolicy.java
STRICT=$SRC/SelfRun3StrictJson.java
MARKER=$SRC/SelfRun3RunMarker.java
MARKER_FACADE=$SRC/SelfRunSignalTransport.java
INPUT=$SRC/SelfRun3UserInput.java
ACTIVITY=$SRC/SelfRunNewActivity.java
SETUP=$SRC/DriveSetupActivity.java
AUTH=$SRC/DriveAuthorization.java
WEB_CONFIG=$SRC/WebViewConfig.java
HEADLESS=$SRC/HeadlessWebViewHost.java
PROFILE=$SRC/RequestProfileScript.java
TURN_PROTOCOL=$SRC/ChatGptTurnProtocolScript.java
TEST_SIGN=tools/sign_test.sh

VERSION_CODE="$(sed -n 's/.*selfRunDriveVersionCode = \([0-9][0-9]*\).*/\1/p' "$BUILD" | head -1)"
VERSION_NAME="$(sed -n "s/.*selfRunDriveVersionName = '\([^']*\)'.*/\1/p" "$BUILD" | head -1)"
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]]
[[ "$VERSION_CODE" -gt 2020048 ]]
[[ "$VERSION_NAME" =~ ^3\.0\.[0-9]+-(dev|rc)[0-9]+$|^3\.0\.[0-9]+$ ]]
grep -Fq "applicationId 'com.shaterguy.chatgptselfrun.drive'" "$BUILD"
grep -Fq "applicationIdSuffix '.test'" "$BUILD"
grep -Fq "selfRunAppLabel: 'SelfRun Drive TEST'" "$BUILD"
grep -Fq 'android:label="${selfRunAppLabel}"' "$MANIFEST"
! grep -Fq 'android:sharedUserId' "$MANIFEST"

for file in "$SERVICE" "$COORD" "$ENGINE" "$LEDGER" "$PROTOCOL" "$DRIVE" "$LOOKUP" "$WEB" "$POWER" "$STRICT" "$MARKER" "$MARKER_FACADE" "$INPUT"; do
  test -s "$file"
done

grep -Fq 'SelfRun3Coordinator' "$SERVICE"
grep -Fq 'V3_STALE_RUN_RETIRED' "$SERVICE"
! grep -Fq 'V3_LEGACY_RUN_PRESERVED' "$SERVICE"
! grep -Fq 'SelfRunRolloverCoordinator' "$SERVICE"
! grep -Fq 'PHASE_POST_PROTOCOL_DRIVE_SYNC' "$SERVICE"
for action in RUN PAUSE RESUME STOP; do
  grep -Fq "BuildConfig.APPLICATION_ID + \".${action}\"" "$SERVICE"
done

grep -Fq 'STATE_SCHEMA = "selfrun-task-state-v3"' "$ENGINE"
grep -Fq 'RESULT_SCHEMA = "selfrun-turn-result-v3"' "$ENGINE"
grep -Fq 'lastConsumedInputRevision' "$ENGINE"
grep -Fq 'lateInput' "$ENGINE"
grep -Fq 'SelfRun3StrictJson.parseObject' "$ENGINE"
grep -Fq 'sendClaimed' "$ENGINE"
grep -Fq 'dispatchObserved' "$ENGINE"
grep -Fq 'receipt_readback' "$ENGINE"

grep -Fq 'beginTransaction' "$LEDGER"
grep -Fq 'PreserveCorruptionHandler' "$LEDGER"
grep -Fq 'selfrun3-ledger.db' "$LEDGER"
grep -Fq 'getNoBackupFilesDir' "$LEDGER"
! grep -Fq 'deleteDatabase' "$LEDGER"

grep -Fq 'CONTRACT_VERSION = "3.0.0"' "$PROTOCOL"
grep -Fq 'SELF_RUN_SKILL_DOCUMENT_ID' "$PROTOCOL"
grep -Fq '1qPTSmJG8GpXMSyIGm6SIpgx6-LtWCBGVW3WUpoKj9fs' "$PROTOCOL"
grep -Fq 'RESULT_DOCUMENT_ID' "$PROTOCOL"
grep -Fq 'REQUIREMENT_DOCUMENT_ID' "$PROTOCOL"
grep -Fq 'PREVIOUS_RESULT_DOCUMENT_ID' "$PROTOCOL"
grep -Fq 'WORK_PROFILE_CHOICES' "$PROTOCOL"
grep -Fq 'compactWorkChoices' "$PROTOCOL"
! grep -Fq 'static final String CONTRACT' "$PROTOCOL"
! grep -Fq 'RESULT_IDENTITY_TEMPLATE' "$PROTOCOL"
! grep -Fq 'ProfileRegistry.exportWorkJson' "$PROTOCOL"
! grep -Fq '[최초 요구사항 원문]' "$PROTOCOL"
! grep -Fq 'REQUEST_ID=' "$PROTOCOL"
! grep -Fq 'SELF_RUN_TURN_COMPLETED' "$PROTOCOL"

grep -Fq 'SelfRun3RunMarker.mark' "$MARKER_FACADE"
grep -Fq 'SelfRun3RunMarker.current' "$MARKER_FACADE"
! grep -Fq 'DriveSignalDocumentIdentity' "$MARKER_FACADE"
! grep -Fq 'SelfRunProtocolRules' "$MARKER_FACADE"
! grep -Fq 'getSharedPreferences' "$MARKER_FACADE"

grep -Fq 'SelfRun3DriveLookup.findSingleDocumentId' "$DRIVE"
grep -Fq 'DOCUMENT_CREATE_UNCONFIRMED' "$DRIVE"
grep -Fq 'SelfRun3Engine.emptyResult(s).toString()' "$DRIVE"
grep -Fq 'multiple V3 documents match exact identity' "$LOOKUP"
grep -Fq 'isAppAuthorized' "$LOOKUP"
grep -Fq 'MAX_RESPONSE_BYTES = 256 * 1024' "$LOOKUP"
grep -Fq 'www.googleapis.com' "$LOOKUP"
! grep -Fq 'http://' "$LOOKUP"

grep -Fq 'RequestProfileScript.setChatProfiles' "$WEB"
grep -Fq 'RequestProfileScript.setChatReasoning' "$WEB"
grep -Fq 'ChatGptTurnProtocolScript.bindTurnAndThen' "$WEB"
grep -Fq 'SelfRun3PowerPolicy.MISSED_CALLBACK_PROBE_MS' "$COORD"
grep -Fq 'scheduleMissedProbe' "$COORD"
grep -Fq 'SelfRun3RunMarker' "$COORD"
grep -Fq 'SelfRun3UserInput' "$COORD"
grep -Fq 'NORMAL_WAIT_POLL_MS = 0L' "$POWER"
grep -Fq 'MAX_MISSED_CALLBACK_PROBES = 3' "$POWER"
grep -Fq 'WAKE_LOCK_MAX_MS = 90_000L' "$POWER"
! grep -Fq 'while (true)' "$COORD"

grep -Fq 'duplicate object key' "$STRICT"
grep -Fq 'trailing object comma' "$STRICT"
grep -Fq 'object key must be a string' "$STRICT"
grep -Fq 'SelfRun3Protocol.CONTRACT_VERSION' "$MARKER"
grep -Fq 'selfrun_drive_user_next_input' "$INPUT"

grep -Fq '/GPT/Self Run/Runs/' "$SETUP"
grep -Fq 'DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"' "$AUTH"
! grep -Fq '"https://www.googleapis.com/auth/drive";' "$AUTH"
! grep -Fq '"https://www.googleapis.com/auth/drive.readonly";' "$AUTH"
grep -Fq 'MODE_VALUES = {SelfRunStore.MODE_CHAT, SelfRunStore.MODE_WORK}' "$ACTIVITY"
grep -Fq 'RequestProfileScript.installDocumentStart' "$WEB_CONFIG"
grep -Fq 'ChatGptTurnProtocolScript.installDocumentStart' "$WEB_CONFIG"
grep -Fq 'virtualDisplay.setSurface(null)' "$HEADLESS"
grep -Fq 'virtualDisplay.setSurface(surface)' "$HEADLESS"
grep -Fq 'conversationRoute' "$PROFILE"
grep -Fq 'bindTurn' "$TURN_PROTOCOL"
grep -Fq 'message_stream_complete' "$TURN_PROTOCOL"
grep -Fq 'finished_successfully_end_turn' "$TURN_PROTOCOL"
grep -Fq '2c95a5644a0ef2959eaecf10460e300fe2ee7a4ebcede685a82a52634c22e86e' "$TEST_SIGN"

for retired in \
  .github/workflows/build-drive-test.yml \
  .github/workflows/build-drive-v1.yml \
  .github/workflows/build-selfrun-v2-test.yml \
  .github/workflows/build-selfrun-v3-test.yml \
  .github/workflows/release-drive-v1.yml \
  docs/SELF_RUN_DRIVE_V1_PROTOCOL.md \
  docs/SELF_RUN_DRIVE_RUNTIME.md; do
  test ! -e "$retired"
done

# SelfRun 3 must never restore the retired title-signal executor into the foreground service.
! grep -Fq 'DriveSignalParser.scan' "$SERVICE"
! grep -Fq 'driveSignalCursor' "$SERVICE"
! grep -Fq 'pollDrive' "$SERVICE"
! grep -Fq 'rolloverConversation' "$SERVICE"
! grep -Fq 'CONTINUATION_VERIFY_INTERVAL_MS' "$SERVICE"

echo "SelfRun Drive ${VERSION_NAME} V3-only policy checks passed (versionCode=${VERSION_CODE})."
