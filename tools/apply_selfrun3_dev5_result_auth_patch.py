from pathlib import Path


def read(path):
    return Path(path).read_text(encoding="utf-8")


def write(path, text):
    Path(path).write_text(text, encoding="utf-8")


def replace_one(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:80]!r}")
    write(path, text.replace(old, new, 1))


def replace_region(path, start, end, replacement):
    text = read(path)
    i = text.find(start)
    if i < 0:
        raise SystemExit(f"{path}: start marker not found: {start}")
    j = text.find(end, i)
    if j < 0:
        raise SystemExit(f"{path}: end marker not found: {end}")
    write(path, text[:i] + replacement + text[j:])


# Version identity.
replace_one("app/build.gradle", "def selfRunDriveVersionCode = 3025004", "def selfRunDriveVersionCode = 3025005")
replace_one("app/build.gradle", "def selfRunDriveVersionName = '3.2.5-dev4'", "def selfRunDriveVersionName = '3.2.5-dev5'")
replace_one(
    "app/build.gradle",
    "'SelfRun3StrictJson.java', 'SelfRun3ResultWatchdog.java', 'SelfRun3RuntimeSettings.java',",
    "'SelfRun3StrictJson.java', 'SelfRun3ResultWatchdog.java', 'SelfRun3ResultDocumentPolicy.java',\n"
    "         'SelfRun3ResultDocumentReader.java', 'SelfRun3DriveTokenPolicy.java', 'SelfRun3RuntimeSettings.java',"
)

# A committed pinned Result may arrive before send claim during crash/recovery reconciliation.
replace_one(
    "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRun3Engine.java",
    '                if(!s.flag("sendClaimed")) return original;',
    '                if(!s.flag("sendClaimed") && stage!=Stage.PREPARING && stage!=Stage.READY) return original;'
)

# Exact-name recovery must not depend on sharing/app-authorization metadata flags.
lookup = "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRun3DriveLookup.java"
replace_one(lookup,
            "/** Narrow V3-only exact-name lookup for app-authorized Drive documents. */",
            "/** Narrow V3-only exact-name lookup for readable Drive documents. */")
replace_one(lookup,
            '                if (file == null || file.optBoolean("trashed") || file.optBoolean("shared")) continue;',
            '                if (file == null || file.optBoolean("trashed")) continue;')
replace_one(lookup,
            '                if (!file.optBoolean("isAppAuthorized", false)) continue;\n',
            '')

