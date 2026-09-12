#!/usr/bin/env bash
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"

SRC=app/src/main/java/com/shaterguy/chatgptselfrun
UNIT=app/src/test/java/com/shaterguy/chatgptselfrun
ANDROID=app/src/androidTest/java/com/shaterguy/chatgptselfrun
BUILD=app/build.gradle
WORKFLOW=.github/workflows/build-selfrun-v3-candidate.yml
EMULATOR=tools/verify_selfrun3_candidate_emulator.sh

ENGINE=$SRC/SelfRun3Engine.java
DRIVE=$SRC/SelfRun3DriveAdapter.java
WATCHDOG=$SRC/SelfRun3ResultWatchdog.java
PROTOCOL=$SRC/SelfRun3Protocol.java
PROBE=$SRC/SelfRun3AttachmentSizeProbe.java
INPUT=$SRC/UserNextInputStore.java
IMMEDIATE=$SRC/UserImmediateInputCoordinator.java
STORE=$SRC/SelfRunStore.java
ACTIVITY=$SRC/SelfRunNewActivity.java
SETTINGS=$SRC/SelfRun3RuntimeSettings.java
COORD=$SRC/SelfRun3Coordinator.java
SERVICE=$SRC/SelfRunService.java
STOPPED_RESUME=$SRC/SelfRunStoppedResume.java
STALL=$SRC/SelfRun3TurnStallPolicy.java
NOTIFIER=$SRC/SelfRun3TurnStallNotifier.java
WEB=$SRC/SelfRun3WebAdapter.java
UNBOUNDED=$UNIT/SelfRun3UnboundedPayloadPolicyTest.java
WATCHDOG_UNIT=$UNIT/SelfRun3ResultWatchdogTest.java
ALWAYS_REPAIR_UNIT=$UNIT/SelfRun3AlwaysRepairPolicyTest.java
SETTINGS_UNIT=$UNIT/SelfRun3RuntimeSettingsTest.java
TURN_STALL_UNIT=$UNIT/SelfRun3TurnStallPolicyTest.java
STOPPED_RESUME_UNIT=$UNIT/SelfRunStoppedResumePolicyTest.java
PREPARATION_RECOVERY_UNIT=$UNIT/SelfRun3PreparationRecoveryWiringTest.java
CONVERSATION_GATE_UNIT=$UNIT/SelfRun3ConversationCreationGateTest.java
UNBOUNDED_ANDROID=$ANDROID/SelfRun3UnboundedLimitsAndroidTest.java
SETTINGS_ANDROID=$ANDROID/SelfRun3RuntimeSettingsAndroidTest.java
SETTINGS_PROCESS=$ANDROID/SelfRun3RuntimeSettingsProcessAndroidTest.java
UPGRADE_ANDROID=$ANDROID/SelfRun3UpgradePersistenceAndroidTest.java
STOPPED_RESUME_ANDROID=$ANDROID/SelfRunStoppedResumeAndroidTest.java

for file in "$ENGINE" "$DRIVE" "$WATCHDOG" "$PROTOCOL" "$PROBE" "$INPUT" "$IMMEDIATE" "$STORE" "$ACTIVITY" \
  "$SETTINGS" "$COORD" "$SERVICE" "$STOPPED_RESUME" "$STALL" "$NOTIFIER" "$WEB" "$UNBOUNDED" "$WATCHDOG_UNIT" "$ALWAYS_REPAIR_UNIT" "$SETTINGS_UNIT" "$TURN_STALL_UNIT" \
  "$STOPPED_RESUME_UNIT" "$PREPARATION_RECOVERY_UNIT" "$CONVERSATION_GATE_UNIT" "$UNBOUNDED_ANDROID" "$SETTINGS_ANDROID" "$SETTINGS_PROCESS" "$UPGRADE_ANDROID" "$STOPPED_RESUME_ANDROID" "$WORKFLOW" "$EMULATOR"; do
  test -s "$file"
done

grep -Fq "selfRunDriveVersionCode = 3023001" "$BUILD"
grep -Fq "selfRunDriveVersionName = '3.2.3-dev1'" "$BUILD"

