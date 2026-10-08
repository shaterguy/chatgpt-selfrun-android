#!/usr/bin/env bash
# Fixture version 1.0.2. Requires a fresh isolated emulator per upgrade edge.
set -euo pipefail
[[ $# == 9 ]] || { echo 'expected EDGE BASE_APK CANDIDATE_APK SIGNED_FIXTURE_APK BASE_SHA CANDIDATE_SHA COMMIT RUN_ID RUN_ATTEMPT' >&2; exit 2; }
: "${ADB_SERIAL:?dedicated emulator serial required}"
: "${ANDROID_HOME:?Android SDK required}"
: "${SOURCE_COMMIT_FILE:?signed artifact SOURCE_COMMIT.txt required}"
: "${CI_ARTIFACT_ID:?exact signed artifact id required}"
: "${CANDIDATE_SHA256SUMS:?formal SHA256SUMS or TEST_SHA256SUMS from the same artifact required}"
[[ "$ADB_SERIAL" =~ ^emulator-[0-9]+$ ]] || { echo 'physical device serial refused' >&2; exit 2; }
ROOT="$(cd "$(dirname "$0")" && pwd)"
EDGE=$1;BASE=$2;CANDIDATE=$3;FIXTURE=$4;BASE_SHA=$5;CANDIDATE_SHA=$6;COMMIT=$7;RUN_ID=$8;RUN_ATTEMPT=$9
ADB="$ANDROID_HOME/platform-tools/adb";BT="$ANDROID_HOME/build-tools/36.0.0"
[[ "$("$ADB" -s "$ADB_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'non-emulator runtime refused' >&2; exit 2; }
TARGET=com.shaterguy.chatgptselfrun.drive;VARIANT=formal
CERT=b3ea944ac1e31438ad697482af6d289c5ffeb0119e89c2e54a755c49c48644fe
case "$EDGE" in
 phone-formal) BASE_CODE=4002010;[[ "$BASE_SHA" == d9a186d97bab55a57123d46e33e2fb1376fbcacfabc734da4b9850af37cd6a15 ]] ;;
 tablet-formal) BASE_CODE=4001000;[[ "$BASE_SHA" == 5cc904e37d601dbfc86a7116278d8e20d0e6d28f13136f695ff0a99f598a0eab ]] ;;
 phone-test) BASE_CODE=4002010;VARIANT=test ;;
 tablet-test) BASE_CODE=4000002;VARIANT=test ;;
 *) echo 'unknown upgrade edge' >&2;exit 2 ;;
esac
if [[ "$VARIANT" == test ]]; then TARGET+=.test;CERT=2c95a5644a0ef2959eaecf10460e300fe2ee7a4ebcede685a82a52634c22e86e;fi
[[ "$BASE_SHA" =~ ^[0-9a-f]{64}$ && "$CANDIDATE_SHA" =~ ^[0-9a-f]{64}$ ]]
[[ "$(tr -d '[:space:]' < "$SOURCE_COMMIT_FILE")" == "$COMMIT" ]]
OUT="$ROOT/evidence/$EDGE";mkdir -p "$OUT"
python3 "$ROOT/verify-successful-run.py" "$COMMIT" "$RUN_ID" "$RUN_ATTEMPT" "$CI_ARTIFACT_ID" > "$OUT/exact-ci-run.json"
[[ "$(sha256sum "$BASE" | cut -d' ' -f1)" == "$BASE_SHA" ]]
[[ "$(sha256sum "$CANDIDATE" | cut -d' ' -f1)" == "$CANDIDATE_SHA" ]]
python3 - "$CANDIDATE_SHA256SUMS" "$CANDIDATE" "$CANDIDATE_SHA" <<'VERIFY_SIGNED_MANIFEST'
from pathlib import Path
import sys
manifest,apk,digest=sys.argv[1:];entries=[]
for line in Path(manifest).read_text().splitlines():
 parts=line.split(maxsplit=1)
 if len(parts)==2 and Path(parts[1].lstrip('*')).name==Path(apk).name:entries.append(parts[0])