# Result transport: authoritative committed Result first, relaxed Result metadata boundary, multi-tab reader.
adapter = "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRun3DriveAdapter.java"
prepare_turn = '''    SelfRun3Engine.State prepareTurn(String token, SelfRun3Engine.State original) throws Exception {
        verifyAccount(token, original);
        SelfRun3Engine.State s = ensureDocument(token, original, "resultDocumentId", "resultCreateIntent", original.taskId() + "-" + original.turnId());
        DriveApiClient.Metadata metadata = validateDocument(token, s, s.resource("resultDocumentId"));
        SelfRun3ResultDocumentReader.Snapshot snapshot = SelfRun3ResultDocumentReader.read(token, s.resource("resultDocumentId"));
        SelfRun3ResultDocumentPolicy.Selection selection = SelfRun3ResultDocumentPolicy.select(snapshot.bodies, s);
        if (selection.committed) return reconcileCommittedBeforeDispatch(s, metadata, selection);

        if (snapshot.bodies.size() == 1 && snapshot.bodies.get(0).trim().isEmpty()) {
            DriveApiClient.DocumentSnapshot existing = api.readTurnDocumentSnapshot(token, s.resource("resultDocumentId"));
            checkpoint();
            api.initializeDocument(token, s.resource("resultDocumentId"), SelfRun3Engine.emptyResult(s).toString(), existing.revisionId);
            metadata = validateDocument(token, s, s.resource("resultDocumentId"));
            snapshot = SelfRun3ResultDocumentReader.read(token, s.resource("resultDocumentId"));
            selection = SelfRun3ResultDocumentPolicy.select(snapshot.bodies, s);
            if (selection.committed) return reconcileCommittedBeforeDispatch(s, metadata, selection);
        }

        String seed = SelfRun3Engine.emptyResult(s).toString();
        if (SelfRun3ResultWatchdog.fingerprint(seed).equals(SelfRun3ResultWatchdog.fingerprint(selection.rawBody))) {
            JSONObject payload = new JSONObject();
            SelfRun3Engine.put(payload, "documentId", s.resource("resultDocumentId"));
            SelfRun3Engine.put(payload, "fingerprint", SelfRun3ResultWatchdog.fingerprint(selection.rawBody));
            s = ledger.apply(new SelfRun3Engine.Event(s.turnId() + ":result-baseline",
                    SelfRun3Engine.Kind.RESULT_BASELINE, s.taskId(), s.turnId(), payload)).execution(s.turnId());
        }
        return s;
    }

    private SelfRun3Engine.State reconcileCommittedBeforeDispatch(SelfRun3Engine.State state,
                                                                   DriveApiClient.Metadata metadata,
                                                                   SelfRun3ResultDocumentPolicy.Selection selection) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "text", selection.candidateBody);
        String version = metadata.modifiedTime + ":" + metadata.version;
        return ledger.apply(new SelfRun3Engine.Event(state.turnId() + ":result:" + version,
                SelfRun3Engine.Kind.RESULT, state.taskId(), state.turnId(), payload)).execution(state.turnId());
    }
'''
replace_region(adapter,
               "    SelfRun3Engine.State prepareTurn(String token, SelfRun3Engine.State original) throws Exception {",
               "    static final class ResultObservation {",
               prepare_turn)

read_observation = '''    private ResultObservation readObservation(String token, SelfRun3Engine.State s) throws Exception {
        diagnosticLog.record(projection, "V3_RESULT_READ", "stage=START;turn=" + s.turn());
        try {
            verifyAccount(token, s);
            DriveApiClient.Metadata metadata = validateDocument(token, s, s.resource("resultDocumentId"));
            checkpoint();
            SelfRun3ResultDocumentReader.Snapshot snapshot = SelfRun3ResultDocumentReader.read(
                    token, s.resource("resultDocumentId"));
            SelfRun3ResultDocumentPolicy.Selection selection = SelfRun3ResultDocumentPolicy.select(snapshot.bodies, s);
            String raw = selection.rawBody;
            String candidate = selection.candidateBody;
            if (selection.committed) {
                diagnosticLog.record(projection, "V3_RESULT_READ",
                        "stage=" + (selection.recovered ? "COMMITTED_RECOVERED_TRAILING_BRACE" : "COMMITTED")
                                + ";turn=" + s.turn());
            } else if (raw.trim().isEmpty()) {
                diagnosticLog.record(projection, "V3_RESULT_READ", "stage=PENDING_EMPTY;turn=" + s.turn());
                candidate = SelfRun3Engine.emptyResult(s).toString();
            } else {
                try {
                    SelfRun3Engine.parseResult(raw, s);
                    diagnosticLog.record(projection, "V3_RESULT_READ", "stage=PENDING_COMMIT;turn=" + s.turn());
                    candidate = raw;
                } catch (RuntimeException incompleteOrMalformed) {
                    diagnosticLog.record(projection, "V3_RESULT_READ", "stage=PENDING_BODY;turn=" + s.turn());
                    candidate = SelfRun3Engine.emptyResult(s).toString();
                }
            }
            return new ResultObservation(metadata.modifiedTime + ":" + metadata.version, raw, candidate);
        } catch (Exception error) {
            diagnosticLog.record(projection, "V3_RESULT_READ", "stage=ERROR;type=" + error.getClass().getSimpleName());
            throw error;
        }
    }
'''
replace_region(adapter,
               "    private ResultObservation readObservation(String token, SelfRun3Engine.State s) throws Exception {",
               "    private SelfRun3Engine.State recordResultBodyObservation",
               read_observation)

