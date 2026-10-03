# Durable conversation lifecycle
Current state is the fsynced lifecycle snapshot. Its transaction includes audit events and pending Drive projections. External publication cannot publish an uncommitted transition. Drive updates merge server-owned fields with a version-checked current client record; v2 ETags bind media readback to a file revision, and If-Match rejects competing updates.

The logical key is task / turn / request. Dispatch attempt, control epoch, process generation and browser attachment generation are distinct. A canonical conversation cannot change after acquisition, or be owned by two unfinished requests. Replacing a CDP connection does not delete the conversation.

## State transitions
The executable allowed edges are in src/lifecycle.mjs.
- Creation: DISCOVERED -> PREPARING -> PREPARED -> POST_PENDING.
- Attachment: ATTACHING -> OBSERVING, without sending a continuation.
- Submission: POST_PENDING or RECOVERY_POST -> POST_UNCERTAIN -> RECONCILING -> OBSERVING.
- Recovery: STALLED -> VERIFY_RESULT -> VERIFY_CONVERSATION -> VERIFY_CURSOR -> QUIESCING when needed -> VERIFY_INPUT -> RECOVERY_POST.
- Response completion: response_status telemetry while the observer remains active. App successor/control signals end or suspend observation.
- Control and failures: STOPPED, PAUSED, BLOCKED or CANCELLED retain identity and durable recovery stage.

The app owns Result, phase, Handoff and successor decisions. Initial submission never reads current/predecessor Result documents and IDs are opaque correlation values. During monitoring, the existing strict Result readback remains a nudge-suppression safeguard: committed or unreadable Result prevents automatic progress input, but never ends observation. Cursor changes measure progress; polling and heartbeats do not.

## Submission safety
A durable intent stores immutable input/hash, baseline, correlation and send count before the browser side effect. UNKNOWN is reconciled and never blindly resent. Readback reloads the owned conversation so an optimistic DOM bubble is insufficient. ABSENT requires successful cancellation before transport release and a fresh unchanged conversation. At most one resend reuses that intent. ABSENT, UNKNOWN and all recovery checkpoints survive restart. A confirmed recovery without new response progress cannot create another recovery input.

## Control and compatibility
Drive dispatch/control schemas remain compatible with additive lifecycle fields. Legacy events import monotonic recovery count and uncertain submission evidence. Newer audit revisions than the snapshot quarantine execution. STOP fences pending browser callbacks immediately and quiesces only the owned conversation; stop_status distinguishes confirmed and unconfirmed browser stopping. Higher epochs resume exact identity. CANCELLED/SUPERSEDED is per-attempt authority, not overridden by unchanged RUNNING control. HTTP ingress now uses the same lifecycle and requires scoped task/turn/request/control_epoch for control commands; unscoped legacy HTTP control fails closed.

The watcher only ingests and ticks. It owns no separate resume policy. Browser submission helpers that bypassed durable intent have been removed. HTTP control ingestion is separate from long-running POST execution so STOP can preempt it.

## Regression migration
Old assertions making Result/predecessor business state control initial submission or terminate the observer are replaced. Technical no-duplicate, restart, ownership, pause and ten-minute liveness safeguards remain. CREATE_REQUESTED prepares, READY_TO_SUBMIT awaits SEND_REQUESTED, and STARTED requires confirmed submission. Browser readiness, response structure, profile interception and transient CDP monitoring tests remain. Controller/watchdog fixtures now provide strict Result documents, current dispatch reads and the explicit submitIntent/readSubmission boundary.
Numbered lifecycle scenario tests cover the required creation, timeout, resume, reconnect, restart, stall, recovery, control and successor paths. Additional tests cover every intermediate restart state, cold ABSENT retry, immutable input, audit mismatch, snapshot failure, slow publication, and callback fencing. Regression probes use the same file against old and new source.
Real authenticated headless smoke and deployment readback remain independent release gates; unit success alone is insufficient.


## Terminal retirement and operator cleanup

STOPPED, CANCELLED, COMMITTED and COMPLETED identities live in `lifecycle.retired`,
not the executable `requests` collection. Retirement removes pending transitions,
Drive publication, retry state, prompt/intent, cursor and browser/conversation URL.
An inert marker retains identity, generations/epochs, dispatch path, ingress,
stop outcome and a conversation digest to reject mismatched explicit resumes.
Only a positive `never_submitted` marker allows an app's newer dispatch attempt;
unknown or previously sent input cannot restart from attempt changes alone.

STOP fences callbacks immediately and makes one best-effort stop on a currently
owned browser session. A late session from the already in-flight attach can be
quiesced under that same STOP epoch. No historical target/conversation is opened
for cleanup. UNCONFIRMED is an inert outcome, never a retry queue. PAUSED is not
retired. RUNNING observer restart and the existing liveness intervals are unchanged.

To resume a retired identity, the app must provide a newer RUNNING control epoch
and its canonical conversation URL. The server reads that marker's dispatch path
for app metadata, validates the identity/digest, and starts an attach-only observer.
Old lifecycle/POST intent and old cancellation projections are not replayed.
An accepted resume whose metadata read was interrupted can retry the same stored
control epoch; it cannot substitute a different URL. HTTP RESUME likewise requires
an explicit `conversation_url`, and cannot take ownership of a Drive task.

App dispatch fingerprints exclude server-owned projection fields. Duplicate inputs
create no new outbox entry. The watcher skips known retired dispatch paths from
listing metadata, and server-only publication echoes do not trigger ingestion.
Publication rechecks the live outbox after remote reads before writing.

`cleanupStoppedSnapshot(snapshot, {allStopped:true, expectedControlEpochs, cutoffMs})`
from `src/retired-requests.mjs` is a pure offline operator helper. Stop the service
only during the bounded cutover, quarantine the original state plus event stream
outside the live data path, then run the helper and atomically install its returned
snapshot with an empty live event stream. The helper validates exact captured
control epochs and app-owned command timestamps, permits old terminal records
under stale RUNNING metadata, and rejects nonterminal RUNNING/PAUSED or untracked
HTTP work. Server publication timestamps do not advance the authorization cutoff.
It clears executable state and HTTP bodies, retaining only signal dedup IDs and
inert request markers. It never touches Drive documents, conversations, credentials,
configuration, browser profiles or the quarantined originals. Do not erase markers
or reuse the old event stream on restart. Newer app commands require a fresh review
of the cleanup snapshot, not an automatic broadened cleanup.
