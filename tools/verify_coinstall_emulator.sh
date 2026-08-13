#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <legacy-debug-apk> <drive-debug-apk>" >&2
  exit 2
fi

LEGACY_APK="$1"
DRIVE_APK="$2"
ADB_BINARY="${ADB:-adb}"
ADB_COMMAND_TIMEOUT_SECONDS="${ADB_COMMAND_TIMEOUT_SECONDS:-60}"

adb_checked() {
  timeout --foreground "${ADB_COMMAND_TIMEOUT_SECONDS}s" "$ADB_BINARY" "$@"
}

ADB=adb_checked
LEGACY_PACKAGE="com.shaterguy.chatgptselfrun"
DRIVE_PACKAGE="com.shaterguy.chatgptselfrun.drive"
LEGACY_MAIN="$LEGACY_PACKAGE/com.shaterguy.chatgptselfrun.MainActivity"
DRIVE_MAIN="$DRIVE_PACKAGE/com.shaterguy.chatgptselfrun.MainActivity"
LEGACY_LOGIN="$LEGACY_PACKAGE/com.shaterguy.chatgptselfrun.LoginActivity"
DRIVE_LOGIN="$DRIVE_PACKAGE/com.shaterguy.chatgptselfrun.LoginActivity"
LEGACY_SERVICE="$LEGACY_PACKAGE/com.shaterguy.chatgptselfrun.SelfRunService"
DRIVE_SERVICE="$DRIVE_PACKAGE/com.shaterguy.chatgptselfrun.SelfRunService"

fail() {
  echo "COINSTALL_VERIFY_FAILED: $*" >&2
  exit 1
}

require_file() {
  [[ -s "$1" ]] || fail "APK missing or empty: $1"
}

package_installed() {
  "$ADB" shell pm list packages | tr -d '\r' | grep -Fxq "package:$1"
}

assert_launcher() {
  local package="$1" expected="$2" resolved actual_package actual_class expected_package expected_class
  resolved="$("$ADB" shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$package" | tr -d '\r' | tail -1)"
  actual_package="${resolved%%/*}"
  actual_class="${resolved#*/}"
  expected_package="${expected%%/*}"
  expected_class="${expected#*/}"
  if [[ "$actual_class" == .* ]]; then
    actual_class="${actual_package}${actual_class}"
  fi
  if [[ "$expected_class" == .* ]]; then
    expected_class="${expected_package}${expected_class}"
  fi
  [[ "$actual_package" == "$expected_package" && "$actual_class" == "$expected_class" ]] \
    || fail "launcher resolution mismatch for $package: $resolved"
}

package_version() {
  "$ADB" shell dumpsys package "$1" | tr -d '\r' | sed -n 's/^[[:space:]]*versionName=//p' | head -1
}

package_uid() {
  "$ADB" shell dumpsys package "$1" | tr -d '\r' | sed -n 's/^[[:space:]]*userId=//p' | head -1
}

run_as() {
  local package="$1"
  shift
  "$ADB" shell run-as "$package" "$@"
}

write_sentinel() {
  local package="$1" value="$2"
  printf '%s' "$value" | "$ADB" shell "run-as $package sh -c 'umask 077; mkdir -p files; cat > files/coinstall-sentinel'"
}

assert_sentinel() {
  local package="$1" expected="$2" actual
  actual="$(run_as "$package" cat files/coinstall-sentinel | tr -d '\r')"
  [[ "$actual" == "$expected" ]] || fail "$package private data changed: $actual"
}

write_paused_state() {
  local package="$1" prefs="$2" run_id="$3" status="$4"
  local xml
  xml="<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name=\"runId\">$run_id</string><string name=\"phase\">PAUSED</string><string name=\"status\">$status</string><boolean name=\"active\" value=\"true\" /><boolean name=\"paused\" value=\"true\" /><boolean name=\"userStopped\" value=\"false\" /></map>"
  printf '%s' "$xml" | "$ADB" shell "run-as $package sh -c 'umask 077; mkdir -p shared_prefs; cat > shared_prefs/$prefs.xml'"
}

