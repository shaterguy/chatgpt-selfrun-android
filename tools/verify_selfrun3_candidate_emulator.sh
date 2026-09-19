#!/usr/bin/env bash
set -euo pipefail

: "${VERSION_NAME:?VERSION_NAME must be set by the candidate workflow}"

FORMAL=com.shaterguy.chatgptselfrun.drive
TEST=com.shaterguy.chatgptselfrun.drive.test
PREVIOUS_FORMAL=stable/chatgpt-selfrun-drive-v3.2.8.apk
PREVIOUS_FORMAL_SHA256=d2cfd398efa193ba90cfb46e6d630021b9b415169422af521e374a45edb2e945
PREVIOUS_FORMAL_URL=https://github.com/shaterguy/chatgpt-selfrun-android/releases/download/drive-v3.2.8/chatgpt-selfrun-drive-v3.2.8.apk
PREVIOUS_TEST=previous/SelfRun-Drive-TEST-3.2.9-dev1.apk
PREVIOUS_TEST_SHA256=a201b9309ef775683100badc314ae120b05d796c6a1762fddf8fa9970e89e199
PREVIOUS_TEST_URL=https://raw.githubusercontent.com/shaterguy/chatgpt-selfrun-android/459e0d52bbd6de77aa3915e57342f10bb05c5fbe/deliverables/SelfRun-Drive-TEST-3.2.9-dev1.apk

mkdir -p stable previous
BT="$ANDROID_HOME/build-tools/36.0.0"

curl --fail --location --retry 3 --retry-all-errors --output "$PREVIOUS_FORMAL" "$PREVIOUS_FORMAL_URL"
echo "$PREVIOUS_FORMAL_SHA256  $PREVIOUS_FORMAL" | sha256sum -c -
"$BT/apksigner" verify --verbose --print-certs "$PREVIOUS_FORMAL" > selfrun-v3-previous-formal-cert.txt
grep -Fqi 'b3ea944ac1e31438ad697482af6d289c5ffeb0119e89c2e54a755c49c48644fe' selfrun-v3-previous-formal-cert.txt
"$BT/aapt" dump badging "$PREVIOUS_FORMAL" | grep -F "versionName='3.2.8'"
"$BT/aapt" dump badging "$PREVIOUS_FORMAL" | grep -F "versionCode='3029000'"

curl --fail --location --retry 3 --retry-all-errors --output "$PREVIOUS_TEST" "$PREVIOUS_TEST_URL"
echo "$PREVIOUS_TEST_SHA256  $PREVIOUS_TEST" | sha256sum -c -
"$BT/apksigner" verify --verbose --print-certs "$PREVIOUS_TEST" > selfrun-v3-previous-test-cert.txt
grep -Fqi '2c95a5644a0ef2959eaecf10460e300fe2ee7a4ebcede685a82a52634c22e86e' selfrun-v3-previous-test-cert.txt
"$BT/aapt" dump badging "$PREVIOUS_TEST" | grep -F "versionName='3.2.9-dev1'"
"$BT/aapt" dump badging "$PREVIOUS_TEST" | grep -F "versionCode='3029001'"

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
[[ "$(adb shell dumpsys package "$FORMAL" | tr -d '\r' | sed -n 's/^[[:space:]]*versionName=//p' | head -1)" == '3.2.8' ]]
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
RECOVERY_CLASS=com.shaterguy.chatgptselfrun.SelfRun3PreparationRecoveryAndroidTest
CURRENT_31_REQUIRED=(
  com.shaterguy.chatgptselfrun.SelfRun3OnDeviceMigrationAndroidTest
  com.shaterguy.chatgptselfrun.SelfRun3OnDeviceCoordinatorAndroidTest
  com.shaterguy.chatgptselfrun.SelfRun31FirstConversationAndroidTest
  com.shaterguy.chatgptselfrun.SelfRun3RuntimeAndroidTest
  com.shaterguy.chatgptselfrun.SelfRun3DispatchAndroidTest
  com.shaterguy.chatgptselfrun.SelfRun3ParallelLedgerAndroidTest
  com.shaterguy.chatgptselfrun.SelfRun3InputCommitAndroidTest
  com.shaterguy.chatgptselfrun.RequestProfileRecreationAndroidTest
  com.shaterguy.chatgptselfrun.ChatReasoningProcessRecreationAndroidTest
  com.shaterguy.chatgptselfrun.SelfRun3WebViewDisposalAndroidTest
)
DIRECT_CLASSES="$RECOVERY_CLASS"
for required in "${CURRENT_31_REQUIRED[@]}"; do
  DIRECT_CLASSES+=",$required"
