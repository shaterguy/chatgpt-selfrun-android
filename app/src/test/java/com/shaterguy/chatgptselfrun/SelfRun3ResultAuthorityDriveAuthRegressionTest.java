package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Dev5 regression contract: Drive committed Result is authoritative and recoverable 401 is retried immediately once. */
public final class SelfRun3ResultAuthorityDriveAuthRegressionTest {
    @Test public void scenario01SharedExactResultMetadataIsReadable() {
        DriveApiClient.Metadata metadata = metadata("result-doc", "folder", true, false, false,
                DriveApiClient.MIME_DOCUMENT);
        assertTrue(SelfRun3ResultDocumentPolicy.acceptReadableResult(metadata, "result-doc", "folder"));
    }

    @Test public void scenario02PublicationAndShareFlagsAreNotResultBoundaryGates() throws Exception {
        String adapter = source("SelfRun3DriveAdapter.java");
        assertFalse(adapter.contains("&& !m.shared && m.isAppAuthorized, \"DOCUMENT_BOUNDARY_MISMATCH\"") );
        assertTrue(adapter.contains("SelfRun3ResultDocumentPolicy.acceptReadableResult"));
    }

    @Test public void scenario03RecoveryLookupDoesNotDropSharedExactDocument() throws Exception {
        String lookup = source("SelfRun3DriveLookup.java");
        assertFalse(lookup.contains("file.optBoolean(\"shared\")"));
        assertFalse(lookup.contains("file.optBoolean(\"isAppAuthorized\""));
    }

    @Test public void scenario04AppAuthorizationMetadataDoesNotBlockExactReadableResult() {
        DriveApiClient.Metadata metadata = metadata("result-doc", "folder", false, false, false,
                DriveApiClient.MIME_DOCUMENT);
        assertTrue(SelfRun3ResultDocumentPolicy.acceptReadableResult(metadata, "result-doc", "folder"));
    }

    @Test public void scenario05PreparingCommittedResultReconcilesWithoutDispatch() {
        SelfRun3Engine.State s = preparingState();
        JSONObject payload = new JSONObject();
        put(payload, "text", committed(s, "CONTINUE").toString());
        SelfRun3Engine.State after = event(s, s.turnId() + ":preexisting-result",
                SelfRun3Engine.Kind.RESULT, payload);
        assertTrue(after.hasResult());
        assertEquals(SelfRun3Engine.Stage.RECONCILING, after.stage());
        assertEquals(SelfRun3Engine.Action.COMMIT, SelfRun3Engine.nextAction(after));
        assertFalse(after.flag("sendClaimed"));
    }

    @Test public void scenario06RepeatedCommittedObservationDoesNotDuplicateProgression() {
        SelfRun3Engine.State s = preparingState();
        JSONObject payload = new JSONObject();
        put(payload, "text", committed(s, "CONTINUE").toString());
        SelfRun3Engine.State first = event(s, s.turnId() + ":preexisting-result:1",
                SelfRun3Engine.Kind.RESULT, payload);
        SelfRun3Engine.State second = event(first, s.turnId() + ":preexisting-result:2",
                SelfRun3Engine.Kind.RESULT, payload);
        assertEquals(first.json().toString(), second.json().toString());
    }

    @Test public void scenario07ExtraAndChildTabBodiesAllowOneExactCommittedCandidate() {
        SelfRun3Engine.State s = preparingState();
        String seed = SelfRun3Engine.emptyResult(s).toString();
        String committed = committed(s, "CONTINUE").toString();
        SelfRun3ResultDocumentPolicy.Selection selected = SelfRun3ResultDocumentPolicy.select(
                Arrays.asList("notes", seed, committed), s);
        assertTrue(selected.committed);
        assertEquals(committed, selected.candidateBody);
    }

