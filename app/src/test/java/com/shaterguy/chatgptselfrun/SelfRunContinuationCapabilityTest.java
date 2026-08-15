package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelfRunContinuationCapabilityTest {
    @Test public void unsupportedGuardPausesOnlyContinuationPhase() {
        assertTrue(SelfRunContinuationCapability.requiresUserAction(true, false));
        assertFalse(SelfRunContinuationCapability.requiresUserAction(true, true));
        assertFalse(SelfRunContinuationCapability.requiresUserAction(false, false));
    }
}
