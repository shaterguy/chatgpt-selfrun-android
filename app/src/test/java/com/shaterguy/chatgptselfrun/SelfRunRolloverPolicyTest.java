package com.shaterguy.chatgptselfrun;

import android.webkit.WebViewClient;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SelfRunRolloverPolicyTest {
    private static final String CONVERSATION="https://chatgpt.com/c/12345678-1234-1234-1234-123456789abc";
    @Test public void transientErrorsNeverCompleteOrRolloverAsLocalCrash() {
        assertFalse(SelfRunRolloverPolicy.rolloverMainFrameError(CONVERSATION,true,WebViewClient.ERROR_TIMEOUT));
        assertFalse(SelfRunRolloverPolicy.rolloverMainFrameError(CONVERSATION,true,WebViewClient.ERROR_TOO_MANY_REQUESTS));
        assertTrue(SelfRunRolloverPolicy.retryHttpStatus(429));
    }
    @Test public void actualRendererCrashRemainsSeparate() {
        assertTrue(SelfRunRolloverPolicy.rolloverRenderer(CONVERSATION,true));
        assertFalse(SelfRunRolloverPolicy.rolloverRenderer(CONVERSATION,false));
    }
    @Test public void protocolGenerationDisablesNoStartRollover() {
        long start=10_000L,end=start+SelfRunRolloverPolicy.CONTINUATION_NO_START_MAX_WAIT_MS;
        assertEquals(SelfRunRolloverPolicy.NO_START_WAIT,
                SelfRunRolloverPolicy.postDispatchNoStartAction(start,start,end,false,true));
        assertEquals(SelfRunRolloverPolicy.NO_START_ROLLOVER,
                SelfRunRolloverPolicy.postDispatchNoStartAction(start,start,end,false,false));
        assertEquals(SelfRunRolloverPolicy.NO_START_PAUSE_TRANSIENT,
                SelfRunRolloverPolicy.postDispatchNoStartAction(start,start,end,true,false));
        assertFalse(SelfRunRolloverPolicy.postDispatchNoStartTimedOut(start,start,end,true));
    }
    @Test public void continuationSubmissionFailuresRemainBounded() {
        long started=1_000L;
        assertTrue(SelfRunRolloverPolicy.shouldCountContinuationFailure("UNKNOWN",started,7_000L));
        assertFalse(SelfRunRolloverPolicy.shouldCountContinuationFailure(SelfRunContinuationDom.STOP,started,99_000L));
        assertTrue(SelfRunRolloverPolicy.continuationProgressStatus("SUBMISSION_PENDING"));
    }
}
