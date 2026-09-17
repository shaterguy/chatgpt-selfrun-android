package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Behavior-level fault injection for the READ_RESULT 401 recovery contract. */
public final class SelfRun3Drive401RecoveryBehaviorTest {
    @Test public void first401SilentlyRefreshesAndImmediatelyRetriesTheSameReadResult() throws Exception {
        SelfRun3Engine.State state = preparingState();
        String seed = SelfRun3Engine.emptyResult(state).toString();
        String changedCommitted = committed(state, "CONTINUE").toString();
        assertNotEquals(SelfRun3ResultWatchdog.fingerprint(seed),
                SelfRun3ResultWatchdog.fingerprint(changedCommitted));

        RecoveryHarness harness = new RecoveryHarness(state,
                FaultOutcome.unauthorized(),
                FaultOutcome.body("v2", changedCommitted));
        harness.warning = "V3_DRIVE_TOKEN_EXPIRED";
        harness.networkAttempt = 2;

        harness.readResult();

        assertEquals(Arrays.asList(
                "READ_RESULT:result-doc-1234:attempt=0",
                "SILENT_REFRESH",
                "READ_RESULT:result-doc-1234:attempt=1",
                "CONSUME_COMMITTED:v2"), harness.trace);
        assertEquals(2, harness.transport.readCount);
        assertEquals(1, harness.refreshCount);
        assertEquals(0, harness.watchdogWaitCount);
        assertTrue(harness.state.hasResult());
        assertEquals(SelfRun3Engine.Stage.RECONCILING, harness.state.stage());
        assertEquals("", harness.warning);
        assertEquals(0, harness.networkAttempt);
        assertTrue(harness.backoffDelays.isEmpty());
    }

    @Test public void pendingRetryClearsTransientWarningAndNetworkAttemptWithoutWatchdogWait() throws Exception {
        SelfRun3Engine.State state = preparingState();
        RecoveryHarness harness = new RecoveryHarness(state,
                FaultOutcome.unauthorized(),
                FaultOutcome.body("pending-v2", SelfRun3Engine.emptyResult(state).toString()));
        harness.warning = "V3_DRIVE_NETWORK_RETRY";
        harness.networkAttempt = 4;

        harness.readResult();

        assertEquals(Arrays.asList(
                "READ_RESULT:result-doc-1234:attempt=0",
                "SILENT_REFRESH",
                "READ_RESULT:result-doc-1234:attempt=1",
                "PENDING:pending-v2"), harness.trace);
        assertFalse(harness.state.hasResult());
        assertEquals("", harness.warning);
        assertEquals(0, harness.networkAttempt);
        assertEquals(0, harness.watchdogWaitCount);
        assertTrue(harness.backoffDelays.isEmpty());
    }

    @Test public void second401StopsImmediateRetryAndEntersBoundedNetworkBackoff() throws Exception {
        SelfRun3Engine.State state = preparingState();
        RecoveryHarness harness = new RecoveryHarness(state,
                FaultOutcome.unauthorized(),
                FaultOutcome.unauthorized());

        harness.readResult();

        assertEquals(Arrays.asList(
                "READ_RESULT:result-doc-1234:attempt=0",
                "SILENT_REFRESH",
                "READ_RESULT:result-doc-1234:attempt=1",
                "BACKOFF:15000"), harness.trace);
        assertEquals(2, harness.transport.readCount);
        assertEquals(1, harness.refreshCount);
        assertEquals(List.of(15_000L), harness.backoffDelays);
        assertEquals("V3_DRIVE_TOKEN_EXPIRED", harness.warning);
        assertEquals(1, harness.networkAttempt);
        assertEquals(240_000L, SelfRun3PowerPolicy.networkRetryDelay(Integer.MAX_VALUE));
        assertEquals(0, harness.watchdogWaitCount);
    }