# Former product payload ceilings must not survive in runtime code.
! grep -Fq 'MAX_RESULT_BYTES' "$ENGINE"
! grep -Fq 'MAX_RESULT_BYTES' "$DRIVE"
! grep -Fq 'MAX_USER_UTF8_BYTES' "$INPUT"
! grep -Fq 'MAX_COMBINED_UTF8_BYTES' "$INPUT"
! grep -Fq 'withinUtf8Limit' "$INPUT"
! grep -Fq 'MAX_USER_UTF8_BYTES' "$IMMEDIATE"
! grep -Fq 'MAX_ATTACHMENTS_PER_RUN' "$STORE"
! grep -Fq 'MAX_ATTACHMENT_BYTES' "$STORE"
! grep -Fq 'MAX_ATTACHMENT_BYTES' "$DRIVE"
! grep -Fq 'ATTACHMENT_TOO_LARGE' "$DRIVE"
! grep -Fq 'MAX_ATTACHMENTS_PER_RUN' "$ACTIVITY"
! grep -Fq 'attachment too large' "$ACTIVITY"
! grep -Fq 'utf8(p.optString("prompt"))' "$ENGINE"

# Result strictness and attachment streaming safety remain intact.
grep -Fq "raw.indexOf('\\0')<0" "$ENGINE"
grep -Fq 'SelfRun3StrictJson.parseObject' "$ENGINE"
grep -Fq 'result document mismatch' "$ENGINE"
grep -Fq 'boolean commit required' "$ENGINE"
grep -Fq 'SelfRun3AttachmentSizeProbe.count(in, permitted)' "$DRIVE"
grep -Fq 'BUFFER_BYTES = 64 * 1024' "$PROBE"
grep -Fq 'size > Long.MAX_VALUE - n' "$PROBE"
grep -Fq 'api.uploadAttachmentResumable' "$DRIVE"
grep -Fq 'MAX_ATTACHMENT_UPLOAD_ATTEMPTS = 3' "$STORE"

# Operator timings keep persisted overrides, while result auto-repair defaults to 10 minutes.
grep -Fq 'PREFS = "selfrun3_runtime_settings"' "$SETTINGS"
grep -Fq 'DEFAULT_RESULT_REPAIR_MINUTES = 10L' "$SETTINGS"
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
grep -Fq 'freshInstallDefaultsMatchCurrentBehavior' "$SETTINGS_ANDROID"
grep -Fq 'assertEquals(10L, settings.resultRepairMinutes())' "$SETTINGS_ANDROID"
grep -Fq 'assertTrue(settings.contains("DEFAULT_RESULT_REPAIR_MINUTES = 10L"));' "$TURN_STALL_UNIT"
! grep -Fq 'assertTrue(settings.contains("DEFAULT_RESULT_REPAIR_MINUTES = 120L"));' "$TURN_STALL_UNIT"

# The existing screen-preparation limit covers the whole conversation-creation stage through canonical /c binding.
grep -Fq 'scope=conversation-create' "$WEB"
grep -Fq 'expireConversationCreation(attempt)' "$WEB"
grep -Fq 'WEB_PREPARATION_WATCHDOG' "$WEB"
grep -Fq 'status=expired;attempt=' "$WEB"
grep -Fq 'WEB_PREPARATION_RECOVERY' "$WEB"
grep -Fq 'strategy=recreate-webview' "$WEB"
grep -Fq 'submitIssued = false' "$WEB"
grep -Fq 'conversationCaptured = false' "$WEB"
grep -Fq 'conversationCaptured = true' "$WEB"
grep -Fq 'listener.onConversation' "$WEB"
grep -Fq 'listener.onStarted' "$WEB"
grep -Fq 'web.loadUrl(SelfRun3ProjectDirectoryNavigation.entryUrl(target));' "$WEB"
grep -Fq 'case DISPATCHING -> s.resource("conversationUrl").isEmpty() ? Action.PREPARE_WEB : Action.WAIT;' "$ENGINE"
grep -Fq 'x.stage()==Stage.DISPATCHING && !x.resource("conversationUrl").isEmpty()' "$ENGINE"
grep -Fq 'request + ":claim:" + UUID.randomUUID()' "$COORD"
grep -Fq 'canonicalConversationIsTheOnlySuccessfulCreationGate' "$PREPARATION_RECOVERY_UNIT"
grep -Fq 'unboundDispatchReentersConversationCreationInsteadOfDrivePolling' "$PREPARATION_RECOVERY_UNIT"
grep -Fq 'dispatchedTurnWithoutCanonicalConversationReentersWebCreationAndCannotPollDrive' "$CONVERSATION_GATE_UNIT"
grep -Fq 'canonicalConversationMakesDispatchEligibleForNormalWaitAndDriveResultObservation' "$CONVERSATION_GATE_UNIT"
! grep -Fq 'fail("SUBMISSION_OUTCOME_UNKNOWN")' "$WEB"