validate = '''    private DriveApiClient.Metadata validateDocument(String token, SelfRun3Engine.State s, String id) throws Exception {
        checkpoint();
        DriveApiClient.Metadata m = api.getMetadata(token, id);
        boolean resultDocument = id.equals(s.resource("resultDocumentId"));
        boolean valid = resultDocument
                ? SelfRun3ResultDocumentPolicy.acceptReadableResult(m, id, s.resource("folderId"))
                : id.equals(m.id) && s.resource("folderId").equals(m.parentId)
                && DriveApiClient.MIME_DOCUMENT.equals(m.mimeType)
                && !m.trashed && !m.shared && m.isAppAuthorized;
        require(valid, "DOCUMENT_BOUNDARY_MISMATCH");
        return m;
    }
'''
replace_region(adapter,
               "    private DriveApiClient.Metadata validateDocument(String token, SelfRun3Engine.State s, String id) throws Exception {",
               "    private void verifyAccount",
               validate)

# Coordinator: pre-dispatch authority check + proactive token freshness + one immediate 401 refresh/retry.
coordinator = "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRun3Coordinator.java"
replace_one(coordinator,
            "    private final Set<String> serverFallbackTurns = new HashSet<>();\n",
            "    private final Set<String> serverFallbackTurns = new HashSet<>();\n"
            "    private final Set<String> authoritativeReadCheckedTurns = new HashSet<>();\n")
replace_one(coordinator,
            "    private String accessToken = \"\";\n    private int networkAttempt;\n",
            "    private String accessToken = \"\";\n    private long accessTokenIssuedElapsed = -1L;\n    private int networkAttempt;\n")
replace_one(coordinator,
            "        recoveryQueue.clear();\n        SelfRunFallbackWakeScheduler.cancel(service);",
            "        recoveryQueue.clear();\n        authoritativeReadCheckedTurns.clear();\n        SelfRunFallbackWakeScheduler.cancel(service);")

# Before the normal action switch, reconcile an existing pinned Result after process/restart recovery.
replace_one(coordinator,
            "        if (serverWaiting) SelfRunServerRecoveryWorker.schedule(service);\n        else SelfRunServerRecoveryWorker.cancel(service);\n\n        switch (SelfRun3Engine.nextAction(state)) {",
            "        if (serverWaiting) SelfRunServerRecoveryWorker.schedule(service);\n        else SelfRunServerRecoveryWorker.cancel(service);\n\n"
            "        if (shouldReadAuthoritativeResultBeforeDispatch(state)\n"
            "                && !authoritativeReadCheckedTurns.contains(state.turnId())) {\n"
            "            runDriveStep(state, DriveStep.READ_RESULT, ReadTrigger.AUTHORITY, null);\n"
            "            return;\n"
            "        }\n\n"
            "        switch (SelfRun3Engine.nextAction(state)) {")

replace_one(coordinator,
            "        if (!accessToken.isEmpty()) {\n            requestFcmAndRegister(execution, accessToken, expectedEpoch, expectedGeneration);",
            "        if (!SelfRun3DriveTokenPolicy.needsRefresh(accessToken, accessTokenIssuedElapsed, SystemClock.elapsedRealtime())) {\n"
            "            requestFcmAndRegister(execution, accessToken, expectedEpoch, expectedGeneration);")
replace_one(coordinator,
            "                accessToken = DriveAuthorization.accessToken(result);\n                if (accessToken.isEmpty()) {\n                    finishServerRegistrationFallback",
            "                setAccessToken(DriveAuthorization.accessToken(result));\n                if (accessToken.isEmpty()) {\n                    finishServerRegistrationFallback")

replace_one(coordinator,
            "    private enum ReadTrigger { NORMAL, PUSH, RECOVERY }",
            "    private enum ReadTrigger { NORMAL, PUSH, RECOVERY, AUTHORITY }")

