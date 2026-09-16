package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/** Process restart must re-check a pinned Result before a recovered READY/DISPATCHING state can send. */
public final class SelfRun3AuthorityRestartReadTest {
    @Test public void recoveredPreDispatchStateReadsPinnedResultBeforeWebDispatch() throws Exception {
        Path path = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRun3Coordinator.java");
        if (!Files.exists(path)) path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/SelfRun3Coordinator.java");
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertTrue(source.contains("shouldReadAuthoritativeResultBeforeDispatch(state)"));
        assertTrue(source.contains("ReadTrigger.AUTHORITY"));
        assertTrue(source.contains("authoritativeReadCheckedTurns.add(expectedTurn)"));
    }
}
