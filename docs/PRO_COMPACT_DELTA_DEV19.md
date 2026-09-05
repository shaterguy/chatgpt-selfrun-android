# SelfRun Drive 2.3.2-dev19: Pro compact delta completion repair

## Basis and action preflight

- Requested base: latest TEST dev18, `selfrun-drive/v2.3.2-dev18`, source `a76fe22592656f98b3444ca1f89a68b937fef581`.
- Candidate: `selfrun-drive/v2.3.2-dev19`, versionCode `2020051`, TEST application identity and fixed signer unchanged.
- Existing active version guards in `app/build.gradle` updated together; `tools/verify_drive_variant.sh` reads version dynamically. Historical fixtures are unchanged.
- Existing workflow: `.github/workflows/build-drive-test.yml`; static compile and JVM tests precede canonical APK build, then API 36 WebView instrumentation and previous-TEST update/formal co-install validation.
- Stable baseline: release `drive-v2.3.1`, source `acc69ccc64303abecc7727354d6dfe0249570651`.
- Actual dev18 predecessor APK: artifact commit `1acd872e7ea2cd3ce13a9bed8f8aec0b22744018`, SHA256 `cc4a1f27553fd60ee9abe4c6b582c91b6194be1572683d81ff077a95d3850fa9`.
- Execution identity is NEW_RUN for the changed source. No baseline rebuild, formal tag, main merge, or Drive binary backup.

## Observations and limits

The supplied run log confirms submission and a still-active native WAIT_TURN_COMPLETION state, but every TURN_PROTOCOL detail was redacted. The user reports the actual conversation was already complete 2m35s after submission. The original encoded Pro stream is not in that log, so the exact live transport/field layout cannot be proven from it.

The previous parser failed to recognize message replacement at `/message`, omitted-path continuation deltas, parts arrays, and relative nested patches. If answer text arrived in those shapes without a final-channel marker, the final `message_stream_complete` remained rejected for missing final-answer evidence.

The native bridge also appended `token=current` to every protocol log detail; the existing privacy filter redacted the entire line on the word `token`. This change logs only validated stage/source/phase enums and `binding=current`, without weakening the privacy filter or logging identifiers/content.

A proposed separate expansion of the Work-only binary/Worker ingress to Chat handoff was blocked at the connector security-decision step and was not applied. This candidate retains that adapter unchanged. It must not be described as verified coverage of every possible live Pro transport.

## Implemented scope

- Preserve current request/conversation/work-turn ownership, early-completion rejection, and single native completion dispatch.
- Track only bounded message role/channel/id and last delta path; never persist the answer text.
- Recognize full message replacement, compact continuation, text-part arrays, and nested relative patches.
- Reject user/tool/analysis/commentary text, metadata fields, foreign message IDs, and stale requests/turns.
- Record an accepted stream handoff as metadata, not completion.
- Keep the UI in THINKING after an ignored early boundary rather than inventing an answer-wait state.
- Preserve Surface detach and protocol-only completion. No DOM completion probing, new network polling, reload, fixed-duration completion, outer-done completion, or status/end-turn completion.

## Verification contract

`ProCompactDeltaWebViewTest` is appended exactly once to the existing instrumentation selection in `tools/verify_drive_ui_runtime.sh`.

The synthetic tests exercise the actual document-start fetch and WebSocket wrappers with a local in-WebView transport fixture: canonical POST, handoff, ignored early complete, compact final answer, authoritative terminal, and exactly one native callback. Separate tests cover negative roles/metadata/message ownership, relative patches/parts arrays, and a replacement request with stale frames.

These are synthetic source-level regressions, not a recording/replay of the unavailable user stream. Test and artifact results are authoritative only after the workflow for the candidate SHA succeeds.
