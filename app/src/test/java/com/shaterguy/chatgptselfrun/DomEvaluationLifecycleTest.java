package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DomEvaluationLifecycleTest {
    @Test
    public void staleObserverEpochCallbackCanReleaseEvaluationItOwns() {
        DomEvaluationLifecycle lifecycle = new DomEvaluationLifecycle();
        long token = lifecycle.begin();
        assertTrue(lifecycle.inFlight());
        assertTrue(lifecycle.release(token));
        assertFalse(lifecycle.inFlight());
    }

    @Test
    public void callbackFromInvalidatedExecutionCannotClearNewerEvaluation() {
        DomEvaluationLifecycle lifecycle = new DomEvaluationLifecycle();
        long oldToken = lifecycle.begin();
        lifecycle.invalidate();
        long newToken = lifecycle.begin();
        assertTrue(lifecycle.inFlight());
        assertFalse(lifecycle.release(oldToken));
        assertTrue("old callback must not clear newer evaluation", lifecycle.inFlight());
        assertTrue(lifecycle.release(newToken));
        assertFalse(lifecycle.inFlight());
    }

    @Test
    public void pauseOrNavigationInvalidationMakesOldCallbackHarmless() {
        DomEvaluationLifecycle lifecycle = new DomEvaluationLifecycle();
        long token = lifecycle.begin();
        lifecycle.invalidate();
        assertFalse(lifecycle.inFlight());
        assertFalse(lifecycle.release(token));
        assertFalse(lifecycle.inFlight());
    }
}
