package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Supplemental wiring checks. R4-R9 behavior itself is executed dynamically by AssistantWaitRuntimeTest. */
public class SelfRunAssistantServicePathTest {
    @Test
    public void serviceUsesProductionAssistantWaitRuntimeForReloadResumeAndProbeResults() throws Exception {
        String service = source("SelfRunService.java");
        assertTrue(service.contains("AssistantWaitRuntime assistantWaitRuntime"));
        assertTrue(service.contains("assistantWaitRuntime = createAssistantWaitRuntime()"));
        assertTrue(service.contains("assistantWaitRuntime.requestImmediate(AssistantWaitCoordinator.Source.RELOAD_PROBE)"));
        assertTrue(service.contains("assistantWaitRuntime.requestImmediate(AssistantWaitCoordinator.Source.RESUME_PROBE)"));
        assertTrue(service.contains("assistantWaitRuntime.onProbeResult(expectedRuntimeEpoch, probeResult)"));
        assertFalse(service.contains("assistantSemanticProbeRunnable"));
        assertFalse(service.contains("assistantTimeoutRunnable"));
    }

    @Test
    public void serviceListenerMapsRuntimeCompletionRecoveryAndRecheckToRealActions() throws Exception {
        String service = source("SelfRunService.java");
        String factory = between(service, "private AssistantWaitRuntime createAssistantWaitRuntime()",
                "@Override\n    public int onStartCommand");
        assertTrue(factory.contains("return handleAssistant(result.text, result.assistantKey)"));
        assertTrue(factory.contains("recoverStalledPhase(\"assistant_timeout_\""));
        assertTrue(factory.contains("hydration 재확인 대기"));
        assertTrue(factory.contains("ensureDomObserver()"));
        assertTrue(factory.contains("scheduleWatchdog()"));
    }

    @Test
    public void staleDomEvaluationStillUsesOwnedTokenBeforeRuntimeDispatch() throws Exception {
        String service = source("SelfRunService.java");
        String evaluate = between(service, "private void evaluate(String phase, String script)",
                "private static boolean isWaitingStatus");
        assertTrue(evaluate.contains("execution_changed"));
        assertTrue(evaluate.contains("assistant_epoch_changed"));
        assertTrue(evaluate.contains("releaseDomEvaluation(activeEvaluationEpoch)"));
        assertTrue(evaluate.contains("assistantWaitRuntime.accepts(activeAssistantWaitEpoch)"));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = text.indexOf(end, from + 1);
        assertTrue("missing section start: " + start, from >= 0);
        assertTrue("missing section end: " + end, to > from);
        return text.substring(from, to);
    }
}
