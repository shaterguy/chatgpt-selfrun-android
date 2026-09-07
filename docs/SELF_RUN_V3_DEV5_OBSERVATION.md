# SelfRun 3.0.1-dev5: observation ownership correction

Baseline: d2313d30f12d80df37f3e123b97b40989ad913f4 (3.0.1-dev4).
Stable baseline: a50cd9d4db2f27fd68486ca14897d88a64155730 (drive-v3.0.0).
Candidate channel: selfrun-v3/v3.0.1-dev5, TEST package, versionCode 3001005.

## Evidence and limits

Run SR-20260907-154154-93XLC0 remained in V3_READY/turn 1. Its first preparation timeout was 2026-09-07 15:43:38.615 KST. However its pinned turn-1 Google Doc was already committed CONTINUE/PLAN/WORK, modified 15:42:45.790 KST. The user also observed the completed answer. Absence of V3_DISPATCH does not establish absence of a physical POST. Previous explanations asserting no initial transmission or a definite editor-model retention failure are not supported by that evidence.

The old source binds the protocol request only in submit(). TurnProtocolLogBridge does not forward events to SelfRun3WebAdapter and compares tokens against the legacy store projection. The engine ignores STARTED unless CLAIM_SEND has already occurred. These are source-confirmed defects. The specific live event which first caused transmission during preparation was not captured by the old log and remains unknown.

## Acceptance conditions

- AC-01: bind and arm current task/request before preparation mutation, and inspect the same protocol state afterward. A current physical POST overrides a DOM return code.
- AC-02: trusted main-frame bridge events are routed to the active V3 adapter using exact task/request/view identity, independently of the legacy token projection.
- AC-03: a current canonical STARTED event during READY records the existing effect in the transactional ledger, moves to WAITING, fences pending preparation callbacks, and prohibits an additional submit.
- AC-04: semantic completion plus a validated pinned result advances to the next logical turn. Reopening the ledger retains the observation. Pause, stop, stale token and malformed completion remain fenced.
- AC-05: use the production native bridge/adapter/ledger in regression, including an early preparation POST and later normal submissions on one conversation.
- AC-06: canonical candidate APK, fixed TEST certificate, dev4-to-dev5 update, stable co-installation and API-36 instrumentation.

## Non-regression boundaries

Composer mutation/selection, SelfRunContinuationDom exclusion, timeout/retry intervals, response parsing, authentication/origin permissions, Drive result contract, model settings, stable branch/release and signing lineage are unchanged. No AI maintenance agent or periodic response DOM polling is added. No new dependency or Android permission is added.

## Implementation mapping

SelfRun3WebAdapter: AC-01/03, per-request observation latches, before/after protocol wrapper, safe structural trace fields. TurnProtocolLogBridge: AC-02, origin/main-frame/schema checks retained, V3 identity owner used. SelfRun3Engine STARTED transition: AC-03/04; actual-effect adoption is not a new permission to send. Coordinator and Drive adapters remain unchanged.

SelfRun3EarlyObservationTest: duplicate claim, stale identity, unprepared task, pause, stop, unobserved result and native wiring assertions. SelfRun3ObservationWebViewTest: actual WebMessage bridge, actual browser adapter preparation/submission, actual SQLite ledger and synthetic service response. Bootstrap profile entry is provided by the harness; the existing profile tests remain separate. This is not a logged-in ChatGPT end-to-end test and not a real Google Drive network test. The early-send fixture exercises the missing observation boundary without claiming to reproduce the unknown live trigger.

## Action preflight

PR-001/002: no probe write or no-op trigger commit. New source/test candidate is committed once before creating its candidate ref. PR-003: app version advanced; protocol contract remains 3.0.0. PR-004: existing run-attempt-specific artifacts retained. PR-005: stable release 3.0.0 freshly verified; dev4 upgrade source pinned by commit/hash/certificate. PR-007: instrumentation coverage added through the existing central runner, not a competing entry point. PR-008: no speculative composer/parser change.

Security delta LIMITED: existing trusted-origin main-frame bridge still bounds strings and validates stage/phase/source. V3 has stricter active-view/task/request ownership. New logs contain only statuses, booleans and bounded element counts, not prompts, response text, auth material or raw network messages. Existing terminal-source allowlist and pinned result validation remain required.

## Verification recording

Only the matching final Actions run, canonical artifact SHA256SUMS/SOURCE_COMMIT, signing evidence and native runtime evidence can establish validation. This document records requirements, not a pre-declared PASS. Live device behavior remains a post-delivery check. Existing development principles cover the diagnostic lesson; no additional global policy or retry redesign is introduced.
