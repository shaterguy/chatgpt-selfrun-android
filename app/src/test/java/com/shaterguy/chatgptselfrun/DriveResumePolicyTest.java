package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class DriveResumePolicyTest {
    @Test public void actualChoiceCompletionRequiresNextInput() {
        assertEquals(DriveResumePolicy.Action.APPLY_COMPLETION,
                DriveResumePolicy.decide(DriveResumePolicy.Origin.AI_USER_ACTION_REQUIRED, 3, 4,
                        Collections.singletonList(event(DriveSignalParser.Type.TURN_COMPLETED, 4, true))).action);
        DriveResumePolicy.Decision missing = DriveResumePolicy.decide(
                DriveResumePolicy.Origin.AI_USER_ACTION_REQUIRED, 3, 4,
                Collections.singletonList(event(DriveSignalParser.Type.TURN_COMPLETED, 4, false)));
        assertEquals(DriveResumePolicy.Action.PROTOCOL_ERROR, missing.action);
        assertEquals("USER_CHOICE_NEXT_INPUT_REQUIRED", missing.reason);
    }

    @Test public void externalManualActionWithoutNewCompletionUsesPlainContinue() {
        DriveResumePolicy.Decision decision = DriveResumePolicy.decide(
                DriveResumePolicy.Origin.EXTERNAL_MANUAL, 3, 3, Collections.emptyList());
        assertEquals(DriveResumePolicy.Action.CONTINUE, decision.action);
        assertEquals("EXTERNAL_MANUAL_ACTION_COMPLETE", decision.reason);
    }

    @Test public void drivePausedSignalMapsToExternalManualAndResumesPlainContinue() {
        String origin = SelfRunStore.pauseOriginForDriveSignal(DriveSignalParser.Type.PAUSED);
        assertEquals(SelfRunStore.PAUSE_ORIGIN_EXTERNAL_MANUAL, origin);
        DriveResumePolicy.Decision decision = DriveResumePolicy.decide(
                DriveResumePolicy.parseOrigin(origin), 3, 3, Collections.emptyList());
        assertEquals(DriveResumePolicy.Action.CONTINUE, decision.action);
        assertEquals("EXTERNAL_MANUAL_ACTION_COMPLETE", decision.reason);
        assertEquals(SelfRunStore.PAUSE_ORIGIN_AI_USER_ACTION_REQUIRED,
                SelfRunStore.pauseOriginForDriveSignal(DriveSignalParser.Type.USER_ACTION_REQUIRED));
    }

    @Test public void localPrerequisitePauseRestoresWithoutDriveButDrivePausedStillReconciles() {
        assertTrue(SelfRunStore.restorePauseWithoutDrive(SelfRunStore.PAUSE_ORIGIN_EXTERNAL_MANUAL, false, false));
        assertTrue(SelfRunStore.restorePauseWithoutDrive(SelfRunStore.PAUSE_ORIGIN_EXTERNAL_MANUAL, false, true));
        assertFalse(SelfRunStore.restorePauseWithoutDrive(SelfRunStore.PAUSE_ORIGIN_EXTERNAL_MANUAL, true, true));
        assertTrue(SelfRunStore.restorePauseWithoutDrive(SelfRunStore.PAUSE_ORIGIN_UI_MANUAL, false, false));
        assertFalse(SelfRunStore.restorePauseWithoutDrive(SelfRunStore.PAUSE_ORIGIN_UI_MANUAL, false, true));
    }

    @Test public void aiUserActionAndAiPauseRemainLatchedWithoutResumeCompletion() {
        DriveResumePolicy.Decision userAction = DriveResumePolicy.decide(
                DriveResumePolicy.Origin.AI_USER_ACTION_REQUIRED, 3, 3, Collections.emptyList());
        DriveResumePolicy.Decision aiPause = DriveResumePolicy.decide(
                DriveResumePolicy.Origin.AI_PAUSED, 3, 3, Collections.emptyList());
        assertEquals(DriveResumePolicy.Action.KEEP_PAUSED, userAction.action);
        assertEquals("USER_ACTION_RESUME_PREPARATION_REQUIRED", userAction.reason);
        assertEquals(DriveResumePolicy.Action.KEEP_PAUSED, aiPause.action);
        assertEquals("AI_PAUSE_REMAINS_LATCHED", aiPause.reason);
    }

    @Test public void uiManualPauseWithoutNewSignalRestoresPriorPhase() {
        DriveResumePolicy.Decision decision = DriveResumePolicy.decide(
                DriveResumePolicy.Origin.UI_MANUAL, 7, 7, Collections.emptyList());
        assertEquals(DriveResumePolicy.Action.RESTORE_PHASE, decision.action);
    }

    @Test public void uiPauseProcessesCompletionWrittenWhilePaused() {
        DriveResumePolicy.Decision decision = DriveResumePolicy.decide(
                DriveResumePolicy.Origin.UI_MANUAL, 7, 8,
                Collections.singletonList(event(DriveSignalParser.Type.TURN_COMPLETED, 8, true)));
        assertEquals(DriveResumePolicy.Action.APPLY_COMPLETION, decision.action);
    }

    @Test public void ackAfterCompletionDoesNotHideMaterialCompletion() {
        DriveSignalParser.Event completion = event(DriveSignalParser.Type.TURN_COMPLETED, 8, true);
        DriveSignalParser.Event ack = event(DriveSignalParser.Type.COMMAND_RECEIVED, 9, false);
        DriveResumePolicy.Decision decision = DriveResumePolicy.decide(
                DriveResumePolicy.Origin.UI_MANUAL, 7, 9, Arrays.asList(completion, ack));
        assertEquals(DriveResumePolicy.Action.APPLY_COMPLETION, decision.action);
        assertSame(completion, decision.event);
    }

    @Test public void newerBlockingAndDonePreventContinue() {
        assertEquals(DriveResumePolicy.Action.KEEP_PAUSED,
                DriveResumePolicy.decide(DriveResumePolicy.Origin.UI_MANUAL, 3, 4,
                        Collections.singletonList(event(DriveSignalParser.Type.USER_ACTION_REQUIRED, 4, false))).action);
        assertEquals(DriveResumePolicy.Action.DONE,
                DriveResumePolicy.decide(DriveResumePolicy.Origin.UI_MANUAL, 3, 4,
                        Collections.singletonList(event(DriveSignalParser.Type.DONE, 4, false))).action);
    }

    @Test public void invalidAnchorOrInvalidPostAnchorFailsClosed() {
        assertEquals(DriveResumePolicy.Action.PROTOCOL_ERROR,
                DriveResumePolicy.decide(DriveResumePolicy.Origin.UI_MANUAL, 6, 5,
                        Collections.emptyList()).action);
        DriveSignalParser.Event invalid = new DriveSignalParser.Event(
                DriveSignalParser.Type.INVALID, "2026.08.16 | 00:00:00", "[x]", 4,
                false, "", "", "NEXT_INPUT_UTF8_INVALID");
        assertEquals(DriveResumePolicy.Action.PROTOCOL_ERROR,
                DriveResumePolicy.decide(DriveResumePolicy.Origin.UI_MANUAL, 3, 4,
                        Collections.singletonList(invalid)).action);
    }

    private static DriveSignalParser.Event event(DriveSignalParser.Type type, int cursor, boolean next) {
        return new DriveSignalParser.Event(type, "2026.08.16 | 00:00:00",
                "[x]", cursor, next, next ? "계속해" : "", next ? "fp" : "", "");
    }
}
