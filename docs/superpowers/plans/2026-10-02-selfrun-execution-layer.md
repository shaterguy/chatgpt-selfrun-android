# SelfRun execution-layer boundary implementation plan

Goal: remove server business eligibility while preserving server prompt rewriting, app transport compatibility, continuous observation until app successor or STOP, and the configured ten-minute stagnation prompt.

Base: 7df9dbf0953cf4a0bc8372a5e4be1a789abf7019. Scope: termux-server plus minimal branch-scoped regression CI. Android, authentication, deployment and production state are unchanged.

Acceptance:
- AC-01: app CREATE_REQUESTED prepares; READY_TO_SUBMIT waits for app SEND_REQUESTED
- AC-02: initial submission does not read/validate current or predecessor Result, phase, Handoff or turn-number semantics
- AC-03: preserve humanizeSelfRunPrompt, configured start/continue directives and durable rewritten input
- AC-04: observe response progress/idle/failure without ending observation on response completion or Result commit; app successor/STOP controls observer lifetime
- AC-05: preserve one-minute cursor observation, meaningful ten-minute stagnation, cross-device/message recheck and configured progress input; retain committed/unreadable Result suppression, paused-generation and same-cursor retry safeguards
- AC-06: strong attempt cancellation, new-attempt handling, explicit STOP, stale-callback fencing and restart safety
- NR-01: preserve profile operations, Drive conditional writes, cluster fencing, UNKNOWN no-blind-resend and early canonical URL
- NR-02: no app changes, new credentials, live browser requests or production deployment

Steps:
1. Commit regression assertions and minimal Node24 CI, observe expected failures against unchanged production source.
2. Remove initial-submission business reads/gates in controller and lifecycle; preserve monitoring Result nudge safeguards, prompt rewriting and app handshake.
3. Align cancellation, opaque IDs, control intake and status publication with app.
4. Keep observer and stagnation behavior; wire explicit STOP to existing owned-conversation stop helper.
5. Replace obsolete business-authority assertions, retain technical safety tests; run full Node suite and syntax checks at exact commit.
6. Independent source review and manual authenticated browser/runtime validation remain separate from unit evidence.
