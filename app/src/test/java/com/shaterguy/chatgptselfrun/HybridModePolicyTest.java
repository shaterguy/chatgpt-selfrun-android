package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HybridModePolicyTest {
    private static final String RUN_ID = "SR-20260903-150432-HQVPQ3";

    @Test public void allBootstrapContinuationModePairingsAreRepresentable() {
        HybridRunProfileStore.Endpoint chat = chatEndpoint();
        HybridRunProfileStore.Endpoint work = workEndpoint();
        assertTrue(new HybridRunProfileStore.Selection(RUN_ID,
                HybridRunProfileStore.STAGE_BOOTSTRAP, chat, chat).valid());
        assertTrue(new HybridRunProfileStore.Selection(RUN_ID,
                HybridRunProfileStore.STAGE_BOOTSTRAP, chat, work).valid());
        assertTrue(new HybridRunProfileStore.Selection(RUN_ID,
                HybridRunProfileStore.STAGE_BOOTSTRAP, work, chat).valid());
        assertTrue(new HybridRunProfileStore.Selection(RUN_ID,
                HybridRunProfileStore.STAGE_BOOTSTRAP, work, work).valid());
    }

    @Test public void requestBridgeSwitchesOnlyOnContinuationBoundaryAndKeepsBothEndpoints() {
        HybridRunProfileStore.Endpoint chat = chatEndpoint();
        HybridRunProfileStore.Endpoint work = workEndpoint();
        HybridRunProfileStore.Selection selection = new HybridRunProfileStore.Selection(
                RUN_ID, HybridRunProfileStore.STAGE_BOOTSTRAP, work, chat);
        String script = HybridRequestProfileScript.documentStartScript(selection);
        assertTrue(script.contains("SELF_RUN_BOOTSTRAP"));
        assertTrue(script.contains("SELF_RUN_CONTINUE"));
        assertTrue(script.contains("if(!switched)markSwitched()"));
        assertTrue(script.contains("const BOOTSTRAP="));
        assertTrue(script.contains("const CONTINUATION="));
        assertTrue(script.contains("engine.begin(e.mode,RUN_ID)"));
        assertTrue(script.contains("endpointMatches(current,e)"));
        assertFalse(script.contains("querySelectorAll('button"));
        assertFalse(script.contains(".click()"));
    }

    @Test public void hybridWorkTitleMatchingIsExact() {
        HybridRunProfileStore.Endpoint work = workEndpoint();
        String raw = "[2026.09.03 | 15:30:00] [SELF_RUN_TURN_COMPLETED " + RUN_ID
                + " MODEL=" + work.model + " REASONING=" + work.reasoning + "]";
        assertTrue(DriveSignalParser.hasWorkFields("MODEL=" + work.model + " REASONING=" + work.reasoning));
        assertTrue(DriveSignalParser.hybridWorkProfileMatches(raw, work.model, work.reasoning));
        assertFalse(DriveSignalParser.hybridWorkProfileMatches(raw, work.model,
                "xhigh".equals(work.reasoning) ? "high" : "xhigh"));
    }

    @Test public void existingChatAndWorkLaunchPathsRemainPresent() throws Exception {
        String activity = src("SelfRunNewActivity.java");
        String dom = src("SelfRunDom.java");
        String protocol = src("SelfRunProtocol.java");
        assertTrue(activity.contains("store.startWork(runId, project, request, new ArrayList<>(selectedAttachments),"));
        assertTrue(activity.contains("store.start(runId, selectedMode, project, request, new ArrayList<>(selectedAttachments))"));
        assertTrue(activity.contains("HybridRunProfileStore.MODE_HYBRID"));
        assertTrue(dom.contains("BootstrapModeDom.inline(requested, runId) + ChatReasoningOptionDom.inline(chatReasoning, runId)"));
        assertTrue(dom.contains("HybridBootstrapDom.inline(runId)"));
        assertTrue(protocol.contains("SELF_RUN_HYBRID_STAGE="));
        assertTrue(protocol.contains("SELF_RUN_HYBRID_CONTINUATION_MODEL="));
    }

    private static HybridRunProfileStore.Endpoint chatEndpoint() {
        List<ProfileRegistry.Profile> profiles = ProfileRegistry.listChat();
        if (profiles.isEmpty()) throw new AssertionError("built-in Chat profile required");
        return HybridRunProfileStore.Endpoint.fromProfile(profiles.get(0));
    }

    private static HybridRunProfileStore.Endpoint workEndpoint() {
        List<ProfileRegistry.Profile> profiles = ProfileRegistry.listWork();
        if (profiles.isEmpty()) throw new AssertionError("built-in Work profile required");
        return HybridRunProfileStore.Endpoint.fromProfile(profiles.get(0));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
