package com.shaterguy.chatgptselfrun;

/**
 * Protocol regression bundle executed from the canonical 2.x instrumentation profile.
 *
 * <p>The TEST workflow currently selects {@link RichComposerBootstrapWebViewTest} for every 2.x
 * candidate. That test already calls the Pro continuation regression helper, so this bundle keeps
 * the Work/Chat protocol WebView matrix on the actually executed 2.x path without changing the
 * signed TEST build workflow.</p>
 */
final class SelfRun2xProtocolRegressionMatrix {
    private SelfRun2xProtocolRegressionMatrix() {}

    static void run() throws Exception {
        WorkTurnProtocolIngressWebViewTest ingress = new WorkTurnProtocolIngressWebViewTest();
        ingress.workWebSocketUsesSemanticFramesNotOuterDone();
        ingress.workIngressDecodesBinaryAndObservesWorkerChannels();
        ingress.chatTargetLeavesWorkOnlyIngressInactive();

        WorkTurnProtocolInspectorCompatibilityWebViewTest inspector =
                new WorkTurnProtocolInspectorCompatibilityWebViewTest();
        inspector.nestedEncodedItemsSupportInspectorJsonUrlBase64AndSseWithoutEarlyComplete();
        inspector.arrayBufferViewQuotedAndBase64JsonPreserveStaleTurnFence();

        TurnProtocolStateWebViewTest state = new TurnProtocolStateWebViewTest();
        state.chatAndWorkUseCanonicalPostVisibleAnswerAndSemanticComplete();
        state.proPrematureStreamCompleteWaitsForFinalEvidence();
        state.proIgnoresNonTerminalPayloadsThenUsesMarkerlessVisibleAnswer();
        state.newestCanonicalPostSupersedesActiveResponseWithoutTurnNumbers();

        WorkProtocolTransportCaptureWebViewRegression.run();
    }
}
