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

## Visible generation progress
The progress cursor includes visible thinking, work and tool output before a final
assistant answer exists. The probe accepts legacy `main [data-turn-key]` wrappers
and current `main section[data-turn-id][data-testid^="conversation-turn"]`
wrappers. It follows the newest user input, excludes explicit/descendant user
sections, and uses an identified assistant node only as a wrapper-free fallback.
The existing text walker excludes user input, hidden content and UI controls.
Content fingerprints detect same-length changes; elapsed-time labels are removed
from response text and the current-response assistant text length. The original
global `assistantCount`, `assistantTextLength` and `assistantMessageId` telemetry
remains unchanged for submission/baseline compatibility. Additive
`responseAssistantCount`, `responseAssistantTextLength` and
`responseAssistantMessageId` fields describe only the selected current response.
`lifecycle.cursor` maps those scoped values into its existing assistant cursor
keys, and the browser monitor compares that same effective cursor. Missing or
undefined scoped fields retain legacy behavior; an explicit scoped null means
there is no current assistant identity. Stored cursor assistant metrics therefore
refer to current-response progress, while raw probe fields remain global.
Expanding an older response cannot reset the current thinking deadline. Polling
does not reset the ten-minute no-progress interval or bypass Result/control checks.

The October 4 read-only runtime diagnostic observed current SECTION attribute
names (`data-turn-id`, `data-turn-id-container`, `data-testid`, `data-turn`) and
zero `data-turn-key` matches. An existing 221-character assistant bubble was
missed by the old response-text collector. This establishes a selector mismatch,
not the exact structure of an earlier active thinking phase. Literal `data-turn`
values were not captured. Bubble-free thinking/tool wrappers and the positional
fallback after the newest user are synthetic compatibility cases, pending live
verification. Tests evaluate the exact production probe expression and exercise
the actual controller with no final answer: unchanged minute samples/timers do
not count as progress, real tool changes reset the deadline, and ten unchanged
minutes trigger one configured recovery. Navigation, input dispatch and app
authority are unchanged.

## Submission safety
A durable intent stores immutable input/hash, baseline, correlation and send count before the browser side effect. UNKNOWN is reconciled and never blindly resent. Readback observes the existing page without reloading or navigation. A passive, bounded clone reader correlates the exact outgoing user message with a recognized semantic response marker or assistant-message event and canonical conversation; the minimal receipt is durably recorded before confirmation. An optimistic DOM bubble, POST observation, or HTTP 200 alone is insufficient. Missing, ambiguous, stale, unsupported, or conflicting receipts remain UNKNOWN. ABSENT requires prior proven cancellation before transport release, never a quiet DOM or timeout. At most one resend reuses that intent. ABSENT, UNKNOWN and all recovery checkpoints survive restart. A confirmed recovery without new response progress cannot create another recovery input.

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
