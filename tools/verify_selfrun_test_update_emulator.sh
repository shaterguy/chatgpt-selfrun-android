#!/usr/bin/env bash
# Runs only against the fresh, isolated CI emulator. Never use a user device.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == 'true' ]]
[[ "${TEST_ONLY:-}" == 'true' ]]
[[ "${VERSION_NAME:-}" == '4.0.10-dev6' ]]
[[ "${VERSION_CODE:-}" == '4002019' ]]
[[ "$(adb get-serialno)" == emulator-* ]]
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == '1' ]]
[[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" == '36' ]]
FORMAL=com.shaterguy.chatgptselfrun.drive
TEST=com.shaterguy.chatgptselfrun.drive.test
package_version() {
  adb shell dumpsys package "$1" | tr -d '\r' | sed -n 's/^[[:space:]]*versionName=//p' | head -1
}
package_version_code() {
  adb shell dumpsys package "$1" | tr -d '\r' | sed -n 's/^[[:space:]]*versionCode=\([0-9][0-9]*\).*/\1/p' | head -1
}
package_uid() {
  adb shell pm list packages -U "$1" | tr -d '\r' | awk -v target="package:$1" '$1 == target {sub(/^uid:/, "", $2); print $2; exit}'
}
package_first_install() {
  adb shell dumpsys package "$1" | tr -d '\r' | sed -n 's/^[[:space:]]*firstInstallTime=//p' | head -1
}
# Abort rather than remove/clear an existing installation.
[[ -z "$(adb shell pm path "$FORMAL" | tr -d '\r')" ]]
[[ -z "$(adb shell pm path "$TEST" | tr -d '\r')" ]]
adb install --no-incremental stable/chatgpt-selfrun-drive-v4.0.8.apk >/dev/null
adb install --no-incremental stable/SelfRun-Drive-TEST-v4.0.10-dev4.apk >/dev/null
[[ "$(package_version "$FORMAL")" == '4.0.8' ]]
[[ "$(package_version_code "$FORMAL")" == '4002010' ]]
[[ "$(package_version "$TEST")" == '4.0.10-dev4' ]]
[[ "$(package_version_code "$TEST")" == '4002017' ]]
OLD_TEST_UID="$(package_uid "$TEST")"
OLD_FIRST_INSTALL="$(package_first_install "$TEST")"
FORMAL_UID="$(package_uid "$FORMAL")"
[[ -n "$OLD_TEST_UID" && -n "$OLD_FIRST_INSTALL" && -n "$FORMAL_UID" ]]
[[ "$FORMAL_UID" != "$OLD_TEST_UID" ]]
adb install -r --no-incremental current/test.apk >/dev/null
[[ "$(package_version "$TEST")" == "$VERSION_NAME" ]]
[[ "$(package_version_code "$TEST")" == "$VERSION_CODE" ]]
[[ "$(package_uid "$TEST")" == "$OLD_TEST_UID" ]]
[[ "$(package_first_install "$TEST")" == "$OLD_FIRST_INSTALL" ]]
[[ "$(package_uid "$FORMAL")" == "$FORMAL_UID" ]]
[[ "$(package_version "$FORMAL")" == '4.0.8' ]]
[[ "$(package_version_code "$FORMAL")" == '4002010' ]]
printf 'TEST_UPDATE_INSTALL_PASS\nfrom=4.0.10-dev4/4002017\nto=%s/%s\ntestUid=%s\nfirstInstallTime=%s\nformalUid=%s\n' \
  "$VERSION_NAME" "$VERSION_CODE" "$OLD_TEST_UID" "$OLD_FIRST_INSTALL" "$FORMAL_UID" > selfrun-test-update-runtime-evidence.txt
adb install -r --no-incremental current/androidTest.apk >/dev/null
INSTRUMENTATION="$(adb shell pm list instrumentation | tr -d '\r' | sed -n "s#^instrumentation:\([^ ]*\) (target=$TEST)#\1#p" | head -1)"
test -n "$INSTRUMENTATION"
adb shell am instrument -w -r \
  -e class com.shaterguy.chatgptselfrun.SelfRun3RuntimeAndroidTest \
  "$INSTRUMENTATION" | tee selfrun-v3-release-runtime-evidence.txt
grep -Fq 'OK (' selfrun-v3-release-runtime-evidence.txt
printf 'TEST_RUNTIME_PASS\n' >> selfrun-test-update-runtime-evidence.txt
