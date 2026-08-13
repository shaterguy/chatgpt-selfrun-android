package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.util.Date;
import static org.junit.Assert.*;

public class SelfRunProtocolTest {
    @Test public void kstPrefixOnlyWrapsAppDriveCommands() {
        String run = "SR-20260813-220315-A1B2C3";
        assertEquals("1970.01.01 | 09:00:00", SelfRunProtocol.kstTimestamp(new Date(0)));
        String bootstrap = SelfRunProtocol.bootstrapDrive(run, SelfRunStore.MODE_CHAT, "work", "document_12345678");
        assertTrue(bootstrap.matches("^\\[\\d{4}\\.\\d{2}\\.\\d{2} \\| \\d{2}:\\d{2}:\\d{2}] \\[SELF_RUN_BOOTSTRAP 0\\.1\\.0 .*"));
        assertTrue(bootstrap.contains("SELF_RUN_COMMAND_RECEIVED " + run));
        assertTrue(bootstrap.contains("DRIVE_TURN_DOCUMENT_ID=document_12345678"));
        assertTrue(SelfRunProtocol.driveContinuation(run).matches("^\\[\\d{4}\\.\\d{2}\\.\\d{2} \\| \\d{2}:\\d{2}:\\d{2}] \\[SELF_RUN_CONTINUE " + run + "]$"));
        assertEquals("[SELF_RUN_CONTINUE " + run + "]", SelfRunProtocol.continuation(run));
    }

    @Test public void assistantControlSignalRemainsUntimestamped() {
        String run = "SR-20260813-220315-A1B2C3";
        SelfRunProtocol.Signal signal = SelfRunProtocol.parseLatest(
                "x\n[SELF_RUN_NEXT " + run + " ROLE=VERIFIER]", run, SelfRunStore.MODE_CHAT);
        assertEquals(SelfRunProtocol.Type.NEXT, signal.type);
        assertTrue(signal.raw.startsWith("[SELF_RUN_NEXT "));
    }
}