# Result repair is anchored to the latest actual incomplete body mutation, not the first canonical POST.
grep -Fq 'resultBodyMutationObservedElapsed' "$ENGINE"
grep -Fq 'resultBodyMutationBootCount' "$ENGINE"
grep -Fq 'recordResultBodyObservation' "$DRIVE"
grep -Fq 'stage=STALE_CONFIRM' "$DRIVE"
grep -Fq 'ResultObservation finalObservation = readObservation(token, current);' "$DRIVE"
grep -Fq 'recordResultBodyObservation(current, finalObservation);' "$DRIVE"
grep -Fq 'mutationBootCount != currentBootCount' "$WATCHDOG"
grep -Fq 'nowElapsed - mutationElapsed >= staleAfterMs' "$WATCHDOG"
grep -Fq 'thresholdIsExactFromLastIncompleteBodyMutation' "$WATCHDOG_UNIT"
grep -Fq 'sameBodyDoesNotResetButEveryActualBodyChangeDoes' "$WATCHDOG_UNIT"
grep -Fq 'returningToSeedClearsWaitAndLaterMutationStartsFresh' "$WATCHDOG_UNIT"
grep -Fq 'processRestartPreservesClockAndRebootRequiresOneRebaseline' "$WATCHDOG_UNIT"
grep -Fq 'driveAdapterFreshReadsAgainBeforeRepairingStaleSnapshot' "$WATCHDOG_UNIT"

# Any actual READ_RESULT failure rolls forward into a fresh repair turn, and repairs can repeat.
grep -Fq 'repairResult(state, "READ_RESULT_FAILURE_"' "$COORD"
grep -Fq 'V3_RESULT_REPAIR_RETRY' "$COORD"
! grep -Fq 'V3_RESULT_REPAIR_EXHAUSTED' "$COORD"
! grep -Fq 'hardPause("V3_RESULT_REPAIR_FAILED"' "$COORD"
! grep -Fq 'put(v,"repairAttempt",1)' "$ENGINE"
grep -Fq 'repairReplacementCanBeRepairedAgain' "$ALWAYS_REPAIR_UNIT"
grep -Fq 'readResultFailuresRouteToRepairAndRepairFailureRetries' "$ALWAYS_REPAIR_UNIT"

# Every normal/branch/merge/repair prompt carries one strict Result-document contract.
grep -Fq 'RESULT_DOCUMENT_RULES' "$PROTOCOL"
grep -Fq '[RESULT_DOCUMENT_CONTRACT]' "$PROTOCOL"
grep -Fq '지정된 기존 RESULT_DOCUMENT_ID의 초기 identity를 보존' "$PROTOCOL"
grep -Fq 'committed를 boolean true로 확정' "$PROTOCOL"
grep -Fq '같은 문서를 다시 읽어 저장된 내용을 검증' "$PROTOCOL"

