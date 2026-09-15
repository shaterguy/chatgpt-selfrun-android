# SelfRun Server Push Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a default SERVER work mode to SelfRun Drive that uses Drive push notifications, a Vercel Workflow gateway, FCM wake-ups, two-stage ACK, retries, and a 15-minute local recovery watchdog while preserving the existing ON_DEVICE polling mode.

**Architecture:** Android owns Drive OAuth and Result parsing. Vercel never receives Drive credentials or document content; it holds only opaque installation/task/turn/document routing metadata inside durable Workflow state. Drive `files.watch` sends a high-entropy capability token to Vercel, Vercel sends FCM data messages, Android returns RECEIVED then PROCESSED ACKs, and durable retry continues until PROCESSED. WorkManager is a final 15-minute server-mode recovery path.

**Tech Stack:** Java 17, Android API 26∼36, Firebase Messaging 25.1.3, WorkManager 2.11.2, Node.js 24, TypeScript, Vercel Workflow 4.8.8, google-auth-library 11.0.2, Node test runner.

**Spec:** `docs/superpowers/specs/2026-09-15-selfrun-server-push-design.md`

## Global Constraints

- Exact baseline is `v3.2.5-dev2@0a8bf5be9fdeb6dacab908d81aa17930c6e42251`.
- Implementation branch is `v3.2.5-dev3`; do not mutate `v3.2.5-dev2`, stable `main`, or stable releases.
- Candidate identity becomes `versionName 3.2.5-dev3`, `versionCode 3025003`.
- Stable package remains `com.shaterguy.chatgptselfrun.drive`; TEST remains `.test` suffix.
- Default work mode is `SERVER`; corrupt/missing persisted mode falls back to `SERVER`.
- ON_DEVICE must preserve existing short Result polling semantics.
- SERVER must never send Drive OAuth access/refresh tokens or Result/Requirement body to Vercel.
- Firebase private credentials live only in Vercel environment variables; no secret values are committed.
- If SERVER transport is unconfigured/unavailable, the active waiting turn falls back to existing local Result polling instead of hard-pausing.
- Duplicate Drive webhook, FCM delivery, ACK, process restart, and stale previous-turn events must be idempotent.

---

### Task 1: Work-mode persistence and settings UI

**Files:**
- Modify: `app/src/main/java/com/shaterguy/chatgptselfrun/SelfRun3RuntimeSettings.java`
- Modify: `app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunLogMenuActivity.java`
- Modify/Test: `app/src/test/java/com/shaterguy/chatgptselfrun/SelfRun3RuntimeSettingsTest.java`
- Modify/Test: `app/src/androidTest/java/com/shaterguy/chatgptselfrun/SelfRun3RuntimeSettingsAndroidTest.java`

**Interfaces:**
- Produces: `SelfRun3RuntimeSettings.WorkMode { SERVER, ON_DEVICE }`, `workMode()`, `saveWorkMode(WorkMode)`.

- [ ] **Step 1: Add failing tests**

```java
@Test public void workModeDefaultsToServerAndRejectsCorruptValues() {
    SelfRun3RuntimeSettings settings = new SelfRun3RuntimeSettings(context);
    assertEquals(SelfRun3RuntimeSettings.WorkMode.SERVER, settings.workMode());
}

@Test public void workModePersistsOnDevice() {
    SelfRun3RuntimeSettings settings = new SelfRun3RuntimeSettings(context);
    assertTrue(settings.saveWorkMode(SelfRun3RuntimeSettings.WorkMode.ON_DEVICE));
    assertEquals(SelfRun3RuntimeSettings.WorkMode.ON_DEVICE,
            new SelfRun3RuntimeSettings(context).workMode());
}
```

Static wiring test must require `"작업 모드"`, `"서버를 통해 실행"`, and `"온디바이스"` in the Settings activity.

- [ ] **Step 2: Push tests-only commit and verify candidate CI fails because WorkMode/saveWorkMode do not exist.**
- [ ] **Step 3: Implement enum/key/default/persistence and a single-choice AlertDialog using the existing Settings row style.**
- [ ] **Step 4: Verify relevant unit/instrumentation compile tests pass.**

### Task 2: Installation identity and push event fencing

**Files:**
- Create: `app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunInstallationIdentity.java`
- Create: `app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunPushEvent.java`
- Create/Test: `app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunPushEventTest.java`

