package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Source contract for the V3-owned composer transport. */
public final class SelfRun3ComposerTransportArchitectureTest {
    @Test public void v3WebAdapterHasNoLegacyContinuationExecutionPath() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("SelfRun3ComposerTransport.prepareInitial"));
        assertTrue(web.contains("SelfRun3ComposerTransport.prepareContinuation"));
        assertTrue(web.contains("SelfRun3ComposerTransport.submitInitial"));
        assertTrue(web.contains("SelfRun3ComposerTransport.submitContinuation"));
        assertTrue(web.contains("SelfRun3ComposerTransport.composerReadyExpression()"));
        assertFalse(web.contains("SelfRunContinuationDom"));
        assertFalse(web.contains("textarea#prompt-textarea,div#prompt-textarea"));
        assertFalse(web.contains("composer-stop-button"));
    }

    @Test public void transportIsCapabilityDrivenAndIndependentOfLegacyCalibration() throws Exception {
        String transport = source("SelfRun3ComposerTransport.java");
        for (String legacy : new String[]{
                "__srFind", "WebUiCalibrationStore", "offsetParent",
                "UserNextInputStore", "LegacyRunModeMigration",
                "selfrun-drive:verified-continuation", "selfrun-drive:verified-bootstrap"}) {
            assertFalse("legacy dependency remains in V3 transport: " + legacy,
                    transport.contains(legacy));
        }
        assertTrue(transport.contains("querySelectorAll('textarea,input,[contenteditable],[role=\"textbox\"]')"));
        assertTrue(transport.contains("form.requestSubmit()"));
        assertTrue(transport.contains("protocol.phase==='THINKING'||protocol.phase==='ANSWERING'"));
        assertTrue(transport.contains("e.isConnected"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
