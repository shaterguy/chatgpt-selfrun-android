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

    @Test public void directServerStartClaimsSendBeforeConversationCallbacks() throws Exception {
        String adapter = source("SelfRun4DriveWebAdapter.java");
        int handler = adapter.indexOf("private void handleRemote");
        int reconcile = adapter.indexOf("V4_SERVER_DIRECT_START_RECONCILE", handler);
        int prepared = adapter.indexOf("listener.onPrepared", reconcile);
        int reconcileReturn = adapter.indexOf("return;", prepared);
        int conversation = adapter.indexOf("listener.onConversation", prepared);
        assertTrue(handler >= 0 && reconcile > handler);
        assertTrue(prepared > reconcile && reconcileReturn > prepared);
        assertTrue(conversation > reconcileReturn);

        int submit = adapter.indexOf("void submit(SelfRun3Engine.State claimed)");
        int alreadyStarted = adapter.indexOf("serverConversationReady(remoteStatus)", submit);
        int reconciled = adapter.indexOf("V4_SERVER_SEND_CLAIM_RECONCILED", alreadyStarted);
        int write = adapter.indexOf("api.writeServerDispatchFile", submit);
        assertTrue(submit >= 0 && alreadyStarted > submit);
        assertTrue(reconciled > alreadyStarted && write > reconciled);
    }

    @Test public void everyV4RequestRefreshesCanonicalRegistryBeforeDispatch() throws Exception {
        String adapter = source("SelfRun4DriveWebAdapter.java");
        assertTrue(adapter.contains("private boolean registryRefreshPending;"));
        assertTrue(adapter.contains("private String registryRefreshRequest = \"\";"));
        assertTrue(adapter.contains("ProfileRegistrySync.refresh(context, result ->"));
        assertTrue(adapter.contains("if (!refreshRequest.equals(state.requestId()))"));
        assertTrue(adapter.contains("refreshRegistryForCurrentRequest();"));
        assertTrue(adapter.contains("registryRefreshRequest = refreshRequest;"));
        assertTrue(adapter.contains("fail(\"PROFILE_REGISTRY_UNAVAILABLE\")"));
        int prepare = adapter.indexOf("void prepare(SelfRun3Engine.State next)");
        int refresh = adapter.indexOf("ProfileRegistrySync.refresh(context, result ->", prepare);
        int dispatch = adapter.indexOf("private void beginAttempt()", prepare);
        assertTrue(prepare >= 0 && refresh > prepare && dispatch > refresh);
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