**Interfaces:**
- Produces: `SelfRunInstallationIdentity.id(Context)` and strict `SelfRunPushEvent.parse(Map<String,String>)` carrying `eventId`, `installationId`, `applicationId`, `taskId`, `turnId`, `resultDocumentId`.

- [ ] **Step 1: Add failing tests proving stable UUID persistence, strict required fields, and mismatched applicationId rejection.**
- [ ] **Step 2: Verify RED in CI.**
- [ ] **Step 3: Implement app-private UUID persistence and strict push payload parser.**
- [ ] **Step 4: Verify GREEN and no secret/device hardware identifier is used.**

### Task 3: Vercel Push Gateway core and durable retry contract

**Files:**
- Create: `command-bridge/package.json`
- Create: `command-bridge/package-lock.json`
- Create: `command-bridge/tsconfig.json`
- Create: `command-bridge/src/contracts.ts`
- Create: `command-bridge/src/security.ts`
- Create: `command-bridge/src/retry.ts`
- Create: `command-bridge/test/retry.test.ts`
- Create: `command-bridge/test/contracts.test.ts`

**Interfaces:**
- Produces: strict registration/ack validators, constant retry schedule `[15,30,60,120,300,600]`, high-entropy watch/event capability generation, safe token comparison helpers.

- [ ] **Step 1: Add package scaffold and failing Node tests for retry schedule, invalid ACK enum/identity, and capability length.**
- [ ] **Step 2: Run CI command `npm ci && npm test && npm run typecheck` and confirm RED.**
- [ ] **Step 3: Implement minimal contracts/security/retry modules.**
- [ ] **Step 4: Verify Node tests GREEN.**

### Task 4: Vercel Workflow, Drive webhook, ACK hook, FCM sender

**Files:**
- Create: `command-bridge/src/hooks.ts`
- Create: `command-bridge/src/firebase.ts`
- Create: `command-bridge/src/workflows/watch.ts`
- Create: `command-bridge/api/health.ts`
- Create: `command-bridge/api/watch/register.ts`
- Create: `command-bridge/api/drive/webhook.ts`
- Create: `command-bridge/api/push/ack.ts`
- Create/Test: `command-bridge/test/workflow-policy.test.ts`
- Create: `command-bridge/vercel.json`

**Interfaces:**
- `POST /api/watch/register` → `{watchKey, channelId, webhookUrl, expirationMs}`.
- Drive webhook resumes `driveHook` using watchKey capability.
- Delivery workflow sends FCM data with `eventId` and exact routing identity.
- ACK endpoint resumes `ackHook` using eventId; workflow races ACK hook against durable `sleep()` and resends until `PROCESSED`.

- [ ] **Step 1: Add failing policy tests requiring no Drive credentials/body fields, PROCESSED terminal state, RECEIVED non-terminal state, and retry after timeout.**
- [ ] **Step 2: Verify RED.**
- [ ] **Step 3: Implement workflow using `Promise.race([ackHook, sleep(...)])`, with FCM send as a `use step` function.**
- [ ] **Step 4: Implement Firebase HTTP v1 sender with `google-auth-library`, reading only `FIREBASE_PROJECT_ID`, `FIREBASE_CLIENT_EMAIL`, `FIREBASE_PRIVATE_KEY` from environment.**
- [ ] **Step 5: Implement health/register/webhook/ack endpoints with strict no-store responses and bounded inputs.**
- [ ] **Step 6: Verify Node tests/typecheck GREEN.**

### Task 5: Android gateway client and Drive `files.watch`

**Files:**
- Modify: `app/src/main/java/com/shaterguy/chatgptselfrun/DriveApiClient.java`
- Create: `app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunPushGatewayClient.java`
- Create: `app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunServerWatch.java`
- Create/Test: `app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunServerWatchPolicyTest.java`

**Interfaces:**
- `DriveApiClient.watchFile(token,fileId,channelId,address,channelToken,expirationMs)`.
- `SelfRunPushGatewayClient.registerWatch(...)` returns the watch capability information.
- `SelfRunServerWatch.register(...)` first registers gateway workflow, then registers Drive watch using the device-held Drive token.

- [ ] **Step 1: Add failing tests/static policy checks requiring HTTPS-only gateway, no redirects, no Drive token field in gateway JSON, and `files/{id}/watch` call.**
- [ ] **Step 2: Verify RED.**
- [ ] **Step 3: Implement bounded HTTPS client and Drive watch POST.**
- [ ] **Step 4: Implement registration orchestration; on any registration error return explicit fallback result rather than pausing the run.**
- [ ] **Step 5: Verify GREEN.**

