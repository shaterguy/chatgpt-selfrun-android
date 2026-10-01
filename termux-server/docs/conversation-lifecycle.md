# Durable conversation lifecycle
Current state is the fsynced lifecycle snapshot. Its transaction includes audit events and pending Drive projections. External publication cannot publish an uncommitted transition. Drive updates merge server-owned fields with a version-checked current client record; v2 ETags bind media readback to a file revision, and If-Match rejects competing updates.

The logical key is task / turn / request. Dispatch attempt, control epoch, process generation and browser attachment generation are distinct. A canonical conversation cannot change after acquisition, or be owned by two unfinished requests. Replacing a CDP connection does not delete the conversation.

## State transitions
The executable allowed edges are in src/lifecycle.mjs.
- Creation: DISCOVERED -> PREPARING -> PREPARED -> POST_PENDING.
- Attachment: ATTACHING -> OBSERVING, without sending a continuation.
- Submission: POST_PENDING or RECOVERY_POST -> POST_UNCERTAIN -> RECONCILING -> OBSERVING.
- Recovery: STALLED -> VERIFY_RESULT -> VERIFY_CONVERSATION -> VERIFY_CURSOR -> QUIESCING when needed -> VERIFY_INPUT -> RECOVERY_POST.
- Completion: exact committed Result readback -> COMMITTED -> COMPLETED.
- Control and failures: STOPPED, PAUSED, BLOCKED or CANCELLED retain identity and durable recovery stage.

Result authority precedes UI observation. Strict Result identity plus identical repeated readback finalizes a turn. Every successor requires the exact predecessor Result. UI idle/streaming alone never finalizes. Cursor changes measure progress; polling and heartbeats do not.

## Submission safety
A durable intent stores immutable input/hash, baseline, correlation and send count before the browser side effect. UNKNOWN is reconciled and never blindly resent. Readback reloads the owned conversation so an optimistic DOM bubble is insufficient. ABSENT requires successful cancellation before transport release and a fresh unchanged conversation. At most one resend reuses that intent. ABSENT, UNKNOWN and all recovery checkpoints survive restart. A confirmed recovery without new response progress cannot create another recovery input.

## Control and compatibility
Drive dispatch/control schemas remain compatible with additive lifecycle fields. Legacy events import monotonic recovery count and uncertain submission evidence. Newer audit revisions than the snapshot quarantine execution. STOP fences pending browser callbacks immediately. Higher epochs resume exact identity. HTTP ingress now uses the same lifecycle and requires scoped task/turn/request/control_epoch for control commands; unscoped legacy HTTP control fails closed.

The watcher only ingests and ticks. It owns no separate resume policy. Browser submission helpers that bypassed durable intent have been removed. HTTP control ingestion is separate from long-running POST execution so STOP can preempt it.

## Regression migration
Old assertions expecting automatic continuation on attach, replacement conversations after uncertain POST, UI-only completion, and unscoped STOP contradict the new contract and are replaced with opposite safety assertions. Browser readiness, response structure, profile interception and transient CDP monitoring tests remain. Controller/watchdog fixtures now provide strict Result documents, current dispatch reads and the explicit submitIntent/readSubmission boundary.
Numbered lifecycle scenario tests cover the required creation, timeout, resume, reconnect, restart, stall, recovery, control and successor paths. Additional tests cover every intermediate restart state, cold ABSENT retry, immutable input, audit mismatch, snapshot failure, slow publication, and callback fencing. Regression probes use the same file against old and new source.
Real authenticated headless smoke and deployment readback remain independent release gates; unit success alone is insufficient.
