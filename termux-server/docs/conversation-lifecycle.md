# Durable conversation lifecycle
Current state is the fsynced lifecycle snapshot. Its transaction includes audit events and pending Drive projections. External publication cannot publish an uncommitted transition. Drive updates merge server-owned fields with a version-checked current client record; v2 ETags bind media readback to a file revision, and If-Match rejects competing updates.

The logical key is task / turn / request. Dispatch attempt, control epoch, process generation and browser attachment generation are distinct. A canonical conversation cannot change after acquisition, or be owned by two unfinished requests. Replacing a CDP connection does not delete the conversation.

## State transitions
The executable allowed edges are in src/lifecycle.mjs.
- Creation: DISCOVERED -> PREPARING -> PREPARED -> POST_PENDING.
- Attachment: ATTACHING -> OBSERVING, without sending a continuation.
- Submission: POST_PENDING or RECOVERY_POST -> POST_UNCERTAIN -> RECONCILING -> OBSERVING.
- Recovery: STALLED -> VERIFY_RESULT -> VERIFY_CONVERSATION -> VERIFY_CURSOR -> QUIESCING when needed -> VERIFY_INPUT -> RECOVERY_POST.
- Response completion: idle response_status alone keeps observation active. Exact current Result committed:true retires as COMMITTED and ends observation; app control can also suspend or stop it.
- Control and failures: STOPPED, PAUSED, BLOCKED or CANCELLED retain identity and durable recovery stage.

The app owns Result, phase, Handoff and successor decisions. Initial submission never reads current/predecessor Result documents and IDs are opaque correlation values. During monitoring, strict exact Result readback makes committed:true final authority for the current request: retire it, end observation and close its proven owned target. An unreadable Result suppresses progress input without claiming completion. Consumed identities never reattach or reread Result, including after restart or an old same-identity RUNNING refresh. Cursor changes measure progress; polling and heartbeats do not.

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
minutes trigger one configured recovery attempt per interval. Navigation, input dispatch and app
authority are unchanged.

## Submission safety
A durable intent stores immutable input/hash, baseline, correlation and send count before the browser side effect. Initial submission UNKNOWN is reconciled and never blindly resent. Readback observes the existing page without reloading or navigation. A passive, bounded clone reader correlates the exact outgoing user message with a recognized semantic response marker or assistant-message event and canonical conversation; the minimal receipt is durably recorded before confirmation. An optimistic DOM bubble, POST observation, or HTTP 200 alone is insufficient. Missing, ambiguous, stale, unsupported, or conflicting receipts remain UNKNOWN. ABSENT requires prior proven cancellation before transport release, never a quiet DOM or timeout. Once the exact owned canonical URL is secured, observation and app URL delivery begin immediately (`STARTED`), independently of receipt confirmation. The intent remains UNKNOWN unless real receipt evidence confirms it; late ACK alone cannot renew the visible-progress clock. No initial resend is allowed for an owned conversation. Before any canonical URL exists, at most one proven-pre-release-absent resend reuses that intent. ABSENT, UNKNOWN and all recovery checkpoints survive restart.

Generic recovery nudges use a different, explicitly approved policy: before the first recovery UI step, including stream quiesce, `last_nudge_attempt_at` is persisted locally and projected to Drive. If the attempt reaches input submission, its unique intent/receipt generation is persisted before that input. Quiesce or input-preparation failure still consumes the full attempt window. The clock is renewed when each UI phase finishes, before receipt reconciliation; slow UI work cannot leave an expired deadline. Streaming-only telemetry never resets actual response-progress time. The next attempt requires a full configured quiet window (default 600000 ms) after the later of actual response progress and the last nudge attempt. Browser STALLED labels, repeated polling, restart, and delayed ACK/readback cannot bypass or renew that deadline. A late receipt for an older intent cannot overwrite its successor. UNKNOWN, PENDING, and proven-absent recovery attempts remain observable and allow one fresh intent after the next quiet window; they never immediately resend. This may produce a rare duplicate generic continuation, as accepted by the user. Initial prompt duplicate protection, Result/control checks, and STOP/WAITING boundaries remain unchanged. Legacy one-shot RECOVERY_NO_PROGRESS blocks resume this interval policy. While app control stays active, attach/auth/load failures retry with pacing instead of a lifetime attempt cap, and transient observation/input errors retain the current page. Result transport/schema failures retain a classified error and retry deadline; they never claim committed proof. Initial uncertainty without a canonical URL and ownership conflicts remain fenced.

## Control and compatibility
Discovery is not execution authorization. Before executable ingestion, browser preparation/attachment and submission, the server reads the actual task control and requires the exact task/turn/request identity, schema-v1 positive epoch and RUNNING state to remain the latest accepted control. Missing, unknown, malformed or stale control never falls back to server projection/default RUNNING, including after restart. A normal dispatch may omit server epoch fields; its separate app control supplies authorization. No age cutoff is applied. Deferred discovery is reconsidered when control arrives. Inert STOP/completion history remains read-free and cannot reactivate itself.

