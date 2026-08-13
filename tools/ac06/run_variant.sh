#!/usr/bin/env bash
set -euo pipefail

VARIANT="${1:?variant}"
APK="${2:?apk}"
OUT="${3:?output dir}"
PKG="com.shaterguy.chatgptselfrun"
ACTIVITY="$PKG/.SelfRunAc06ProbeActivity"
CURRENT_STAGE="init"

mkdir -p "$OUT"

dump_failure() {
  local code=$?
  if [ "$code" -eq 0 ]; then return 0; fi
  echo "AC06 variant failed: variant=$VARIANT stage=$CURRENT_STAGE exit=$code" >&2
  for file in "$OUT"/*.json; do
    if [ -f "$file" ]; then
      echo "--- $file ---" >&2
      cat "$file" >&2 || true
    fi
  done
  echo "--- package pid ---" >&2
  adb shell pidof "$PKG" >&2 || true
  echo "--- recent logcat ---" >&2
  adb logcat -d -t 500 >&2 || true
  return "$code"
}
trap dump_failure EXIT

CURRENT_STAGE="install"
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install "$APK" >/dev/null

pull_result() {
  local file="$1"
  local dest="$2"
  for _ in $(seq 1 90); do
    if adb shell run-as "$PKG" test -f "files/$file" >/dev/null 2>&1; then
      adb shell run-as "$PKG" cat "files/$file" > "$dest"
      if jq -e '.error == null' "$dest" >/dev/null; then
        return 0
      fi
      echo "Probe returned an error in $file:" >&2
      cat "$dest" >&2 || true
      return 1
    fi
    sleep 1
  done
  echo "Timed out waiting for $file" >&2
  adb shell dumpsys activity activities | tail -n 200 >&2 || true
  return 1
}

run_mode() {
  local mode="$1"
  local file="$2"
  local dest="$3"
  CURRENT_STAGE="mode:$mode"
  echo "AC06 stage: $VARIANT $CURRENT_STAGE"
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  adb shell am start -W -n "$ACTIVITY" --es mode "$mode" >/dev/null
  pull_result "$file" "$dest"
}

run_mode matrix ac06-matrix.json "$OUT/matrix.json"

CURRENT_STAGE="process_seed"
echo "AC06 stage: $VARIANT $CURRENT_STAGE"
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
adb shell am start -W -n "$ACTIVITY" --es mode process_seed >/dev/null
pull_result ac06-process-seed.json "$OUT/process-seed.json"
OLD_PID="$(jq -r '.pid' "$OUT/process-seed.json")"
test -n "$OLD_PID"
CURRENT_STAGE="process_force_stop"
adb shell am force-stop "$PKG" >/dev/null
for _ in $(seq 1 20); do
  if ! adb shell pidof "$PKG" >/dev/null 2>&1; then break; fi
  sleep 0.2
done
if adb shell pidof "$PKG" >/dev/null 2>&1; then
  echo "process still alive after force-stop" >&2
  exit 1
fi
CURRENT_STAGE="process_verify"
echo "AC06 stage: $VARIANT $CURRENT_STAGE"
adb shell am start -W -n "$ACTIVITY" --es mode process_verify >/dev/null
pull_result ac06-process-verify.json "$OUT/process-verify.json"
NEW_PID="$(jq -r '.pid' "$OUT/process-verify.json")"
if ! jq -e '.recovered == true and .history_recovered == true and .service_started == true and .service_recovered == true' \
  "$OUT/process-verify.json" >/dev/null; then
  echo "Fresh-process recovery assertion failed:" >&2
  cat "$OUT/process-verify.json" >&2
  exit 1
fi
jq --arg old_pid "$OLD_PID" --arg new_pid "$NEW_PID" \
  '. + {previous_pid:($old_pid|tonumber), pid_changed:($old_pid != $new_pid)}' \
  "$OUT/process-verify.json" > "$OUT/process-verify.tmp.json"
mv "$OUT/process-verify.tmp.json" "$OUT/process-verify.json"

run_mode renderer ac06-renderer.json "$OUT/renderer.json"
if ! jq -e '.renderer_recovered == true and .renderer_gone_logged == true' "$OUT/renderer.json" >/dev/null; then
  echo "Renderer recovery assertion failed:" >&2
  cat "$OUT/renderer.json" >&2
  exit 1
fi

CURRENT_STAGE="combine"
jq -n \
  --arg variant "$VARIANT" \
  --slurpfile matrix "$OUT/matrix.json" \
  --slurpfile seed "$OUT/process-seed.json" \
  --slurpfile verify "$OUT/process-verify.json" \
  --slurpfile renderer "$OUT/renderer.json" \
  '{variant:$variant,matrix:$matrix[0],process_seed:$seed[0],process_verify:$verify[0],renderer:$renderer[0]}' \
  > "$OUT/combined.json"

CURRENT_STAGE="complete"
cat "$OUT/combined.json"
