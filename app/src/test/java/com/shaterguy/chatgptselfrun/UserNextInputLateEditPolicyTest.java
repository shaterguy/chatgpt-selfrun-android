package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class UserNextInputLateEditPolicyTest {
    @Test public void lateEditUsesRevisionPreflightBeforeRealClick() throws Exception {
        String store = src("UserNextInputStore.java");
        String dom = src("SelfRunContinuationDom.java");
        assertTrue(store.contains("PREFLIGHT_CONTINUATION"));
        assertTrue(store.contains("LOCKED_CONTINUATION"));
        assertTrue(store.contains("preflightMatches"));
        assertTrue(store.contains("phaseAllowsEditing"));
        assertTrue(dom.contains("UserNextInputStore.promptForPreparation"));
        assertTrue(dom.contains("UserNextInputStore.nextClickPlan"));
        assertTrue(dom.contains("preflightPreparedDriveTurn"));
        assertTrue(dom.contains("latest continuation verified before submission lock"));
    }

    @Test public void actualSendScriptStillOwnsTheIrreversibleBoundary() throws Exception {
        String dom = src("SelfRunContinuationDom.java");
        String click = between(dom, "static String clickPreparedDriveTurn", "private static String preflightPreparedDriveTurn");
        String preflight = between(dom, "private static String preflightPreparedDriveTurn", "static String observeTurnCompletion");
        assertTrue(click.contains("c.send.click()"));
        assertTrue(click.contains("requestComposerSubmit()"));
        assertTrue(click.contains("armCompletionObserver(false)"));
        assertFalse(preflight.contains("c.send.click()"));
        assertFalse(preflight.contains("requestComposerSubmit()"));
        assertFalse(preflight.contains("armCompletionObserver(false)"));
        assertTrue(preflight.contains("READY_TO_SUBMIT"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        return source.substring(source.indexOf(start), source.indexOf(end));
    }
}
