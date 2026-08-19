package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class ContinuationGuardDomPolicyTest {
    @Test public void stopAlwaysWinsAndCalibrationAloneIsNotSend() {
        String script = ContinuationGuardDom.responseIdleCheck(
                "https://chatgpt.com/c/conversation123", "tok", "h1", "c1", "s1");
        assertTrue(script.contains("if(stops.length)return{action:'STOP'"));
        assertTrue(script.contains("if(sends.length!==1)return{action:'UNKNOWN'"));
        assertTrue(script.contains("const calibrated=__srFind"));
        assertTrue(script.contains("nodes.push(calibrated)"));
        assertFalse(script.contains("return calibrated"));
        assertFalse(script.contains("calibrated?'SEND'"));
    }

    @Test public void stopClassifierUsesCurrentSemanticMetadataAndNeverClicksStop() {
        String script = ContinuationGuardDom.clickPreparedDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "m", "tok", "h1", "c1", "s1");
        assertTrue(script.contains("dataset?.testid"));
        assertTrue(script.contains("getAttribute?.('aria-label')"));
        assertTrue(script.contains("getAttribute?.('title')"));
        assertTrue(script.contains("생성[ _-]?중지"));
        assertTrue(script.contains("응답[ _-]?중지"));
        assertTrue(script.contains("SUBMIT_BLOCKED_STOP"));
        assertEquals(1, count(script, ".click()"));
        assertTrue(script.contains("a.send.click()"));
        assertFalse(script.contains("stop.click()"));
    }

    @Test public void prepareChecksSendBeforeInputAndAgainAfterInput() {
        String script = ContinuationGuardDom.prepareDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "m", "tok", "h1", "c1", "s1");
        int firstClassify = script.indexOf("let a=classifyAction()");
        int input = script.indexOf("composer.focus();if('value'in composer)");
        int secondClassify = script.indexOf("a=classifyAction()", firstClassify + 1);
        assertTrue(firstClassify >= 0 && input > firstClassify && secondClassify > input);
        assertTrue(script.contains("RESPONSE_ACTIVE_AFTER_INPUT"));
        assertTrue(script.contains("composer replaced during input"));
    }

    @Test public void finalClickBindsPromptComposerProbeAndPreparedIdentity() {
        String script = ContinuationGuardDom.clickPreparedDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "m", "tok", "h1", "c1", "s1");
        assertTrue(script.contains("__srCurrent.head===__srExpectedHead"));
        assertTrue(script.contains("__srCurrent.composer===__srExpectedComposer"));
        assertTrue(script.contains("__srCurrent.sig===__srExpectedSig"));
        assertTrue(script.contains("__srProbe.currentComposer===composer"));
        assertTrue(script.contains("prepared.composer!==composer"));
        assertTrue(script.contains("if(!same())return result('SUBMIT_BLOCKED_FRESHNESS'"));
        assertTrue(script.contains("prepared.clicked"));
    }

    @Test public void simultaneousStopAndSendFailsClosed() {
        String script = ContinuationGuardDom.responseIdleCheck(
                "https://chatgpt.com/c/conversation123", "tok", "h1", "c1", "s1");
        assertTrue(script.indexOf("if(stops.length)") < script.indexOf("if(sends.length!==1)"));
        assertTrue(script.contains("RESPONSE_ACTIVE"));
    }

    private static int count(String value, String token) {
        int result = 0, index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            result++;
            index += token.length();
        }
        return result;
    }
}
