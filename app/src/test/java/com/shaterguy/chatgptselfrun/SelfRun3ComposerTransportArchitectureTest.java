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
    @Test public void v3UsesVerifiedBootstrapModuleAndContinuationOnlyComposerTransport() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        String transport = source("SelfRun3ComposerTransport.java");
        assertTrue(web.contains("SelfRun3BootstrapTransport.prepare"));
        assertTrue(web.contains("SelfRun3BootstrapTransport.submit"));
        assertTrue(web.contains("SelfRun3ComposerTransport.prepareContinuation"));
        assertTrue(web.contains("SelfRun3ComposerTransport.submitContinuation"));
        assertTrue(web.contains("SelfRun3ComposerTransport.composerReadyExpression()"));
        assertFalse(web.contains("SelfRunContinuationDom"));
        assertFalse(transport.contains("prepareInitial"));
        assertFalse(transport.contains("submitInitial"));
        assertFalse(transport.contains("projectGuard"));
    }

    @Test public void bootstrapRestoresKnownGoodPreparedThenBindThenSubmitBoundary() throws Exception {
        String bootstrap = source("SelfRun3BootstrapTransport.java");
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(bootstrap.contains("state:'prepared'"));
        assertTrue(bootstrap.contains("READY_TO_SUBMIT"));
        assertTrue(bootstrap.contains("baselineUserCount"));
        assertTrue(bootstrap.contains("c.send.click()"));
        assertTrue(bootstrap.contains("requestComposerSubmit()"));
        assertFalse(bootstrap.contains("SelfRunContinuationDom"));
        int prepare = web.indexOf("SelfRun3BootstrapTransport.prepare");
        int noBind = web.indexOf("observeWithoutBinding(script)");
        int submit = web.indexOf("SelfRun3BootstrapTransport.submit");
        int bind = web.indexOf("evaluate(observeBeforeAndAfter(claimed, action)");
        assertTrue(prepare >= 0 && noBind > prepare);
        assertTrue(submit >= 0 && bind > submit);
    }

    @Test public void continuationUsesPersistentPreparePhasesAndDetachedNodeFence() throws Exception {
        String transport = source("SelfRun3ComposerTransport.java");
        for (String retired : new String[]{"SelfRunContinuationDom", "UserNextInputStore", "LegacyRunModeMigration"}) {
            assertFalse("retired continuation dependency remains: " + retired, transport.contains(retired));
        }
        assertTrue(transport.contains("selfrun-drive:v3-cont:"));
        assertTrue(transport.contains("state:'clearing'"));
        assertTrue(transport.contains("state:'inputting'"));
        assertTrue(transport.contains("state:'prepared'"));
        assertTrue(transport.contains("state:'clicked'"));
        assertTrue(transport.contains("if(!e.isConnected)return"));
        assertTrue(transport.contains("String.fromCharCode(13)"));
        assertTrue(transport.contains("inputComposer(composer,expected)"));
        assertTrue(transport.contains("clearComposer(composer)"));
        assertTrue(transport.contains("exact continuation prepared"));
        assertTrue(transport.contains("shadowRoot"));
        assertTrue(transport.contains("form.requestSubmit()"));
        assertTrue(transport.contains("KeyboardEvent"));
    }

    @Test public void continuationRegressionOnlyExercisesReachableTwoTurnPath() throws Exception {
        String test = androidTestSource("SelfRun3ComposerTransportWebViewTest.java");
        assertTrue(test.contains("twoContinuationsSurviveSynchronousShadowEditorReplacementAndCanonicalProtocol"));
        assertTrue(test.contains("attachShadow({mode:'open'})"));
        assertTrue(test.contains("syncContinuationRebuild=()=>true"));
        assertTrue(test.contains("window.rebuildComposer()"));
        assertTrue(test.contains("message_stream_complete"));
        assertTrue(test.contains("turn-two|turn-three"));
        assertTrue(test.contains("String(window.canonicalPosts.length)"));
        assertFalse(test.contains("prepareInitial"));
        assertFalse(test.contains("submitInitial"));
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
