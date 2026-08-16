package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import static org.junit.Assert.*;

public class DrivePollAdmissionTest {
    @Test public void readyPollIsAdmittedImmediately() {
        assertEquals(0L, DrivePollAdmission.delayMs(true, true, false, false));
    }

    @Test public void driveInFlightPollIsRequeuedInsteadOfDropped() {
        assertEquals(DrivePollAdmission.BUSY_RETRY_MS,
                DrivePollAdmission.delayMs(true, true, true, false));
    }

    @Test public void authorizationInFlightPollIsRequeuedInsteadOfDropped() {
        assertEquals(DrivePollAdmission.BUSY_RETRY_MS,
                DrivePollAdmission.delayMs(true, true, false, true));
    }

    @Test public void bothBusyConditionsStillUseSingleBoundedRetry() {
        assertEquals(DrivePollAdmission.BUSY_RETRY_MS,
                DrivePollAdmission.delayMs(true, true, true, true));
    }

    @Test public void inactiveOrIneligiblePollingDoesNotRequeue() {
        assertEquals(-1L, DrivePollAdmission.delayMs(false, true, true, false));
        assertEquals(-1L, DrivePollAdmission.delayMs(true, false, true, false));
    }
}