run_drive = '''    private void runDriveStep(SelfRun3Engine.State state, DriveStep step) {
        runDriveStep(state, step, ReadTrigger.NORMAL, null);
    }

    private void runDriveStep(SelfRun3Engine.State state, DriveStep step,
                              ReadTrigger trigger, SelfRunPushEvent push) {
        requireMain();
        if (driveInFlight || authorizationInFlight || !canRun()) return;
        if (!SelfRun3DriveTokenPolicy.needsRefresh(accessToken, accessTokenIssuedElapsed, SystemClock.elapsedRealtime())) {
            executeDriveStep(state, step, accessToken, trigger, push, 0);
            return;
        }
        clearAccessToken();
        int expectedEpoch = epoch;
        authorizationInFlight = true;
        DriveAuthorization.requestSilently(service, new DriveAuthorization.Callback() {
            @Override public void onAuthorized(AuthorizationResult result) {
                authorizationInFlight = false;
                if (!validEpoch(expectedEpoch) || !canRun()) return;
                setAccessToken(DriveAuthorization.accessToken(result));
                if (accessToken.isEmpty()) {
                    if (trigger == ReadTrigger.RECOVERY) abortRecoveryCycle();
                    scheduleNetworkRetry("V3_DRIVE_TOKEN_EMPTY");
                    return;
                }
                executeDriveStep(state, step, accessToken, trigger, push, 0);
            }
            @Override public void onResolutionRequired(PendingIntent pendingIntent) {
                authorizationInFlight = false;
                if (trigger == ReadTrigger.RECOVERY) abortRecoveryCycle();
                if (validEpoch(expectedEpoch)) pause("V3_DRIVE_AUTH_REQUIRED");
            }
            @Override public void onFailure(Throwable error) {
                authorizationInFlight = false;
                if (trigger == ReadTrigger.RECOVERY) abortRecoveryCycle();
                if (validEpoch(expectedEpoch)) scheduleNetworkRetry("V3_DRIVE_AUTH_FAILED");
            }
        });
    }

'''
replace_region(coordinator,
               "    private void runDriveStep(SelfRun3Engine.State state, DriveStep step) {",
               "    private void executeDriveStep",
               run_drive)

# Signature and PREPARE_TURN routing.
replace_one(coordinator,
            "    private void executeDriveStep(SelfRun3Engine.State state, DriveStep step, String token,\n                                  ReadTrigger trigger, SelfRunPushEvent push) {",
            "    private void executeDriveStep(SelfRun3Engine.State state, DriveStep step, String token,\n"
            "                                  ReadTrigger trigger, SelfRunPushEvent push, int authRetryAttempt) {")

old_prepare = '''                    after = drive.prepareTurn(token, state);
                    SelfRun3UserInput.Snapshot input = SelfRun3UserInput.snapshot(service, after.taskId());
                    long consumed = after.time("lastConsumedInputRevision");
                    boolean branch = SelfRun3Engine.isBranch(after);
                    boolean repair = "REPAIR".equals(after.text("executionKind"));
                    long inputRevision = branch ? after.time("branchInputRevision") : repair ? consumed : input.revision;
                    String inputText = branch ? after.text("branchInputText") : repair ? "" : (input.revision > consumed ? input.text : "");
                    if (input.revision <= consumed && !input.text.isEmpty()) {
                        SelfRun3UserInput.consumeIfRevision(service, after.taskId(), input.revision);
                    }
                    JSONObject payload = new JSONObject();
                    SelfRun3Engine.put(payload, "prompt", SelfRun3Protocol.prompt(after, inputText));
                    SelfRun3Engine.put(payload, "inputText", inputText);
                    SelfRun3Engine.put(payload, "inputRevision", inputRevision);
                    after = ledger.apply(event(after, after.turnId() + ":turn-ready",
                            SelfRun3Engine.Kind.TURN_READY, payload));
'''
new_prepare = '''                    after = drive.prepareTurn(token, state);
                    if (!after.hasResult()) {
                        SelfRun3UserInput.Snapshot input = SelfRun3UserInput.snapshot(service, after.taskId());
                        long consumed = after.time("lastConsumedInputRevision");
                        boolean branch = SelfRun3Engine.isBranch(after);
                        boolean repair = "REPAIR".equals(after.text("executionKind"));
                        long inputRevision = branch ? after.time("branchInputRevision") : repair ? consumed : input.revision;
                        String inputText = branch ? after.text("branchInputText") : repair ? "" : (input.revision > consumed ? input.text : "");
                        if (input.revision <= consumed && !input.text.isEmpty()) {
                            SelfRun3UserInput.consumeIfRevision(service, after.taskId(), input.revision);
                        }
                        JSONObject payload = new JSONObject();
                        SelfRun3Engine.put(payload, "prompt", SelfRun3Protocol.prompt(after, inputText));
                        SelfRun3Engine.put(payload, "inputText", inputText);
                        SelfRun3Engine.put(payload, "inputRevision", inputRevision);
                        after = ledger.apply(event(after, after.turnId() + ":turn-ready",
                                SelfRun3Engine.Kind.TURN_READY, payload));
                    }
'''
replace_one(coordinator, old_prepare, new_prepare)

