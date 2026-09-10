#!/usr/bin/env bash
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"

BUILD=app/build.gradle
MANIFEST=app/src/main/AndroidManifest.xml
SRC=app/src/main/java/com/shaterguy/chatgptselfrun
UNIT_TEST=app/src/test/java/com/shaterguy/chatgptselfrun
ANDROID_TEST=app/src/androidTest/java/com/shaterguy/chatgptselfrun
SERVICE=$SRC/SelfRunService.java
COORD=$SRC/SelfRun3Coordinator.java
ENGINE=$SRC/SelfRun3Engine.java
ENGINE_TEST=$UNIT_TEST/SelfRun3EngineTest.java
LEDGER=$SRC/SelfRun3Ledger.java
CONTRACT=$SRC/SelfRun3Protocol.java
DRIVE=$SRC/SelfRun3DriveAdapter.java
WATCHDOG=$SRC/SelfRun3ResultWatchdog.java
SETTINGS=$SRC/SelfRun3RuntimeSettings.java
STALL=$SRC/SelfRun3TurnStallPolicy.java
NOTIFIER=$SRC/SelfRun3TurnStallNotifier.java
USER_NEXT=$SRC/UserNextInputStore.java
LOOKUP=$SRC/SelfRun3DriveLookup.java
WEB=$SRC/SelfRun3WebAdapter.java
DISPATCH=$SRC/SelfRun3DispatchScript.java
BOOTSTRAP=$SRC/SelfRun3BootstrapTransport.java
POWER=$SRC/SelfRun3PowerPolicy.java
STRICT=$SRC/SelfRun3StrictJson.java
MARKER=$SRC/SelfRun3RunMarker.java
INPUT=$SRC/SelfRun3UserInput.java
STORE=$SRC/SelfRunStore.java
ACTIVITY=$SRC/SelfRunNewActivity.java
SETUP=$SRC/DriveSetupActivity.java
AUTH=$SRC/DriveAuthorization.java
WEB_CONFIG=$SRC/WebViewConfig.java
HEADLESS=$SRC/HeadlessWebViewHost.java
PROFILE=$SRC/RequestProfileScript.java
PROFILE_REGISTRY=$SRC/ProfileRegistry.java
RUNNER=$ANDROID_TEST/SelfRunAndroidTestRunner.java
FIRST_TEST=$ANDROID_TEST/SelfRun31FirstConversationAndroidTest.java
UNBOUNDED_TEST=$UNIT_TEST/SelfRun3UnboundedPayloadPolicyTest.java
SETTINGS_TEST=$UNIT_TEST/SelfRun3RuntimeSettingsTest.java
SETTINGS_ANDROID_TEST=$ANDROID_TEST/SelfRun3RuntimeSettingsAndroidTest.java
TEST_SIGN=tools/sign_test.sh

VERSION_CODE="$(sed -n 's/.*selfRunDriveVersionCode = \([0-9][0-9]*\).*/\1/p' "$BUILD" | head -1)"
VERSION_NAME="$(sed -n "s/.*selfRunDriveVersionName = '\([^']*\)'.*/\1/p" "$BUILD" | head -1)"
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]]
[[ "$VERSION_CODE" -gt 2020048 ]]
[[ "$VERSION_NAME" =~ ^3\.1\.[0-9]+(-(dev|rc)[0-9]+)?$ ]]
grep -Fq "applicationId 'com.shaterguy.chatgptselfrun.drive'" "$BUILD"
grep -Fq "applicationIdSuffix '.test'" "$BUILD"
grep -Fq "selfRunAppLabel: 'SelfRun Drive TEST'" "$BUILD"
grep -Fq 'android:label="${selfRunAppLabel}"' "$MANIFEST"
! grep -Fq 'android:sharedUserId' "$MANIFEST"

for file in "$SERVICE" "$COORD" "$ENGINE" "$ENGINE_TEST" "$LEDGER" "$CONTRACT" "$DRIVE" "$WATCHDOG" "$SETTINGS" "$STALL" "$NOTIFIER" "$USER_NEXT" "$LOOKUP" "$WEB" "$DISPATCH" "$BOOTSTRAP" "$POWER" "$STRICT" "$MARKER" "$INPUT" "$RUNNER" "$FIRST_TEST" "$UNBOUNDED_TEST" "$SETTINGS_TEST" "$SETTINGS_ANDROID_TEST"; do
  test -s "$file"
done

