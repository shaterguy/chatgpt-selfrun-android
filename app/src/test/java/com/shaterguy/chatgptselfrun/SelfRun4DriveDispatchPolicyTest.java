package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class SelfRun4DriveDispatchPolicyTest {
    @Test public void coordinatorDelegatesOnlyBrowserExecutionToDriveServer() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        String adapter = source("SelfRun4DriveWebAdapter.java");
        assertTrue(coordinator.contains("private final SelfRun4DriveWebAdapter web;"));
        assertTrue(coordinator.contains("web = new SelfRun4DriveWebAdapter(service, this);"));
        assertFalse(coordinator.contains("web = new SelfRun3WebAdapter(service, this);"));
        assertTrue(adapter.contains("runtimeSettings.webPreparationMs()"));
        assertTrue(adapter.contains("WEB_PREPARATION_TIMEOUT"));
        assertTrue(adapter.contains("listener.onPrepared"));
        assertTrue(adapter.contains("listener.onConversation"));
        assertTrue(adapter.contains("listener.onStarted"));
    }

    @Test public void dispatchUsesDriveAsTheOnlyAppServerTransport() throws Exception {
        String adapter = source("SelfRun4DriveWebAdapter.java");
        String drive = source("DriveApiClient.java");
        assertTrue(adapter.contains("selfrun-server-dispatch-v1"));
        assertTrue(adapter.contains("CREATE_REQUESTED"));
        assertTrue(adapter.contains("SEND_REQUESTED"));
        assertTrue(adapter.contains("READY_TO_SUBMIT"));
        assertTrue(adapter.contains("conversation_url"));
        assertTrue(drive.contains("createServerDispatchFile"));
        assertTrue(drive.contains("readServerDispatchFile"));
        assertTrue(drive.contains("writeServerDispatchFile"));
        assertFalse(adapter.contains("HttpURLConnection"));
        assertFalse(adapter.contains("127.0.0.1"));
        assertFalse(adapter.contains("SELFRUN_PUSH_GATEWAY_URL"));
    }

    @Test public void existingTaskAndResultAuthorityRemainInTheApp() throws Exception {
        String coordinator = source("SelfRun3Coordinator.java");
        String protocol = source("SelfRun3Protocol.java");
        assertTrue(coordinator.contains("drive.observeResult(token, current)"));
        assertTrue(coordinator.contains("SelfRun3ResultWatchdog.shouldRepair"));
        assertTrue(coordinator.contains("commitTurn(state)"));
        assertTrue(protocol.contains("RESULT_DOCUMENT_ID"));
        assertTrue(protocol.contains("REQUIREMENT_DOCUMENT_ID"));
    }

    private static String source(String name) throws Exception {
        Path p = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(p)) p = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
