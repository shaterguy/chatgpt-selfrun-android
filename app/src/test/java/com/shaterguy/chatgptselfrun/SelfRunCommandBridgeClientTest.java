package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class SelfRunCommandBridgeClientTest {
    @Test
    public void validResponsePreservesCommandAndVerifiesHash() throws Exception {
        String command = "한글\n```text\nquote=\\\"value\"\n```";
        SelfRunCommandBridgeClient.Result result =
                SelfRunCommandBridgeClient.validatePayload(
                        "ok", command, "2026-08-12T00:00:00.000Z",
                        SelfRunCommandBridgeClient.sha256(command));

        assertEquals(SelfRunCommandBridgeClient.Status.SUCCESS, result.status);
        assertEquals(command, result.command);
        assertEquals("2026-08-12T00:00:00.000Z", result.savedAt);
    }

    @Test
    public void hashMismatchIsRejectedAndExistingInputIsPreserved() throws Exception {
        String existing = "기존 요구사항\n두 번째 줄";
        SelfRunCommandBridgeClient.Result result =
                SelfRunCommandBridgeClient.validatePayload(
                        "ok", "새 명령", "2026-08-12T00:00:00.000Z",
                        SelfRunCommandBridgeClient.sha256("다른 명령"));

        assertEquals(SelfRunCommandBridgeClient.Status.FAILURE, result.status);
        assertEquals(existing, SelfRunCommandBridgeClient.commandForInput(existing, result));
        assertNotEquals("새 명령", SelfRunCommandBridgeClient.commandForInput(existing, result));
    }

    @Test
    public void unauthorizedAndServerFailurePreserveExistingInput() {
        String existing = "사용자가 입력한 명령";

        SelfRunCommandBridgeClient.Result unauthorized =
                SelfRunCommandBridgeClient.parseResponse(401, "");
        SelfRunCommandBridgeClient.Result unavailable =
                SelfRunCommandBridgeClient.parseResponse(503, "");

        assertEquals(existing, SelfRunCommandBridgeClient.commandForInput(existing, unauthorized));
        assertEquals(existing, SelfRunCommandBridgeClient.commandForInput(existing, unavailable));
    }

    @Test
    public void emptyResponseDoesNotReplaceExistingInput() {
        String existing = "사용자가 입력한 명령";
        SelfRunCommandBridgeClient.Result result =
                SelfRunCommandBridgeClient.validatePayload("empty", "", "", "");

        assertEquals(SelfRunCommandBridgeClient.Status.EMPTY, result.status);
        assertEquals(existing, SelfRunCommandBridgeClient.commandForInput(existing, result));
    }
}
