#!/usr/bin/env bash
set -euo pipefail

: "${VERSION_NAME:?VERSION_NAME must be set by the candidate workflow}"

FORMAL=com.shaterguy.chatgptselfrun.drive
TEST=com.shaterguy.chatgptselfrun.drive.test
PREVIOUS_FORMAL=stable/chatgpt-selfrun-drive-v4.0.6.apk
PREVIOUS_FORMAL_SHA256=8bf1a36671b4d50af1adbf66e5b4211983847477bc06244e41442d834cd26de9
PREVIOUS_FORMAL_URL=https://github.com/shaterguy/chatgpt-selfrun-android/releases/download/drive-v4.0.6/chatgpt-selfrun-drive-v4.0.6.apk
PREVIOUS_TEST=previous/SelfRun-Drive-TEST-4.0.2.apk
PREVIOUS_TEST_SHA256=23c878ad4b03c39f769175a40117ef1000a53761c65335071f9762df5631a208
PREVIOUS_TEST_URL=https://raw.githubusercontent.com/shaterguy/chatgpt-selfrun-android/4cc495c98c7b1fcbd64467600231e931b896a02a/deliverables/SelfRun-Drive-TEST-4.0.2.apk

mkdir -p stable previous
BT="$ANDROID_HOME/build-tools/36.0.0"

curl --fail --location --retry 3 --retry-all-errors --output "$PREVIOUS_FORMAL" "$PREVIOUS_FORMAL_URL"
echo "$PREVIOUS_FORMAL_SHA256  $PREVIOUS_FORMAL" | sha256sum -c -
"$BT/apksigner" verify --verbose --print-certs "$PREVIOUS_FORMAL" > selfrun-v3-previous-formal-cert.txt
grep -Fqi 'b3ea944ac1e31438ad697482af6d289c5ffeb0119e89c2e54a755c49c48644fe' selfrun-v3-previous-formal-cert.txt
"$BT/aapt" dump badging "$PREVIOUS_FORMAL" | grep -F "versionName='4.0.6'"
"$BT/aapt" dump badging "$PREVIOUS_FORMAL" | grep -F "versionCode='4002006'"

curl --fail --location --retry 3 --retry-all-errors --output "$PREVIOUS_TEST" "$PREVIOUS_TEST_URL"
echo "$PREVIOUS_TEST_SHA256  $PREVIOUS_TEST" | sha256sum -c -
"$BT/apksigner" verify --verbose --print-certs "$PREVIOUS_TEST" > selfrun-v3-previous-test-cert.txt
grep -Fqi '2c95a5644a0ef2959eaecf10460e300fe2ee7a4ebcede685a82a52634c22e86e' selfrun-v3-previous-test-cert.txt
"$BT/aapt" dump badging "$PREVIOUS_TEST" | grep -F "versionName='4.0.2'"

adb install -r "$PREVIOUS_FORMAL" >/dev/null
adb install -r "$PREVIOUS_TEST" >/dev/null
adb install -r current/androidTest.apk >/dev/null
INSTRUMENTATION="$(adb shell pm list instrumentation | tr -d '\r' | sed -n "s#^instrumentation:\([^ ]*\) (target=$TEST)#\1#p" | head -1)"
test -n "$INSTRUMENTATION"
adb shell am instrument -w -r \
  -e class com.shaterguy.chatgptselfrun.SelfRun3UpgradePersistenceAndroidTest#seedUpgradeState \
  "$INSTRUMENTATION" | tee selfrun-v3-upgrade-seed.txt
grep -Fq 'OK (' selfrun-v3-upgrade-seed.txt
adb shell am force-stop "$TEST"
adb install -r current/candidate.apk >/dev/null
adb shell am instrument -w -r \
  -e class com.shaterguy.chatgptselfrun.SelfRun3UpgradePersistenceAndroidTest#verifyUpgradeState \
  "$INSTRUMENTATION" | tee selfrun-v3-upgrade-verify.txt
