#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <aligned-unsigned-apk> <signing-pass-file> <output-apk>" >&2
  exit 2
fi

UNSIGNED="$1"
PASS_FILE="$2"
OUTPUT="$3"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

EXPECTED_CERT_SHA256="b3ea944ac1e31438ad697482af6d289c5ffeb0119e89c2e54a755c49c48644fe"
APKSIGNER="${APKSIGNER:-apksigner}"

if [[ ! -s "$UNSIGNED" ]]; then
  echo "aligned unsigned APK not found or empty: $UNSIGNED" >&2
  exit 1
fi
if [[ ! -s "$PASS_FILE" ]]; then
  echo "signing passphrase file not found or empty" >&2
  exit 1
fi
if ! command -v "$APKSIGNER" >/dev/null 2>&1; then
  echo "apksigner not found; set APKSIGNER to the Android Build Tools apksigner path" >&2
  exit 1
fi

python3 "$ROOT/tools/derive_signing_identity.py" \
  --pass-file "$PASS_FILE" \
  --out-p12 "$TMP/selfrun.p12" \
  --out-cert "$TMP/selfrun-cert.pem" \
  > "$TMP/cert-sha256.txt"

DERIVED_CERT_SHA256="$(tr -d '[:space:]' < "$TMP/cert-sha256.txt" | tr '[:upper:]' '[:lower:]')"
if [[ "$DERIVED_CERT_SHA256" != "$EXPECTED_CERT_SHA256" ]]; then
  echo "derived signing certificate does not match the SelfRun update lineage" >&2
  echo "expected_certificate_sha256=$EXPECTED_CERT_SHA256" >&2
  echo "derived_certificate_sha256=$DERIVED_CERT_SHA256" >&2
  exit 1
fi

rm -f "$OUTPUT"
"$APKSIGNER" sign \
  --ks "$TMP/selfrun.p12" \
  --ks-type PKCS12 \
  --ks-key-alias selfrun \
  --ks-pass "file:$PASS_FILE" \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$OUTPUT" \
  "$UNSIGNED"

VERIFY_LOG="$TMP/apksigner-verify.txt"
"$APKSIGNER" verify --verbose --print-certs "$OUTPUT" | tee "$VERIFY_LOG"

grep -Fq "Verified using v2 scheme (APK Signature Scheme v2): true" "$VERIFY_LOG"
grep -Fq "Verified using v3 scheme (APK Signature Scheme v3): true" "$VERIFY_LOG"
grep -Fq "Number of signers: 1" "$VERIFY_LOG"

SIGNED_CERT_SHA256="$(awk -F': ' '/Signer #1 certificate SHA-256 digest:/ {print $2; exit}' "$VERIFY_LOG" | tr -d '[:space:]:' | tr '[:upper:]' '[:lower:]')"
if [[ "$SIGNED_CERT_SHA256" != "$EXPECTED_CERT_SHA256" ]]; then
  echo "signed APK certificate does not match the SelfRun update lineage" >&2
  echo "expected_certificate_sha256=$EXPECTED_CERT_SHA256" >&2
  echo "signed_certificate_sha256=${SIGNED_CERT_SHA256:-missing}" >&2
  exit 1
fi

sha256sum "$OUTPUT"
echo "certificate_sha256=$SIGNED_CERT_SHA256"
echo "signature_v2=true"
echo "signature_v3=true"
echo "signers=1"
