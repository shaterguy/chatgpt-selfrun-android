#!/usr/bin/env bash
# Local-first entrypoint for SelfRun Drive formal APK signing.
# Android runtime / instrumentation belongs in local QA; this script never runs GitHub emulator CI.
set -euo pipefail

REPO='shaterguy/chatgpt-selfrun-android'
WORKFLOW='sign-selfrun-drive-stable.yml'
FORMAL_PACKAGE='com.shaterguy.chatgptselfrun.drive'
FORMAL_CERT='b3ea944ac1e31438ad697482af6d289c5ffeb0119e89c2e54a755c49c48644fe'

usage() {
  cat <<'EOF'
Usage:
  tools/selfrun_stable_release.sh plan
  tools/selfrun_stable_release.sh dispatch --qa-report <local-qa.json>
  tools/selfrun_stable_release.sh collect --run-id <successful-signing-run-id> [--output-dir <directory>]

The stable signing workflow is selected automatically. 'dispatch' requires local QA evidence
bound to the exact source SHA and stable version. No hosted Android emulator tests are run.
After 'collect', verify signed APK in-place updates, data/UID retention and TEST co-install
locally before creating an immutable GitHub tag/Release with the verified APK bytes.
EOF
}

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"; }

mode="${1:-plan}"
if (($#)); then shift; fi
case "$mode" in plan|dispatch|collect|help|--help|-h) ;; *) usage >&2; fail "Unknown mode: $mode";; esac
if [[ "$mode" == 'help' || "$mode" == '--help' || "$mode" == '-h' ]]; then usage; exit 0; fi

