package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class UserNextInputLateEditPolicyTest {
    @Test public void lateEditUsesRevisionPreflightBeforeRealDispatch() throws Exception {
        String store = src("UserNextInputStore.java");
        String dom = src("SelfRunContinuationDom.java");
        assertTrue(store.contains("PREFLIGHT_CONTINUATION"));
        assertTrue(store.contains("LOCKED_CONTINUATION"));
        assertTrue(store.contains("LOCK_PROBE_CONTINUATION"));
        assertTrue(store.contains("preflightMatches"));
        assertTrue(store.contains("lockProbeMatches"));
        assertTrue(store.contains("phaseAllowsEditing"));
        assertTrue(store.contains("beginLockedRetryProbe"));
        assertTrue(dom.contains("UserNextInputStore.promptForPreparation"));
        assertTrue(dom.contains("UserNextInputStore.nextClickPlan"));
        assertTrue(dom.contains("preflightPreparedDriveTurn"));
        assertTrue(dom.contains("probeLockedDriveTurn"));
        assertTrue(dom.contains("latest continuation verified before submission lock"));
        assertTrue(dom.contains("locked continuation has definite no-dispatch evidence"));
    }

    @Test public void actualSendScriptOwnsIrreversibleBoundaryButDoesNotClaimSuccess() throws Exception {
        String dom = src("SelfRunContinuationDom.java");
        String click = between(dom, "static String clickPreparedDriveTurn", "private static String probeLockedDriveTurn");
        String probe = between(dom, "private static String probeLockedDriveTurn", "private static String preflightPreparedDriveTurn");
        String preflight = between(dom, "private static String preflightPreparedDriveTurn", "private static String conversationGuard");
        assertTrue(click.contains("c.send.click()"));
        assertTrue(click.contains("requestComposerSubmit()"));
        assertTrue(click.indexOf("c.send.click()") < click.indexOf("requestComposerSubmit()"));
        assertTrue(click.contains("dispatch=CONTINUE_CLICKED"));
        assertTrue(click.contains("verification=pending"));
        assertFalse(click.contains("MutationObserver"));
        assertFalse(click.contains("return result('CONTINUE_CLICKED'"));
        assertFalse(click.contains("SUBMISSION_CONFIRMED"));
        assertFalse(probe.contains("c.send.click()"));
        assertFalse(probe.contains("requestComposerSubmit()"));
        assertTrue(probe.contains("continuationClickedVerification()"));
        assertTrue(probe.contains("definite no-dispatch evidence"));
        assertFalse(preflight.contains("c.send.click()"));
        assertFalse(preflight.contains("requestComposerSubmit()"));
        assertTrue(preflight.contains("READY_TO_SUBMIT"));
    }

    @Test public void unmanagedDomAdapterAlsoUsesEvidenceGatedDispatchContract() throws Exception {
        String dom = src("SelfRunContinuationDom.java");
        String click = between(dom, "static String clickPreparedDriveTurn", "private static String probeLockedDriveTurn");
        assertTrue(click.contains("UserNextInputStore.initialized() && UserNextInputStore.managesContinuation(runId)"));
        assertTrue(click.contains("if (!plan.clickAllowed) {"));
        assertTrue(click.contains("return preflightPreparedDriveTurn("));
        assertTrue(click.contains("preferSendWhenStopCoexists);"));
        assertTrue(click.contains("c.send.click()"));
        assertTrue(click.contains("return result('SUBMISSION_PENDING','dispatch=CONTINUE_CLICKED"));
    }

    @Test public void missingPreparedMarkerNeverClaimsSubmission() throws Exception {
        String dom = src("SelfRunContinuationDom.java");
        String click = between(dom, "static String clickPreparedDriveTurn", "private static String probeLockedDriveTurn");
        assertTrue(click.contains("m.state==='clicked'||m.state==='confirmed'"));
        assertTrue(click.contains("if(!m.state)writeMarker({state:'clearing'"));
        assertTrue(click.contains("prepared marker unavailable before click"));
        assertFalse(click.contains("VERIFY_REQUIRED"));
        assertFalse(click.contains("SUBMISSION_CONFIRMED"));
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
