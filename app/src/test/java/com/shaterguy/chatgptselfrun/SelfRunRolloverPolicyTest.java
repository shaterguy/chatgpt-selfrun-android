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
        assertFalse(SelfRunRolloverPolicy.shouldCountContinuationFailure("UNKNOWN",started,7_000L));
        assertFalse(SelfRunRolloverPolicy.shouldCountContinuationFailure(SelfRunContinuationDom.STOP,started,99_000L));
        assertTrue(SelfRunRolloverPolicy.continuationProgressStatus("SUBMISSION_PENDING"));
    }
    @Test public void readinessStatesDoNotSpendHardFailureBudgetBeforeTheirBoundedDeadline() {
        long start=500_000L;
        assertFalse(SelfRunRolloverPolicy.hardContinuationFailureStatus(SelfRunContinuationDom.UNKNOWN));
        assertTrue(SelfRunRolloverPolicy.continuationReadinessStatus(SelfRunContinuationDom.COMPOSER_UNAVAILABLE));
        assertTrue(SelfRunRolloverPolicy.continuationReadinessStatus(SelfRunContinuationDom.COMPOSER_NOT_EDITABLE));
        assertEquals(1,SelfRunRolloverPolicy.continuationReadinessProgress("COMPOSER_CLEARING"));
        assertEquals(2,SelfRunRolloverPolicy.continuationReadinessProgress("COMPOSER_INPUTTING"));
        assertFalse(SelfRunRolloverPolicy.continuationReadinessDeadlineExpired(start,
                start+SelfRunRolloverPolicy.CONTINUATION_READINESS_MAX_WAIT_MS-1L));
        assertTrue(SelfRunRolloverPolicy.continuationReadinessDeadlineExpired(start,
                start+SelfRunRolloverPolicy.CONTINUATION_READINESS_MAX_WAIT_MS));
    }
    @Test public void reconnectGraceStartsFromActualOutputReattach() {
        long phaseStarted=1_000L,wallNow=99_000L,reconnectStarted=500_000L;
        assertFalse(SelfRunRolloverPolicy.shouldCountContinuationFailure(SelfRunContinuationDom.UNKNOWN,
                phaseStarted,wallNow,reconnectStarted,reconnectStarted+4_999L));
        assertFalse(SelfRunRolloverPolicy.shouldCountContinuationFailure(SelfRunContinuationDom.UNKNOWN,
                phaseStarted,wallNow,reconnectStarted,reconnectStarted+5_000L));
        assertFalse(SelfRunRolloverPolicy.shouldCountContinuationFailure(SelfRunContinuationDom.SEND_DISABLED,
                phaseStarted,wallNow,reconnectStarted,reconnectStarted+4_999L));
        assertFalse(SelfRunRolloverPolicy.shouldCountContinuationFailure(SelfRunContinuationDom.SEND_DISABLED,
                phaseStarted,wallNow,reconnectStarted,reconnectStarted+5_000L));
    }
}