grep -Fq 'OK (' selfrun-v3-upgrade-verify.txt
adb shell pm list packages | tr -d '\r' | grep -Fx "package:$FORMAL"
adb shell pm list packages | tr -d '\r' | grep -Fx "package:$TEST"
[[ "$(adb shell dumpsys package "$FORMAL" | tr -d '\r' | sed -n 's/^[[:space:]]*versionName=//p' | head -1)" == '4.0.6' ]]
[[ "$(adb shell dumpsys package "$TEST" | tr -d '\r' | sed -n 's/^[[:space:]]*versionName=//p' | head -1)" == "$VERSION_NAME" ]]

FORMAL_UID="$(adb shell pm list packages -U "$FORMAL" | tr -d '\r' | sed -n 's/.*uid://p' | head -1)"
TEST_UID="$(adb shell pm list packages -U "$TEST" | tr -d '\r' | sed -n 's/.*uid://p' | head -1)"
test -n "$FORMAL_UID"
test -n "$TEST_UID"
[[ "$FORMAL_UID" != "$TEST_UID" ]]

adb shell am start -W -n "$FORMAL/com.shaterguy.chatgptselfrun.MainActivity" >/dev/null
adb shell am start -W -n "$TEST/com.shaterguy.chatgptselfrun.MainActivity" >/dev/null
adb shell pidof "$TEST" >/dev/null

adb install -r current/androidTest.apk >/dev/null
INSTRUMENTATION="$(adb shell pm list instrumentation | tr -d '\r' | sed -n "s#^instrumentation:\([^ ]*\) (target=$TEST)#\1#p" | head -1)"
test -n "$INSTRUMENTATION"
CLASSES=(
  SelfRunDualSubmissionAndroidTest
  SelfRun31FirstConversationAndroidTest
  SelfRun3WebViewDisposalAndroidTest
  SelfRun3OnDeviceCoordinatorAndroidTest
  SelfRun3OnDeviceMigrationAndroidTest
  SelfRun3RuntimeSettingsAndroidTest
  SelfRun3DispatchAndroidTest
  SelfRun3RuntimeAndroidTest
  SelfRun3ParallelLedgerAndroidTest
  SelfRun3InputCommitAndroidTest
  SelfRun3ResultRoutingAndroidTest
  SelfRunStoppedResumeAndroidTest
)
SELECTED=""
for class in "${CLASSES[@]}"; do
  [[ -z "$SELECTED" ]] || SELECTED+=","
  SELECTED+="com.shaterguy.chatgptselfrun.$class"
done
adb shell am instrument -w -r -e class "$SELECTED" "$INSTRUMENTATION" | tee selfrun-dual-runtime-evidence.txt
grep -Fq 'OK (' selfrun-dual-runtime-evidence.txt
! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' selfrun-dual-runtime-evidence.txt
for class in "${CLASSES[@]}"; do
  grep -Fq "class=com.shaterguy.chatgptselfrun.$class" selfrun-dual-runtime-evidence.txt
done
cp selfrun-dual-runtime-evidence.txt selfrun-v3-runtime-evidence.txt
adb shell am instrument -w -r \
  -e class com.shaterguy.chatgptselfrun.SelfRun3RuntimeSettingsProcessAndroidTest#seedSettingsBeforeProcessRestart \
  "$INSTRUMENTATION" | tee selfrun-v3-runtime-settings-restart-seed.txt
grep -Fq 'OK (' selfrun-v3-runtime-settings-restart-seed.txt
adb shell am force-stop "$TEST"
adb shell am instrument -w -r \
  -e class com.shaterguy.chatgptselfrun.SelfRun3RuntimeSettingsProcessAndroidTest#verifySettingsAfterProcessRestart \
  "$INSTRUMENTATION" | tee selfrun-v3-runtime-settings-restart-verify.txt
grep -Fq 'OK (' selfrun-v3-runtime-settings-restart-verify.txt
echo 'DUAL_SUBMISSION_RUNTIME_AND_TEST_LINEAGE=PASS' | tee -a selfrun-dual-runtime-evidence.txt
