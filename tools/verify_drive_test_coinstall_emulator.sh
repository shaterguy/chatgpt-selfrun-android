#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <formal-drive-debug-apk> <test-prev-apk> <test-current-apk>" >&2
  exit 2
fi

FORMAL_APK="$1"
TEST_PREV_APK="$2"
TEST_APK="$3"
ADB_BINARY="${ADB:-adb}"
ADB_COMMAND_TIMEOUT_SECONDS="${ADB_COMMAND_TIMEOUT_SECONDS:-60}"
FORMAL_EXPECTED_VERSION="${FORMAL_EXPECTED_VERSION:-2.3.1}"
TEST_PREV_EXPECTED_VERSION="${TEST_PREV_EXPECTED_VERSION:-1.4.0-dev0}"
TEST_EXPECTED_VERSION="${TEST_EXPECTED_VERSION:-1.4.0-dev1}"

adb_checked() {
  timeout --foreground "${ADB_COMMAND_TIMEOUT_SECONDS}s" "$ADB_BINARY" "$@"
}
ADB=adb_checked

FORMAL_PACKAGE="com.shaterguy.chatgptselfrun.drive"
TEST_PACKAGE="com.shaterguy.chatgptselfrun.drive.test"
FORMAL_MAIN="$FORMAL_PACKAGE/com.shaterguy.chatgptselfrun.MainActivity"
TEST_MAIN="$TEST_PACKAGE/com.shaterguy.chatgptselfrun.MainActivity"

fail() {
  echo "DRIVE_TEST_COINSTALL_VERIFY_FAILED: $*" >&2
  exit 1
}

require_file() {
  [[ -s "$1" ]] || fail "APK missing or empty: $1"
}

package_installed() {
  "$ADB" shell pm list packages | tr -d '\r' | grep -Fx "package:$1" >/dev/null
}

package_version() {
  "$ADB" shell dumpsys package "$1" | tr -d '\r' | sed -n 's/^[[:space:]]*versionName=//p' | head -1
}

package_uid_line() {
  "$ADB" shell pm list packages -U "$1" | tr -d '\r' | grep -F "package:$1" | head -1 || true
}

uid_from_line() {
  awk '{
    for (i = 1; i <= NF; i++) {
      if ($i ~ /^uid:/) {
        sub(/^uid:/, "", $i)
        print $i
        exit
      }
    }
  }' <<<"$1"
}

package_data_dir() {
  "$ADB" shell dumpsys package "$1" | tr -d '\r' | sed -n 's/^[[:space:]]*dataDir=//p' | head -1
}

assert_launcher() {
  local package="$1" expected="$2" resolved actual_package actual_class expected_package expected_class
  resolved="$("$ADB" shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$package" | tr -d '\r' | tail -1)"
  actual_package="${resolved%%/*}"
  actual_class="${resolved#*/}"
  expected_package="${expected%%/*}"
  expected_class="${expected#*/}"
  if [[ "$actual_class" == .* ]]; then actual_class="${actual_package}${actual_class}"; fi
  if [[ "$expected_class" == .* ]]; then expected_class="${expected_package}${expected_class}"; fi
  [[ "$actual_package" == "$expected_package" && "$actual_class" == "$expected_class" ]] \
    || fail "launcher resolution mismatch for $package: $resolved"
}

launch_package() {
  local component="$1"
  "$ADB" shell am start -W -n "$component" >/dev/null || fail "failed to launch $component"
  sleep 1
}

require_file "$FORMAL_APK"
require_file "$TEST_PREV_APK"
require_file "$TEST_APK"
"$ADB" wait-for-device

"$ADB" uninstall "$FORMAL_PACKAGE" >/dev/null 2>&1 || true
"$ADB" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true

echo "Installing formal SelfRun Drive $FORMAL_EXPECTED_VERSION and current TEST app together"
"$ADB" install -r "$FORMAL_APK" >/dev/null
"$ADB" install -r "$TEST_APK" >/dev/null
package_installed "$FORMAL_PACKAGE" || fail "formal Drive package not installed"
package_installed "$TEST_PACKAGE" || fail "TEST Drive package not installed"

assert_launcher "$FORMAL_PACKAGE" "$FORMAL_MAIN"
assert_launcher "$TEST_PACKAGE" "$TEST_MAIN"
[[ "$(package_version "$FORMAL_PACKAGE")" == "$FORMAL_EXPECTED_VERSION" ]] \
  || fail "unexpected formal version: $(package_version "$FORMAL_PACKAGE")"
[[ "$(package_version "$TEST_PACKAGE")" == "$TEST_EXPECTED_VERSION" ]] \
  || fail "unexpected TEST version: $(package_version "$TEST_PACKAGE")"

formal_uid_raw="$(package_uid_line "$FORMAL_PACKAGE")"
test_uid_raw="$(package_uid_line "$TEST_PACKAGE")"
formal_uid="$(uid_from_line "$formal_uid_raw")"
test_uid="$(uid_from_line "$test_uid_raw")"
formal_data_dir="$(package_data_dir "$FORMAL_PACKAGE")"
test_data_dir="$(package_data_dir "$TEST_PACKAGE")"
echo "formal_uid_raw=$formal_uid_raw"
echo "test_uid_raw=$test_uid_raw"
echo "formal_uid=$formal_uid"
echo "test_uid=$test_uid"
[[ -n "$formal_uid" ]] || fail "formal UID unavailable from pm list packages -U: $formal_uid_raw"
[[ -n "$test_uid" ]] || fail "TEST UID unavailable from pm list packages -U: $test_uid_raw"
[[ "$formal_uid" != "$test_uid" ]] \
  || fail "formal and TEST packages have the same UID: $formal_uid"
