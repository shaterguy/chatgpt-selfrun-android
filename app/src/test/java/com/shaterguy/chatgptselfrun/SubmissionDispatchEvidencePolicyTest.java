package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public final class SubmissionDispatchEvidencePolicyTest {
    private static final String PROJECT = "https://chatgpt.com/g/g-p-test";
    private static final String CONVERSATION = "https://chatgpt.com/g/g-p-test/c/conversation123";
    private static final String PROMPT = "SELF_RUN_SUBMISSION_EVIDENCE_PROBE";

    @Test public void bootstrapRequiresDurableConversationRouteBeforeConfirmation() {
        String prepare = SelfRunContinuationDom.prepareBootstrap(PROJECT, PROMPT, "bootstrap-marker");
        String click = SelfRunContinuationDom.clickPreparedBootstrap(
                PROJECT, PROMPT, "bootstrap-marker", "SR-TEST", "observer-token", 5000L);
        assertTrue(prepare.contains("bootstrap conversation route confirmed"));
        assertTrue(prepare.contains("conversation route pending"));
        assertTrue(prepare.contains("after('c')"));
        assertTrue(prepare.contains("users>baseline"));
        assertTrue(prepare.contains("c.state==='STOP'"));
        assertTrue(prepare.contains("if(conversation){"));
        assertTrue(prepare.indexOf("if(conversation){") < prepare.indexOf("const activity="));
        assertTrue(prepare.contains("SUBMISSION_CONFIRMED"));
        assertFalse(prepare.contains("VERIFY_REQUIRED"));
        assertTrue(click.contains("c.send.focus"));
        assertTrue(click.contains("requestComposerSubmit()"));
        assertTrue(click.indexOf("c.send.focus") < click.indexOf("requestComposerSubmit()"));
        assertTrue(click.contains("dispatch=BOOTSTRAP_CLICKED"));
        assertTrue(click.contains("verification=pending"));
        assertFalse(click.contains("return result('BOOTSTRAP_CLICKED'"));
        assertFalse(click.contains("SUBMISSION_CONFIRMED"));
    }

    @Test public void continuationDispatchUsesUserMessageOrStopAsEvidence() {
        String prepare = SelfRunContinuationDom.prepareDriveTurn(CONVERSATION, PROMPT, "continuation-marker");
        String click = SelfRunContinuationDom.clickPreparedDriveTurn(
                CONVERSATION, PROMPT, "continuation-marker", "SR-TEST", "observer-token", 5000L);
        assertTrue(prepare.contains("continuation submission evidence confirmed"));
        assertTrue(prepare.contains("users>baseline"));
        assertTrue(prepare.contains("c.state==='STOP'"));
        assertTrue(prepare.contains("SUBMISSION_CONFIRMED"));
        assertFalse(prepare.contains("VERIFY_REQUIRED"));
        assertTrue(click.contains("c.send.focus"));
        assertTrue(click.contains("requestComposerSubmit()"));
        assertTrue(click.indexOf("c.send.focus") < click.indexOf("requestComposerSubmit()"));
        assertTrue(click.contains("dispatch=CONTINUE_CLICKED"));
        assertTrue(click.contains("verification=pending"));
        assertFalse(click.contains("return result('CONTINUE_CLICKED'"));
        assertFalse(click.contains("SUBMISSION_CONFIRMED"));
    }
}