# Successful prepare establishes the pre-dispatch authority read for this process.
replace_one(coordinator,
            "                    networkAttempt = 0;\n                    if (step == DriveStep.READ_RESULT) {",
            "                    networkAttempt = 0;\n"
            "                    if (step == DriveStep.PREPARE_TURN && completed.stage() == SelfRun3Engine.Stage.READY) {\n"
            "                        authoritativeReadCheckedTurns.add(expectedTurn);\n"
            "                    }\n"
            "                    if (step == DriveStep.READ_RESULT) {")

# Pending AUTHORITY read means the exact pinned Result was checked successfully; proceed immediately.
replace_one(coordinator,
            "                        if (trigger == ReadTrigger.RECOVERY) {\n                            nextResultPoll.remove(expectedTurn);\n                            finishRecoveryRead();\n                        } else {\n                            scheduleResultRetry(state);\n                        }",
            "                        if (trigger == ReadTrigger.RECOVERY) {\n"
            "                            nextResultPoll.remove(expectedTurn);\n"
            "                            finishRecoveryRead();\n"
            "                        } else if (trigger == ReadTrigger.AUTHORITY) {\n"
            "                            authoritativeReadCheckedTurns.add(expectedTurn);\n"
            "                            nextResultPoll.remove(expectedTurn);\n"
            "                            scheduleNext(0L);\n"
            "                        } else {\n"
            "                            scheduleResultRetry(state);\n"
            "                        }")

# First 401: refresh silently and retry the identical Drive step before altering server-watch state or ACKing push.
replace_one(coordinator,
            "                    if (!validEpoch(expectedEpoch) || !expectedTask.equals(store.runId())) return;\n                    if (push != null && step == DriveStep.READ_RESULT) acknowledgeProcessed(push);",
            "                    if (!validEpoch(expectedEpoch) || !expectedTask.equals(store.runId())) return;\n"
            "                    if (error instanceof DriveApiClient.ApiException api && api.status == 401\n"
            "                            && SelfRun3DriveTokenPolicy.onUnauthorized(authRetryAttempt)\n"
            "                            == SelfRun3DriveTokenPolicy.UnauthorizedAction.REFRESH_AND_RETRY) {\n"
            "                        retryDriveStepAfterUnauthorized(state, step, trigger, push, authRetryAttempt);\n"
            "                        return;\n"
            "                    }\n"
            "                    if (push != null && step == DriveStep.READ_RESULT) acknowledgeProcessed(push);")

# Remove authority marker on committed turn cleanup.
replace_one(coordinator,
            "                    serverFallbackTurns.remove(current.turnId());\n                    syncProjection(after);",
            "                    serverFallbackTurns.remove(current.turnId());\n"
            "                    authoritativeReadCheckedTurns.remove(current.turnId());\n"
            "                    syncProjection(after);")

