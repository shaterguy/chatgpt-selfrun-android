package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class SelfRunNextInputProtocolTest {
    private static final String RUN = "SR-20260816-011429-9SZ8A4";

    @Test public void protocolVersionBumpsForNextInputGrammar() {
        assertEquals("0.2.0", SelfRunProtocol.DRIVE_PROTOCOL_VERSION);
        String bootstrap = SelfRunProtocol.bootstrapDrive(RUN, SelfRunStore.MODE_CHAT, "work", "document_12345678");
        assertTrue(bootstrap.split("\\n", 2)[0].contains("[SELF_RUN_BOOTSTRAP 0.2.0 " + RUN + " MODE=CHAT]"));
    }

    @Test public void oldContinueRemainsExactlyTwoLines() {
        String prompt = SelfRunProtocol.driveContinuation(RUN);
        String[] lines = prompt.split("\\n", -1);
        assertEquals(2, lines.length);
        assertTrue(lines[0].matches("^\\[\\d{4}\\.\\d{2}\\.\\d{2} \\| \\d{2}:\\d{2}:\\d{2}] \\[SELF_RUN_CONTINUE " + RUN + "]$"));
        assertEquals("Command Recevied Record Required", lines[1]);
    }

    @Test public void nextInputIsAppendedAfterInvariantCommandLinesWithoutMutation() {
        String input = "  승인할게.  \n둘째 줄 ] = \" 😎  ";
        String prompt = SelfRunProtocol.driveContinuation(RUN, input);
        String prefix = prompt.substring(0, prompt.indexOf('\n', prompt.indexOf('\n') + 1) + 1);
        assertTrue(prefix.matches("^\\[\\d{4}\\.\\d{2}\\.\\d{2} \\| \\d{2}:\\d{2}:\\d{2}] \\[SELF_RUN_CONTINUE " + RUN + "]\\nCommand Recevied Record Required\\n$"));
        assertTrue(prompt.endsWith(input));
        assertFalse(prompt.contains("SELF_RUN_PAUSE"));
        assertFalse(prompt.contains("SELF_RUN_USER_ACTION_REQUIRED"));
    }

    @Test public void queuedNextInputIsConsumedOnce() {
        String input = "원격 push를 진행해";
        SelfRunProtocol.requestNextInput(RUN, input);
        String first = SelfRunProtocol.driveContinuation(RUN);
        assertTrue("first=" + first.replace("\n", "\\n"), first.endsWith("\n" + input));
        String second = SelfRunProtocol.driveContinuation(RUN);
        assertFalse("second=" + second.replace("\n", "\\n"), second.endsWith("\n" + input));
    }
}
