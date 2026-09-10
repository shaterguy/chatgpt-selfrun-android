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
PROBE=$SRC/SelfRun3AttachmentSizeProbe.java
INPUT=$SRC/UserNextInputStore.java
IMMEDIATE=$SRC/UserImmediateInputCoordinator.java
STORE=$SRC/SelfRunStore.java
ACTIVITY=$SRC/SelfRunNewActivity.java
SETTINGS=$SRC/SelfRun3RuntimeSettings.java
COORD=$SRC/SelfRun3Coordinator.java
STALL=$SRC/SelfRun3TurnStallPolicy.java
NOTIFIER=$SRC/SelfRun3TurnStallNotifier.java
WEB=$SRC/SelfRun3WebAdapter.java
UNBOUNDED=$UNIT/SelfRun3UnboundedPayloadPolicyTest.java
SETTINGS_UNIT=$UNIT/SelfRun3RuntimeSettingsTest.java
UNBOUNDED_ANDROID=$ANDROID/SelfRun3UnboundedLimitsAndroidTest.java
SETTINGS_ANDROID=$ANDROID/SelfRun3RuntimeSettingsAndroidTest.java
SETTINGS_PROCESS=$ANDROID/SelfRun3RuntimeSettingsProcessAndroidTest.java

for file in "$ENGINE" "$DRIVE" "$PROBE" "$INPUT" "$IMMEDIATE" "$STORE" "$ACTIVITY" \
  "$SETTINGS" "$COORD" "$STALL" "$NOTIFIER" "$WEB" "$UNBOUNDED" "$SETTINGS_UNIT" \
  "$UNBOUNDED_ANDROID" "$SETTINGS_ANDROID" "$SETTINGS_PROCESS" "$WORKFLOW" "$EMULATOR"; do
  test -s "$file"
done

grep -Fq "selfRunDriveVersionCode = 3012004" "$BUILD"
grep -Fq "selfRunDriveVersionName = '3.1.2-dev4'" "$BUILD"

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

# Four operator timings keep dev3 defaults, persist separately, and reach the live consumers.
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

# Candidate upgrade fixture must be the exact dev3 baseline for this task.
grep -Fq 'chatgpt-selfrun-drive-test-v3.1.2-dev3.apk' "$EMULATOR"
grep -Fq '8a5e2125d7311dbbdd3b925e8faa5791c87bb50a4420f687dd4347029ff7d2cb' "$EMULATOR"
grep -Fq 'aa430205136f9215fc144b396c319705b8184516/deliverables/current-test.apk' "$EMULATOR"
grep -Fq "versionCode='3012003'" "$EMULATOR"
grep -Fq 'SelfRun3RuntimeSettingsProcessAndroidTest#seedSettingsBeforeProcessRestart' "$EMULATOR"
grep -Fq 'adb shell am force-stop "$TEST"' "$EMULATOR"
grep -Fq 'SelfRun3RuntimeSettingsProcessAndroidTest#verifySettingsAfterProcessRestart' "$EMULATOR"
grep -Fq 'SelfRun3UnboundedLimitsAndroidTest' "$EMULATOR"

# Direct publication must expose a product/version-bearing APK filename.
grep -Fq 'SelfRun-Drive-TEST-${VERSION_NAME}.apk' "$WORKFLOW"

echo 'SelfRun 3.1.2-dev4 unbounded payload and runtime-settings checks passed.'
