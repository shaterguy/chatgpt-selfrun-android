package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class TurnProtocolGenerationGuardPolicyTest {
    @Test public void protocolGenerationPreventsTimeOnlyRollover() {
        long start=10_000L,deadline=start+SelfRunRolloverPolicy.CONTINUATION_NO_START_MAX_WAIT_MS;
        assertEquals(SelfRunRolloverPolicy.NO_START_WAIT,
                SelfRunRolloverPolicy.postDispatchNoStartAction(start,start,deadline,false,true));
        assertEquals(SelfRunRolloverPolicy.NO_START_ROLLOVER,
                SelfRunRolloverPolicy.postDispatchNoStartAction(start,start,deadline,false,false));
        assertEquals(SelfRunRolloverPolicy.NO_START_PAUSE_TRANSIENT,
                SelfRunRolloverPolicy.postDispatchNoStartAction(start,start,deadline,true,false));
    }
    @Test public void oneProtocolTokenOwnsNativeCorrelation() throws Exception {
        String protocol=source("ChatGptTurnProtocolScript.java");
        String bridge=source("TurnProtocolLogBridge.java");
        String store=source("SelfRunStore.java");
        assertTrue(protocol.contains("turnToken:safe(state.turnToken)"));
        assertTrue(bridge.contains("turnToken.equals(store.turnProtocolToken())"));
        assertTrue(store.contains("prepareTurnProtocolToken"));
        assertFalse(protocol.contains("__selfRunDriveTurnObserver"));
        assertFalse(store.contains("boolean turnObserverSawStop()"));
    }
    private static String source(String name) throws Exception {
        Path path=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+name);
        if(!Files.exists(path))path=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+name);
        return Files.readString(path,StandardCharsets.UTF_8);
    }
}