# Explicit user-stopped recovery keeps the exact ledger lineage and cannot be reached by ordinary resume.
grep -Fq 'RESUME_STOPPED' "$ENGINE"
grep -Fq 'original.flag("taskStopped") && e.kind != Kind.RESUME_STOPPED' "$ENGINE"
grep -Fq 'ACTION_RESUME_STOPPED' "$SERVICE"
grep -Fq 'stoppedResume.hasPending() ? ACTION_RESUME_STOPPED : ACTION_RUN' "$SERVICE"
grep -Fq 'ledger.load(target)' "$STOPPED_RESUME"
grep -Fq 'DRIVE_BINDING_MISMATCH' "$STOPPED_RESUME"
grep -Fq 'coordinator.onStart(SelfRunService.ACTION_RUN)' "$STOPPED_RESUME"
grep -Fq 'stoppedTaskRequiresExplicitRecoveryAndPreservesWaitingSendClaim' "$UNIT/SelfRun3EngineTest.java"
grep -Fq 'recoveryReusesExistingLedgerAndPinnedDriveIdentity' "$STOPPED_RESUME_UNIT"
grep -Fq 'stoppedLedgerSurvivesProjectionRestartAndResumeEventIsIdempotent' "$STOPPED_RESUME_ANDROID"

# Regression fixtures execute the former boundaries rather than only scanning source text.
grep -Fq 'turnReadyAcceptsPromptBeyondFormerOneMiBGate' "$UNBOUNDED"
grep -Fq 'committedResultBeyondFormer512KiBGateKeepsStrictIdentityChecks' "$UNBOUNDED"
grep -Fq 'additionalInputMergeExceedsFormer64KiBWithoutTruncation' "$UNBOUNDED"
grep -Fq 'unknownAttachmentSizeProbeStreamsBeyondFormer100MiBCeiling' "$UNBOUNDED"
grep -Fq 'attachmentDraftStateAcceptsMoreThanTenAndKnownFileOver100MiB' "$UNBOUNDED_ANDROID"
grep -Fq 'additionalInputPersistsBeyondFormer64KiBGateWithRevisionUpdates' "$UNBOUNDED_ANDROID"
grep -Fq 'positiveIntegerParserRejectsBlankZeroNegativeTextAndOverflow' "$SETTINGS_UNIT"
grep -Fq 'invalidSaveNeverOverwritesLastGoodValueAndCorruptStoredTypeFallsBack' "$SETTINGS_ANDROID"
grep -Fq 'seedSettingsBeforeProcessRestart' "$SETTINGS_PROCESS"
grep -Fq 'verifySettingsAfterProcessRestart' "$SETTINGS_PROCESS"
grep -Fq 'RUNTIME_RESULT_REPAIR_OVERRIDE = 77L' "$UPGRADE_ANDROID"
grep -Fq 'runtime result repair override preserved' "$UPGRADE_ANDROID"
grep -Fq 'reads persisted runtime override instead of new default' "$UPGRADE_ANDROID"

# Candidate upgrade fixture must use the latest delivered TEST and latest formal baselines.
grep -Fq 'SelfRun-Drive-TEST-3.2.2-dev2.apk' "$EMULATOR"
grep -Fq 'bf7a325c08f3be363d28bf757ee9792235b0b8dede6205bdda4ff147fca5b258' "$EMULATOR"
grep -Fq 'eb6908a15b6dadb5de108b97446875f4312e6257/deliverables/SelfRun-Drive-TEST-3.2.2-dev2.apk' "$EMULATOR"
grep -Fq "versionCode='3022002'" "$EMULATOR"
grep -Fq "versionName='3.2.2-dev2'" "$EMULATOR"
grep -Fq 'stable/chatgpt-selfrun-drive-v3.2.2.apk' "$EMULATOR"
grep -Fq 'SelfRun3RuntimeSettingsProcessAndroidTest#seedSettingsBeforeProcessRestart' "$EMULATOR"
grep -Fq 'adb shell am force-stop "$TEST"' "$EMULATOR"
grep -Fq 'SelfRun3RuntimeSettingsProcessAndroidTest#verifySettingsAfterProcessRestart' "$EMULATOR"
grep -Fq 'SelfRun3UnboundedLimitsAndroidTest' "$EMULATOR"
grep -Fq 'SelfRunStoppedResumeAndroidTest' "$EMULATOR"

# Direct publication must expose a product/version-bearing APK filename.
grep -Fq 'SelfRun-Drive-TEST-${VERSION_NAME}.apk' "$WORKFLOW"

echo 'SelfRun 3.2.3-dev1 always-repair, conversation-creation gate, preparation recovery, stopped-resume, result-repair, prompt-contract, upgrade-persistence, unbounded-payload and runtime-settings checks passed.'
