package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SelfRunDriveVersionPollingPolicyTest {
    @Test public void unchangedPostDomVersionSkipsOnlyTheDocumentSnapshot() {
        assertFalse(SelfRunService.shouldReadDriveSnapshot(true, false, "17", "17"));
    }

    @Test public void changedOrUnknownPostDomVersionReadsTheDocumentSnapshot() {
        assertTrue(SelfRunService.shouldReadDriveSnapshot(true, false, "17", "18"));
        assertTrue(SelfRunService.shouldReadDriveSnapshot(true, false, "", "17"));
        assertTrue(SelfRunService.shouldReadDriveSnapshot(true, false, "17", ""));
    }

    @Test public void resumeBaselineAlwaysReadsTheDocumentSnapshot() {
        assertTrue(SelfRunService.shouldReadDriveSnapshot(false, true, "17", "17"));
    }
}