start_own_component() {
  local package="$1" main="$2" service="$3" action="$4"
  run_as "$package" am start -W -n "$main" >/dev/null
  run_as "$package" am start-foreground-service -n "$service" -a "$action" >/dev/null
}

assert_service_running() {
  local package="$1"
  "$ADB" shell dumpsys activity services "$package" | tr -d '\r' \
    | grep -Fq 'com.shaterguy.chatgptselfrun.SelfRunService' \
    || fail "$package foreground service not running"
}

assert_cross_start_blocked() {
  local caller="$1" target_service="$2" action="$3" output
  output="$(run_as "$caller" am startservice -n "$target_service" -a "$action" 2>&1 || true)"
  if ! grep -Eqi 'permission denial|not exported|not allowed|error' <<<"$output"; then
    fail "$caller unexpectedly started $target_service: $output"
  fi
}

require_file "$LEGACY_APK"
require_file "$DRIVE_APK"
"$ADB" wait-for-device

echo "Installing legacy APK first"
"$ADB" install -r "$LEGACY_APK" >/dev/null
package_installed "$LEGACY_PACKAGE" || fail "legacy package not installed"
legacy_version_before="$(package_version "$LEGACY_PACKAGE")"
[[ "$legacy_version_before" == "0.2.3" ]] || fail "unexpected legacy version: $legacy_version_before"
legacy_uid_before="$(package_uid "$LEGACY_PACKAGE")"
write_sentinel "$LEGACY_PACKAGE" "legacy-private-data"

run_as "$LEGACY_PACKAGE" am start -W -n "$LEGACY_LOGIN" >/dev/null
sleep 4
"$ADB" shell am force-stop "$LEGACY_PACKAGE"
run_as "$LEGACY_PACKAGE" test -d app_webview || fail "legacy WebView store not created"
legacy_webview_hash_before="$("$ADB" exec-out run-as "$LEGACY_PACKAGE" tar -cf - app_webview | sha256sum | cut -d' ' -f1)"

echo "Installing Drive APK alongside legacy"
"$ADB" install -r "$DRIVE_APK" >/dev/null
package_installed "$LEGACY_PACKAGE" || fail "legacy package disappeared after Drive install"
package_installed "$DRIVE_PACKAGE" || fail "Drive package not installed"
assert_launcher "$LEGACY_PACKAGE" "$LEGACY_MAIN"
assert_launcher "$DRIVE_PACKAGE" "$DRIVE_MAIN"
[[ "$(package_version "$LEGACY_PACKAGE")" == "$legacy_version_before" ]] \
  || fail "legacy version changed after Drive install"
[[ "$(package_uid "$LEGACY_PACKAGE")" == "$legacy_uid_before" ]] \
  || fail "legacy UID changed after Drive install"
[[ "$(package_version "$DRIVE_PACKAGE")" == "1.0.0-dev1" ]] \
  || fail "unexpected Drive version"
drive_uid="$(package_uid "$DRIVE_PACKAGE")"
[[ -n "$legacy_uid_before" && -n "$drive_uid" && "$legacy_uid_before" != "$drive_uid" ]] \
  || fail "packages do not have distinct UIDs"
assert_sentinel "$LEGACY_PACKAGE" "legacy-private-data"

run_as "$DRIVE_PACKAGE" am start -W -n "$DRIVE_LOGIN" >/dev/null
sleep 4
"$ADB" shell am force-stop "$DRIVE_PACKAGE"
run_as "$DRIVE_PACKAGE" test -d app_webview || fail "Drive WebView store not created"
legacy_webview_hash_after="$("$ADB" exec-out run-as "$LEGACY_PACKAGE" tar -cf - app_webview | sha256sum | cut -d' ' -f1)"
[[ "$legacy_webview_hash_before" == "$legacy_webview_hash_after" ]] \
  || fail "Drive launch changed legacy WebView data"