[[ -n "$formal_data_dir" && -n "$test_data_dir" && "$formal_data_dir" != "$test_data_dir" ]] \
  || fail "formal and TEST packages share one data directory"

launch_package "$FORMAL_MAIN"
launch_package "$TEST_MAIN"
package_installed "$FORMAL_PACKAGE" || fail "formal package disappeared after TEST launch"
package_installed "$TEST_PACKAGE" || fail "TEST package disappeared after formal launch"

formal_path_before="$("$ADB" shell pm path "$FORMAL_PACKAGE" | tr -d '\r')"
test_path_before="$("$ADB" shell pm path "$TEST_PACKAGE" | tr -d '\r')"
[[ -n "$formal_path_before" && -n "$test_path_before" ]] || fail "package path unavailable"

"$ADB" shell pm clear "$FORMAL_PACKAGE" >/dev/null
package_installed "$TEST_PACKAGE" || fail "clearing formal app affected TEST install"
[[ "$("$ADB" shell pm path "$TEST_PACKAGE" | tr -d '\r')" == "$test_path_before" ]] \
  || fail "clearing formal app changed TEST package path"

"$ADB" uninstall "$FORMAL_PACKAGE" >/dev/null
package_installed "$TEST_PACKAGE" || fail "uninstalling formal app removed TEST app"
"$ADB" install -r "$FORMAL_APK" >/dev/null
package_installed "$FORMAL_PACKAGE" || fail "formal reinstall failed"

"$ADB" shell pm clear "$TEST_PACKAGE" >/dev/null
package_installed "$FORMAL_PACKAGE" || fail "clearing TEST app affected formal install"
"$ADB" uninstall "$TEST_PACKAGE" >/dev/null
package_installed "$FORMAL_PACKAGE" || fail "uninstalling TEST app removed formal app"
"$ADB" install -r "$TEST_APK" >/dev/null
package_installed "$TEST_PACKAGE" || fail "TEST reinstall failed"

echo "Verifying TEST vPrev to vNext in-place update"
"$ADB" uninstall "$TEST_PACKAGE" >/dev/null
package_installed "$FORMAL_PACKAGE" || fail "removing current TEST app affected formal app"
"$ADB" install -r "$TEST_PREV_APK" >/dev/null
package_installed "$TEST_PACKAGE" || fail "TEST vPrev install failed"
[[ "$(package_version "$TEST_PACKAGE")" == "$TEST_PREV_EXPECTED_VERSION" ]] \
  || fail "unexpected TEST vPrev version: $(package_version "$TEST_PACKAGE")"

test_prev_uid_raw="$(package_uid_line "$TEST_PACKAGE")"
test_prev_uid="$(uid_from_line "$test_prev_uid_raw")"
test_prev_data_dir="$(package_data_dir "$TEST_PACKAGE")"
formal_uid_before_update_raw="$(package_uid_line "$FORMAL_PACKAGE")"
formal_uid_before_update="$(uid_from_line "$formal_uid_before_update_raw")"
echo "test_prev_uid_raw=$test_prev_uid_raw"
echo "test_prev_uid=$test_prev_uid"
echo "formal_uid_before_update_raw=$formal_uid_before_update_raw"
[[ -n "$test_prev_uid" ]] || fail "TEST vPrev UID unavailable: $test_prev_uid_raw"
[[ -n "$formal_uid_before_update" ]] || fail "formal UID unavailable before TEST update: $formal_uid_before_update_raw"
[[ "$test_prev_uid" != "$formal_uid_before_update" ]] \
  || fail "formal and TEST vPrev packages have the same UID: $test_prev_uid"

"$ADB" install -r "$TEST_APK" >/dev/null || fail "TEST vPrev to vNext adb install -r failed"
package_installed "$TEST_PACKAGE" || fail "TEST package missing after in-place update"
[[ "$(package_version "$TEST_PACKAGE")" == "$TEST_EXPECTED_VERSION" ]] \
  || fail "TEST version did not advance after update: $(package_version "$TEST_PACKAGE")"

test_updated_uid_raw="$(package_uid_line "$TEST_PACKAGE")"
test_updated_uid="$(uid_from_line "$test_updated_uid_raw")"
test_updated_data_dir="$(package_data_dir "$TEST_PACKAGE")"
echo "test_updated_uid_raw=$test_updated_uid_raw"
echo "test_updated_uid=$test_updated_uid"
[[ -n "$test_updated_uid" ]] || fail "updated TEST UID unavailable: $test_updated_uid_raw"
[[ "$test_updated_uid" == "$test_prev_uid" ]] \
  || fail "TEST UID changed across in-place update: before=$test_prev_uid after=$test_updated_uid"
if [[ -n "$test_prev_data_dir" && -n "$test_updated_data_dir" ]]; then
  [[ "$test_updated_data_dir" == "$test_prev_data_dir" ]] \
    || fail "TEST data directory changed across in-place update"
fi
package_installed "$FORMAL_PACKAGE" || fail "TEST update affected formal package"

cat <<EOF
DRIVE_TEST_COINSTALL_VERIFY_PASS
formal_package=$FORMAL_PACKAGE
test_package=$TEST_PACKAGE
formal_version=$FORMAL_EXPECTED_VERSION
test_prev_version=$TEST_PREV_EXPECTED_VERSION
test_version=$TEST_EXPECTED_VERSION
formal_uid=$formal_uid
test_uid=$test_uid
formal_data_dir=$formal_data_dir
test_data_dir=$test_data_dir
simultaneous_install=true
simultaneous_launch=true
clear_isolation=true
uninstall_isolation=true
test_update_in_place=true
test_update_uid_preserved=true
EOF
