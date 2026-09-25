package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public final class SelfRun3DocumentCreateRecoveryTest {
    private static final String NAME = "SR-test-task:turn:2";

    @Test public void transientFailureBeforeRemoteCreateRetriesFromDurableIntent() throws Exception {
        MemoryStore store = new MemoryStore();
        FakeRemote remote = new FakeRemote();
        remote.failBeforeCreateOnce = true;

        try {
            SelfRun3DocumentCreateRecovery.ensure(NAME, store, remote);
            fail();
        } catch (DriveApiClient.CreateNotSubmittedException expected) { }

        assertTrue(store.intent.contains("\"v\":2"));
        assertEquals("", store.documentId);
        assertEquals("RETRYABLE", store.createState);
        assertEquals(1, remote.createCalls);
        assertEquals(0, remote.actualCreates);

        String recovered = SelfRun3DocumentCreateRecovery.ensure(NAME, store, remote);
        assertEquals("document0001", recovered);
        assertEquals(recovered, store.documentId);
        assertEquals(2, remote.createCalls);
        assertEquals(1, remote.actualCreates);
        assertEquals(1, remote.changeReads);
    }

    @Test public void retryableHttpRejectionRearmsWithoutDuplicate() throws Exception {
        MemoryStore store = new MemoryStore();
        FakeRemote remote = new FakeRemote();
        remote.reject429Once = true;

        try {
            SelfRun3DocumentCreateRecovery.ensure(NAME, store, remote);
            fail();
        } catch (DriveApiClient.ApiException expected) {
            assertEquals(429, expected.status);
        }

        assertEquals("RETRYABLE", store.createState);
        assertEquals(1, remote.createCalls);
        assertEquals(0, remote.actualCreates);

        assertEquals("document0001", SelfRun3DocumentCreateRecovery.ensure(NAME, store, remote));
        assertEquals(2, remote.createCalls);
        assertEquals(1, remote.actualCreates);
    }

    @Test public void lostCreateResponseUsesChangeLogWithoutSecondCreate() throws Exception {
        MemoryStore store = new MemoryStore();
        FakeRemote remote = new FakeRemote();
        remote.hideExact = true;
        remote.loseResponseAfterCreateOnce = true;

        try {
            SelfRun3DocumentCreateRecovery.ensure(NAME, store, remote);
            fail();
        } catch (DriveApiClient.OutcomeUnknownException expected) { }

        assertEquals(1, remote.actualCreates);
        assertEquals("", store.documentId);
        assertEquals("SUBMITTING", store.createState);
        String recovered = SelfRun3DocumentCreateRecovery.ensure(NAME, store, remote);
        assertEquals("document0001", recovered);
        assertEquals(1, remote.createCalls);
        assertEquals(1, remote.actualCreates);
        assertEquals(1, remote.changeReads);
    }

    @Test public void unknownSubmittedOutcomeWithoutVisibilityNeverBlindlyCreatesAgain() throws Exception {
        MemoryStore store = new MemoryStore();
        FakeRemote remote = new FakeRemote();
        remote.unknownWithoutRemoteOnce = true;

        try {
            SelfRun3DocumentCreateRecovery.ensure(NAME, store, remote);
            fail();
        } catch (DriveApiClient.OutcomeUnknownException expected) { }

        assertEquals("SUBMITTING", store.createState);
        assertEquals(1, remote.createCalls);
        assertEquals(0, remote.actualCreates);

        try {
            SelfRun3DocumentCreateRecovery.ensure(NAME, store, remote);
            fail();
        } catch (SelfRun3DocumentCreateRecovery.PendingOutcomeException expected) { }
        assertEquals(1, remote.createCalls);
        assertEquals(0, remote.actualCreates);
        assertEquals(1, remote.changeReads);
    }

    @Test public void restartAfterIntentBeforeCreateSafelyCreatesOnce() throws Exception {
        MemoryStore store = new MemoryStore();
        store.intent = new SelfRun3DocumentCreateRecovery.Intent(NAME, "cursor-before", false).encode();
        FakeRemote remote = new FakeRemote();

        String id = SelfRun3DocumentCreateRecovery.ensure(NAME, store, remote);
        assertEquals("document0001", id);
        assertEquals(1, remote.changeReads);
        assertEquals(1, remote.actualCreates);
        assertEquals(0, remote.tokenReads);
    }

    @Test public void exactExistingDocumentWinsEvenForLegacyIntent() throws Exception {
        MemoryStore store = new MemoryStore();
        store.intent = NAME;
        FakeRemote remote = new FakeRemote();
        remote.addRemote("document0099");

        String id = SelfRun3DocumentCreateRecovery.ensure(NAME, store, remote);
        assertEquals("document0099", id);
        assertEquals(0, remote.createCalls);
        assertEquals(0, remote.changeReads);
    }

    @Test public void duplicateChangedDocumentsFailClosedWithoutAnotherCreate() throws Exception {
        MemoryStore store = new MemoryStore();
        store.intent = new SelfRun3DocumentCreateRecovery.Intent(NAME, "cursor-before", false).encode();
        FakeRemote remote = new FakeRemote();
        remote.hideExact = true;
        remote.addRemote("document0001");
        remote.addRemote("document0002");

        try {
            SelfRun3DocumentCreateRecovery.ensure(NAME, store, remote);
            fail();
        } catch (IllegalStateException expected) {
            assertEquals("DOCUMENT_CREATE_DUPLICATE", expected.getMessage());
        }
        assertEquals(0, remote.createCalls);
        assertEquals("", store.documentId);
    }

    @Test public void repeatedReconciliationTransportFailureDoesNotCreate() throws Exception {
        MemoryStore store = new MemoryStore();
        store.intent = new SelfRun3DocumentCreateRecovery.Intent(NAME, "cursor-before", false).encode();
        FakeRemote remote = new FakeRemote();
        remote.changeFailures = 2;

        for (int i = 0; i < 2; i++) {
            try {
                SelfRun3DocumentCreateRecovery.ensure(NAME, store, remote);
                fail();
            } catch (IOException expected) { }
        }
        assertEquals(0, remote.createCalls);
        assertEquals("", store.documentId);

        assertEquals("document0001", SelfRun3DocumentCreateRecovery.ensure(NAME, store, remote));
        assertEquals(1, remote.createCalls);
        assertEquals(1, remote.actualCreates);
    }

    @Test public void legacyIntentWithoutObservableDocumentStopsBeforeCreate() throws Exception {
        MemoryStore store = new MemoryStore();
        store.intent = NAME;
        FakeRemote remote = new FakeRemote();

        try {
            SelfRun3DocumentCreateRecovery.ensure(NAME, store, remote);
            fail();
        } catch (IllegalStateException expected) {
            assertEquals("DOCUMENT_CREATE_LEGACY_UNCONFIRMED", expected.getMessage());
        }
        assertEquals(0, remote.createCalls);
    }

    @Test public void safeDriveLogDetailDistinguishesCreateRecoveryFailures() {
        assertEquals("DOCUMENT_CREATE_OUTCOME_PENDING",
                SelfRun3Coordinator.safeDriveErrorDetail(
                        new SelfRun3DocumentCreateRecovery.PendingOutcomeException()));
        assertEquals("DOCUMENT_CREATE_NOT_SUBMITTED",
                SelfRun3Coordinator.safeDriveErrorDetail(
                        new DriveApiClient.CreateNotSubmittedException("x", new IOException("x"))));
        assertEquals("DRIVE_CREATE_OUTCOME_UNKNOWN",
                SelfRun3Coordinator.safeDriveErrorDetail(
                        new DriveApiClient.OutcomeUnknownException("x", new IOException("x"))));
        assertEquals("DRIVE_HTTP_429",
                SelfRun3Coordinator.safeDriveErrorDetail(new DriveApiClient.ApiException(429, "x")));
    }

    @Test public void recoveredDocumentKeepsPreparingReadyAndPlanWorkVerifyContinuity() {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "reasoning", "medium");
        SelfRun3Engine.State state = SelfRun3Engine.create("task", "task:turn:1", config);
        state = resource(state, "folderId", "folder0001");
        state = resource(state, "requirementDocumentId", "requirement0001");
        state = reduce(state, "setup", SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
        state = resource(state, "resultCreateIntent", "intent-v2");
        state = documentCreateState(state, "resultDocumentId", "SUBMITTING");
        assertEquals("SUBMITTING", state.documentCreateState("resultDocumentId"));
        state = documentCreateState(state, "resultDocumentId", "RETRYABLE");
        state = documentCreateState(state, "resultDocumentId", "SUBMITTING");
        state = resource(state, "resultDocumentId", "resultdoc0001");
        state = ready(state, "plan");

        state = resultAndCommit(state, "PLAN", "WORK");
        assertEquals(2, state.turn());
        assertEquals("", state.documentCreateState("resultDocumentId"));
        assertEquals("WORK", state.text("phase"));
        assertEquals(SelfRun3Engine.Stage.PREPARING, state.stage());

        state = resource(state, "resultDocumentId", "resultdoc0002");
        state = ready(state, "work");
        assertEquals(SelfRun3Engine.Stage.READY, state.stage());

        state = resultAndCommit(state, "WORK", "VERIFY");
        assertEquals(3, state.turn());
        assertEquals("VERIFY", state.text("phase"));
        assertEquals(SelfRun3Engine.Stage.PREPARING, state.stage());
    }

    private static SelfRun3Engine.State resultAndCommit(SelfRun3Engine.State state,
                                                         String completed, String next) {
        JSONObject result = new JSONObject();
        SelfRun3Engine.put(result, "schema", SelfRun3Engine.RESULT_SCHEMA);
        SelfRun3Engine.put(result, "task_id", state.taskId());
        SelfRun3Engine.put(result, "turn_id", state.turnId());
        SelfRun3Engine.put(result, "turn", state.turn());
        SelfRun3Engine.put(result, "document_id", state.resource("resultDocumentId"));
        SelfRun3Engine.put(result, "event_id", state.turnId() + ":result");
        SelfRun3Engine.put(result, "committed", true);
        SelfRun3Engine.put(result, "status", "CONTINUE");
        SelfRun3Engine.put(result, "phase_completed", completed);
        SelfRun3Engine.put(result, "next_phase", next);
        JSONObject profile = new JSONObject();
        SelfRun3Engine.put(profile, "mode", "CHAT");
        SelfRun3Engine.put(profile, "model", "gpt-5-6-thinking");
        SelfRun3Engine.put(profile, "reasoning", "medium");
        SelfRun3Engine.put(result, "next_profile", profile);
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "text", result.toString());
        state = reduce(state, state.turnId() + ":result", SelfRun3Engine.Kind.RESULT, payload);
        return reduce(state, state.turnId() + ":commit", SelfRun3Engine.Kind.COMMIT, new JSONObject());
    }

    private static SelfRun3Engine.State ready(SelfRun3Engine.State state, String prompt) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "prompt", prompt);
        SelfRun3Engine.put(payload, "inputText", "");
        SelfRun3Engine.put(payload, "inputRevision", 0L);
        return reduce(state, state.turnId() + ":ready", SelfRun3Engine.Kind.TURN_READY, payload);
    }

    private static SelfRun3Engine.State documentCreateState(SelfRun3Engine.State state,
                                                                String key, String value) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", key);
        SelfRun3Engine.put(payload, "state", value);
        return reduce(state, state.turnId() + ":create:" + value,
                SelfRun3Engine.Kind.DOCUMENT_CREATE_STATE, payload);
    }

    private static SelfRun3Engine.State resource(SelfRun3Engine.State state, String key, String value) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", key);
        SelfRun3Engine.put(payload, "value", value);
        return reduce(state, state.turnId() + ":resource:" + key,
                SelfRun3Engine.Kind.RESOURCE, payload);
    }

    private static SelfRun3Engine.State reduce(SelfRun3Engine.State state, String id,
                                                SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(state,
                new SelfRun3Engine.Event(id, kind, state.taskId(), state.turnId(), payload));
    }

    private static final class MemoryStore implements SelfRun3DocumentCreateRecovery.Store {
        String documentId = "";
        String intent = "";
        String createState = "";

        @Override public String documentId() { return documentId; }
        @Override public String createIntent() { return intent; }
        @Override public String createState() { return createState; }

        @Override public void pinIntent(String value) {
            if (!intent.isEmpty() && !intent.equals(value)) {
                throw new IllegalStateException("intent rebind");
            }
            intent = value;
        }

        @Override public void markSubmitting() {
            if (!createState.isEmpty() && !"RETRYABLE".equals(createState)) {
                throw new IllegalStateException("create state");
            }
            createState = "SUBMITTING";
        }

        @Override public void markRetryable() {
            if (!"SUBMITTING".equals(createState)) throw new IllegalStateException("create state");
            createState = "RETRYABLE";
        }

        @Override public void pinDocument(String id) {
            if (!documentId.isEmpty() && !documentId.equals(id)) {
                throw new IllegalStateException("document rebind");
            }
            documentId = id;
        }
    }

    private static final class FakeRemote implements SelfRun3DocumentCreateRecovery.Remote {
        final List<String> documents = new ArrayList<>();
        final List<String> changes = new ArrayList<>();
        boolean hideExact;
        boolean failBeforeCreateOnce;
        boolean reject429Once;
        boolean loseResponseAfterCreateOnce;
        boolean unknownWithoutRemoteOnce;
        int changeFailures;
        int createCalls;
        int actualCreates;
        int changeReads;
        int tokenReads;

        @Override public String findExact() {
            if (hideExact || documents.isEmpty()) return "";
            if (documents.size() > 1) {
                throw new IllegalStateException("DOCUMENT_CREATE_DUPLICATE");
            }
            return documents.get(0);
        }

        @Override public String currentChangeToken() {
            tokenReads++;
            return "cursor-before";
        }

        @Override public String findCreatedSince(String changeToken) throws Exception {
            assertEquals("cursor-before", changeToken);
            changeReads++;
            if (changeFailures > 0) {
                changeFailures--;
                throw new IOException("transient");
            }
            String found = "";
            for (String id : changes) {
                if (!found.isEmpty() && !found.equals(id)) {
                    throw new IllegalStateException("DOCUMENT_CREATE_DUPLICATE");
                }
                found = id;
            }
            return found;
        }

        @Override public String create() throws Exception {
            createCalls++;
            if (failBeforeCreateOnce) {
                failBeforeCreateOnce = false;
                throw new DriveApiClient.CreateNotSubmittedException(
                        "native create was not submitted", new IOException("connect"));
            }
            if (reject429Once) {
                reject429Once = false;
                throw new DriveApiClient.ApiException(429, "HTTP 429");
            }
            if (unknownWithoutRemoteOnce) {
                unknownWithoutRemoteOnce = false;
                throw new DriveApiClient.OutcomeUnknownException(
                        "native document create result unknown", new IOException("readback"));
            }
            String id = String.format("document%04d", actualCreates + 1);
            actualCreates++;
            documents.add(id);
            changes.add(id);
            if (loseResponseAfterCreateOnce) {
                loseResponseAfterCreateOnce = false;
                throw new DriveApiClient.OutcomeUnknownException(
                        "native document create result unknown", new IOException("readback"));
            }
            return id;
        }

        void addRemote(String id) {
            documents.add(id);
            changes.add(id);
            actualCreates++;
        }
    }
}
