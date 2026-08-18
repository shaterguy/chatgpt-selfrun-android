package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class LatestComposerSubmissionPolicyTest {
    @Test public void continuationAlwaysTargetsLatestVisibleNonTurnComposer() {
        String script = SelfRunDom.prepareDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "marker-latest");
        assertTrue(script.contains("const __srComposerPool=()=>"));
        assertTrue(script.contains("!__srTurnContained(e)"));
        assertTrue(script.contains("xs[xs.length-1]"));
        assertTrue(script.contains("data-message-author-role"));
        assertTrue(script.contains("conversation-turn"));
        assertFalse(script.contains("outsideTurns.length?outsideTurns:all"));
    }

    @Test public void historicalCalibratedComposerIsRejectedFailClosed() {
        String script = SelfRunDom.prepareDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "marker-old-calibration");
        assertTrue(script.contains("const __srTurnContained=e=>"));
        assertTrue(script.contains("const safeCalibratedComposer=calibratedComposer&&!__srTurnContained(calibratedComposer)?calibratedComposer:null"));
        assertTrue(script.contains("let composer=__srLatestComposer()||safeCalibratedComposer"));
        assertFalse(script.contains("__srLatestComposer()||calibratedComposer"));
    }

    @Test public void calibratedSendMustBelongToLatestComposerScope() {
        String script = SelfRunDom.prepareDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "marker-send-scope");
        assertTrue(script.contains("scope.contains(calibrated)"));
        assertTrue(script.contains("buttons[buttons.length-1]"));
    }

    @Test public void preClickComposerReplacementIsFastRetryNotDriveAckWait() {
        String click = SelfRunDom.clickPreparedDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "marker-reacquire");
        assertTrue(click.contains("제출 직전 최신 continuation 입력창 재확보 · 입력 재반영"));
        assertTrue(click.contains("return result('UI_WAIT'"));
        assertFalse(click.contains("SUBMISSION_AMBIGUOUS"));
        assertFalse(click.contains("location.reload"));
        assertFalse(click.contains("loadUrl"));
    }

    @Test public void bootstrapUsesSameLatestComposerRecoveryPolicy() {
        String click = SelfRunDom.clickPreparedDriveInitial(
                "https://chatgpt.com", "bootstrap", "marker-bootstrap-latest");
        assertTrue(click.contains("제출 직전 최신 입력창 재확보 · 입력 재반영"));
        assertFalse(click.contains("BOOTSTRAP_SUBMISSION_AMBIGUOUS"));
        assertFalse(click.contains("location.reload"));
    }
}
