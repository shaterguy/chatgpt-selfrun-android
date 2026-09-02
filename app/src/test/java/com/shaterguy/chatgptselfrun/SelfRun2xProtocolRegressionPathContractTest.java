package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

/** Prevents the 2.x TEST instrumentation selector from silently skipping protocol regressions. */
public final class SelfRun2xProtocolRegressionPathContractTest {
    @Test public void canonical2xRichComposerPathExecutesWorkAndChatProtocolMatrix() throws Exception {
        String rich = androidTestSource("RichComposerBootstrapWebViewTest.java");
        String pro = androidTestSource("ProBootstrapStaleStopContinuationWebViewRegression.java");
        String matrix = androidTestSource("SelfRun2xProtocolRegressionMatrix.java");

        assertTrue(rich.contains("ProBootstrapStaleStopContinuationWebViewRegression.run()"));
        assertTrue(pro.contains("SelfRun2xProtocolRegressionMatrix.run()"));
        assertTrue(matrix.contains("workWebSocketUsesSemanticFramesNotOuterDone()"));
        assertTrue(matrix.contains("workIngressDecodesBinaryAndObservesWorkerChannels()"));
        assertTrue(matrix.contains("chatTargetLeavesWorkOnlyIngressInactive()"));
        assertTrue(matrix.contains("nestedEncodedItemsSupportInspectorJsonUrlBase64AndSseWithoutEarlyComplete()"));
        assertTrue(matrix.contains("arrayBufferViewQuotedAndBase64JsonPreserveStaleTurnFence()"));
        assertTrue(matrix.contains("chatAndWorkUseCanonicalPostVisibleAnswerAndSemanticComplete()"));
        assertTrue(matrix.contains("newestCanonicalPostSupersedesActiveResponseWithoutTurnNumbers()"));
    }

    private static String androidTestSource(String file) throws Exception {
        Path path = Paths.get("app/src/androidTest/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/androidTest/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