grep -Fq 'SelfRun3Coordinator' "$SERVICE"
grep -Fq 'V3_STALE_RUN_RETIRED' "$SERVICE"
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
grep -Fq 'activeCount(original)<2' "$ENGINE"
grep -Fq 'maybeMerge' "$ENGINE"
grep -Fq 'routingPhase' "$ENGINE"
grep -Fq 'usableParallelPlan' "$ENGINE"
! grep -Fq 'full handoff missing' "$ENGINE"
! grep -Fq 'verification required for DONE' "$ENGINE"
! grep -Fq 'branch identity mismatch' "$ENGINE"
grep -Fq 'case ENDED -> { return original; }' "$ENGINE"
grep -Fq 'case RESULT_BASELINE' "$ENGINE"
grep -Fq 'case RESULT_MUTATED' "$ENGINE"
grep -Fq 'canonicalPostConfirmedElapsed' "$ENGINE"
grep -Fq 'resultSeedFingerprint' "$ENGINE"
grep -Fq 'resultBodyMutationObserved' "$ENGINE"
grep -Fq 's.flag("superseded") && e.kind!=Kind.RESOURCE' "$ENGINE"
! grep -Fq 'invalid phase transition' "$ENGINE"
grep -Fq 'canonicalContinueNextPhaseCanBypassLegacyTransitionTable' "$ENGINE_TEST"
grep -Fq 'unknownNextPhaseDoesNotInvalidateCommittedResult' "$ENGINE_TEST"
grep -Fq 'nonterminalDoneRoutingHintFallsBackInsteadOfRejectingTurn' "$ENGINE_TEST"
grep -Fq 'doneStatusIsTrustedAsAiDecisionInsteadOfRevalidatedByApp' "$ENGINE_TEST"
grep -Fq 'identityOnlyCommittedResultCreatesRecoveryTurn' "$ENGINE_TEST"

grep -Fq 'DEFAULT_RESULT_REPAIR_MINUTES * 60_000L' "$WATCHDOG"
grep -Fq 'state.stage() != SelfRun3Engine.Stage.WAITING' "$WATCHDOG"
grep -Fq 'canonicalPostBootCount' "$WATCHDOG"
grep -Fq 'resultBodyMutationObserved' "$WATCHDOG"
grep -Fq 'MessageDigest.getInstance("SHA-256")' "$WATCHDOG"
grep -Fq 'PREFS = "selfrun3_runtime_settings"' "$SETTINGS"
grep -Fq 'DEFAULT_RESULT_REPAIR_MINUTES = 120L' "$SETTINGS"
grep -Fq 'DEFAULT_STALL_ALERT_MINUTES = 125L' "$SETTINGS"
grep -Fq 'DEFAULT_RESULT_POLL_SECONDS = 30L' "$SETTINGS"
grep -Fq 'DEFAULT_WEB_PREPARATION_SECONDS = 90L' "$SETTINGS"
grep -Fq 'value <= Long.MAX_VALUE / multiplier' "$SETTINGS"
grep -Fq 'runtimeSettings.resultRepairMs()' "$COORD"
grep -Fq 'runtimeSettings.resultPollMs()' "$COORD"
grep -Fq 'runtimeSettings.stallAlertMs()' "$NOTIFIER"
grep -Fq 'settingsPrefs.registerOnSharedPreferenceChangeListener(settingsListener)' "$NOTIFIER"
grep -Fq 'prepareTimeoutMs = runtimeSettings.webPreparationMs()' "$WEB"
grep -Fq 'long alertAfterMs' "$STALL"
! grep -Fq 'NORMAL_WAIT_POLL_MS' "$POWER"
! grep -Fq 'WEB_PREPARATION_MAX_MS' "$POWER"

grep -Fq 'beginTransaction' "$LEDGER"
grep -Fq 'PreserveCorruptionHandler' "$LEDGER"
grep -Fq 'selfrun3-ledger.db' "$LEDGER"
grep -Fq 'getNoBackupFilesDir' "$LEDGER"
! grep -Fq 'deleteDatabase' "$LEDGER"

grep -Fq 'CONTRACT_VERSION="3.1.0"' "$CONTRACT"
grep -Fq 'SELF_RUN_SKILL_DOCUMENT_ID=SKILL_DOCUMENT_ID' "$CONTRACT"
grep -Fq 'SKILL_DOCUMENT_ID="1gktKYJzz4zW_M2gbJ7OsodaTE1lkx-pUtuFBV5fucJo"' "$CONTRACT"
for field in TASK_ID TURN_ID REQUEST_ID TURN PHASE TASK_MODE MODE EXECUTION_KIND SIGNAL_TYPE RESULT_DOCUMENT_ID REQUIREMENT_DOCUMENT_ID PREVIOUS_RESULT_DOCUMENT_ID FOLDER_ID RECEIPT EXECUTION_PROFILE; do
  grep -Fq "$field" "$CONTRACT"
