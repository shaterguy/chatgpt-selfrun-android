package com.shaterguy.chatgptselfrun;

/** Pure admission policy for WAIT/RESUME Drive polling. */
final class DrivePollAdmission {
    static final long BUSY_RETRY_MS = 250L;
    private DrivePollAdmission() {}

    static long delayMs(boolean eligiblePhase, boolean canRun,
                        boolean driveInFlight, boolean authorizationInFlight) {
        if (!eligiblePhase || !canRun) return -1L;
        return driveInFlight || authorizationInFlight ? BUSY_RETRY_MS : 0L;
    }
}
