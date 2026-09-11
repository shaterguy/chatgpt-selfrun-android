#!/usr/bin/env bash
set -euo pipefail

: "${VERSION_NAME:?VERSION_NAME must be set by the candidate workflow}"

FORMAL=com.shaterguy.chatgptselfrun.drive
TEST=com.shaterguy.chatgptselfrun.drive.test
PREVIOUS_TEST=previous/chatgpt-selfrun-drive-test-v3.1.2-dev4.apk
PREVIOUS_TEST_SHA256=22bbc785a96a26b695220b4ddad20687aaf137dd3d52d37f7c56110ff8fde6cf
PREVIOUS_TEST_URL=https://raw.githubusercontent.com/shaterguy/chatgpt-selfrun-android/b22da7f68e7222c7daf2ee49569c0134125ef925/deliverables/current-test.apk

mkdir -p previous
curl --fail --location --retry 3 --retry-all-errors --output "$PREVIOUS_TEST" "$PREVIOUS_TEST_URL"
echo "$PREVIOUS_TEST_SHA256  $PREVIOUS_TEST" | sha256sum -c -
BT="$ANDROID_HOME/build-tools/36.0.0"
"$BT/apksigner" verify --verbose --print-certs "$PREVIOUS_TEST" > selfrun-v3-previous-test-cert.txt
grep -Fqi '2c95a5644a0ef2959eaecf10460e300fe2ee7a4ebcede685a82a52634c22e86e' selfrun-v3-previous-test-cert.txt
"$BT/aapt" dump badging "$PREVIOUS_TEST" | grep -F "versionName='3.1.2-dev4'"
"$BT/aapt" dump badging "$PREVIOUS_TEST" | grep -F "versionCode='3012004'"

adb install -r stable/chatgpt-selfrun-drive-v3.1.1.apk >/dev/null
adb install -r "$PREVIOUS_TEST" >/dev/null
adb install -r current/androidTest.apk >/dev/null
INSTRUMENTATION="$(adb shell pm list instrumentation | tr -d '\r' | sed -n "s#^instrumentation:\\([^ ]*\\) (target=$TEST)#\\1#p" | head -1)"
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
[[ "$(adb shell dumpsys package "$FORMAL" | tr -d '\r' | sed -n 's/^[[:space:]]*versionName=//p' | head -1)" == '3.1.1' ]]
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
adb shell am instrument -w -r \
  -e class com.shaterguy.chatgptselfrun.SelfRun3RuntimeAndroidTest \
  "$INSTRUMENTATION" | tee selfrun-v3-runtime-evidence.txt
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
