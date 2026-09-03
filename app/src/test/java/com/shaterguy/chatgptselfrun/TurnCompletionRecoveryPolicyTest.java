package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public final class TurnCompletionRecoveryPolicyTest {
    private static final String JOB = "SR-20260903-120000-ABC123";

    @Test public void normalCompletionWithoutBlockingSignalIsUsable() {
        DriveSignalParser.Event completion = new DriveSignalParser.Event(
                DriveSignalParser.Type.TURN_COMPLETED, "2026.09.03 | 12:00:01",
                "[2026.09.03 | 12:00:01] [SELF_RUN_TURN_COMPLETED " + JOB + "]", 1);
        DriveSignalParser.Scan scan = new DriveSignalParser.Scan(
                Collections.singletonList(completion), 1, completion, completion, false);
        assertTrue(TurnCompletionRecoveryPolicy.hasUsableDriveCompletion(scan));
    }

    @Test public void newerBlockingSignalPreventsRecoveryCompletion() {
        DriveSignalParser.Event completion = new DriveSignalParser.Event(
                DriveSignalParser.Type.TURN_COMPLETED, "2026.09.03 | 12:00:01",
                "[2026.09.03 | 12:00:01] [SELF_RUN_TURN_COMPLETED " + JOB + "]", 1);
        DriveSignalParser.Event blocking = new DriveSignalParser.Event(
                DriveSignalParser.Type.USER_ACTION_REQUIRED, "2026.09.03 | 12:00:02",
                "[2026.09.03 | 12:00:02] [SELF_RUN_USER_ACTION_REQUIRED " + JOB + "]", 2);
        DriveSignalParser.Scan scan = new DriveSignalParser.Scan(
                Arrays.asList(completion, blocking), 2, blocking, blocking, false);
        assertFalse(TurnCompletionRecoveryPolicy.hasUsableDriveCompletion(scan));
    }

    @Test public void recoveryIdCompletionDoesNotCountAsNormalTurnCompletion() {
        DriveSignalParser.Event recovery = new DriveSignalParser.Event(
                DriveSignalParser.Type.TURN_COMPLETED, "2026.09.03 | 12:00:01",
                "[2026.09.03 | 12:00:01] [SELF_RUN_TURN_COMPLETED " + JOB + " RECOVERY_ID=R123]", 1);
        DriveSignalParser.Scan scan = new DriveSignalParser.Scan(
                Collections.singletonList(recovery), 1, recovery, recovery, false);
        assertFalse(TurnCompletionRecoveryPolicy.hasUsableDriveCompletion(scan));
    }

    @Test public void rebasedCursorIsNeverTrustedByOneShotProbe() {
        DriveSignalParser.Event completion = new DriveSignalParser.Event(
                DriveSignalParser.Type.TURN_COMPLETED, "2026.09.03 | 12:00:01",
                "[2026.09.03 | 12:00:01] [SELF_RUN_TURN_COMPLETED " + JOB + "]", 1);
        DriveSignalParser.Scan scan = new DriveSignalParser.Scan(
                Collections.singletonList(completion), 1, completion, completion, true);
        assertFalse(TurnCompletionRecoveryPolicy.hasUsableDriveCompletion(scan));
    }

    @Test public void watchdogHostsAreDedicatedAndDoNotAliasCompletion() {
        assertEquals("turn-watchdog-rebind", TurnCompletionRecoveryPolicy.REBIND_HOST);
        assertEquals("turn-watchdog-probe", TurnCompletionRecoveryPolicy.DRIVE_PROBE_HOST);
        assertEquals("turn-watchdog-recover", TurnCompletionRecoveryPolicy.RECOVER_HOST);
        assertNotEquals(SelfRunContinuationDom.TURN_COMPLETION_HOST, TurnCompletionRecoveryPolicy.DRIVE_PROBE_HOST);
    }
}
