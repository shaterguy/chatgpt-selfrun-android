package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class SelfRunHistoryRedactionTest {
    @Test public void historyNeverCopiesRawNextInputPayload() throws Exception {
        String history = src("SelfRunHistoryStore.java");
        assertTrue(history.contains("DriveSignalParser.historySafeRaw(store.lastDriveSignalRaw())"));
        assertTrue(history.contains("DriveSignalParser.historySafeRaw(store.pendingDriveSignalRaw())"));
        assertFalse(history.contains("bounded(store.pendingDriveSignalRaw(), 1_000)"));
    }
    private static String src(String f) throws Exception { Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f); if(!Files.exists(p)) p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f); return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8); }
}