done
grep -Fq 'compactProfileChoices' "$CONTRACT"
grep -Fq 'PROFILE_REGISTRY_CHAT' "$CONTRACT"
grep -Fq 'PROFILE_REGISTRY_WORK' "$CONTRACT"
grep -Fq 'PARALLEL_MERGE' "$CONTRACT"
grep -Fq 'REPAIR_TARGET_DOCUMENT_ID' "$CONTRACT"
grep -Fq '사용자 추가 지시 원문' "$CONTRACT"
! grep -Eq 'static[[:space:]]+final[[:space:]]+String[[:space:]]+CONTRACT[[:space:]]*=' "$CONTRACT"
! grep -Fq 'contract-version: 3.1.0' "$CONTRACT"
! grep -Fq 'RESULT_IDENTITY_TEMPLATE' "$CONTRACT"
! grep -Fq 'SelfRun3Engine.emptyResult' "$CONTRACT"
! grep -Fq 'ProfileRegistry.exportChatJson' "$CONTRACT"
! grep -Fq 'ProfileRegistry.exportWorkJson' "$CONTRACT"
! grep -Fq '[최초 요구사항 원문]' "$CONTRACT"
! grep -Fq 'optString("requirement")' "$CONTRACT"
! grep -Fq 'phase_completed' "$CONTRACT"
! grep -Fq 'full checkpoint' "$CONTRACT"
! grep -Fq 'USER_ACTION_RESOLVED' "$CONTRACT"

grep -Fq 'SelfRun3DriveLookup.findSingleDocumentId' "$DRIVE"
grep -Fq 'DOCUMENT_CREATE_UNCONFIRMED' "$DRIVE"
grep -Fq 'REQUIREMENT_READBACK_MISMATCH' "$DRIVE"
grep -Fq 'static final class ResultObservation' "$DRIVE"
grep -Fq 'ResultObservation observeResult(' "$DRIVE"
grep -Fq 'SelfRun3ResultWatchdog.fingerprint(existing.text)' "$DRIVE"
grep -Fq 'acquireResultReadWakeLock();' "$DRIVE"
grep -Fq 'PowerManager.PARTIAL_WAKE_LOCK' "$DRIVE"
grep -Fq ':selfrun3-result-read' "$DRIVE"
grep -Fq 'resultReadWakeLock.acquire(SelfRun3PowerPolicy.WAKE_LOCK_MAX_MS)' "$DRIVE"
grep -Fq 'releaseResultReadWakeLock();' "$DRIVE"
grep -Fq 'InvalidCommittedResultException' "$DRIVE"
! grep -Fq 'DriveSignalParser' "$DRIVE"
! grep -Fq 'MAX_RESULT_BYTES' "$ENGINE"
! grep -Fq 'MAX_RESULT_BYTES' "$DRIVE"
! grep -Fq 'MAX_USER_UTF8_BYTES' "$USER_NEXT"
! grep -Fq 'MAX_COMBINED_UTF8_BYTES' "$USER_NEXT"
! grep -Fq 'withinUtf8Limit' "$USER_NEXT"
! grep -Fq 'MAX_ATTACHMENTS_PER_RUN' "$STORE"
! grep -Fq 'MAX_ATTACHMENT_BYTES' "$STORE"
! grep -Fq 'MAX_ATTACHMENT_BYTES' "$DRIVE"
! grep -Fq 'ATTACHMENT_TOO_LARGE' "$DRIVE"
! grep -Fq 'MAX_ATTACHMENTS_PER_RUN' "$ACTIVITY"
grep -Fq 'turnReadyAcceptsPromptBeyondFormerOneMiBGate' "$UNBOUNDED_TEST"
grep -Fq 'committedResultBeyondFormer512KiBGateKeepsStrictIdentityChecks' "$UNBOUNDED_TEST"
grep -Fq 'additionalInputMergeExceedsFormer64KiBWithoutTruncation' "$UNBOUNDED_TEST"
grep -Fq 'DriveAndPickerContainNoFormerAttachmentOrResultCeilings' "$UNBOUNDED_TEST"
grep -Fq 'positiveIntegerParserRejectsBlankZeroNegativeTextAndOverflow' "$SETTINGS_TEST"
grep -Fq 'committedSettingsSurviveNewSettingsInstanceAndUseRequestedUnits' "$SETTINGS_ANDROID_TEST"
grep -Fq 'multiple V3 documents match exact identity' "$LOOKUP"
grep -Fq 'isAppAuthorized' "$LOOKUP"
grep -Fq 'MAX_RESPONSE_BYTES = 256 * 1024' "$LOOKUP"
grep -Fq 'www.googleapis.com' "$LOOKUP"
! grep -Fq 'http://' "$LOOKUP"

