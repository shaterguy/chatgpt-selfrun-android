#!/usr/bin/env bash
set -euo pipefail

PKG="com.shaterguy.chatgptselfrun"
COMP="$PKG/.SelfRunRuntimeProbeActivity"
OUT="${1:?output directory required}"
mkdir -p "$OUT"

wait_json() {
  local remote_name="$1"
  local dest="$2"
  local tmp="$dest.tmp"
  rm -f "$tmp"
  for _ in $(seq 1 300); do
    if adb shell "run-as $PKG cat files/ac06-$remote_name.json" >"$tmp" 2>/dev/null; then
      if python3 - "$tmp" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    json.load(fh)
PY
      then
        mv "$tmp" "$dest"
        return 0
      fi
    fi
    sleep 0.2
  done
  echo "Timed out waiting for ac06-$remote_name.json" >&2
  adb shell dumpsys activity activities | tail -n 120 >&2 || true
  adb logcat -d -t 300 >&2 || true
  return 1
}

assert_pass() {
  python3 - "$1" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    item=json.load(fh)
if not item.get("pass"):
    raise SystemExit(f"scenario failed: {item}")
print(json.dumps(item, sort_keys=True))
PY
}

run_scenario() {
  local scenario="$1"
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  adb shell pm clear "$PKG" >/dev/null
  adb shell am start -W -n "$COMP" --es action "$scenario" >/dev/null
  wait_json "$scenario" "$OUT/$scenario.json"
  assert_pass "$OUT/$scenario.json"
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
}

for scenario in \
  long_response \
  dom_mutation \
  watchdog \
  long_stable \
  pause_user_action \
  pause_self \
  pause_manual \
  stale_callback \
  renderer_recovery
do
  run_scenario "$scenario"
done

# True process-boundary recovery: keep the preparation process alive, force-stop it externally,
# then launch a second Activity process which reads only persisted app state/history.
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
adb shell pm clear "$PKG" >/dev/null
adb shell am start -W -n "$COMP" --es action process_prepare >/dev/null
wait_json process_prepare "$OUT/process_prepare.json"
assert_pass "$OUT/process_prepare.json"

old_pid="$(python3 - "$OUT/process_prepare.json" <<'PY'
import json, sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["pid"])
PY
)"
test -n "$old_pid"
test "$old_pid" -gt 0

adb shell am force-stop "$PKG" >/dev/null
for _ in $(seq 1 50); do
  running="$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)"
  if [ -z "$running" ]; then
    break
  fi
  sleep 0.1
done
running="$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)"
test -z "$running"

adb shell am start -W -n "$COMP" --es action process_verify >/dev/null
wait_json process "$OUT/process.json"
assert_pass "$OUT/process.json"

new_pid="$(python3 - "$OUT/process.json" <<'PY'
import json, sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["pid"])
PY
)"
test "$new_pid" -gt 0
test "$new_pid" -ne "$old_pid"

echo "AC06 device matrix complete: $OUT"