    @Test public void productionWiringStillRoutes401ThroughThePolicyBackedImmediateRetryPath() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        int failure = coordinator.indexOf("SelfRun3DriveTokenPolicy.onUnauthorized(authRetryAttempt)");
        int recovery = coordinator.indexOf("retryDriveStepAfterUnauthorized(", failure);
        int refresh = coordinator.indexOf("DriveAuthorization.requestSilently(service", recovery);
        int retry = coordinator.indexOf("authRetryAttempt + 1, serverRecheckAttempt", refresh);
        assertTrue(failure >= 0);
        assertTrue(recovery > failure);
        assertTrue(refresh > recovery);
        assertTrue(retry > refresh);
    }

    private static final class RecoveryHarness {
        private final FaultTransport transport;
        private final List<String> trace = new ArrayList<>();
        private final List<Long> backoffDelays = new ArrayList<>();
        private SelfRun3Engine.State state;
        private String warning = "";
        private int networkAttempt;
        private int refreshCount;
        private int watchdogWaitCount;

        RecoveryHarness(SelfRun3Engine.State state, FaultOutcome... outcomes) {
            this.state = state;
            this.transport = new FaultTransport(outcomes);
        }

        void readResult() throws Exception {
            readResult(0);
        }

        private void readResult(int authRetryAttempt) throws Exception {
            String documentId = state.resource("resultDocumentId");
            trace.add("READ_RESULT:" + documentId + ":attempt=" + authRetryAttempt);
            try {
                FaultObservation observation = transport.read(documentId);
                JSONObject parsed = SelfRun3Engine.parseResult(observation.body, state);
                if (parsed != null) {
                    JSONObject payload = new JSONObject();
                    put(payload, "text", observation.body);
                    state = event(state, state.turnId() + ":fault-result:" + observation.version,
                            SelfRun3Engine.Kind.RESULT, payload);
                    trace.add("CONSUME_COMMITTED:" + observation.version);
                } else {
                    trace.add("PENDING:" + observation.version);
                }
                clearRecoveredOutcome();
            } catch (DriveApiClient.ApiException unauthorized) {
                assertEquals(401, unauthorized.status);
                SelfRun3DriveTokenPolicy.UnauthorizedAction action =
                        SelfRun3DriveTokenPolicy.onUnauthorized(authRetryAttempt);
                if (action == SelfRun3DriveTokenPolicy.UnauthorizedAction.REFRESH_AND_RETRY) {
                    refreshCount++;
                    trace.add("SILENT_REFRESH");
                    readResult(authRetryAttempt + 1);
                    return;
                }
                warning = "V3_DRIVE_TOKEN_EXPIRED";
                long delay = SelfRun3PowerPolicy.networkRetryDelay(networkAttempt++);
                backoffDelays.add(delay);
                trace.add("BACKOFF:" + delay);
            }
        }

        private void clearRecoveredOutcome() {
            if (SelfRun3Coordinator.transientDriveWarning(warning)) warning = "";
            networkAttempt = 0;
        }
    }

    private static final class FaultTransport {
        private final ArrayDeque<FaultOutcome> outcomes = new ArrayDeque<>();
        private int readCount;

        FaultTransport(FaultOutcome... outcomes) {
            this.outcomes.addAll(Arrays.asList(outcomes));
        }

        FaultObservation read(String documentId) throws DriveApiClient.ApiException {
            assertEquals("result-doc-1234", documentId);
            readCount++;
            FaultOutcome outcome = outcomes.pollFirst();
            if (outcome == null) throw new AssertionError("unexpected extra READ_RESULT");
            if (outcome.unauthorized) throw new DriveApiClient.ApiException(401, "fault-injected-401");
            return new FaultObservation(outcome.version, outcome.body);
        }
    }

    private static final class FaultOutcome {
        final boolean unauthorized;
        final String version;
        final String body;

        private FaultOutcome(boolean unauthorized, String version, String body) {
            this.unauthorized = unauthorized;
            this.version = version;
            this.body = body;
        }

        static FaultOutcome unauthorized() {
            return new FaultOutcome(true, "", "");
        }

        static FaultOutcome body(String version, String body) {
            return new FaultOutcome(false, version, body);
        }
    }

    private static final class FaultObservation {
        final String version;
        final String body;

        FaultObservation(String version, String body) {
            this.version = version;
            this.body = body;
        }
    }

    private static SelfRun3Engine.State preparingState() {
        JSONObject config = new JSONObject();
        put(config, "mode", "CHAT");
        put(config, "taskMode", "CHAT");
        put(config, "reasoning", "xhigh");
        SelfRun3Engine.State state = SelfRun3Engine.create(
                "authority-task", "authority-task:turn:1", config);
        state = resource(state, "folderId", "folder-1234");
        state = resource(state, "requirementDocumentId", "requirement-1234");
        state = event(state, state.turnId() + ":setup",
                SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        return resource(state, "resultDocumentId", "result-doc-1234");
    }

    private static JSONObject committed(SelfRun3Engine.State state, String status) {
        JSONObject result = SelfRun3Engine.emptyResult(state);
        put(result, "committed", true);
        put(result, "status", status);
        put(result, "phase_completed", "WORK");
        put(result, "next_phase", "WORK");
        put(result, "next_input", "");
        return result;
    }

    private static SelfRun3Engine.State resource(SelfRun3Engine.State state, String key, String value) {
        JSONObject payload = new JSONObject();
        put(payload, "key", key);
        put(payload, "value", value);
        return event(state, state.turnId() + ":resource:" + key,
                SelfRun3Engine.Kind.RESOURCE, payload);
    }

    private static SelfRun3Engine.State event(SelfRun3Engine.State state, String id,
                                               SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(state,
                new SelfRun3Engine.Event(id, kind, state.taskId(), state.turnId(), payload));
    }

    private static String source(String name) throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of(
                "app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!java.nio.file.Files.exists(path)) {
            path = java.nio.file.Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        }
        return new String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void put(JSONObject object, String key, Object value) {
        SelfRun3Engine.put(object, key, value);
    }
}