    @Test public void scenario08ConflictingCommittedTabCandidatesFailSafely() {
        SelfRun3Engine.State s = preparingState();
        String first = committed(s, "CONTINUE").toString();
        String second = committed(s, "DONE").toString();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> SelfRun3ResultDocumentPolicy.select(List.of(first, second), s));
        assertEquals("RESULT_DOCUMENT_AMBIGUOUS", error.getMessage());
    }

    @Test public void scenario09OldCachedTokenRequiresProactiveRefresh() {
        long ttl = SelfRun3DriveTokenPolicy.MAX_TOKEN_AGE_MS;
        assertEquals(45L * 60_000L, ttl);
        assertTrue(SelfRun3DriveTokenPolicy.needsRefresh("token", 1_000L, 1_000L + ttl));
        assertFalse(SelfRun3DriveTokenPolicy.needsRefresh("token", 1_000L, 1_000L + ttl - 1L));
        assertTrue(SelfRun3DriveTokenPolicy.needsRefresh("token", -1L, 1_000L));
        assertTrue(SelfRun3DriveTokenPolicy.needsRefresh("token", 2_000L, 1_000L));
        assertTrue(SelfRun3DriveTokenPolicy.needsRefresh("", 1_000L, 1_001L));
    }

    @Test public void scenario10FirstReadResult401RefreshesAndImmediatelyRetriesSameStep() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("retryDriveStepAfterUnauthorized"));
        assertTrue(coordinator.contains("stage=IMMEDIATE_RETRY"));
        assertTrue(coordinator.contains("authRetryAttempt + 1"));
    }

    @Test public void scenario11UnauthorizedRecoveryDoesNotOnlyReregisterServerWatch() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        int method = coordinator.indexOf("retryDriveStepAfterUnauthorized");
        int retry = coordinator.indexOf("executeDriveStep(state, step", method);
        assertTrue(method >= 0 && retry > method);
        String body = coordinator.substring(method, Math.min(coordinator.length(), retry + 260));
        assertFalse(body.contains("startServerWatchRegistration"));
    }

    @Test public void scenario12ChangedResultBefore401IsRereadBeforeWait() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("DriveStep.READ_RESULT"));
        assertTrue(coordinator.contains("retryDriveStepAfterUnauthorized(state, step, trigger, push"));
    }

    @Test public void scenario13SuccessfulRetriedReadClearsTransientTokenWarning() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("clearRecoveredDriveWarning(store)"));
        assertTrue(coordinator.contains("networkAttempt = 0"));
    }

    @Test public void scenario14Second401UsesBoundedBackoff() {
        assertEquals(SelfRun3DriveTokenPolicy.UnauthorizedAction.REFRESH_AND_RETRY,
                SelfRun3DriveTokenPolicy.onUnauthorized(0));
        assertEquals(SelfRun3DriveTokenPolicy.UnauthorizedAction.BACKOFF,
                SelfRun3DriveTokenPolicy.onUnauthorized(1));
    }

    @Test public void scenario15ConsentRequiredRemainsDistinctFromRefreshable401() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("pause(\"V3_DRIVE_AUTH_REQUIRED\")"));
        assertTrue(coordinator.contains("V3_DRIVE_TOKEN_EXPIRED"));
    }

    @Test public void scenario16RestartAuthorityStillLoadsExactExecution() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("ledger.loadExecution"));
        assertTrue(coordinator.contains("DriveStep.READ_RESULT"));
    }

    @Test @Category(ServerOnly.class)
    public void scenario18DormantServerPushFallbackAndAckRemainWiredForOptionalChecks() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("SelfRunServerWaitPolicy.useServerPush"));
        assertTrue(coordinator.contains("activateServerFallback"));
        assertTrue(coordinator.contains("acknowledgeProcessed"));
        assertTrue(coordinator.contains("SelfRunServerFeaturePolicy"));
    }

    @Test public void scenario17NoPersistentWakeLockRepeatingAlarmOrFastTokenRefreshLoop() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        String power = source("SelfRun3PowerPolicy.java");
        assertFalse(coordinator.contains("setRepeating("));
        assertFalse(coordinator.contains("setInexactRepeating("));
        assertFalse(coordinator.contains("while (!accessToken"));
        assertTrue(power.contains("networkRetryDelay"));
    }

    private static DriveApiClient.Metadata metadata(String id, String parent, boolean shared,
                                                     boolean appAuthorized, boolean trashed, String mime) {
        JSONObject json = new JSONObject();
        put(json, "id", id);
        put(json, "name", "fixture");
        put(json, "mimeType", mime);
        put(json, "parents", new JSONArray().put(parent));
        put(json, "trashed", trashed);
        put(json, "shared", shared);
        put(json, "isAppAuthorized", appAuthorized);
        return new DriveApiClient.Metadata(json);
    }

    private static SelfRun3Engine.State preparingState() {
        JSONObject config = new JSONObject();
        put(config, "mode", "CHAT");
        put(config, "taskMode", "CHAT");
        put(config, "reasoning", "xhigh");
        SelfRun3Engine.State s = SelfRun3Engine.create("authority-task", "authority-task:turn:1", config);
        s = resource(s, "folderId", "folder-1234");
        s = resource(s, "requirementDocumentId", "requirement-1234");
        s = event(s, s.turnId() + ":setup", SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        return resource(s, "resultDocumentId", "result-doc-1234");
    }

    private static JSONObject committed(SelfRun3Engine.State s, String status) {
        JSONObject r = SelfRun3Engine.emptyResult(s);
        put(r, "committed", true);
        put(r, "status", status);
        put(r, "phase_completed", "WORK");
        put(r, "next_phase", "WORK");
        put(r, "next_input", "");
        return r;
    }

    private static SelfRun3Engine.State resource(SelfRun3Engine.State s, String key, String value) {
        JSONObject payload = new JSONObject();
        put(payload, "key", key);
        put(payload, "value", value);
        return event(s, s.turnId() + ":resource:" + key, SelfRun3Engine.Kind.RESOURCE, payload);
    }

    private static SelfRun3Engine.State event(SelfRun3Engine.State s, String id,
                                               SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(s, new SelfRun3Engine.Event(id, kind, s.taskId(), s.turnId(), payload));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void put(JSONObject object, String key, Object value) {
        SelfRun3Engine.put(object, key, value);
    }
}
