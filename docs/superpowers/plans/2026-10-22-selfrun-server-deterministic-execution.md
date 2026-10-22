# SelfRun deterministic server execution plan

> For agentic workers: execute the already-authorized implementation with BUILDER, independent VERIFICATION, and RELEASE_OPERATIONS responsibilities kept separate. Existing production tasks continue until the deferred install becomes eligible.

**Goal:** Execute the app's create, watch, continue, replace, stop, and done commands without deciding whether the AI task is progressing correctly; stage automatic installation after all current tasks disappear.

**Architecture:** Keep the existing app ingress, durable lifecycle storage, authentication, and execution ownership. Replace progress decisions with a character-count clock and an intervention-only current-document check, and await physical tab disposal before any successor creates a tab.

**Tech stack:** Node.js 24 ESM, Chromium CDP with the existing extension adapter, Google Drive control/dispatch transport, and the existing Termux runit service.

**Baseline:** The running Galaxy-Tab-S11-Ultra server is `shaterguy/chatgpt-selfrun-android` at `c24edfef`, `hotfix/server-v4.0.0-dev40-tgwo3z-profile`. Changes are isolated from the running checkout. The requested delivery is a server update and a verified one-shot deferred installation, not an Android APK or stable release.

## User contract and scope

- AC-01: Every app conversation-creation request uses the same fresh-tab preparation and prompt submission path. Deliver the conversation URL as soon as it exists, including after delayed or inconclusive submission readback.
- AC-02: Observe generated conversation character count. Any length change resets the quiet clock. At each 600000 ms without a length change, freshly read the current turn's task document (`result_document_id`). If its exact state is `status=USER_ACTION_REQUIRED`, skip only that cycle's continuation input; continue monitoring and repeat the document check after the next quiet interval. Otherwise send the configured continuation prompt and repeat the observation cycle. Content meaning, fingerprints, response IDs, busy/paused indicators, `committed`, and other Result fields do not decide progress. The user explicitly added this intervention-only exception during implementation.
- AC-03: Before creating the next conversation for a task, abort the prior monitor/input and confirm all prior task targets are absent. At most one task tab exists at any instant. Turn number never selects another execution process.
- AC-04: App STOP or DONE immediately revokes further input and observation, then disposes task tabs, in-flight openings, timers, sessions, and task resources. Late async completions cannot recreate or adopt resources.
- AC-05: Remove progress-inference errors and gates. Retain errors backed by an invalid request or an actual failure of the requested authentication, transport, browser, navigation, or input operation. Uncertain receipt or delayed URL alone is not such proof.
- NR-01: Preserve app command authority, exactly-once initial submission, authenticated ownership, isolation between unrelated tasks, existing profile selection, and remote source identity. Never stop existing production tasks to make deployment eligible.
- REL-01: Register a one-shot installation of the exact verified candidate. Wait while any app task, pending dispatch, local execution, or owned task tab remains. Exclude ingress during the final idle recheck; resume the old process whenever a new task or uncertainty appears. Recreate the owned browser when needed so old extension code cannot survive installation.

The current task-document pointer is grounded in `SELF_RUN_RESULT_HANDOFF_CONTRACT` v1.6.0, section 1.1 (Drive modified 2026-10-22T04:02:29.020Z), which explicitly defines “작업문서” as the current turn Result. The exact intervention state and resolution are `USER_ACTION_REQUIRED` and `USER_ACTION_RESOLVED` in `SELF_RUN_INTERVENTION_RECOVERY_CONTRACT` v1.2.2. A failed or inconclusive required document read does not establish absence of intervention: continue observing and retry that I/O with bounded calls and paced retries. Do not emit a progress-inference error or stop the monitor. Recheck the unchanged-length condition and task authority after the read so a late reply cannot send a stale continuation after new characters, STOP, DONE, or a successor.

## Implementation units

1. `termux-server/src/drive-controller.mjs` and `src/browser/chatgpt.mjs`: replace result/receipt/state recovery decisions with the AC-01/02 command sequence; exercise length-only observation, delayed URL, repeated continuations, and no semantic gates. Test two consecutive intervention windows with no input and continued observation, intervention resolution followed by the next scheduled input, length-change clock resets, and STOP while the document read is pending.
2. `termux-server/src/terminal-browser-cleanup.mjs` and the affected CDP/extension close primitives: await physical predecessor-target disposal and preserve cleanup authority until target absence is observed; exercise replacement, close failure, STOP/DONE during prepare/submit, and late target creation.
3. `termux-server/tools/apply-when-idle.mjs` and its adjacent tests: implement a one-shot deferred installer with source/launcher identity checks, final idle exclusion, restoration on interrupted installation, and readback of registration/application state. RELEASE owns this unit; BUILDER owns execution changes.
4. Add a source-backed audit mapping reachable server gates and errors to removal, replacement, or concrete operation failure. Keep the audit with the candidate source.

## Verification and delivery

The independent verifier reviews the final frozen diff and exact file hashes, maps evidence to AC-01 through AC-05, NR-01, and REL-01, and reports unverified paths explicitly. Reuse tests whose contract is unchanged. Reconcile tests whose prior progress-inference expectations were deliberately replaced. Run syntax checks, targeted deterministic regressions, applicable existing regression tests, and available isolated browser fixtures; production conversations are not test fixtures.

Preserve the final source on a versioned GitHub ref and read back its exact SHA. Only after independent verification may RELEASE register the deferred installation and read back its running PID, pinned source, state, and unchanged active launcher/tasks. Report `WAITING_FOR_IDLE` separately from `APPLIED`. No GitHub Actions execution is needed for this server-only change.
