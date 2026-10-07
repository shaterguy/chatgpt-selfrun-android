# Preparation-only retry and timing diagnostics

## Retry boundary

The server durably records `pre_submit_proof` at fresh locally authorized DISCOVERED intake, before entering browser preparation. It contains a version and digest of the accepted project URL, prompt, profile operations and Result document identity. A failed preparation keeps this proof even if its visible state becomes BLOCKED. Cancellation can retire such a request as `never_submitted:true`, including cancellation before browser preparation begins.

A replacement requires a strictly higher app attempt, an exact matching positive-epoch RUNNING CONTROL, empty conversation identity and unchanged accepted input. Cancellation from an older attempt cannot cancel the replacement. Any durable send intent, existing conversation URL, submission confirmation, observation, STOP or committed result revokes the proof. Caller-supplied Drive proofs and never_submitted claims are stripped at cold import. Historical projections, prior audit/legacy identities and old local snapshots without proof cannot manufacture it during preparation. A genuine local snapshot restart or trusted retired higher-attempt retry preserves existing proof. Existing false retired markers are not automatically migrated.

## Metadata-only CDP evidence

Runtime evaluation timeouts retain existing `CDP_QUERY_TIMEOUT` / `CDP_QUERY_LATE_REPLY` names. Other commands, including Page.enable, emit `CDP_COMMAND_TIMEOUT` / `CDP_COMMAND_LATE_REPLY`. Command IDs, sanitized method names, monotonic elapsed time, timeout overrun, socket state, inbound frame/reply counts, event-loop maximum sampling delay and sample count are the only new evidence.

A single per-session timer samples the Node event loop once per second while commands are pending and is cleared when the final command settles. Slow successful progress observations include this evidence beside existing in-engine/outside-engine elapsed times. High event-loop delay indicates Node-side scheduling delay; low delay with high outside-engine duration leaves browser scheduling, IPC or transport as remaining possibilities. It is evidence for diagnosis, not a cause verdict. Short commands may have zero samples; timeout overrun remains available.

Diagnostic output is capped at 64 events per session per minute. Late-reply tracking retains at most 32 IDs for 60 seconds. No params, scripts, reply bodies, credentials, websocket URLs or page/account text enter these diagnostics. Command payloads, deadlines, navigation and submission behavior are unchanged; no retry is added.

## Exact offline recovery

`tools/repair-preparation-cancellation.mjs` exports:

`repairPreparationCancellation(snapshot, evidence)`

This is a pure function with no device, process, filesystem or network effects. It is intentionally pinned to SR-20261007-150030-4JV4X6, its current turn-2 request and the false A1 cancellation marker at revision 5 / run generation 3 / browser generation 1 / epoch 3 / updated_at 1791353759426. It changes only that marker's `never_submitted` and `pre_submit_proof` fields in a cloned snapshot. It neither directly creates a request nor changes CONTROL, attempts, events, outboxes, browser cleanup or unrelated retired records.

Evidence fields:

- serverQuiesced: true, after stopping the server's state writer through the authorized deployment procedure
- expectedSnapshotDigest: SHA-256 of JSON.stringify(snapshot), using lifecycle.mjs digest
- currentControl: fresh exact target CONTROL body matching the local epoch and updated_at_ms
- cancelledDispatch: fresh A1 body with its explicit durable BLOCKED revision-4 record, null intent, empty URL, submission_confirmed false, owned acquired target and Page.enable timeout
- dispatches: complete fresh same-request inventory, including exactly one A1 body deep-equal to cancelledDispatch and no duplicate attempt numbers. Every non-A1 body must be an initial CREATE_REQUESTED/CANCELLED + PENDING app record with only observed initial app fields; any top-level or nested lifecycle, submission, completion or unknown server projection fails closed. All operational model/profile/document fields must match A1. Provided A1 server projections must agree with its exact preparation-only durable record
- expectedLatestAttempt: the verified latest inventory attempt, strictly above A1, still CREATE_REQUESTED/PENDING with unchanged accepted input
- history: complete raw event rows or event details for the target, consisting of exactly the four known pre-submit revisions
- resultReads: two identical raw reads of the exact current Result, valid and committed:false
- evidenceAt: current evidence time in milliseconds; all evidence must be obtained within the 30-second window before invocation
- now: optional test clock; omit in production
- operatorHold: optional `{originalControl, heldControl}` for the explicitly authorized bounded hold. Original CONTROL must be exact RUNNING epoch 3; held CONTROL must be same-identity PAUSED epoch 4 with reason `OPERATOR_PREPARATION_RETRY_HOLD`. All other original fields are preserved except updated_at_ms. The fresh remote CONTROL and local CONTROL must both deep-equal the held body. The marker and original A1/history remain pinned to epoch 3

Before atomically writing the returned snapshot, the operator must recheck that the server writer remains quiesced, the source snapshot is still byte-identical, and the external CONTROL, latest attempt/inventory and Result versions remain unchanged. A changed source or failed guard means stop and gather new evidence; do not relax pins, blanket-clear retired history or invent a resume command. Preserve the original snapshot as an operator recovery copy under the existing authorized deployment procedure. After restarting, ordinary ingress must still read fresh app authorization and a higher attempt before preparing a replacement. Validate live preparation/submission separately; offline tests are not production success.

The helper never writes or restores CONTROL, including the optional hold. Its authorized deployment wrapper owns provider-ETag compare-and-swap hold/release. Release can restore original fields with RUNNING epoch 5 and a new updated_at_ms only while the exact own PAUSED epoch-4 body and ETag remain unchanged. Any user STOP, DONE, new request or newer epoch wins; never restore epoch 3 or overwrite another control. No Result writes are part of recovery.
