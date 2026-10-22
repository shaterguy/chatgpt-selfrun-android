# Input preparation and terminal delivery repair

Server: 4.0.0-dev43. Android DEV: 4.0.11-dev4, version code 4002026,
package `com.shaterguy.chatgptselfrun.drive.test`.

## Findings

- `SR-20261022-140331-7HEFIQ` wrote DONE control epoch 7 at 14:23:48 KST,
  following its 14:23:46 completion log. Its server work was discarded.
- `SR-20261022-143439-5ERSLM` recorded completion in Result and the app log,
  but its control remained RUNNING epoch 2. The terminal publisher used an
  adapter-cached access token, skipped empty-token/shutdown cases, and did not
  require readback or retain a durable retry after the adapter closed. Its
  longer runtime and unchanged control are consistent with this delivery gap;
  the original retained log does not establish the precise HTTP failure.
- The immutable v1 helper could attest after permanent revocation, so server
  installation succeeded while every input failed with ownership unavailable.
- Historical canonical-parent protection existed in commits 61565ac4 and
  6216acf7. It was absent from the current v4 server path.
- DONE skipped a CANCELLED request in `LifecycleStore.applyControl`; a new
  all-state matrix reproduced the retained record before the correction.

## Changes

`submitIntent` alone prepares input. Preparation renews the private helper
state and invalidates asynchronous work from earlier input generations. Normal
character-count observation does not prepare, repair, or fetch conversation
parents. The current `current_node` is read immediately before forwarding the
matching conversation POST, with session authorization fallback. Only
`parent_message_id` changes beyond the existing four profile controls.
Transient graph reads retry inside that input; they add no task pause or error
state. Disposal cancels pending forwarding.

Existing v1 documents retain their frozen property and startup-captured
transports. After an input decision and ownership attestation, a namespaced v2
helper handles DOM actions, and a request-stage CDP interceptor handles that
input's actual native POST. It detaches after forwarding. Task/session lifetime
is separate from the preparation command's deadline, and parent reads remain
on the same physical action connection. Terminal cancellation closes the owned
target if a paused request cannot be cancelled; unresolved requests are not
released by disabling or reconnecting the interceptor. Existing character-count
observation continues without receipt acknowledgement as a prerequisite.

DONE and STOPPED enter the same durable Android outbox before adapter shutdown.
Delivery obtains fresh authorization, reads the current control epoch, writes
the terminal state, and confirms the actual stored control. Interrupted empty
control-file creation and process restart scheduling are recoverable. Late
nonterminal adapter writes cannot restore RUNNING after terminal publication.
The server discards DONE/STOPPED requests across every lifecycle state,
including CANCELLED, and releases their attached resources.

## Verification

- Android identity checks, all 522 JVM tests, lint, and unsigned DEV assembly
  passed locally on Galaxy-Tab-S11-Ultra. GitHub only verified and signed that
  prepared APK; it did not rebuild it.
- Signed app source: `11965da016fdc3f715a1c71f1e001643d0706dd1`.
  APK SHA-256: `9a0fc0aa4f77ef967255a17506a925860c3abb6845348e4555d0dd46a548c57c`.
  Existing signer SHA-256:
  `2c95a5644a0ef2959eaecf10460e300fe2ee7a4ebcede685a82a52634c22e86e`.
  Both source/hash and APK signature were independently read back on the tablet.
- 182 selected server tests (181 passed, one platform-specific skip) and syntax
  checks passed after the final cancellation
  refinements.
  Coverage includes immutable-v1 migration, revoked helper renewal, captured
  fetch, newest parent, superseded body parsing, transient reads, STOP, and
  DONE/STOPPED across all 24 lifecycle states.
- Broader verification reproduced failed-close cleanup retention on released
  b8e88e5a. Terminal cleanup now discards the extension ownership and consumes
  its cleanup entry even when CDP/extension close attempts fail; cancelled
  in-flight creation also releases its owner. The related regression tests
  now pass. The remaining repository-wide historical suite is not reported
  as passing.
- Deferred installation now uses the authoritative repeated Drive proof and
  frozen task snapshot rather than treating queued discovery reads as active
  work. Its own extension controller page is recognized as idle; ordinary
  browser pages continue to prevent browser retirement.
- `tools/input-repair-live-smoke.mjs` reads the real conversation graph and
  fulfills the synthetic POST through CDP. It does not submit a real message.
  On Galaxy-Tab-S11-Ultra the live probe passed: the released v1 captured
  transport and v2 parent path both used the real latest graph tip; helper
  renewal used no reload; one synthetic POST was locally fulfilled and the
  probe target was confirmed absent. Deployment readback is recorded by the
  delivery session.

App release: https://github.com/shaterguy/chatgpt-selfrun-android/releases/tag/v4.0.11-dev4
