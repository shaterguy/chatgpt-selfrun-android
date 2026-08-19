package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class SelfRunContinuationRetryRegressionTest {
    private static final String URL = "https://chatgpt.com/g/g-p-test/c/conversation123";

    @Test public void timestampOnlyChangeKeepsSameContinuationMeaning() {
        String first = "[2026.08.20 | 00:42:10] [SELF_RUN_CONTINUE SR-RETRY]\nCommand Received Record Required";
        String retry = "[2026.08.20 | 00:47:11] [SELF_RUN_CONTINUE SR-RETRY]\nCommand Received Record Required";

        assertEquals(SelfRunDom.continuationComparablePrompt(first),
                SelfRunDom.continuationComparablePrompt(retry));
    }

    @Test public void nextInputChangeIsNotCollapsedIntoTimestampEquivalence() {
        String prior = "[2026.08.20 | 00:42:10] [SELF_RUN_CONTINUE SR-RETRY]\nCommand Received Record Required";
        String changed = "[2026.08.20 | 00:47:11] [SELF_RUN_CONTINUE SR-RETRY]\nCommand Received Record Required\n새 요구사항";

        assertNotEquals(SelfRunDom.continuationComparablePrompt(prior),
                SelfRunDom.continuationComparablePrompt(changed));
    }

    @Test public void continuationPreparationUsesBoundedIdempotentComposerReplacement() {
        String prompt = "[2026.08.20 | 00:47:11] [SELF_RUN_CONTINUE SR-RETRY]\nCommand Received Record Required";
        String script = SelfRunDom.prepareDriveTurn(URL, prompt, "SR-RETRY:2");

        assertTrue(script.contains("const acceptable=()=>same()||comparable(raw())===comparableExpected"));
        assertTrue(script.contains("selfrun-drive:input:SR-RETRY:2"));
        assertTrue(script.contains("count>0&&now-at<2500"));
        assertTrue(script.contains("nextCount>=3"));
        assertTrue(script.contains("SUBMISSION_PENDING"));
        assertTrue(script.contains("document.execCommand('insertText',false,expected)"));
        assertTrue(script.contains("replaceChildren(document.createTextNode(expected))"));
        assertFalse(script.contains("document.execCommand('delete',false,null)"));
    }

    @Test public void clickReadbackAcceptsTimestampEquivalentContinuation() {
        String prompt = "[2026.08.20 | 00:47:11] [SELF_RUN_CONTINUE SR-RETRY]\nCommand Received Record Required";
        String script = SelfRunDom.clickPreparedDriveTurn(URL, prompt, "SR-RETRY:2");

        assertTrue(script.contains("const acceptable=()=>same()||comparable(raw())===comparableExpected"));
        assertTrue(script.contains("if(!acceptable())return result('SUBMISSION_AMBIGUOUS'"));
    }
}