Drive dispatch/control schemas remain compatible with additive lifecycle fields. Legacy events import monotonic recovery count and uncertain submission evidence. Newer audit revisions than the snapshot quarantine execution. APP STOP fences pending browser callbacks immediately, disposes the local CDP session and closes the proven owned target directly. It never reads submission receipts or invokes generation-stop UI; stop_status NOT_REQUESTED makes no remote-generation stop claim. Higher epochs resume exact identity. CANCELLED/SUPERSEDED is per-attempt authority, not overridden by unchanged RUNNING control. HTTP ingress now uses the same lifecycle and requires scoped task/turn/request/control_epoch for control commands; unscoped legacy HTTP control fails closed. STOP atomically purges only matching old HTTP dispatch/control cache entries, preserves minimal signal dedupe and newer pending inputs, and retries this purge after restart. Consumed completion caches remain available for final status publication. Fresh explicit resume metadata is read from the app rather than a stopped cache.

The watcher only ingests and ticks. It owns no separate resume policy. Browser submission helpers that bypassed durable intent have been removed. HTTP control ingestion is separate from long-running POST execution so STOP can preempt it.

### User intervention turn boundary

USER_ACTION_REQUIRED and USER_ACTION_RESOLVED do not change the completion rule:
a strictly verified current Result with committed:true consumes that identity.
Its monitor/session and proven owned tab are retired immediately, including when
the app has not yet sent WAITING or DONE. Later edits to that consumed Result,
old-identity RUNNING epochs, dispatch attempts and reimports cannot revive it.
An unreadable Result without committed proof remains nonterminal and cannot nudge.

Only an app-provided new turn/request/Result starts resumed work through the normal
CREATE_REQUESTED / SEND_REQUESTED handshake. The server creates no successor and
never sends a continuation into the consumed intervention conversation. WAITING
or a generation/Result-identity change while readback is pending fences its callback.

A small completion-only outbox publishes server_status COMPLETED and lifecycle_state
COMMITTED without retaining the prompt or POST intent. Publication failures remain
retryable independently of browser cleanup; a superseded remote identity is never
overwritten. Publication retries never reread the consumed Result.

If exact app DONE arrives before the server reads Result, the existing local
STOPPED retirement also saves a completion-only projection in that same durable
transaction. It publishes server_status COMPLETED, lifecycle_state STOPPED,
server_control_state DONE and server_completion_reason APP_DONE. The observed
result_state is preserved; app DONE does not establish server-side COMMITTED
proof. Result-first completion retains lifecycle_state/result_state COMMITTED
and uses server_completion_reason RESULT_COMMITTED. Neither ordering waits for
publication before browser cleanup. Failed publication survives restart, contains
no executable snapshot, and cannot overwrite a reused request or dispatch attempt.
STOP retains its existing local teardown and explicit-resume behavior. A newer
explicit same-identity resume discards pending DONE telemetry and clears a
previously published completion reason. Historical retired markers are not
reopened or scanned for Result proof to backfill old telemetry.

## Regression migration
Initial submission still follows app authorization without predecessor business gating. During observation, exact committed:true is now an explicit terminal boundary. Technical no-duplicate, restart, ownership, pause and ten-minute liveness safeguards remain. CREATE_REQUESTED prepares, READY_TO_SUBMIT awaits SEND_REQUESTED, and STARTED means the exact owned canonical URL is ready for the app; receipt truth remains separate in post_outcome. Browser readiness, response structure, profile interception and transient CDP monitoring tests remain. Controller/watchdog fixtures now provide strict Result documents, current dispatch reads and the explicit submitIntent/readSubmission boundary.
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

APP STOP fences callbacks and directly tears down the local session/proven owned
tab. A late in-flight attachment is also disposed without receipt readback or remote
UI actions. No historical target/conversation is opened. stop_status NOT_REQUESTED
means remote generation stopping was not attempted; the remote ChatGPT generation
may continue. PAUSED is not retired. Stream quiesce for an authorized ten-minute
recovery nudge remains separate and unchanged.

### Owned browser resource release

Terminal retirement additionally writes `lifecycle.browserCleanup`, a separate
cleanup-only outbox containing the exact target ID, request identity, epoch and
conversation digest. It contains no prompt, POST intent or executable task state.
Only a target created by SelfRun and still covered by durable browser-instance
creation proof can be closed. Merely attaching to or touching a matching manual
tab never establishes creation ownership. Older retired markers without that
proof are left untouched; a matching conversation digest alone is insufficient.

STOP/DONE and cancellation schedule immediate cleanup independently of ingestion.
The final close guard protects newer controls, accepted pending HTTP work and
active target/conversation reuse. Delayed browser attachment cannot resurrect
retired requests. A failed close remains durable and is retried after restart or
on the bounded cleanup retry interval, without opening a page or retrying STOP.
PAUSED, standby, and uncommitted RUNNING/WAIT_RESULT observers retain their tabs
when an answer merely looks idle. Verified Result commit triggers the same owned
resource cleanup as terminal app control.

The browser can exit only when active/pending work and other browser usage are
absent and its exact spawned process identity is proven. Cleanup never starts
Chromium; new demand is serialized against process release. The SelfRun server,
worker and supervisor remain alive. This closes local browser tabs/processes;
it never deletes ChatGPT conversation history or browser profiles.

COMMITTED/COMPLETED identities are permanently consumed and cannot resume.
To resume a stopped or cancelled identity, the app must provide a newer RUNNING control epoch
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
