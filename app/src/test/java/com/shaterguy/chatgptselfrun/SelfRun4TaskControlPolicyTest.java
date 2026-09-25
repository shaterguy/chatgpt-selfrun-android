package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class SelfRun4TaskControlPolicyTest {
    @Test public void controlFileIdentitySchemaEpochAndReadbackArePinned() throws Exception {
        String api = source("DriveApiClient.java");
        String adapter = source("SelfRun4DriveWebAdapter.java");
        assertTrue(api.contains("\"__SELFRUN_CONTROL__\" + taskId + \".json\""));
        assertTrue(adapter.contains("CONTROL_SCHEMA = \"selfrun-task-control-v1\""));
        assertTrue(adapter.contains("long nextEpoch = controlEpoch + 1L"));
        assertTrue(adapter.contains(".put(\"control_epoch\", nextEpoch)"));
        assertTrue(adapter.contains("JSONObject readback = api.readServerDispatchFile(token, controlFileId)"));
        assertTrue(adapter.contains("readback.optLong(\"control_epoch\", -1L) != nextEpoch"));
        assertTrue(adapter.contains("!control.equals(readback.optString(\"state\"))"));
    }

    @Test public void controlProjectionCoversActiveWaitingPausedStoppedAndDoneStates() throws Exception {
        String adapter = source("SelfRun4DriveWebAdapter.java");
        assertTrue(adapter.contains("case WAITING_USER_INTERVENTION -> \"WAITING_USER_INTERVENTION\""));
        assertTrue(adapter.contains("case PAUSED -> \"PAUSED\""));
        assertTrue(adapter.contains("case STOPPED -> \"STOPPED\""));
        assertTrue(adapter.contains("case DONE -> \"DONE\""));
        assertTrue(adapter.contains("default -> \"RUNNING\""));
    }

    @Test public void explicitPauseResumeStopAndDonePreserveControlOrdering() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        assertOrdered(coordinator, "web.publishControlState(\"PAUSED\", reason)", "web.quiesce()");
        assertOrdered(coordinator, "web.publishControlState(\"RESUME_REQUESTED\", \"USER_RESUME\")", "SelfRun3Engine.Kind.RESUME");
        assertOrdered(coordinator, "web.publishControlState(\"STOPPED\", \"USER_STOP\")", "web.close()");
        int projection = coordinator.indexOf("syncProjection(after);");
        int done = coordinator.indexOf("if (after.stage() == SelfRun3Engine.Stage.DONE)", projection);
        int close = coordinator.indexOf("web.close();", done);
        assertTrue(projection >= 0 && done > projection && close > done);
    }

    private static void assertOrdered(String source, String first, String second) {
        int a = source.indexOf(first);
        int b = source.indexOf(second, Math.max(0, a));
        assertTrue(first + " must precede " + second, a >= 0 && b > a);
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