# Replace the failure method and add immediate retry/token helpers before web callbacks.
handle_block = '''    private void handleDriveFailure(SelfRun3Engine.State state, DriveStep step, Throwable error) {
        if (error instanceof DriveApiClient.ApiException api && api.status == 401) {
            clearAccessToken();
            scheduleNetworkRetry("V3_DRIVE_TOKEN_EXPIRED");
            return;
        }
        if (error instanceof DriveApiClient.ApiException api && api.retryable()) {
            scheduleNetworkRetry("V3_DRIVE_HTTP_RETRY_" + api.status);
            return;
        }
        if (error instanceof IOException) {
            scheduleNetworkRetry("V3_DRIVE_NETWORK_RETRY");
            return;
        }
        hardPause("V3_" + step.name() + "_FAILED", error);
    }

    private void retryDriveStepAfterUnauthorized(SelfRun3Engine.State state, DriveStep step,
                                                  ReadTrigger trigger, SelfRunPushEvent push,
                                                  int authRetryAttempt) {
        requireMain();
        clearAccessToken();
        if (!canRun()) return;
        int expectedEpoch = epoch;
        authorizationInFlight = true;
        log.record(store, "V3_DRIVE_TOKEN_REFRESH", "stage=START;step=" + step.name());
        DriveAuthorization.requestSilently(service, new DriveAuthorization.Callback() {
            @Override public void onAuthorized(AuthorizationResult result) {
                authorizationInFlight = false;
                if (!validEpoch(expectedEpoch) || !canRun()) return;
                setAccessToken(DriveAuthorization.accessToken(result));
                if (accessToken.isEmpty()) {
                    if (trigger == ReadTrigger.RECOVERY) abortRecoveryCycle();
                    scheduleNetworkRetry("V3_DRIVE_TOKEN_EMPTY");
                    return;
                }
                log.record(store, "V3_DRIVE_TOKEN_REFRESH", "stage=IMMEDIATE_RETRY;step=" + step.name());
                executeDriveStep(state, step, accessToken, trigger, push, authRetryAttempt + 1);
            }
            @Override public void onResolutionRequired(PendingIntent pendingIntent) {
                authorizationInFlight = false;
                if (trigger == ReadTrigger.RECOVERY) abortRecoveryCycle();
                if (validEpoch(expectedEpoch)) pause("V3_DRIVE_AUTH_REQUIRED");
            }
            @Override public void onFailure(Throwable error) {
                authorizationInFlight = false;
                if (trigger == ReadTrigger.RECOVERY) abortRecoveryCycle();
                if (validEpoch(expectedEpoch)) scheduleNetworkRetry("V3_DRIVE_AUTH_FAILED");
            }
        });
    }

    private void setAccessToken(String token) {
        accessToken = token == null ? "" : token;
        accessTokenIssuedElapsed = accessToken.isEmpty() ? -1L : SystemClock.elapsedRealtime();
    }

    private void clearAccessToken() {
        accessToken = "";
        accessTokenIssuedElapsed = -1L;
    }

    private static boolean shouldReadAuthoritativeResultBeforeDispatch(SelfRun3Engine.State state) {
        if (state == null || state.resource("resultDocumentId").isEmpty() || state.hasResult()) return false;
        return state.stage() == SelfRun3Engine.Stage.READY
                || (state.stage() == SelfRun3Engine.Stage.DISPATCHING
                && state.resource("conversationUrl").isEmpty());
    }

'''
replace_region(coordinator,
               "    private void handleDriveFailure(SelfRun3Engine.State state, DriveStep step, Throwable error) {",
               "    @Override public void onPrepared",
               handle_block)

# Final source sanity checks before commit.
checks = {
    "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRun3DriveAdapter.java": [
        "SelfRun3ResultDocumentPolicy.acceptReadableResult", "reconcileCommittedBeforeDispatch",
        "SelfRun3ResultDocumentReader.read"
    ],
    "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRun3Coordinator.java": [
        "ReadTrigger.AUTHORITY", "retryDriveStepAfterUnauthorized", "stage=IMMEDIATE_RETRY",
        "authRetryAttempt + 1", "SelfRun3DriveTokenPolicy.needsRefresh"
    ],
}
for path, needles in checks.items():
    text = read(path)
    for needle in needles:
        if needle not in text:
            raise SystemExit(f"{path}: missing expected post-patch token {needle}")

print("SelfRun 3 dev5 Result authority/auth patch applied")
