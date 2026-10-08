# SelfRun extension browser adapter implementation plan

Goal: replace the Termux server's CDP browser control and observation path with a browser-resident extension, while preserving the Android app, Drive transport, ResultDoc, and common Vercel completion-signal contracts.

Baseline: 223217851fb4470642b7a96bd2e70a2b6be06aa1. Existing syntax checks and all 872 server tests passed in the isolated Git checkout with Node 24.19.0. The installed tablet Chromium 149.0.7827.155 passed a separate localhost-only extension proof with zero CDP calls. That proof is not ChatGPT integration evidence.

## Constraints and acceptance criteria

- AC01: extension input, observation and tab lifecycle avoid CDP. Native-profile fixtures use zero CDP. The existing Android client-profile contract explicitly retains only three initial Emulation commands on a unique newly owned localhost bootstrap target, and retains that profile-only CDP connection for the owned tab lifetime. On loss it re-proves the same existing target/document and reapplies only these three settings without navigation; the continuity gap is exposed. Never Page.enable, Runtime.enable, or Runtime.evaluate. The old CDP adapter remains an explicit rollback choice, never an invisible fallback.
- AC02: preserve prompt rewriting, four-field model/profile operations, immutable intent, semantic request receipt correlation, canonical conversation URL matching, and uncertain-submit handling.
- AC03: preserve the existing one-minute observation and ten-minute continuation behavior. STOP/DONE/committed suppress further submission. App stop closes owned tabs without clicking the page stop button. Existing recovery quiesce remains separate and unchanged.
- AC04: never reload or navigate an existing conversation to repair observation or a local bridge reconnect. Explicit new-task navigation and user-requested resume remain separate entry actions.
- AC05: only extension-owned tabs may receive commands or be closed. Browser exit requires proven process ownership and no unrelated tabs. Unknown ownership fails closed.
- AC06: no Android source, APK, Drive/API migration, credential export, OAuth change, app feature, or new cloud execution environment.
- AC07: package static page code; never evaluate arbitrary command-supplied JavaScript. Request only ChatGPT and loopback host access, scripting and local storage as required. Disclose the dormant host-wide document_start hook, private pairing/activation material, bridge port 17832 and initial Android profile CDP port 19227 before activation. ChatGPT permission/activation remains a separate action-time approval before real access.
- AC08: coalesce DOM change signals, keep server timers authoritative, avoid idle keepalive polling, and clean up observers and pending work on close. Battery benefit remains unmeasured.
- AC09: source/hash-fenced staging, full test run and independent review precede an idle-only tablet rollout. Preserve rollback to the previous deployed source and CDP adapter. Phone work is separate.

## Architecture

Keep ChatGptBrowser's existing lifecycle/receipt logic, but express page operations by fixed names and typed payloads. Its CDP implementation maps those operations to the existing expression factories. The extension implementation sends the same named operations through a loopback-only bridge to an extension-owned controller page and an isolated content-script bridge. A public loopback bootstrap endpoint serves only an empty CSP-restricted page so a new tab URL is observable without requesting broad tabs permission. A document_start MAIN wrapper is statically packaged and remains pass-through on unowned pages without reading request URLs, bodies or responses. Private profile/receipt closures are activated only after isolated content document validation and HMAC bootstrap verification. All effectful page actions remain restricted to owned documents. An active controller waits on an authenticated POST/NDJSON stream without periodic keepalive. GET was verified to omit Origin in installed Chromium, so it is not accepted. An idle controller closes its own tab only after guarded zero-target/no-unrelated-tab checks, allowing Chromium to exit naturally. A local transport reconnect does not navigate a ChatGPT tab and never replays an uncertain mutation automatically.

Use a separate extension browser ownership journal. Old CDP cleanup entries are not reinterpreted as extension tabs. Retain the current profile without copying cookies; refuse a conflicting already-running browser profile unless its safe transition and ownership are explicitly established. Existing runtime state and job ingress are not switched while work is active.

