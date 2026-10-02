# Restore on-device submission with shared completion signaling

## Goal and approved behavior
The ON_DEVICE setting submits each prompt through the phone's existing v3 WebView transport. It only confirms submission and captures the conversation URL, then destroys the WebView after the correlated URL/start state is persisted. It never reads or monitors the response. SERVER retains the current v4 Drive-to-Termux submission and server monitoring. Both modes receive current Vercel/FCM completion signals; the app still reads the authoritative correlated Result after a signal and applies the existing bounded recovery policy.

## Verified source
- Current app baseline: selfrun-v4/v4.0.7-dev2, 0a90983736db71e26742933ae267b3ce3034c40d
- Latest stable: drive-v4.0.6, 513a507806ad4c39a85194013f2fbdb31366db8d
- Historical submission contract: drive-v3.3.2, addea1128a4d491989a13a5aa930101d05d1ac75
- Historical files: SelfRun3WebAdapter.java, SelfRun3BootstrapTransport.java, SelfRun3DispatchScript.java, SelfRun3Coordinator.java
- Existing v3 submission and disposal remain present in the current baseline; retain current profile registry compatibility.

## Minimal implementation
1. Add a mode-aware adapter that selects v3 or v4 submission for the persisted request. Preserve existing task control writes and STOP fences.
2. Persist submissionMode with TURN_READY; retain through restart and retries. Existing prepared requests with no field use SERVER, matching the previous v4 behavior. New turns and explicit stopped restart select the current setting.
3. Make Vercel completion capability and wait/recovery independent of submission mode, retaining build feature gating, identity checks, deduplication, authoritative Result reads, fallback recovery, and STOP.
4. Explain the two submission choices and shared completion path in settings and README.
5. Deliver v4.0.7-dev3 TEST APK with existing TEST application ID/signature and increased versionCode. Do not modify server code, Vercel configuration, credentials, or formal release.

## Validation
- First commit: new contract tests run against unchanged exact product baseline and must fail with assertions, not compilation errors.
- Implementation: mode persistence, legacy fallback, next-turn mode changes, STOP/restart, common push, no response monitoring, one-shot submission and URL-first disposal tests
- Complete JVM regression and static policies; Android test-source compilation
- Android API 36 fixture instrumentation for v3 submission/disposal, settings persistence, coordinator state and profile behavior
- TEST co-install/in-place-update, package/version/signature/checksum verification
- Independent final diff review; immutable direct APK publication with downloaded byte/hash equality

## Boundaries
Mode changes affect newly prepared requests, never switch an existing in-flight request to a second submission path. No new authentication, permission, sharing, device execution, or live test conversation is authorized by this change. User-device login and live-site acceptance are post-delivery checks, not a substitute for automated checks.
