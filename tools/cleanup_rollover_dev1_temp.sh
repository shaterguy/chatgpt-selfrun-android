#!/usr/bin/env bash
set -euo pipefail
# This one-shot helper is executed in CI and deleted by the same cleanup commit.
rm -f \
  .github/workflows/apply-rollover-dev1.yml \
  .github/workflows/apply-rollover-dev1-v2.yml \
  .github/workflows/check-rollover-dev1.yml \
  .github/workflows/fix-rollover-dev1-policy.yml \
  .github/workflows/fix-rollover-dev1-policy-v2.yml \
  .github/workflows/fix-rollover-dev1-identity-tests.yml \
  tools/apply_rollover_dev1.py \
  tools/fix_rollover_dev1_policy.py \
  tools/fix_rollover_dev1_identity_tests.py \
  tools/cleanup_rollover_dev1_temp.sh
