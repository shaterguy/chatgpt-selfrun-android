# SelfRun 3.0.0 rebuild

Original request: 현재 셀프런을 구조적으로 처음부터 다시 만들 것. 지금의 방식을 버리고 처음부터 다시 생각해. 배터리 소모나 발열 등은 현재 수준으로 유지해야하고. 내외부 어떠한 변경이 있어도 완벽히 적용이 가능해야해.
Authorized implementation: AI 유지보수 이런거 넣지 말고. 3.0.0으로 새로 만드는 프로젝트 시작해. 완성본 가져와.

Base: drive-v2.3.2 / 9a567558d6c81a1e9cd4c9f267b0e5171df3dec7.
Target: 3.0.0; development branch selfrun-v3/v3.0.0-dev1.
Scope: new deterministic task engine, transactional journal, versioned exact-document results, replaceable WebView/Drive adapters, bounded reconciliation, existing UI/auth/profile/attachment/history compatibility, signed installable APK.
Non-goals: no AI maintenance agent, no self-modifying code, no remote execution-code updater, no extra server, no paid API conversion, no unrelated product changes.

AC-01: every newly started task uses the V3 engine, not the legacy service state machine.
AC-02: task/turn/request/result identities are durable; one claimed submission is never blindly repeated after timeout, pause, service recreation, or duplicate callbacks.
AC-03: results are read from the exact pinned per-turn native Doc and validated by body schema/identity/commit; titles, timestamps, and folder ordering never determine completion.
AC-04: network completion and logical completion are independent facts; both are required before the next turn. Missing callbacks enter bounded reconciliation rather than automatic rollover or success.
AC-05: existing CHAT/WORK, Project/general chat, profile capture, attachments, user input, pause/resume/stop and history remain accessible. Legacy in-flight runs retain their legacy contract.
AC-06: normal WAIT has no DOM polling, no display output, no held wake lock, no AI calls and no new network polling. Recovery has finite budgets. Device energy/temperature equivalence must not be fabricated from static or emulator evidence.
AC-07: parser/transaction/recreation/race/stop/late-event/power-policy tests, APK signing, TEST lineage and stable update compatibility, and direct artifact delivery are verified against exact source identity.
SAFE-01: unchanged auth scopes and allowed network hosts; exact task/turn/document/origin checks; bounded JSON; no token or prompt payload logging; app-private non-backed-up ledger; no destructive schema migration.
NR-01: current stable installation id and signer preserved; TEST id/signer remain separate and stable.
NR-02: reusable V2 low-power browser/auth/profile adapters and historical tests are retained only where needed for compatibility, not used as V3's decision authority.

Preflight: current stable checked 2.3.2/2020048; main verified at base SHA; 3.0.0 branch and closed/open PR number search found none before dev1 creation. Initial branch creation does not match existing workflow push filters and creates no CI run. Code is written directly to GitHub; no local development or signing.
Applicable prevention rules: PR-001 no effectful tool probes; PR-002 no dummy CI trigger; PR-003/007 version checks tied to actual build identity; PR-004 unique run-attempt artifact provenance; PR-005 latest stable update fixture 2.3.2; PR-008 preserve verified external transport semantics and test new self-owned result schema against fixtures. PR-006 applies only to Docs updates; PR-009 applies at final metadata sync.
Validation order: static compilation incl androidTest -> JVM contracts -> canonical signed APK -> emulator journal/browser/installation checks -> direct publication -> source/artifact/readback audit.

Status: implementation in progress. No build, runtime, physical power, thermal or release success is claimed by this planning record.
