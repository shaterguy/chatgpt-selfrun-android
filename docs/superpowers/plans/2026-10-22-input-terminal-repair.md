# Input and terminal delivery repair

Goal: repair the page helper only after a send decision, append to the current server conversation tip, and reliably discard completed/stopped server work.

The user's three requested behaviors and input-only scope are the design brief. Existing observation, character-count monitoring, profile controls and task scheduling remain the execution paths. No new readiness barrier or task error state is introduced.

Active workspaces: server at b8e88e5a (v4.0.0-dev43 branch); Android at f9edc2f8 (selfrun-v4/v4.0.11-dev4 branch). The separate existing release tracks preserve the newer DEV package lineage.

Applicable prevention rules: PR-003 (update version references together), PR-008 (use the live conversation response and historical parent implementation), PR-009 (keep delivery metadata from blocking the functional result).

- [x] Reproduce revoked-helper failure using the actual generated runtime. Add an authenticated input-preparation operation which renews private helper state and fences old async work without navigation. Call it exclusively from submitIntent.
- [x] Verify the current conversation response. Add a private parent synchronizer enabled only by prepared input; obtain the authoritative current_node immediately before forwarding the matching conversation POST. Preserve message content, identifiers, profile controls and unrelated requests. Retry transient reads within the submission, while ordinary observation remains independent. Terminal disposal cancels pending work.
- [x] Compare 7HEFIQ DONE epoch 7 with 5ERSLM's missing DONE. Reproduce expired/missing-token and process teardown cases. Deliver DONE and STOPPED through durable event-triggered work with fresh authorization and readback; preserve local completion and existing stop retry semantics.
- [x] Exercise DONE/STOPPED across observing, failed input, pending preparation and receipt states. Run the server suite and Android checks, build the existing DEV package once, preserve its certificate, and publish the exact source and artifact.
- [ ] Deploy the server candidate through the existing updater on both server devices, then read back versions, idle/task state, actual tab cleanup, and no-refresh input behavior. Report only observed results and remaining limits.
