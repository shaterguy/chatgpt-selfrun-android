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
        int defaultAllowed = click.indexOf("boolean clickAllowed = true;");
        int managedStart = click.indexOf("if (UserNextInputStore.initialized() && UserNextInputStore.managesContinuation(runId)) {");
        assertTrue("unmanaged default must exist", defaultAllowed >= 0);
        assertTrue("managed branch must exist", managedStart >= 0);
        int managedEnd = click.indexOf("\n        }", managedStart);
        int plan = click.indexOf("UserNextInputStore.nextClickPlan(");
        int planPrompt = click.indexOf("effectivePrompt = plan.prompt;");
        int planAllowed = click.indexOf("clickAllowed = plan.clickAllowed;");
        assertTrue("managed branch end must exist", managedEnd >= 0);
        assertTrue("managed click plan must exist", plan >= 0);
        assertTrue("managed prompt assignment must exist", planPrompt >= 0);
        assertTrue("managed permission assignment must exist", planAllowed >= 0);
        assertTrue(defaultAllowed < managedStart);
        assertTrue(managedStart < plan && plan < planPrompt);
        assertTrue(planPrompt < planAllowed && planAllowed < managedEnd);

        int finalPrompt = click.indexOf("effectivePrompt = LegacyRunModeMigration.appendNotice(runId, effectivePrompt);");
        int deniedStart = click.indexOf("if (!clickAllowed) {");
        assertTrue("final migration notice composition must exist", finalPrompt >= 0);
        assertTrue("dispatch permission check must exist", deniedStart >= 0);
        int deniedEnd = click.indexOf("\n        }", deniedStart);
        int preflightReturn = click.indexOf("return preflightPreparedDriveTurn(");
        int preflightArguments = click.indexOf("conversationUrl, effectivePrompt, markerId, preferSendWhenStopCoexists);");
        int dispatchScript = click.indexOf("return \"(() =>{const result=");
        assertTrue("denied branch end must exist", deniedEnd >= 0);
        assertTrue("preflight return must exist", preflightReturn >= 0);
        assertTrue("preflight must receive the final effective prompt", preflightArguments >= 0);
        assertTrue("actual dispatch script must exist", dispatchScript >= 0);
        assertTrue(managedEnd < finalPrompt && finalPrompt < deniedStart);
        assertTrue(deniedStart < preflightReturn && preflightReturn < preflightArguments);
        assertTrue(preflightArguments < deniedEnd && deniedEnd < dispatchScript);
        String denied = click.substring(deniedStart, deniedEnd);
        assertFalse(denied.contains("c.send.click()"));
        assertFalse(denied.contains("requestComposerSubmit()"));

        int preparedGuard = click.indexOf("if(m.state!=='prepared')");
        int readbackGuard = click.indexOf("if(!same())");
        int sendGuard = click.indexOf("SEND no longer enabled");
        int dispatch = click.indexOf("c.send.click()");
        assertTrue("prepared marker guard must exist", preparedGuard >= 0);
        assertTrue("exact readback guard must exist", readbackGuard >= 0);
        assertTrue("SEND guard must exist", sendGuard >= 0);
        assertTrue("click dispatch must exist", dispatch >= 0);
        assertTrue(dispatchScript < preparedGuard && preparedGuard < readbackGuard);
        assertTrue(readbackGuard < sendGuard && sendGuard < dispatch);
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
