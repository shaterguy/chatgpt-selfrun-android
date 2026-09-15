#!/usr/bin/env bash
set -euo pipefail

for name in \
  SELFRUN_FIREBASE_API_KEY \
  SELFRUN_FIREBASE_APPLICATION_ID \
  SELFRUN_FIREBASE_PROJECT_ID \
  SELFRUN_FIREBASE_SENDER_ID; do
  if [[ -z "${!name:-}" ]]; then
    echo "::error title=Firebase candidate configuration missing::${name} is not configured"
    exit 1
  fi
done
