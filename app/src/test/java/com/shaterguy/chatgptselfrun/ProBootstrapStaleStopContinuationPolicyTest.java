package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Scope and safety gates for the Pro-bootstrap stale STOP continuation exception. */
public final class ProBootstrapStaleStopContinuationPolicyTest {
    @Test public void exceptionComesOnlyFromTheDurableBootstrapProfileForThisRun() throws Exception {
        String source = source();
        String prepare = section(source,
                "static String prepareDriveTurn", "static String clickPreparedDriveTurn");
        String selector = section(source,
                "private static boolean proBootstrapRun", "private static String runIdFromContinuationMarker");

        assertTrue(prepare.contains("String runId = runIdFromContinuationMarker(markerId);"));
        assertTrue(prepare.contains("boolean preferSendWhenStopCoexists = proBootstrapRun(runId);"));
        assertTrue(selector.contains("\"pro\".equals(ChatReasoningPreferenceStore.selectionForRun(runId))"));
        assertFalse(selector.contains("continuationSelectionForRun"));
    }

    @Test public void stopCanLosePriorityOnlyToAnActuallyDiscoveredSendControl() throws Exception {
        String controls = section(source(),
                "private static String controls", "private static String composerOps");

        assertTrue(controls.contains("const controlState=(preferSendWhenStopCoexists=false)=>"));
        assertTrue(controls.contains("const stop=controls.find(isStop);const send="));
        assertTrue(controls.contains("if(stop&&!(preferSendWhenStopCoexists&&send))"));
        assertTrue(controls.contains("if(send){if(send.disabled"));
        assertTrue(controls.contains("if(stop)return{state:'\" + STOP + \"',send:null};"));
        assertTrue(controls.indexOf("if(stop&&!(preferSendWhenStopCoexists&&send))")
                < controls.indexOf("if(send){if(send.disabled"));
    }

    @Test public void bootstrapAndCompletionObserverKeepStrictStopPriority() throws Exception {
        String source = source();
        String bootstrap = section(source,
                "static String prepareBootstrap", "static String prepareDriveTurn");
        String observer = section(source,
                "private static String completionObserver", "private static String conversationGuard");

        assertFalse(bootstrap.contains("proBootstrapRun"));
        assertFalse(bootstrap.contains("controlState(true)"));
        assertTrue(observer.contains("const confirmed=controlState()"));
        assertTrue(observer.contains("const current=controlState()"));
        assertFalse(observer.contains("controlState(true)"));
        assertFalse(observer.contains("preferSendWhenStopCoexists"));
    }

    @Test public void relaxedPreClickStopCannotConfirmAFailedDispatchByItself() throws Exception {
        String source = source();
        String click = section(source,
                "static String clickPreparedDriveTurn", "private static String probeLockedDriveTurn");
        String verification = section(source,
                "private static String continuationClickedVerification", "private static boolean proBootstrapRun");

        assertTrue(click.contains("const strictBeforeClick=controlState(),baselineStop="));
        assertTrue(click.contains("baselineStop,submitPath:'pending'"));
        assertTrue(verification.contains("baselineStop=!!m.baselineStop"));
        assertTrue(verification.contains("c.state==='\" + STOP + \"'&&!baselineStop"));
    }

    @Test public void continuationPreparationClickAndPreflightShareTheSameNarrowGate() throws Exception {
        String source = source();
        String prepare = section(source,
                "static String prepareDriveTurn", "static String clickPreparedDriveTurn");
        String click = section(source,
                "static String clickPreparedDriveTurn", "private static String probeLockedDriveTurn");
        String preflight = section(source,
                "private static String preflightPreparedDriveTurn", "static String observeTurnCompletion");

        assertTrue(prepare.contains("controlState(\" + preferSendWhenStopCoexists + \""));
        assertTrue(click.contains("controlState(\" + preferSendWhenStopCoexists + \""));
        assertTrue(preflight.contains("controlState(\" + preferSendWhenStopCoexists + \""));
        assertTrue(click.contains("return preflightPreparedDriveTurn("));
    }

    private static String source() throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunContinuationDom.java");
        if (!Files.exists(path)) {
            path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/SelfRunContinuationDom.java");
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String section(String text, String start, String end) {
        int a = text.indexOf(start);
        int b = text.indexOf(end, a + start.length());
        assertTrue("missing start: " + start, a >= 0);
        assertTrue("missing end: " + end, b > a);
        return text.substring(a, b);
    }
}
