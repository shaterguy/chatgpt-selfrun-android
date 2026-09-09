#!/usr/bin/env bash
set -euo pipefail

: "${FORMAL_VERSION_NAME:?FORMAL_VERSION_NAME required}"
: "${FORMAL_VERSION_CODE:?FORMAL_VERSION_CODE required}"

FORMAL=com.shaterguy.chatgptselfrun.drive
TEST=com.shaterguy.chatgptselfrun.drive.test
BASE_APK=stable/chatgpt-selfrun-drive-v3.0.0.apk
FORMAL_APK=current/formal.apk
TEST_APK=current/test.apk
ANDROID_TEST_APK=current/androidTest.apk

package_version() {
  adb shell dumpsys package "$1" | tr -d '\r' | sed -n 's/^[[:space:]]*versionName=//p' | head -1
}
package_version_code() {
  adb shell dumpsys package "$1" | tr -d '\r' | sed -n 's/^[[:space:]]*versionCode=\([0-9][0-9]*\).*/\1/p' | head -1
}
package_uid() {
  adb shell pm list packages -U "$1" | tr -d '\r' | sed -n 's/.*uid:\([0-9][0-9]*\).*/\1/p' | head -1
}

adb uninstall "$FORMAL" >/dev/null 2>&1 || true
adb uninstall "$TEST" >/dev/null 2>&1 || true

adb install --no-incremental "$BASE_APK" >/dev/null
[[ "$(package_version "$FORMAL")" == '3.0.0' ]]
BASE_UID="$(package_uid "$FORMAL")"
test -n "$BASE_UID"

adb install -r --no-incremental "$FORMAL_APK" >/dev/null
[[ "$(package_version "$FORMAL")" == "$FORMAL_VERSION_NAME" ]]
[[ "$(package_version_code "$FORMAL")" == "$FORMAL_VERSION_CODE" ]]
[[ "$(package_uid "$FORMAL")" == "$BASE_UID" ]]

adb install --no-incremental "$TEST_APK" >/dev/null
[[ "$(package_version "$TEST")" == "$FORMAL_VERSION_NAME" ]]
FORMAL_UID="$(package_uid "$FORMAL")"
TEST_UID="$(package_uid "$TEST")"
test -n "$FORMAL_UID"
test -n "$TEST_UID"
[[ "$FORMAL_UID" != "$TEST_UID" ]]

adb shell am start -W -n "$FORMAL/com.shaterguy.chatgptselfrun.MainActivity" >/dev/null
adb shell am start -W -n "$TEST/com.shaterguy.chatgptselfrun.MainActivity" >/dev/null
adb shell pidof "$FORMAL" >/dev/null
adb shell pidof "$TEST" >/dev/null

adb install -r --no-incremental "$ANDROID_TEST_APK" >/dev/null
INSTRUMENTATION="$(adb shell pm list instrumentation | tr -d '\r' | sed -n "s#^instrumentation:\([^ ]*\) (target=$TEST)#\1#p" | head -1)"
test -n "$INSTRUMENTATION"
adb shell am instrument -w -r \
  -e class com.shaterguy.chatgptselfrun.SelfRun3RuntimeAndroidTest \
  "$INSTRUMENTATION" | tee selfrun-v3-release-runtime-evidence.txt
grep -Fq 'OK (19 tests)' selfrun-v3-release-runtime-evidence.txt

echo 'SELFRUN3_RELEASE_EMULATOR_PASS'
