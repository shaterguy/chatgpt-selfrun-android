#!/usr/bin/env bash
set -euo pipefail

: "${VERSION_NAME:?VERSION_NAME must be set by the candidate workflow}"

FORMAL=com.shaterguy.chatgptselfrun.drive
TEST=com.shaterguy.chatgptselfrun.drive.test

adb install -r stable/chatgpt-selfrun-drive-v3.0.0.apk >/dev/null
adb install -r previous/chatgpt-selfrun-drive-test-v3.0.1-dev21.apk >/dev/null
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
[[ "$(adb shell dumpsys package "$FORMAL" | tr -d '\r' | sed -n 's/^[[:space:]]*versionName=//p' | head -1)" == '3.0.0' ]]
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
  -e class com.shaterguy.chatgptselfrun.SelfRun3DispatchAndroidTest \
  "$INSTRUMENTATION" | tee selfrun-v3-dispatch-evidence.txt
grep -Fq 'OK (' selfrun-v3-dispatch-evidence.txt
cat selfrun-v3-dispatch-evidence.txt >> selfrun-v3-runtime-evidence.txt