grep -Fq 'SelfRun3BootstrapTransport.prepare' "$WEB"
grep -Fq 'SelfRun3BootstrapTransport.submit' "$WEB"
grep -Fq 'RequestProfileScript.beginTarget' "$WEB"
grep -Fq 'RequestProfileScript.setChatReasoning' "$WEB"
grep -Fq 'SelfRun3DispatchScript.arm' "$WEB"
! grep -Fq 'SelfRunContinuationDom.prepareBootstrap' "$WEB"
! grep -Fq 'SelfRun3ComposerTransport.prepareContinuation' "$WEB"
! grep -Fq 'message_stream_complete' "$WEB"

grep -Fq 'const result=nativeFetch(input,init)' "$DISPATCH"
grep -Fq 'const result=send.call(this,body)' "$DISPATCH"
! grep -Fq 'throw new Error' "$DISPATCH"
! grep -Fq 'reject()' "$DISPATCH"
! grep -Fq 'captureIdentity' "$DISPATCH"
! grep -Fq 'response.clone' "$DISPATCH"

grep -Fq "state:'prepared'" "$BOOTSTRAP"
grep -Fq 'READY_TO_SUBMIT' "$BOOTSTRAP"
grep -Fq 'baselineUserCount' "$BOOTSTRAP"
! grep -Fq 'scheduleMissedProbe' "$COORD"
! grep -Fq 'web.receipt' "$COORD"
grep -Fq 'drive.observeResult' "$COORD"
grep -Fq 'SelfRun3ResultWatchdog.shouldRepair' "$COORD"
grep -Fq 'STALE_RESULT_WATCHDOG' "$COORD"
grep -Fq '"source", "canonical_post"' "$COORD"
grep -Fq 'Settings.Global.BOOT_COUNT' "$COORD"
grep -Fq 'web.detach();' "$COORD"
grep -Fq 'SelfRun3RunMarker' "$COORD"
grep -Fq 'SelfRun3UserInput' "$COORD"
grep -Fq 'clearRecoveredDriveWarning(store);' "$COORD"
grep -Fq 'service.stopForeground(Service.STOP_FOREGROUND_REMOVE);' "$COORD"
grep -Fq 'NotificationHelper.notifyUser(service, "작업 완료"' "$COORD"
grep -Fq 'WAKE_LOCK_MAX_MS = 90_000L' "$POWER"
grep -Fq 'modelLabel + " · " + reasoningLabel' "$PROFILE_REGISTRY"
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
grep -Fq 'MODE_VALUES = {SelfRunStore.MODE_CHAT, SelfRunStore.MODE_WORK, SelfRunStore.MODE_HYBRID}' "$ACTIVITY"
grep -Fq 'static final String MODE_HYBRID = "HYBRID";' "$STORE"
grep -Fq 'String taskMode()' "$STORE"
grep -Fq 'void setExecutionProjection(' "$STORE"
! grep -Fq 'LegacyRunModeMigration.migrateCurrent' "$STORE"
grep -Fq 'RequestProfileScript.installDocumentStart' "$WEB_CONFIG"
grep -Fq 'SelfRun3DispatchScript.install' "$WEB_CONFIG"
grep -Fq 'virtualDisplay.setSurface(null)' "$HEADLESS"
grep -Fq 'virtualDisplay.setSurface(surface)' "$HEADLESS"
grep -Fq "conversationRoute" "$PROFILE"

grep -Fq 'SelfRun31FirstConversationAndroidTest' "$RUNNER"
grep -Fq 'SelfRun3UpgradePersistenceAndroidTest#' "$RUNNER"
grep -Fq 'seedUpgradeState' "$RUNNER"
grep -Fq 'verifyUpgradeState' "$RUNNER"
grep -Fq 'SelfRun3BootstrapTransport.prepare' "$FIRST_TEST"
grep -Fq 'SelfRun3BootstrapTransport.submit' "$FIRST_TEST"
grep -Fq '/backend-api/f/conversation' "$FIRST_TEST"
grep -Fq 'detachOutput()' "$FIRST_TEST"
grep -Fq '2c95a5644a0ef2959eaecf10460e300fe2ee7a4ebcede685a82a52634c22e86e' "$TEST_SIGN"

! grep -Fq 'DriveSignalParser.scan' "$SERVICE"
! grep -Fq 'driveSignalCursor' "$SERVICE"
! grep -Fq 'pollDrive' "$SERVICE"
! grep -Fq 'rolloverConversation' "$SERVICE"
! grep -Fq 'CONTINUATION_VERIFY_INTERVAL_MS' "$SERVICE"

echo "SelfRun Drive ${VERSION_NAME} V3.1 policy checks passed (versionCode=${VERSION_CODE})."
