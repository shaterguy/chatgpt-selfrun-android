package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CompletedTurnStaleStopContinuationPolicyTest {
    @Test public void protocolCompleteOverridesStopOnlyBeforeContinuationDispatch() throws Exception {
        String dom = src("SelfRunContinuationDom.java");
        String prepare = between(dom,
                "static String prepareDriveTurn", "static String clickPreparedDriveTurn");
        String click = between(dom,
                "static String clickPreparedDriveTurn", "private static String probeLockedDriveTurn");
        String preflight = between(dom,
                "private static String preflightPreparedDriveTurn", "static String observeTurnCompletion");
        String controls = between(dom,
                "private static String controls", "private static String composerOps");
        String verification = between(dom,
                "private static String continuationClickedVerification",
                "private static String runIdFromContinuationMarker");
        String observer = between(dom,
                "private static String completionObserver", "private static String conversationGuard");
        String bootstrap = between(dom,
                "static String prepareBootstrap", "static String prepareDriveTurn");

        assertTrue(controls.contains("const protocolPhase=()=>"));
        assertTrue(controls.contains("window.__selfRunTurnProtocol?.diagnostics?.()?.phase"));
        assertTrue(controls.contains("const controlState=(allowCompletedTurn=false)=>"));
        assertTrue(controls.contains(
                "completedTurn=!!allowCompletedTurn&&protocolPhase()==='COMPLETE'"));
        assertTrue(controls.contains("if(stop&&!completedTurn)return"));
        assertTrue(controls.contains("if(stop)return"));

        assertTrue(prepare.contains("const c0=controlState(true)"));
        assertEquals(2, occurrences(prepare, "const c=controlState(true)"));
        assertTrue(click.contains("const c=controlState(true)"));
        assertTrue(preflight.contains("const c=controlState(true)"));

        assertFalse(bootstrap.contains("controlState(true)"));
        assertFalse(verification.contains("controlState(true)"));
        assertFalse(observer.contains("controlState(true)"));
        assertTrue(verification.contains("const c=controlState()"));
        assertTrue(observer.contains("const confirmed=controlState()"));
        assertTrue(observer.contains("const current=controlState()"));
    }

    @Test public void generatedContinuationScriptsCarryTheCompletedTurnOverride() {
        String conversationUrl =
                "https://chatgpt.com/c/12345678-1234-1234-1234-123456789abc";
        String prepare = SelfRunContinuationDom.prepareDriveTurn(
                conversationUrl, "[SELF_RUN_CONTINUE SR-TEST]", "SR-TEST:continue:1:1");
        String click = SelfRunContinuationDom.clickPreparedDriveTurn(
                conversationUrl, "[SELF_RUN_CONTINUE SR-TEST]", "SR-TEST:continue:1:1",
                "SR-TEST", "observer-token", 1000L);

        assertTrue(prepare.contains("protocolPhase"));
        assertTrue(prepare.contains("controlState(true)"));
        assertTrue(click.contains("protocolPhase"));
        assertTrue(click.contains("controlState(true)"));
        assertTrue(click.contains("const confirmed=controlState()"));
        assertTrue(click.contains("const current=controlState()"));
        assertFalse(click.contains("const confirmed=controlState(true)"));
        assertFalse(click.contains("const current=controlState(true)"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) {
            path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = text.indexOf(end, from + start.length());
        assertTrue("missing start marker: " + start, from >= 0);
        assertTrue("missing end marker: " + end, to > from);
        return text.substring(from, to);
    }
}
