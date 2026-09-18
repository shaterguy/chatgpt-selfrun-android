#!/usr/bin/env bash
set -euo pipefail

FORMAL_PACKAGE="com.shaterguy.chatgptselfrun.drive"
BASELINE_APK="${BASELINE_APK:-stable/chatgpt-selfrun-drive-v3.2.6.apk}"
CANDIDATE_APK="${CANDIDATE_APK:-current/formal.apk}"
FORMAL_ANDROID_TEST_APK="${FORMAL_ANDROID_TEST_APK:-current/formal-androidTest.apk}"
EXPECTED_VERSION_NAME="${EXPECTED_VERSION_NAME:-3.2.7-dev1}"
EXPECTED_VERSION_CODE="${EXPECTED_VERSION_CODE:-3027001}"
CLASS="com.shaterguy.chatgptselfrun.SelfRun3OnDeviceFormalUpgradeAndroidTest"

test -s "$BASELINE_APK"
test -s "$CANDIDATE_APK"
test -s "$FORMAL_ANDROID_TEST_APK"

adb install -r "$BASELINE_APK" | tee formal-upgrade-baseline-install.txt
grep -Fq 'Success' formal-upgrade-baseline-install.txt
BASE_UID="$(adb shell pm list packages -U "$FORMAL_PACKAGE" | tr -d '\r' | sed -n 's/.*uid://p' | head -1)"
test -n "$BASE_UID"
[[ "$(adb shell dumpsys package "$FORMAL_PACKAGE" | tr -d '\r' | sed -n 's/^[[:space:]]*versionName=//p' | head -1)" == '3.2.6' ]]

adb install -r "$FORMAL_ANDROID_TEST_APK" | tee formal-upgrade-instrumentation-install.txt
grep -Fq 'Success' formal-upgrade-instrumentation-install.txt
INSTRUMENTATION="$(adb shell pm list instrumentation | tr -d '\r' | sed -n "s#^instrumentation:\([^ ]*\) (target=$FORMAL_PACKAGE)#\1#p" | head -1)"
test -n "$INSTRUMENTATION"

adb shell am instrument -w -r \
  -e class "$CLASS#seedFormal326ServerStateAndWaitingLedger" \
  "$INSTRUMENTATION" | tee formal-upgrade-seed.txt
grep -Fq 'OK (' formal-upgrade-seed.txt

SEEDED_UID="$(adb shell pm list packages -U "$FORMAL_PACKAGE" | tr -d '\r' | sed -n 's/.*uid://p' | head -1)"
[[ "$SEEDED_UID" == "$BASE_UID" ]]

adb shell am force-stop "$FORMAL_PACKAGE"
adb install -r --no-incremental "$CANDIDATE_APK" | tee formal-upgrade-candidate-install.txt
grep -Fq 'Success' formal-upgrade-candidate-install.txt

UPDATED_UID="$(adb shell pm list packages -U "$FORMAL_PACKAGE" | tr -d '\r' | sed -n 's/.*uid://p' | head -1)"
test -n "$UPDATED_UID"
[[ "$UPDATED_UID" == "$BASE_UID" ]]
[[ "$(adb shell dumpsys package "$FORMAL_PACKAGE" | tr -d '\r' | sed -n 's/^[[:space:]]*versionName=//p' | head -1)" == "$EXPECTED_VERSION_NAME" ]]
adb shell dumpsys package "$FORMAL_PACKAGE" | tr -d '\r' | grep -F "versionCode=$EXPECTED_VERSION_CODE" >/dev/null

adb shell am instrument -w -r \
  -e class "$CLASS#verifyFormal327MigrationAndCommittedContinuation" \
  "$INSTRUMENTATION" | tee formal-upgrade-verify.txt
grep -Fq 'OK (' formal-upgrade-verify.txt

adb shell am force-stop "$FORMAL_PACKAGE"
adb shell am instrument -w -r \
  -e class "$CLASS#verifyFormal327AfterProcessRestart" \
  "$INSTRUMENTATION" | tee formal-upgrade-restart-verify.txt
grep -Fq 'OK (' formal-upgrade-restart-verify.txt

RESTART_UID="$(adb shell pm list packages -U "$FORMAL_PACKAGE" | tr -d '\r' | sed -n 's/.*uid://p' | head -1)"
[[ "$RESTART_UID" == "$BASE_UID" ]]

{
  echo "FORMAL_PACKAGE=$FORMAL_PACKAGE"
  echo "BASELINE_VERSION=3.2.6"
  echo "CANDIDATE_VERSION=$EXPECTED_VERSION_NAME"
  echo "CANDIDATE_VERSION_CODE=$EXPECTED_VERSION_CODE"
  echo "BASELINE_UID=$BASE_UID"
  echo "UPDATED_UID=$UPDATED_UID"
  echo "RESTART_UID=$RESTART_UID"
  echo "UNINSTALL_OR_PM_CLEAR_USED=NO"
  echo "SERVER_TO_ON_DEVICE_MIGRATION=PASS"
  echo "NON_MODE_RUNTIME_PREFS_PRESERVED=PASS"
  echo "LEDGER_WAITING_STATE_PRESERVED=PASS"
  echo "COMMITTED_RESULT_TO_NEXT_TURN=PASS"
  echo "DUPLICATE_COMMIT_NEXT_TURN_COUNT=ONE"
  echo "PROCESS_RESTART_LEDGER_RECOVERY=PASS"
  echo "EVIDENCE_CLASS=FORMAL_TARGET_FIXTURE_BACKED_INTEGRATION"
} > formal-upgrade-summary.txt
cat formal-upgrade-summary.txt