qa_report=''
run_id=''
output_dir=''
while (($#)); do
  case "$1" in
    --qa-report) (($# >= 2)) || fail '--qa-report requires a path'; qa_report="$2"; shift 2 ;;
    --run-id) (($# >= 2)) || fail '--run-id requires an ID'; run_id="$2"; shift 2 ;;
    --output-dir) (($# >= 2)) || fail '--output-dir requires a path'; output_dir="$2"; shift 2 ;;
    *) fail "Unknown option: $1" ;;
  esac
done

need git
need sed
need jq
repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || fail 'Execute from a SelfRun Git checkout'
cd "$repo_root"
[[ -s app/build.gradle ]] || fail 'Missing app/build.gradle'
# The signing workflow is registered on GitHub main; the candidate checkout may predate it.

source_sha="$(git rev-parse HEAD)"
[[ "$source_sha" =~ ^[0-9a-f]{40}$ ]] || fail 'Invalid Git source SHA'
version_name="$(sed -n "s/.*selfRunDriveVersionName = '\([^']*\)'.*/\1/p" app/build.gradle | head -1)"
version_code="$(sed -n 's/.*selfRunDriveVersionCode = \([0-9][0-9]*\).*/\1/p' app/build.gradle | head -1)"
[[ "$version_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "Stable version required (got $version_name); do not sign a DEV/RC candidate as formal"
[[ "$version_code" =~ ^[0-9]+$ ]] || fail 'Invalid stable versionCode'

tag="drive-v${version_name}"
run_title="Sign SelfRun Drive v${version_name} (${source_sha})"

printf 'REPO=%s\nSOURCE_SHA=%s\nVERSION_NAME=%s\nVERSION_CODE=%s\nSELECTED_WORKFLOW=%s\nTAG=%s\n' \
  "$REPO" "$source_sha" "$version_name" "$version_code" "$WORKFLOW" "$tag"
if [[ "$mode" == 'plan' ]]; then
  printf 'STATUS=PLAN_ONLY_NO_GITHUB_ACTIONS\n'
  exit 0
fi

need gh
if [[ "$mode" == 'dispatch' ]]; then
  [[ -z "$run_id" && -z "$output_dir" ]] || fail 'dispatch accepts only --qa-report'
  [[ -n "$qa_report" && -s "$qa_report" ]] || fail 'Provide existing local QA report with --qa-report; do not rerun unchanged tests'
  [[ -z "$(git status --porcelain --untracked-files=no)" ]] || fail 'Committed candidate required; tracked working tree is dirty'
  jq -e \
    --arg sha "$source_sha" --arg name "$version_name" --arg code "$version_code" '
      .schema == "selfrun-local-release-qa-v1" and
      .source_sha == $sha and .version_name == $name and
      (.version_code | tostring) == $code and
      (.checks.scope | IN("PASS", "REUSED")) and
      (.checks.static | IN("PASS", "REUSED")) and
      (.checks.unit | IN("PASS", "REUSED")) and
      (.checks.runtime | IN("PASS", "REUSED", "UNVERIFIED"))
    ' "$qa_report" >/dev/null || fail 'Local QA report is invalid or does not match exact source/version'

  remote_sha="$(gh api "repos/$REPO/git/commits/$source_sha" --jq '.sha')" || fail 'Candidate source is not preserved on GitHub'
  [[ "$remote_sha" == "$source_sha" ]] || fail 'GitHub remote source SHA mismatch'
  workflow_state="$(gh api "repos/$REPO/actions/workflows/$WORKFLOW" --jq '.state')" || fail 'New stable signing workflow is not registered on GitHub'
  [[ "$workflow_state" == 'active' ]] || fail "Stable signing workflow is not active: $workflow_state"

  if gh api "repos/$REPO/releases/tags/$tag" --silent >/dev/null 2>&1; then
    fail "Immutable formal Release already exists: $tag; choose a new version instead"
  fi

  existing="$(gh run list -R "$REPO" --workflow "$WORKFLOW" --limit 100 \
    --json databaseId,displayTitle,status,conclusion \
    --jq ".[] | select(.displayTitle == \"$run_title\") | [.databaseId, .status, (.conclusion // \"\")] | @tsv" | head -1)"
  if [[ -n "$existing" ]]; then
    printf 'EXISTING_SIGNING_RUN=%s\nSTATUS=REUSE_OR_DIAGNOSE_NO_DUPLICATE_DISPATCH\n' "$existing"
    exit 0
  fi

  # Only one remote responsibility: formally build/sign the pinned source.
  gh workflow run "$WORKFLOW" -R "$REPO" --ref main \
    -f "expected_source_sha=$source_sha" \
    -f "expected_version_name=$version_name" \
    -f "expected_version_code=$version_code"
  printf 'STATUS=DISPATCH_ACCEPTED_TERMINAL_NOT_YET_VERIFIED\n'
  printf 'NEXT=Check exact run status/conclusion directly in GitHub until terminal, then use collect\n'
  exit 0
fi

# Do not access artifact, job or log details until the exact run has terminal success.
[[ "$mode" == 'collect' ]] || fail 'Unexpected release mode'
[[ -z "$qa_report" ]] || fail 'collect does not use --qa-report'
[[ "$run_id" =~ ^[0-9]+$ ]] || fail 'collect requires numeric --run-id'
need sha256sum
need apksigner
need aapt
terminal="$(gh run view "$run_id" -R "$REPO" --json status,conclusion,displayTitle)" || fail 'Cannot read exact signing run status'
run_status="$(jq -r '.status' <<< "$terminal")"
run_conclusion="$(jq -r '.conclusion // ""' <<< "$terminal")"
run_display="$(jq -r '.displayTitle' <<< "$terminal")"
[[ "$run_status" == 'completed' && "$run_conclusion" == 'success' ]] || fail "Signing run not successful terminal: $run_status/$run_conclusion"
[[ "$run_display" == "$run_title" ]] || fail 'Signing run source/version title differs from current candidate'
run_attempt="$(gh api "repos/$REPO/actions/runs/$run_id" --jq '.run_attempt')"
[[ "$run_attempt" =~ ^[0-9]+$ ]] || fail 'Cannot resolve successful run attempt'
artifact="selfrun-drive-stable-${version_name}-${run_id}-${run_attempt}"
if [[ -z "$output_dir" ]]; then
  output_dir="$repo_root/.release-check/stable-signing-${version_name}-${source_sha:0:12}-${run_id}-${run_attempt}"
fi
[[ ! -e "$output_dir" ]] || fail "Output already exists; verify/reuse it instead of overwriting: $output_dir"
mkdir -p "$output_dir"
gh run download "$run_id" -R "$REPO" --name "$artifact" --dir "$output_dir"

# GitHub artifacts can extract their common release-output prefix at either depth.
mapfile -t apks < <(find "$output_dir" -type f -name "SelfRun-Drive-v${version_name}.apk")
[[ ${#apks[@]} -eq 1 ]] || fail "Expected one signed formal APK, got ${#apks[@]}"
apk="${apks[0]}"
mapfile -t sources < <(find "$output_dir" -type f -name SOURCE_COMMIT.txt)
mapfile -t manifests < <(find "$output_dir" -type f -name SHA256SUMS.txt)
mapfile -t identities < <(find "$output_dir" -type f -name IDENTITY.txt)
[[ ${#sources[@]} -eq 1 && ${#manifests[@]} -eq 1 && ${#identities[@]} -eq 1 ]] || fail 'Ambiguous or missing signing provenance files'
[[ "$(tr -d '[:space:]' < "${sources[0]}")" == "$source_sha" ]] || fail 'Artifact source commit mismatch'
grep -Fxq "sourceSha=$source_sha" "${identities[0]}" || fail 'Identity source mismatch'
grep -Fxq "versionName=$version_name" "${identities[0]}" || fail 'Identity versionName mismatch'
grep -Fxq "versionCode=$version_code" "${identities[0]}" || fail 'Identity versionCode mismatch'
grep -Fxiq "certificateSha256=$FORMAL_CERT" "${identities[0]}" || fail 'Identity certificate mismatch'
expected_hash="$(awk -v n="SelfRun-Drive-v${version_name}.apk" '{p=$2; sub(/^\*/, "", p); sub(/^.*\//, "", p); if(p==n) print $1}' "${manifests[0]}")"
[[ "$expected_hash" =~ ^[0-9a-f]{64}$ ]] || fail 'Missing or invalid published APK SHA256SUMS entry'
actual_hash="$(sha256sum "$apk" | awk '{print $1}')"
[[ "$expected_hash" == "$actual_hash" ]] || fail 'Signed APK hash mismatch'
cert="$output_dir/FORMAL_APKSIGNER_LOCAL_VERIFY.txt"
apksigner verify --verbose --print-certs "$apk" > "$cert"
grep -Fqi "$FORMAL_CERT" "$cert" || fail 'Formal signing certificate mismatch'
badging="$(aapt dump badging "$apk")"
grep -Fq "package: name='$FORMAL_PACKAGE'" <<< "$badging" || fail 'Formal applicationId mismatch'
grep -Fq "versionCode='$version_code'" <<< "$badging" || fail 'Formal versionCode mismatch'
grep -Fq "versionName='$version_name'" <<< "$badging" || fail 'Formal versionName mismatch'
printf 'STATUS=SIGNED_APK_IDENTITY_VERIFIED\nRUN_ID=%s\nRUN_ATTEMPT=%s\nAPK_PATH=%s\nSHA256=%s\n' \
  "$run_id" "$run_attempt" "$apk" "$actual_hash"
printf 'NEXT=Run local signed-APK in-place update, UID/data retention and TEST co-install checks BEFORE tag/Release publication\n'
