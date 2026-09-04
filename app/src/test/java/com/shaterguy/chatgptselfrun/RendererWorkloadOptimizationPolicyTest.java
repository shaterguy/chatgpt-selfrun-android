package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Static regression and workload guardrails for the renderer workload reduction. */
public final class RendererWorkloadOptimizationPolicyTest {
    @Test public void documentStartRoutingMatchesChatWorkAndHybridContracts() {
        WebViewConfig.AutomationPlan chat = WebViewConfig.automationPlan(SelfRunStore.MODE_CHAT, false, false, true);
        assertEquals(3, chat.documentStartScriptCount());
        assertTrue(chat.requestProfile && chat.domFallback && chat.chatProtocol);
        assertFalse(chat.hybridProfile || chat.workIngress || chat.workTransport);

        WebViewConfig.AutomationPlan work = WebViewConfig.automationPlan(SelfRunStore.MODE_WORK, false, false, true);
        assertEquals(5, work.documentStartScriptCount());
        assertTrue(work.workIngress && work.workTransport);

        WebViewConfig.AutomationPlan hybridChat = WebViewConfig.automationPlan(
                HybridRunProfileStore.MODE_HYBRID, true, false, true);
        assertEquals(4, hybridChat.documentStartScriptCount());
        assertTrue(hybridChat.hybridProfile);
        assertFalse(hybridChat.workIngress || hybridChat.workTransport);

        WebViewConfig.AutomationPlan hybridWork = WebViewConfig.automationPlan(
                HybridRunProfileStore.MODE_HYBRID, true, true, true);
        assertEquals(6, hybridWork.documentStartScriptCount());
        assertTrue(hybridWork.hybridProfile && hybridWork.workIngress && hybridWork.workTransport);
    }

    @Test public void visibleManagementWebViewsDoNotInstallSelfRunTransportEngines() throws Exception {
        String config = source("WebViewConfig.java");
        int profile = config.indexOf("rawContext instanceof ProfileRegistryActivity");
        int calibration = config.indexOf("rawContext instanceof WebUiCalibrationActivity");
        int bridge = config.indexOf("TurnProtocolLogBridge.install(webView)");
        assertTrue(profile >= 0 && profile < bridge);
        assertTrue(calibration >= 0 && calibration < bridge);
        assertTrue(config.substring(profile, calibration).contains("RequestProfileScript.installDocumentStart(webView)"));
    }

    @Test public void workPrimitiveHooksHaveSingleOwnerPerPrimitive() throws Exception {
        String ingress = source("WorkTurnProtocolIngressScript.java");
        String capture = source("WorkProtocolTransportCaptureScript.java");
        assertFalse(ingress.contains("window.fetch=wrappedFetch"));
        assertFalse(ingress.contains("XMLHttpRequest.prototype.open=function"));
        assertFalse(ingress.contains("XMLHttpRequest.prototype.send=function"));
        assertTrue(capture.contains("window.fetch=wrappedFetch"));
        assertTrue(capture.contains("XMLHttpRequest.prototype.open=function"));
        assertTrue(capture.contains("XMLHttpRequest.prototype.send=function"));
        assertTrue(ingress.contains("window.WebSocket=WrappedWebSocket"));
        assertTrue(ingress.contains("window.Worker=WrappedWorker"));
        assertTrue(ingress.contains("window.SharedWorker=WrappedSharedWorker"));
        assertFalse(capture.contains("wrapCreated('Worker'"));
        assertFalse(capture.contains("wrapCreated('SharedWorker'"));
    }

    @Test public void decoderUsesBoundedMacrotaskQueueAndShortCircuitsCompletedSemantics() throws Exception {
        String ingress = source("WorkTurnProtocolIngressScript.java");
        assertTrue(ingress.contains("MAX_SYNC_BATCH=4"));
        assertTrue(ingress.contains("MAX_DECODE_NODES=512"));
        assertTrue(ingress.contains("setTimeout(drainQueue,0)"));
        assertFalse(ingress.contains("queueMicrotask"));
        assertTrue(ingress.contains("completionReached()"));
        assertTrue(ingress.contains("visitedNodes=new WeakSet()"));
        assertTrue(ingress.contains("maxSynchronousBatch"));
        assertTrue(ingress.contains("eventLoopYields"));
    }

    @Test public void domFallbackIsDormantWithoutTokenAndCoalescesActiveMutationBursts() throws Exception {
        String dom = source("TurnCompletionDomFallbackScript.java");
        int observerCreation = dom.indexOf("new MutationObserver");
        int ensureObserver = dom.indexOf("const ensureObserver");
        assertTrue(observerCreation > ensureObserver);
        assertTrue(dom.contains("if(!state.token||state.fired||observerConnected)return"));
        assertTrue(dom.contains("disconnectObserver"));
        assertTrue(dom.contains("EVALUATION_DELAY_MS"));
        assertTrue(dom.contains("observerRoot=()=>document.querySelector('main')"));
        assertTrue(dom.contains("attributeFilter:['disabled','aria-disabled','aria-label','data-testid','hidden']"));
        assertFalse(dom.contains("'title','class'"));
        assertTrue(dom.contains("expensiveEvaluations"));
        assertTrue(dom.contains("protocolHealthyForToken"));
    }

    @Test public void protocolHotPathAvoidsDeepSnapshotCloneAndStopsAfterCompletion() throws Exception {
        String protocol = source("ChatGptTurnProtocolScript.java");
        assertTrue(protocol.contains("const snapshot=()=>({...state})"));
        assertFalse(protocol.contains("JSON.parse(JSON.stringify(state))"));
        assertTrue(protocol.contains("const visited=seen||new WeakSet()"));
        assertTrue(protocol.contains("if(state.completionDispatched)return"));
        assertTrue(protocol.contains("message_stream_complete"));
        assertTrue(protocol.contains("finished_successfully_end_turn"));
    }

    @Test public void noForbiddenRendererRecoveryOrDisplayPolicyWasAddedToOptimizationLayer() throws Exception {
        String combined = source("WebViewConfig.java") + source("TurnCompletionDomFallbackScript.java")
                + source("WorkTurnProtocolIngressScript.java") + source("WorkProtocolTransportCaptureScript.java")
                + source("ChatGptTurnProtocolScript.java");
        for (String forbidden : new String[]{"pauseTimers()", "resumeTimers()", "setSurface(null)",
                "WebViewRenderProcess.terminate", "terminate()", "about:blank"}) {
            assertFalse("forbidden optimization behavior: " + forbidden, combined.contains(forbidden));
        }
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