### Task 6: Firebase receiver and persistent two-stage ACK outbox

**Files:**
- Modify: `app/build.gradle`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunFirebase.java`
- Create: `app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunFirebaseMessagingService.java`
- Create: `app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunPushAckOutbox.java`
- Create: `app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunPushAckWorker.java`
- Create/Test: `app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunPushAckOutboxTest.java`

**Interfaces:**
- Runtime Firebase config comes from BuildConfig fields populated from environment/Gradle properties; missing config returns `unavailable` without crashing.
- ACK outbox key is `eventId:type`; enqueue is idempotent.
- Worker flushes only after successful 2xx gateway ACK.

- [ ] **Step 1: Add failing outbox/state tests and manifest/build static checks.**
- [ ] **Step 2: Verify RED.**
- [ ] **Step 3: Add `firebase-messaging:25.1.3`, `work-runtime:2.11.2`, and non-secret Firebase BuildConfig inputs.**
- [ ] **Step 4: Implement Firebase initialization/token acquisition, receiver, durable ACK outbox, and network-constrained flush worker.**
- [ ] **Step 5: Verify duplicate RECEIVED/PROCESSED enqueue does not duplicate side effects and tests GREEN.**

### Task 7: Coordinator SERVER mode and recovery watchdog

**Files:**
- Modify: `app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java`
- Modify: `app/src/main/java/com/shaterguy/chatgptselfrun/SelfRun3Coordinator.java`
- Create: `app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunServerRecoveryWorker.java`
- Create/Test: `app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunServerModePolicyTest.java`

**Interfaces:**
- New service actions derived from `BuildConfig.APPLICATION_ID`: `PUSH_RESULT`, `SERVER_RECOVERY`.
- PUSH_RESULT carries exact event/task/turn/document identity.
- SERVER waiting turn registers one watch, schedules 15-minute periodic recovery work, and does not schedule normal short Result poll unless registration/config failed.
- ON_DEVICE follows existing polling path unchanged.

- [ ] **Step 1: Add failing policy tests for default SERVER no-short-poll, ON_DEVICE existing poll, stale push rejection, and 15-minute recovery scheduling.**
- [ ] **Step 2: Verify RED.**
- [ ] **Step 3: Extend service action validation and coordinator push/recovery entry points.**
- [ ] **Step 4: Register server watch after dispatch/Result document readiness; cache registration per turn and fallback locally on failure.**
- [ ] **Step 5: On RECEIVED push validate ledger identity, trigger one READ_RESULT, then enqueue PROCESSED ACK at the completed read boundary.**
- [ ] **Step 6: Schedule/cancel unique WorkManager recovery work with active SERVER waiting state.**
- [ ] **Step 7: Verify tests GREEN and existing ledger/parallel/repair tests remain GREEN.**

### Task 8: Candidate identity, CI, gateway deploy, and delivery evidence

**Files:**
- Modify: `app/build.gradle`
- Modify: `app/src/test/java/com/shaterguy/chatgptselfrun/TestAppVariantPolicyTest.java` if identity assertion requires it.
- Modify: `.github/workflows/build-selfrun-v3-candidate.yml`
- Modify: `tools/verify_drive_variant.sh` or adjacent static policy only where new required files/contracts need canonical verification.
- Modify: `README.md` with SERVER/ON_DEVICE configuration and required Vercel/Firebase env names.

**Interfaces:**
- Candidate identity `3.2.5-dev3/3025003`.
- Vercel root `command-bridge` must build independently.

- [ ] **Step 1: Update version identity and CI to run `command-bridge` Node tests/typecheck before Android candidate build.**
- [ ] **Step 2: Push branch and wait for full `Build SelfRun 3 Candidate` workflow.**
- [ ] **Step 3: If CI fails, diagnose first failing cause, fix only that cause, and rerun.**
- [ ] **Step 4: Deploy `selfrun-command-bridge` from the linked Vercel project and verify `/api/health` responds.**
- [ ] **Step 5: If Firebase service credentials are not configured in Vercel, verify health reports `configured:false` and Android remains safely fallback-capable; do not invent credentials.**
- [ ] **Step 6: Obtain signed TEST APK artifact from successful candidate CI and verify package/version/signing identity.**
- [ ] **Step 7: Perform independent verification against AC-01∼AC-12 before claiming completion.**
