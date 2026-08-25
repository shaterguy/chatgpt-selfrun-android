package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import android.webkit.WebViewClient;
import static org.junit.Assert.*;

public final class SelfRunRolloverPolicyTest {
    private static final String CONVERSATION="https://chatgpt.com/c/12345678-1234-1234-1234-123456789abc";
    @Test public void transientGlobalWebErrorsNeverRollover() {
        assertFalse(SelfRunRolloverPolicy.rolloverMainFrameError(CONVERSATION,true,WebViewClient.ERROR_HOST_LOOKUP));
        assertFalse(SelfRunRolloverPolicy.rolloverMainFrameError(CONVERSATION,true,WebViewClient.ERROR_CONNECT));
        assertFalse(SelfRunRolloverPolicy.rolloverMainFrameError(CONVERSATION,true,WebViewClient.ERROR_TIMEOUT));
        assertFalse(SelfRunRolloverPolicy.rolloverMainFrameError(CONVERSATION,false,WebViewClient.ERROR_UNKNOWN));
    }
    @Test public void localConversationFailuresRolloverOnlyWithAConversation() {
        assertTrue(SelfRunRolloverPolicy.rolloverMainFrameError(CONVERSATION,true,WebViewClient.ERROR_UNKNOWN));
        assertTrue(SelfRunRolloverPolicy.rolloverHttpStatus(CONVERSATION,true,404));
        assertTrue(SelfRunRolloverPolicy.rolloverHttpStatus(CONVERSATION,true,410));
        assertTrue(SelfRunRolloverPolicy.rolloverRenderer(CONVERSATION,true));
        assertFalse(SelfRunRolloverPolicy.rolloverRenderer(CONVERSATION,false));
        assertFalse(SelfRunRolloverPolicy.rolloverRenderer("https://chatgpt.com/",true));
    }
    @Test public void repeatedContinuationFailuresAreBoundedButTransientStatesGetGrace() {
        long started=1_000L;
        assertFalse(SelfRunRolloverPolicy.shouldCountContinuationFailure("UNKNOWN",started,5_999L));
        assertTrue(SelfRunRolloverPolicy.shouldCountContinuationFailure("UNKNOWN",started,6_000L));
        assertFalse(SelfRunRolloverPolicy.shouldCountContinuationFailure(SelfRunContinuationDom.STOP,started,15_999L));
        assertTrue(SelfRunRolloverPolicy.shouldCountContinuationFailure(SelfRunContinuationDom.STOP,started,16_000L));
        assertTrue(SelfRunRolloverPolicy.hardContinuationFailureStatus("SUBMISSION_FAILED"));
        assertTrue(SelfRunRolloverPolicy.continuationProgressStatus("READY_TO_SUBMIT"));
        assertEquals(SelfRunRolloverPolicy.continuationFailureBucket("UNKNOWN"),
                SelfRunRolloverPolicy.continuationFailureBucket("SUBMISSION_FAILED"));
        assertEquals(SelfRunRolloverPolicy.continuationFailureBucket(SelfRunContinuationDom.STOP),
                SelfRunRolloverPolicy.continuationFailureBucket(SelfRunContinuationDom.SEND_DISABLED));
        assertNotEquals(SelfRunRolloverPolicy.continuationFailureBucket("UNKNOWN"),
                SelfRunRolloverPolicy.continuationFailureBucket(SelfRunContinuationDom.STOP));
    }

    @Test public void lineageCauseSetBlocksSameCauseFromRecurring() {
        String causes=SelfRunRolloverPolicy.appendCause("",SelfRunRolloverPolicy.ROUTE_MISMATCH);
        assertTrue(SelfRunRolloverPolicy.containsCause(causes,SelfRunRolloverPolicy.ROUTE_MISMATCH));
        assertEquals(causes,SelfRunRolloverPolicy.appendCause(causes,SelfRunRolloverPolicy.ROUTE_MISMATCH));
    }
}
