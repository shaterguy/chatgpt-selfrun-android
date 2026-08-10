#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <unsigned-apk> <signing-pass-file> <output-apk>" >&2
  exit 2
fi

UNSIGNED="$1"
PASS_FILE="$2"
OUTPUT="$3"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

python3 "$ROOT/tools/derive_signing_identity.py" \
  --pass-file "$PASS_FILE" \
  --out-p12 "$TMP/selfrun.p12" \
  --out-cert "$TMP/selfrun-cert.pem" \
  > "$TMP/cert-sha256.txt"

PASS="$(tr -d '\r\n' < "$PASS_FILE")"
jarsigner \
  -keystore "$TMP/selfrun.p12" \
  -storetype PKCS12 \
  -storepass "$PASS" \
  -keypass "$PASS" \
  -sigalg SHA256withRSA \
  -digestalg SHA-256 \
  -signedjar "$OUTPUT" \
  "$UNSIGNED" selfrun

jarsigner -verify -strict -certs "$OUTPUT" >/dev/null
sha256sum "$OUTPUT"
echo "certificate_sha256=$(cat "$TMP/cert-sha256.txt")"
