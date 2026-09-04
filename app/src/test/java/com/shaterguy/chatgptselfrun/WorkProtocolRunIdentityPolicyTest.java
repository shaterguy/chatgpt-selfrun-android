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

    @Test public void hybridConfiguresItsEffectiveRequestProfileBeforeDelegatingFetch() throws Exception {
        String web = source("WebViewConfig.java");
        String hybrid = source("HybridRequestProfileScript.java");
        String profile = source("RequestProfileScript.java");
        assertTrue(web.indexOf("RequestProfileScript.installDocumentStart(webView)")
                < web.indexOf("HybridRequestProfileScript.installDocumentStart(webView)"));
        assertTrue(hybrid.contains("configure(decision.endpoint)"));
        assertTrue(hybrid.lastIndexOf("try{prepare(text);") < hybrid.lastIndexOf("return innerFetch(input,init)"));
        assertTrue(profile.contains("const planned=profileForBody(body)"));
        assertTrue(profile.contains("const t=state.target"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
