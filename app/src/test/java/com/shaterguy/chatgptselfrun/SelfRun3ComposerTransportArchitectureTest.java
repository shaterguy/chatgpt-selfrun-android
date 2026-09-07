package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Source contract for separate V3 bootstrap and continuation transports. */
public final class SelfRun3ComposerTransportArchitectureTest {
    @Test public void v3UsesVerifiedBootstrapModuleAndKeepsContinuationOnNewTransport() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("SelfRun3BootstrapTransport.prepare"));
        assertTrue(web.contains("SelfRun3BootstrapTransport.submit"));
        assertTrue(web.contains("SelfRun3ComposerTransport.prepareContinuation"));
        assertTrue(web.contains("SelfRun3ComposerTransport.submitContinuation"));
        assertTrue(web.contains("SelfRun3ComposerTransport.composerReadyExpression()"));
        assertTrue(web.contains("observeWithoutBinding(script)"));
        assertTrue(web.contains("continuationComposerStage"));
        assertFalse(web.contains("SelfRun3ComposerTransport.prepareInitial("));
        assertFalse(web.contains("SelfRun3ComposerTransport.submitInitial("));
        assertFalse(web.contains("SelfRunContinuationDom"));
    }

    @Test public void bootstrapRestoresKnownGoodPreparedThenBindThenSubmitBoundary() throws Exception {
        String bootstrap = source("SelfRun3BootstrapTransport.java");
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(bootstrap.contains("state:'prepared'"));
        assertTrue(bootstrap.contains("READY_TO_SUBMIT"));
        assertTrue(bootstrap.contains("baselineUserCount"));
        assertTrue(bootstrap.contains("c.send.click()"));
        assertTrue(bootstrap.contains("requestComposerSubmit()"));
        assertTrue(bootstrap.contains("WebUiCalibrationDom.runtimePrelude()"));
        assertTrue(bootstrap.contains("WebUiCalibrationStore.TARGET_PROJECT_COMPOSER"));
        assertFalse(bootstrap.contains("SelfRunContinuationDom"));
        int prepare = web.indexOf("SelfRun3BootstrapTransport.prepare");
        int noBind = web.indexOf("observeWithoutBinding(script)");
        int submit = web.indexOf("SelfRun3BootstrapTransport.submit");
        int bind = web.indexOf("evaluate(observeBeforeAndAfter(claimed, action)");
        assertTrue(prepare >= 0 && noBind > prepare);
        assertTrue(submit >= 0 && bind > submit);
    }

    @Test public void continuationTransportUsesCapabilitiesInsteadOfLegacyUiIdentity() throws Exception {
        String transport = source("SelfRun3ComposerTransport.java");
        for (String legacy : new String[]{
                "SelfRunContinuationDom", "__srFind", "WebUiCalibrationStore", "offsetParent",
                "UserNextInputStore", "LegacyRunModeMigration",
                "selfrun-drive:verified-continuation", "selfrun-drive:verified-bootstrap",
                "prompt-textarea", "data-testid", "findSendControl", "send.click()",
                "composer-stop-button"}) {
            assertFalse("legacy dependency remains in V3 continuation transport: " + legacy,
                    transport.contains(legacy));
        }
        assertTrue(transport.contains("textarea,input,[contenteditable],[role=\"textbox\"]"));
        assertTrue(transport.contains("shadowRoot"));
        assertTrue(transport.contains("form.requestSubmit()"));
        assertTrue(transport.contains("KeyboardEvent"));
        assertTrue(transport.contains("protocol.phase==='THINKING'||protocol.phase==='ANSWERING'"));
        assertTrue(transport.contains("e.isConnected"));
        assertTrue(transport.contains("nearestForm"));
        assertTrue(transport.contains("TURN_PROTOCOL_BUSY"));
    }

    @Test public void nativeRegressionRejectsUnboundNoiseAndThenRunsThreeOwnedTurns() throws Exception {
        String test = androidTestSource("SelfRun3ObservationWebViewTest.java");
        assertTrue(test.contains("window.unrelatedPost()"));
        assertTrue(test.contains("assertFalse(h.current.get().flag(\"dispatchObserved\"))"));
        assertTrue(test.contains("first-prompt|second-prompt|third-prompt"));
        assertTrue(test.contains("awaitFlag(\"dispatchObserved\")"));
        assertTrue(test.contains("message_stream_complete"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String androidTestSource(String name) throws Exception {
        Path path = Paths.get("app/src/androidTest/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/androidTest/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