done
adb shell am instrument -w -r \
  -e class "$DIRECT_CLASSES" \
  "$INSTRUMENTATION" | tee selfrun-v3-preparation-recovery-evidence.txt
grep -Fq 'OK (' selfrun-v3-preparation-recovery-evidence.txt
grep -Fq "class=$RECOVERY_CLASS" selfrun-v3-preparation-recovery-evidence.txt
for required in "${CURRENT_31_REQUIRED[@]}"; do
  grep -Fq "class=$required" selfrun-v3-preparation-recovery-evidence.txt
done
cat selfrun-v3-preparation-recovery-evidence.txt > selfrun-v3-runtime-evidence.txt

adb shell am instrument -w -r \
  -e class com.shaterguy.chatgptselfrun.SelfRun3RuntimeAndroidTest \
  "$INSTRUMENTATION" | tee -a selfrun-v3-runtime-evidence.txt
grep -Fq 'OK (' selfrun-v3-runtime-evidence.txt
adb shell am instrument -w -r \
  -e class com.shaterguy.chatgptselfrun.SelfRun3RuntimeSettingsAndroidTest \
  "$INSTRUMENTATION" | tee selfrun-v3-runtime-settings-evidence.txt
grep -Fq 'OK (' selfrun-v3-runtime-settings-evidence.txt
adb shell am instrument -w -r \
  -e class com.shaterguy.chatgptselfrun.SelfRun3RuntimeSettingsProcessAndroidTest#seedSettingsBeforeProcessRestart \
  "$INSTRUMENTATION" | tee selfrun-v3-runtime-settings-restart-seed.txt
grep -Fq 'OK (' selfrun-v3-runtime-settings-restart-seed.txt
adb shell am force-stop "$TEST"
adb shell am instrument -w -r \
  -e class com.shaterguy.chatgptselfrun.SelfRun3RuntimeSettingsProcessAndroidTest#verifySettingsAfterProcessRestart \
  "$INSTRUMENTATION" | tee selfrun-v3-runtime-settings-restart-verify.txt
grep -Fq 'OK (' selfrun-v3-runtime-settings-restart-verify.txt
adb shell am instrument -w -r \
  -e class com.shaterguy.chatgptselfrun.SelfRun3UnboundedLimitsAndroidTest \
  "$INSTRUMENTATION" | tee selfrun-v3-unbounded-limits-evidence.txt
grep -Fq 'OK (' selfrun-v3-unbounded-limits-evidence.txt
adb shell am instrument -w -r \
  -e class com.shaterguy.chatgptselfrun.SelfRun31WorkFixAndroidTest \
  "$INSTRUMENTATION" | tee selfrun-v3-work-fix-evidence.txt
grep -Fq 'OK (' selfrun-v3-work-fix-evidence.txt
adb shell am instrument -w -r \
  -e class com.shaterguy.chatgptselfrun.SelfRun3ProjectDirectoryNavigationWebViewTest \
  "$INSTRUMENTATION" | tee selfrun-v3-project-directory-evidence.txt
grep -Fq 'OK (' selfrun-v3-project-directory-evidence.txt
adb shell am instrument -w -r \
  -e class com.shaterguy.chatgptselfrun.SelfRunStoppedResumeAndroidTest \
  "$INSTRUMENTATION" | tee selfrun-v3-stopped-resume-evidence.txt
grep -Fq 'OK (' selfrun-v3-stopped-resume-evidence.txt

adb shell am instrument -w -r \
  -e class com.shaterguy.chatgptselfrun.SelfRun3OnDeviceMigrationAndroidTest,com.shaterguy.chatgptselfrun.SelfRun3OnDeviceCoordinatorAndroidTest \
  "$INSTRUMENTATION" | tee selfrun-v3-on-device-core-evidence.txt
grep -Fq 'OK (' selfrun-v3-on-device-core-evidence.txt
