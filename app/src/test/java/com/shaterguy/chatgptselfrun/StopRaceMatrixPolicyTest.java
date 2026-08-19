package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class StopRaceMatrixPolicyTest {
    @Test public void stopAfterGuardAndRepeatedStopRemainBlocked() {
        String check = ContinuationGuardDom.responseIdleCheck(
                "https://chatgpt.com/c/conversation123", "tok", "h", "c", "s");
        assertTrue(check.contains("RESPONSE_ACTIVE"));
        assertFalse(check.contains(".click()"));
    }

    @Test public void calibratedControlThatChangesToStopIsReclassified() {
        String check = ContinuationGuardDom.responseIdleCheck(
                "https://chatgpt.com/c/conversation123", "tok", "h", "c", "s");
        int calibrated=check.indexOf("nodes.push(calibrated)");
        int classify=check.indexOf("stops=xs.filter(__srStop)");
        assertTrue(calibrated>=0 && classify>calibrated);
    }

    @Test public void postInputStopAndUnknownBothBlock() {
        String prepare = ContinuationGuardDom.prepareDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "m", "tok", "h", "c", "s");
        assertTrue(prepare.contains("RESPONSE_ACTIVE_AFTER_INPUT"));
        assertTrue(prepare.contains("ACTION_UNKNOWN"));
        assertFalse(prepare.contains("send.click()"));
    }

    @Test public void simultaneousStopAndSendChoosesStop() {
        String check = ContinuationGuardDom.responseIdleCheck(
                "https://chatgpt.com/c/conversation123", "tok", "h", "c", "s");
        assertTrue(check.indexOf("if(stops.length)") < check.indexOf("if(sends.length!==1)"));
    }
}