legacy_data_dir="$(run_as "$LEGACY_PACKAGE" pwd | tr -d '\r')"
drive_data_dir="$(run_as "$DRIVE_PACKAGE" pwd | tr -d '\r')"
[[ "$legacy_data_dir" != "$drive_data_dir" ]] || fail "packages share one data directory"
write_sentinel "$DRIVE_PACKAGE" "drive-private-data"

"$ADB" shell pm grant "$LEGACY_PACKAGE" android.permission.POST_NOTIFICATIONS || true
"$ADB" shell pm grant "$DRIVE_PACKAGE" android.permission.POST_NOTIFICATIONS || true
"$ADB" shell am force-stop "$LEGACY_PACKAGE"
"$ADB" shell am force-stop "$DRIVE_PACKAGE"
write_paused_state "$LEGACY_PACKAGE" selfrun CI-LEGACY-PAUSED "SelfRun co-install check"
write_paused_state "$DRIVE_PACKAGE" selfrun_drive CI-DRIVE-PAUSED "SelfRun Drive co-install check"
start_own_component "$LEGACY_PACKAGE" "$LEGACY_MAIN" "$LEGACY_SERVICE" "$LEGACY_PACKAGE.RUN"
start_own_component "$DRIVE_PACKAGE" "$DRIVE_MAIN" "$DRIVE_SERVICE" "$DRIVE_PACKAGE.RUN"
sleep 3
assert_service_running "$LEGACY_PACKAGE"
assert_service_running "$DRIVE_PACKAGE"
notifications="$("$ADB" shell dumpsys notification --noredact | tr -d '\r')"
grep -Fq "$LEGACY_PACKAGE" <<<"$notifications" || fail "legacy notification missing"
grep -Fq "$DRIVE_PACKAGE" <<<"$notifications" || fail "Drive notification missing"
assert_cross_start_blocked "$LEGACY_PACKAGE" "$DRIVE_SERVICE" "$LEGACY_PACKAGE.PAUSE"
assert_cross_start_blocked "$DRIVE_PACKAGE" "$LEGACY_SERVICE" "$DRIVE_PACKAGE.PAUSE"

echo "Checking clear/uninstall isolation"
"$ADB" shell pm clear "$DRIVE_PACKAGE" >/dev/null
assert_sentinel "$LEGACY_PACKAGE" "legacy-private-data"
"$ADB" install -r "$DRIVE_APK" >/dev/null
write_sentinel "$DRIVE_PACKAGE" "drive-private-data"
"$ADB" shell pm clear "$LEGACY_PACKAGE" >/dev/null
assert_sentinel "$DRIVE_PACKAGE" "drive-private-data"
"$ADB" install -r "$LEGACY_APK" >/dev/null
write_sentinel "$LEGACY_PACKAGE" "legacy-private-data"
"$ADB" uninstall "$DRIVE_PACKAGE" >/dev/null
assert_sentinel "$LEGACY_PACKAGE" "legacy-private-data"
"$ADB" install -r "$DRIVE_APK" >/dev/null
write_sentinel "$DRIVE_PACKAGE" "drive-private-data"
"$ADB" uninstall "$LEGACY_PACKAGE" >/dev/null
assert_sentinel "$DRIVE_PACKAGE" "drive-private-data"
"$ADB" install -r "$LEGACY_APK" >/dev/null
package_installed "$LEGACY_PACKAGE" || fail "legacy reinstall failed"
package_installed "$DRIVE_PACKAGE" || fail "Drive package lost after legacy reinstall"

cat <<EOF
COINSTALL_VERIFY_PASS
legacy_package=$LEGACY_PACKAGE
legacy_version=$legacy_version_before
legacy_uid=$legacy_uid_before
legacy_data_dir=$legacy_data_dir
drive_package=$DRIVE_PACKAGE
drive_version=1.0.0-dev1
drive_uid=$drive_uid
drive_data_dir=$drive_data_dir
legacy_webview_hash_preserved=$legacy_webview_hash_after
simultaneous_services=true
simultaneous_notifications=true
cross_package_service_start_blocked=true
clear_uninstall_isolation=true
EOF
