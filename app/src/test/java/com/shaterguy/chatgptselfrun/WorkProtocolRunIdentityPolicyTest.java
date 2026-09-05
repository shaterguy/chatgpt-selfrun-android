package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

/** Locks the Work protocol observer to the actual SelfRun run identity. */
public final class WorkProtocolRunIdentityPolicyTest {
    @Test public void workRetargetPreservesExistingSelfRunIdentity() throws Exception {
        String work = source("WorkPreferenceDom.java");
        assertTrue(work.contains("const previousTarget=profileEngine.target()"));
        assertTrue(work.contains("const previousRunId=String(previousTarget?.runId||'')"));
        assertTrue(work.contains("previousRunId&&!previousRunId.startsWith('project:')&&!previousRunId.startsWith('conversation:')"));
        assertTrue(work.contains("profileEngine.begin('work',workRunId)"));
        assertTrue(work.contains("targetRunId:t?.runId||''"));
    }

    @Test public void protocolProducerAndNativeBridgeUseTheSameRunIdentity() throws Exception {
        String bootstrap = source("BootstrapModeDom.java");
        String protocol = source("ChatGptTurnProtocolScript.java");
        String bridge = source("TurnProtocolLogBridge.java");
        assertTrue(bootstrap.contains("profileEngine.begin(requestedMode,modeRunId)"));
        assertTrue(protocol.contains("profileTarget()?.runId"));
        assertTrue(bridge.contains("eventRunId.equals(store.runId())"));
    }

    @Test public void profileIsSnapshottedBeforeAsynchronousFetchBodyRead() throws Exception {
        String profile = source("RequestProfileScript.java");
        int snapshot = profile.indexOf("const target=targetSnapshot();");
        assertTrue(snapshot >= 0);
        assertTrue(profile.indexOf("await request.clone().text()", snapshot) > snapshot);
        assertTrue(profile.contains("patchObject(body,target)"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