if entries!=[digest]:raise SystemExit('candidate SHA is not uniquely present in exact signed artifact manifest')
VERIFY_SIGNED_MANIFEST
for artifact in "$BASE" "$CANDIDATE" "$FIXTURE"; do
  "$BT/apksigner" verify --verbose --print-certs "$artifact" > "$OUT/cert-$(basename "$artifact").txt"
  grep -Fqi "Signer #1 certificate SHA-256 digest: $CERT" "$OUT/cert-$(basename "$artifact").txt"
  grep -Fq 'Number of signers: 1' "$OUT/cert-$(basename "$artifact").txt"
done
BASE_INFO="$("$BT/aapt" dump badging "$BASE")";CANDIDATE_INFO="$("$BT/aapt" dump badging "$CANDIDATE")"
grep -Fq "package: name='$TARGET'" <<<"$BASE_INFO";grep -Fq "versionCode='$BASE_CODE'" <<<"$BASE_INFO"
grep -Fq "package: name='$TARGET'" <<<"$CANDIDATE_INFO";grep -Fq "versionCode='4002016'" <<<"$CANDIDATE_INFO";grep -Fq "versionName='4.0.10-dev3'" <<<"$CANDIDATE_INFO"
FIXTURE_PACKAGE=com.shaterguy.selfrun.upgradefixture.$VARIANT
FIXTURE_INFO="$("$BT/aapt" dump badging "$FIXTURE")";grep -Fq "package: name='$FIXTURE_PACKAGE'" <<<"$FIXTURE_INFO";grep -Fq "versionName='1.0.2'" <<<"$FIXTURE_INFO"
if "$ADB" -s "$ADB_SERIAL" shell pm path "$TARGET" 2>/dev/null | tr -d '\r' | grep -q '^package:';then echo 'fresh isolated emulator required; existing install preserved' >&2;exit 2;fi
"$ADB" -s "$ADB_SERIAL" install --no-incremental "$BASE" > "$OUT/baseline-install.txt"
"$ADB" -s "$ADB_SERIAL" install --no-incremental "$FIXTURE" > "$OUT/fixture-install.txt"
for phase in SEED VERIFY;do
  if [[ "$phase" == VERIFY ]];then "$ADB" -s "$ADB_SERIAL" install -r --no-incremental "$CANDIDATE" > "$OUT/candidate-update.txt";fi
  "$ADB" -s "$ADB_SERIAL" shell am instrument -w -r -e allowIsolatedEmulator 1.0.2 -e expectedTarget "$TARGET" -e phase "$phase" \
    "$FIXTURE_PACKAGE/com.shaterguy.selfrun.upgradefixture.UpgradeFixture" > "$OUT/$phase.txt"
  grep -Fq 'INSTRUMENTATION_RESULT: application_initialization_barrier=PASS' "$OUT/$phase.txt"
  grep -Fq 'INSTRUMENTATION_RESULT: fixture_status=PASS' "$OUT/$phase.txt"
  grep -Fq "INSTRUMENTATION_RESULT: fixture_phase=$phase" "$OUT/$phase.txt"
done
BEFORE="$(sed -n 's/^INSTRUMENTATION_RESULT: snapshot_sha256=//p' "$OUT/SEED.txt" | tr -d '\r')";AFTER="$(sed -n 's/^INSTRUMENTATION_RESULT: snapshot_sha256=//p' "$OUT/VERIFY.txt" | tr -d '\r')"
SEED_CHECKPOINT="$(sed -n 's/^INSTRUMENTATION_RESULT: checkpoint_sha256=//p' "$OUT/SEED.txt" | tr -d '\r')"
VERIFY_CHECKPOINT="$(sed -n 's/^INSTRUMENTATION_RESULT: checkpoint_sha256=//p' "$OUT/VERIFY.txt" | tr -d '\r')"
[[ "$BEFORE" =~ ^[0-9a-f]{64}$ && "$BEFORE" == "$AFTER" && "$BEFORE" == "$SEED_CHECKPOINT" && "$BEFORE" == "$VERIFY_CHECKPOINT" ]]
printf 'ISOLATED_UPDATE_DATA_PASS edge=%s candidate_commit=%s run=%s attempt=%s\n' "$EDGE" "$COMMIT" "$RUN_ID" "$RUN_ATTEMPT" | tee "$OUT/PASS.txt"
