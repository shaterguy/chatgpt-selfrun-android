package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

/** Protocol-only regression coverage introduced by 2.3.2-dev5. */
public final class SelfRunDriveDev3PolicyTest {
    @Test public void driveStartsOnlyAfterProtocolComplete() throws Exception {
        String service=source("SelfRunService.java"),store=source("SelfRunStore.java");
        assertTrue(service.contains("beginPostProtocolDriveSync(token,source)"));
        assertTrue(store.contains("PHASE_POST_PROTOCOL_DRIVE_SYNC"));
        assertFalse(service.contains("observeTurnCompletion"));
        assertFalse(service.contains("stable_idle"));
    }
    @Test public void callbackIsFullyFenced() throws Exception {
        String service=source("SelfRunService.java");
        assertTrue(service.contains("token.equals(store.turnProtocolToken())"));
        assertTrue(service.contains("TurnProtocolLogBridge.isAllowedCompletionSource(source)"));
        assertTrue(service.contains("store.turnProtocolCompletionConsumed()"));
    }
    private static String source(String name) throws Exception {
        Path path=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+name);
        if(!Files.exists(path))path=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+name);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }
}
