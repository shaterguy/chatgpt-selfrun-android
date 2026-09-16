package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** SelfRun 3.2.5: committed=true also requires exact mechanical identity; semantic checkpoint fields remain AI-trusted. */
public final class SelfRun3CommittedOnlyResultGateTest {
    @Test public void committedTrueRequiresMachineIdentityButIgnoresSemanticFields() {
        SelfRun3Engine.State state = state();
        JSONObject body = SelfRun3Engine.emptyResult(state);
        put(body, "committed", true);
        put(body, "status", 123);
        put(body, "phase_completed", 17);
        put(body, "next_phase", JSONObject.NULL);
        put(body, "handoff", "not-a-handoff");

        JSONObject parsed = SelfRun3Engine.parseResult(body.toString(), state);
        assertNotNull(parsed);
        assertTrue(parsed.optBoolean("committed"));

        put(body, "document_id", "other-document");
        assertNull(SelfRun3Engine.parseResult(body.toString(), state));
    }

    @Test public void onlyJsonBooleanTrueIsCommitted() {
        SelfRun3Engine.State state = state();
        JSONObject body = SelfRun3Engine.emptyResult(state);

        assertNull(SelfRun3Engine.parseResult(body.toString(), state));

        put(body, "committed", "true");
        assertNull(SelfRun3Engine.parseResult(body.toString(), state));

        body.remove("committed");
        assertNull(SelfRun3Engine.parseResult(body.toString(), state));

        put(body, "committed", 1);
        assertNull(SelfRun3Engine.parseResult(body.toString(), state));

        put(body, "committed", true);
        assertNotNull(SelfRun3Engine.parseResult(body.toString(), state));
    }

    @Test public void invalidJsonNeverPretendsToBeCommitted() {
        SelfRun3Engine.State state = state();
        assertThrows(RuntimeException.class,
                () -> SelfRun3Engine.parseResult("{\"committed\":true", state));
    }

    @Test public void committedObservationBypassesRepairCountdownAndTimeoutFreshReadCanCancelRepair() throws Exception {
        String drive = source("SelfRun3DriveAdapter.java");
        int firstGate = drive.indexOf("SelfRun3Engine.parseResult(observation.candidateBody, s)");
        int firstMutation = drive.indexOf("recordResultBodyObservation(s, observation)", firstGate);
        int watchdog = drive.indexOf("SelfRun3ResultWatchdog.shouldRepair(current", firstMutation);
        assertTrue(firstGate >= 0 && firstMutation > firstGate && watchdog > firstMutation);
        assertTrue(drive.substring(firstGate, firstMutation).contains("return observation;"));

        int finalRead = drive.indexOf("ResultObservation finalObservation = readObservation(token, current)", watchdog);
        int finalGate = drive.indexOf("SelfRun3Engine.parseResult(finalObservation.candidateBody, current)", finalRead);
        int finalMutation = drive.indexOf("recordResultBodyObservation(current, finalObservation)", finalGate);
        assertTrue(finalRead > watchdog && finalGate > finalRead && finalMutation > finalGate);
        assertTrue(drive.substring(finalGate, finalMutation).contains("return finalObservation;"));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static SelfRun3Engine.State state() {
        JSONObject config = new JSONObject();
        put(config, "mode", "CHAT");
        put(config, "reasoning", "medium");
        SelfRun3Engine.State state = SelfRun3Engine.create("gate-task", "gate-task:turn:1", config);
        JSONObject resource = new JSONObject();
        put(resource, "key", "resultDocumentId");
        put(resource, "value", "result-doc");
        return SelfRun3Engine.reduce(state, new SelfRun3Engine.Event("pin-result",
                SelfRun3Engine.Kind.RESOURCE, state.taskId(), state.turnId(), resource));
    }

    private static void put(JSONObject object, String key, Object value) {
        SelfRun3Engine.put(object, key, value);
    }
}
