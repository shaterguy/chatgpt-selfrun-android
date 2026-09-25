package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class SelfRun4DriveDispatchArchitectureTest {
    @Test public void coordinatorKeepsExistingStateMachineAndDelegatesOnlyBrowserExecution() throws Exception {
        String coordinator=source("SelfRun3Coordinator.java");
        String engine=source("SelfRun3Engine.java");
        assertTrue(coordinator.contains("new SelfRun4DriveDispatchAdapter(service, this, ledger)"));
        assertTrue(coordinator.contains("case PREPARE_WEB ->"));
        assertTrue(coordinator.contains("web.prepare(state)"));
        assertTrue(coordinator.contains("web.submit(claimed.execution(turn))"));
        assertTrue(coordinator.contains("scheduleNext(5_000L)"));
        assertTrue(engine.contains("case READY -> Action.PREPARE_WEB"));
        assertTrue(engine.contains("s.resource(\"conversationUrl\").isEmpty() ? Action.PREPARE_WEB : Action.WAIT"));
    }

    @Test public void ninetySecondConversationCreationWatchdogRemainsInAndroid() throws Exception {
        String settings=source("SelfRun3RuntimeSettings.java");
        String adapter=source("SelfRun4DriveDispatchAdapter.java");
        assertTrue(settings.contains("DEFAULT_WEB_PREPARATION_SECONDS = 90L"));
        assertTrue(adapter.contains("prepareTimeoutMs = runtimeSettings.webPreparationMs()"));
        assertTrue(adapter.contains("\"WEB_PREPARATION_TIMEOUT\""));
        assertTrue(adapter.contains("\"SUCCESSOR_TRANSITION_TIMEOUT\""));
        assertTrue(adapter.contains("listener.onFailure"));
    }

    @Test public void driveDispatchPreservesTwoPhaseSendAndCanonicalConversationBinding() throws Exception {
        String adapter=source("SelfRun4DriveDispatchAdapter.java");
        String contract=source("SelfRun4DispatchFile.java");
        String engine=source("SelfRun3Engine.java");
        assertTrue(contract.contains("PREPARE_REQUESTED"));
        assertTrue(contract.contains("READY_TO_SUBMIT"));
        assertTrue(contract.contains("SEND_REQUESTED"));
        assertTrue(contract.contains("STARTED"));
        assertTrue(contract.contains("conversation_url"));
        assertTrue(adapter.contains("listener.onPrepared"));
        assertTrue(adapter.contains("listener.onConversation"));
        assertTrue(adapter.contains("listener.onStarted"));
        assertTrue(engine.contains("\"dispatchFileId\""));
    }

    @Test public void resultCompletionClosesOnlyServerLivenessAndDoesNotReplaceResultAuthority() throws Exception {
        String drive=source("SelfRun3DriveAdapter.java");
        String contract=source("SelfRun4DispatchFile.java");
        assertTrue(drive.contains("selection.committed"));
        assertTrue(drive.contains("dispatchDrive.markResultCommitted(token, s)"));
        assertTrue(contract.contains("RESULT_COMMITTED"));
        assertTrue(drive.contains("SelfRun3ResultDocumentReader.read"));
        assertTrue(drive.contains("SelfRun3ResultDocumentPolicy.select"));
    }

    @Test public void appStillUsesExistingVercelPushPathForResultWakeups() throws Exception {
        String coordinator=source("SelfRun3Coordinator.java");
        assertTrue(coordinator.contains("SelfRunServerWatch"));
        assertTrue(coordinator.contains("startServerWatchRegistration"));
        assertTrue(coordinator.contains("onPushResult"));
    }

    private static String source(String name) throws Exception {
        Path p=Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/"+name);
        if(!Files.exists(p)) p=Path.of("src/main/java/com/shaterguy/chatgptselfrun/"+name);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