## Tasks and verification

1. Named page-operation seam
   - Modify src/browser/chatgpt.mjs and add static page-operation packaging helpers.
   - Red tests: extension snapshots and prepare/submit flow work with no session.call; mutation uncertainty keeps released=true; old CDP behavior and receipt tests remain unchanged.
   - Green: focused browser suite, then all server tests.

2. Loopback transport and browser-resident controller
   - Add src/browser/extension-bridge.mjs and extension/controller.js plus isolated content bridge.
   - Red tests: reject foreign Origin/Host, unowned target, stale document, duplicate response, oversized payload; abort releases pending requests; disconnected mutation is never blindly replayed; reconnect emits no page navigation.
   - Green: actual local HTTP event-stream tests and mocked Chrome API ownership tests.

3. Extension manager and static package
   - Add src/browser/extension-manager.mjs, src/browser/extension-package.mjs and tools/build-extension.mjs.
   - Red tests: exact host/permission allowlist, no eval/new Function/CDP in core files, no scripts affecting unowned tabs, profile/process ownership fences, restart journal recovery, idle cleanup, no forced page refresh.
   - Green: package syntax tests and approved isolated tablet localhost smoke against the exact candidate package.

4. Server integration and full verification
   - Modify src/config.mjs, src/server.mjs, package check script and focused documentation.
   - Add explicit adapter selection, health identity and clean shutdown. Preserve all existing external HTTP/Drive contracts.
   - Run syntax and full server tests. Review final diff, lifecycle races, network boundary, package identity and rollback before staging real access.

5. Permission-gated ChatGPT staging and idle rollout
   - Obtain exact ChatGPT extension host/installation approval before granting access. Do not copy credentials or use a different profile silently.
   - Check current tablet source hashes, active requests/writers, owned browser and unrelated tabs before any switch.
   - Validate actual prompt/profile/URL/progress/continuation/stop/completion behavior without reload/re-navigation recovery, under authorized test scope.
   - Commit/preserve source remotely, publish one hash-verified server package, switch only at safe idle, verify running identity and actual user entry path. If prerequisites fail, keep the old live adapter and report the precise blocker.

## Review focus

1. A submit response is lost after the UI click: no duplicate prompt.
2. STOP or a higher epoch arrives while a command is queued: no stale action.
3. The local connection drops or server restarts: no conversation navigation or reload.
4. A formerly owned tab is repurposed by the user: no unsafe close or input.
5. MAIN-world messages or a malicious website reach the local bridge: no arbitrary evaluation, cross-tab actions, or credential disclosure.

## Progress

- Baseline syntax and 872/872 tests passed. No production source changes yet.
- Browser host permission has not been granted. Local-only tests remain authorized.
- Reviewer spawning currently blocked by thread limit; parent review or a newly available independent reviewer is required before rollout.

## Verified implementation milestones

- Initial full suite 927/927 passed independently; later profile/bootstrap and successor resource cleanup changes require a new final full run.
- Installed tablet Chromium native localhost candidate10 passed startup-captured-fetch four-field modification, semantic receipt, DOM observation, owned-tab close, controller close, and natural browser exit, zero CDP and zero remaining owned processes.
- The actual production client profile is android-webview. Native is not a permitted silent replacement. Initial Emulation compatibility is explicitly opt-in and remains unexecuted on the device until separate port/access approval.
- Native prepare path revealed hidden about:blank URL metadata. Loopback initial target replaces it without tabs permission. Latest exact-candidate native smoke remains in progress.
- No production profile, account, app, Android APK, or active browser source has been changed.

- Installed149 profile-lifetime fixture14 passed: initial headers and UA/Client Hints/viewport/DPR/touch match before first navigation; deliberate socket detach breaks profile; same-target restoration with three calls recovers, page GET count stays1, no Page.enable/Runtime.evaluate, all temporary ports/processes cleaned. Profile-only CDP session retention is required on this installed build.
