package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class TurnDocumentRetryAndroidTest {
    private static final String ACCOUNT = "acct01";
    private static final String BASE = "BaseFolder12345";
    private static final String JOB = "JobFolder12345";
    private static final String TURN = "TurnDocument12345";
    private static final String JOB2 = "JobFolder67890";
    private static final String TURN2 = "TurnDocument67890";
    private static final String CONVERSATION = "https://chatgpt.com/c/12345678-1234-1234-1234-123456789abc";
    private Context context;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        clearAll();
        awaitMainQueue();
        UserNextInputStore.initialize(context);
        awaitMainQueue();
    }

    @After public void tearDown() {
        clearAll();
        awaitMainQueue();
    }

    @Test public void firstTimeoutRequestsDocumentRetryInsteadOfRollover() {
        SelfRunStore store = eligibleRun();
        String runId = store.runId();
        SelfRunRolloverCoordinator coordinator = new SelfRunRolloverCoordinator(context);

        SelfRunRolloverCoordinator.Result result = coordinator.beginOrResume(
                store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT);

        assertEquals(SelfRunRolloverCoordinator.RESULT_TURN_DOCUMENT_RETRY, result.status);
        assertEquals(runId, result.successorRunId);
        assertEquals(runId, store.runId());
        assertEquals(SelfRunStore.PHASE_SEND_CONTINUE, store.phase());
        assertTrue(SelfRunRolloverCoordinator.turnDocumentRetryPromptPending(runId));
        assertTrue(retryUsed());
        String prompt = SelfRunProtocol.driveContinuation(runId);
        assertTrue(prompt.contains("[SELF_RUN_TURN_DOCUMENT_RETRY " + runId + "]"));
        assertFalse(prompt.contains("[SELF_RUN_CONTINUE " + runId + "]"));
        assertFalse(coordinator.hasPendingClaim());
    }

    @Test public void duplicateTimeoutWhileRetryPendingKeepsSameRunAndRetryPrompt() {
        SelfRunStore store = eligibleRun();
        String runId = store.runId();
        SelfRunRolloverCoordinator coordinator = new SelfRunRolloverCoordinator(context);
        assertEquals(SelfRunRolloverCoordinator.RESULT_TURN_DOCUMENT_RETRY,
                coordinator.beginOrResume(store,
                        SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT).status);
        assertTrue(SelfRunRolloverCoordinator.turnDocumentRetryPromptPending(runId));

        SelfRunRolloverCoordinator.Result duplicate = coordinator.beginOrResume(
                store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT);

        assertEquals(SelfRunRolloverCoordinator.RESULT_TURN_DOCUMENT_RETRY, duplicate.status);
        assertEquals(runId, duplicate.successorRunId);
        assertEquals(runId, store.runId());
        assertEquals(SelfRunStore.PHASE_SEND_CONTINUE, store.phase());
        assertTrue(SelfRunRolloverCoordinator.turnDocumentRetryPromptPending(runId));
        assertTrue(retryUsed());
        assertFalse(coordinator.hasPendingClaim());
        String prompt = SelfRunProtocol.driveContinuation(runId);
        assertTrue(prompt.contains("[SELF_RUN_TURN_DOCUMENT_RETRY " + runId + "]"));
        assertFalse(prompt.contains("[SELF_RUN_CONTINUE " + runId + "]"));
    }

    @Test public void pendingRetrySurvivesRecreationUntilSubmissionIsConfirmed() {
        SelfRunStore store = eligibleRun();
        String runId = store.runId();
        SelfRunRolloverCoordinator coordinator = new SelfRunRolloverCoordinator(context);
        assertEquals(SelfRunRolloverCoordinator.RESULT_TURN_DOCUMENT_RETRY,
                coordinator.beginOrResume(store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT).status);

        coordinator = new SelfRunRolloverCoordinator(context);
        assertTrue(SelfRunRolloverCoordinator.turnDocumentRetryPromptPending(runId));
        assertTrue(SelfRunProtocol.driveContinuation(runId).contains("SELF_RUN_TURN_DOCUMENT_RETRY"));

        String observer = "retryobserver";
        store.prepareTurnProtocolToken(observer);
        store.beginTurnCompletionWait(observer, "문서 재생성 요청 제출 확인");
        coordinator = new SelfRunRolloverCoordinator(context);

        assertFalse(SelfRunRolloverCoordinator.turnDocumentRetryPromptPending(runId));
        assertTrue(retryUsed());
        store.setPhase(SelfRunStore.PHASE_SEND_CONTINUE);
        String next = SelfRunProtocol.driveContinuation(runId);
        assertTrue(next.contains("[SELF_RUN_CONTINUE " + runId + "]"));
        assertFalse(next.contains("SELF_RUN_TURN_DOCUMENT_RETRY"));
        assertTrue(retryUsed());
    }

    @Test public void transportRetryDoesNotConsumeLateUserNextInput() {
        SelfRunStore store = eligibleRun();
        String runId = store.runId();
        assertTrue(UserNextInputStore.save(runId, "late user next input"));
        SelfRunRolloverCoordinator coordinator = new SelfRunRolloverCoordinator(context);
        assertEquals(SelfRunRolloverCoordinator.RESULT_TURN_DOCUMENT_RETRY,
                coordinator.beginOrResume(store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT).status);

        assertFalse(UserNextInputStore.managesContinuation(runId));
        assertEquals("late user next input", UserNextInputStore.current(runId));

        String observer = "retryobserver";
        store.prepareTurnProtocolToken(observer);
        store.beginTurnCompletionWait(observer, "문서 재생성 요청 제출 확인");
        assertEquals("late user next input", UserNextInputStore.current(runId));

        store.setPhase(SelfRunStore.PHASE_SEND_CONTINUE);
        awaitMainQueue();
        String normal = SelfRunProtocol.driveContinuation(runId);
        assertTrue(normal.contains("[SELF_RUN_CONTINUE " + runId + "]"));
        assertTrue(normal.endsWith("late user next input"));
    }

    @Test public void secondTimeoutWithoutValidCompletionRollsOver() {
        SelfRunStore store = eligibleRun();
        String predecessor = store.runId();
        SelfRunRolloverCoordinator coordinator = new SelfRunRolloverCoordinator(context);
        assertEquals(SelfRunRolloverCoordinator.RESULT_TURN_DOCUMENT_RETRY,
                coordinator.beginOrResume(store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT).status);

        submitRetryAndEnterPostProtocol(store, "retryobserver");
        assertTrue(retryUsed());

        SelfRunRolloverCoordinator.Result rollover = coordinator.beginOrResume(
                store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT);
        assertEquals(SelfRunRolloverCoordinator.RESULT_STARTED, rollover.status);
        assertNotEquals(predecessor, rollover.successorRunId);
        assertEquals(rollover.successorRunId, store.runId());
    }

    @Test public void secondTimeoutRollsOverAndSuccessorGetsFreshRetryBudget() {
        SelfRunStore store = eligibleRun();
        String predecessor = store.runId();
        SelfRunRolloverCoordinator coordinator = new SelfRunRolloverCoordinator(context);
        assertEquals(SelfRunRolloverCoordinator.RESULT_TURN_DOCUMENT_RETRY,
                coordinator.beginOrResume(store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT).status);

        submitRetryAndEnterPostProtocol(store, "retryobserver");
        SelfRunRolloverCoordinator.Result rollover = coordinator.beginOrResume(
                store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT);
        assertEquals(SelfRunRolloverCoordinator.RESULT_STARTED, rollover.status);
        assertNotEquals(predecessor, rollover.successorRunId);
        assertEquals(rollover.successorRunId, store.runId());

        store.saveJobFolder(JOB2);
        store.saveTurnDocument(TURN2, "https://docs.google.com/document/d/" + TURN2 + "/edit");
        store.captureConversationUrl(CONVERSATION);
        String successor = store.runId();
        SelfRunRolloverCoordinator.Result retry = coordinator.beginOrResume(
                store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT);

        assertEquals(SelfRunRolloverCoordinator.RESULT_TURN_DOCUMENT_RETRY, retry.status);
        assertEquals(successor, retry.successorRunId);
        assertEquals(successor, store.runId());
        assertTrue(SelfRunRolloverCoordinator.turnDocumentRetryPromptPending(successor));
    }

    @Test public void consumedChatCompletionRestoresSameRunRetryBudgetForNextCycle() {
        SelfRunStore store = eligibleRun();
        String runId = store.runId();
        SelfRunRolloverCoordinator coordinator = useRetryAndEnterPostProtocol(store, "retryobserver");
        assertTrue(retryUsed());

        consumeValidCompletion(store, 1);

        assertEquals(SelfRunStore.PHASE_SEND_CONTINUE, store.phase());
        assertFalse(retryUsed());
        assertEquals("", retryOwner());

        beginNextCompletionCycle(store, "nextobserver");
        SelfRunRolloverCoordinator.Result nextMiss = coordinator.beginOrResume(
                store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT);
        assertEquals(SelfRunRolloverCoordinator.RESULT_TURN_DOCUMENT_RETRY, nextMiss.status);
        assertEquals(runId, nextMiss.successorRunId);
        assertEquals(runId, store.runId());
    }

    @Test public void nextCycleStillAllowsOnlyOneRetryBeforeRollover() {
        SelfRunStore store = eligibleRun();
        String runId = store.runId();
        SelfRunRolloverCoordinator coordinator = useRetryAndEnterPostProtocol(store, "retryobserver");
        consumeValidCompletion(store, 1);
        beginNextCompletionCycle(store, "nextobserver");

        assertEquals(SelfRunRolloverCoordinator.RESULT_TURN_DOCUMENT_RETRY,
                coordinator.beginOrResume(store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT).status);
        assertEquals(runId, store.runId());
        submitRetryAndEnterPostProtocol(store, "secondretryobserver");

        SelfRunRolloverCoordinator.Result rollover = coordinator.beginOrResume(
                store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT);
        assertEquals(SelfRunRolloverCoordinator.RESULT_STARTED, rollover.status);
        assertNotEquals(runId, rollover.successorRunId);
    }

    @Test public void usedBudgetSurvivesCoordinatorRecreationWithoutValidCompletion() {
        SelfRunStore store = eligibleRun();
        String runId = store.runId();
        useRetryAndEnterPostProtocol(store, "retryobserver");
        assertTrue(retryUsed());

        SelfRunRolloverCoordinator recreated = new SelfRunRolloverCoordinator(context);
        assertTrue(retryUsed());
        SelfRunRolloverCoordinator.Result secondMiss = recreated.beginOrResume(
                new SelfRunStore(context), SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT);
        assertEquals(SelfRunRolloverCoordinator.RESULT_STARTED, secondMiss.status);
        assertNotEquals(runId, secondMiss.successorRunId);
    }

    @Test public void restoredBudgetSurvivesCoordinatorRecreation() {
        SelfRunStore store = eligibleRun();
        String runId = store.runId();
        useRetryAndEnterPostProtocol(store, "retryobserver");
        consumeValidCompletion(store, 1);
        assertFalse(retryUsed());

        SelfRunRolloverCoordinator recreated = new SelfRunRolloverCoordinator(context);
        store = new SelfRunStore(context);
        assertEquals(runId, store.runId());
        assertFalse(retryUsed());
        beginNextCompletionCycle(store, "nextobserver");

        SelfRunRolloverCoordinator.Result nextMiss = recreated.beginOrResume(
                store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT);
        assertEquals(SelfRunRolloverCoordinator.RESULT_TURN_DOCUMENT_RETRY, nextMiss.status);
        assertEquals(runId, nextMiss.successorRunId);
    }

    @Test public void consumedWorkCompletionRestoresSameRunRetryBudget() {
        SelfRunStore store = eligibleWorkRun();
        String runId = store.runId();
        SelfRunRolloverCoordinator coordinator = useRetryAndEnterPostProtocol(store, "workretryobserver");
        assertTrue(retryUsed());

        consumeValidCompletion(store, 1);

        assertEquals(SelfRunStore.PHASE_APPLY_PREFS, store.phase());
        assertFalse(retryUsed());
        coordinator = new SelfRunRolloverCoordinator(context);
        store = new SelfRunStore(context);
        beginNextCompletionCycle(store, "worknextobserver");
        SelfRunRolloverCoordinator.Result nextMiss = coordinator.beginOrResume(
                store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT);
        assertEquals(SelfRunRolloverCoordinator.RESULT_TURN_DOCUMENT_RETRY, nextMiss.status);
        assertEquals(runId, nextMiss.successorRunId);
    }

    @Test public void malformedCompletionDoesNotRestoreRetryBudget() {
        SelfRunStore store = eligibleRun();
        String runId = store.runId();
        SelfRunRolloverCoordinator coordinator = useRetryAndEnterPostProtocol(store, "retryobserver");
        String raw = "[2026.09.04 | 00:10:00] [SELF_RUN_TURN_COMPLETED " + runId
                + " NEXT_INPUT_B64URL=INVALID]";
        DriveSignalParser.Event malformed = new DriveSignalParser.Event(
                DriveSignalParser.Type.TURN_COMPLETED, "2026.09.04 | 00:10:00", raw, 1,
                false, "", "NEXT_INPUT_INVALID");

        store.applyDriveSignals(Collections.singletonList(malformed), System.currentTimeMillis());

        assertEquals(SelfRunStore.PHASE_POST_PROTOCOL_DRIVE_SYNC, store.phase());
        assertTrue(retryUsed());
        coordinator = new SelfRunRolloverCoordinator(context);
        SelfRunRolloverCoordinator.Result secondMiss = coordinator.beginOrResume(
                new SelfRunStore(context), SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT);
        assertEquals(SelfRunRolloverCoordinator.RESULT_STARTED, secondMiss.status);
        assertNotEquals(runId, secondMiss.successorRunId);
    }

    @Test public void nonTimeoutRolloverDoesNotSpendDocumentRetryBudget() {
        SelfRunStore store = eligibleRun();
        SelfRunRolloverCoordinator coordinator = new SelfRunRolloverCoordinator(context);
        SelfRunRolloverCoordinator.Result result = coordinator.beginOrResume(
                store, SelfRunRolloverPolicy.ROUTE_MISMATCH);
        assertEquals(SelfRunRolloverCoordinator.RESULT_STARTED, result.status);
        assertFalse(SelfRunRolloverCoordinator.turnDocumentRetryPromptPending(result.successorRunId));
    }

    private SelfRunRolloverCoordinator useRetryAndEnterPostProtocol(SelfRunStore store, String observer) {
        SelfRunRolloverCoordinator coordinator = new SelfRunRolloverCoordinator(context);
        assertEquals(SelfRunRolloverCoordinator.RESULT_TURN_DOCUMENT_RETRY,
                coordinator.beginOrResume(store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT).status);
        submitRetryAndEnterPostProtocol(store, observer);
        return coordinator;
    }

    private void submitRetryAndEnterPostProtocol(SelfRunStore store, String observer) {
        store.prepareTurnProtocolToken(observer);
        store.beginTurnCompletionWait(observer, "문서 재생성 요청 제출 확인");
        assertFalse(SelfRunRolloverCoordinator.turnDocumentRetryPromptPending(store.runId()));
        assertTrue(retryUsed());
        store.setPhase(SelfRunStore.PHASE_POST_PROTOCOL_DRIVE_SYNC);
    }

    private void beginNextCompletionCycle(SelfRunStore store, String observer) {
        store.prepareTurnProtocolToken(observer);
        store.beginTurnCompletionWait(observer, "다음 턴 제출 확인 · 답변 완료 감지 중");
        store.setPhase(SelfRunStore.PHASE_POST_PROTOCOL_DRIVE_SYNC);
    }

    private void consumeValidCompletion(SelfRunStore store, int cursor) {
        String timestamp = "2026.09.04 | 00:10:00";
        String raw = SelfRunStore.MODE_WORK.equals(store.mode())
                ? "[" + timestamp + "] [SELF_RUN_TURN_COMPLETED " + store.runId()
                    + " MODEL=sol REASONING=xhigh]"
                : "[" + timestamp + "] [SELF_RUN_TURN_COMPLETED " + store.runId() + "]";
        DriveSignalParser.Event completion = new DriveSignalParser.Event(
                DriveSignalParser.Type.TURN_COMPLETED, timestamp, raw, cursor,
                false, "", "");
        store.applyDriveSignals(Collections.singletonList(completion), System.currentTimeMillis());
        awaitMainQueue();
    }

    private SelfRunStore eligibleRun() {
        SelfRunStore store = new SelfRunStore(context);
        store.bindBaseFolder(ACCOUNT, BASE, "Runs", "https://drive.google.com/drive/folders/" + BASE,
                System.currentTimeMillis());
        String runId = SelfRunRunId.create();
        assertTrue(ChatReasoningPreferenceStore.save(context, runId, ChatReasoningPreferenceStore.KEEP));
        assertTrue(ChatPickerStateStore.saveObserved(context, runId, ChatReasoningPreferenceStore.EXTRA_HIGH));
        assertTrue(SelfRunSignalTransport.mark(context, runId));
        store.start(runId, SelfRunStore.MODE_CHAT, SelfRunScript.GENERAL_CHAT_URL,
                "original requirement", Collections.emptyList());
        prepareEligibleDriveState(store);
        return store;
    }

    private SelfRunStore eligibleWorkRun() {
        SelfRunStore store = new SelfRunStore(context);
        store.bindBaseFolder(ACCOUNT, BASE, "Runs", "https://drive.google.com/drive/folders/" + BASE,
                System.currentTimeMillis());
        String runId = SelfRunRunId.create();
        assertTrue(SelfRunSignalTransport.mark(context, runId));
        store.startWork(runId, SelfRunScript.GENERAL_CHAT_URL, "original requirement",
                Collections.emptyList(), "sol", "xhigh");
        prepareEligibleDriveState(store);
        return store;
    }

    private void prepareEligibleDriveState(SelfRunStore store) {
        store.saveJobFolder(JOB);
        store.saveTurnDocument(TURN, "https://docs.google.com/document/d/" + TURN + "/edit");
        store.captureConversationUrl(CONVERSATION);
        assertEquals(CONVERSATION, store.conversationUrl());
    }

    private SharedPreferences retryPrefs() {
        return context.getSharedPreferences("selfrun_drive_rollover", Context.MODE_PRIVATE);
    }

    private boolean retryUsed() {
        return retryPrefs().getBoolean("turnDocumentRetryUsed", false);
    }

    private String retryOwner() {
        return retryPrefs().getString("turnDocumentRetryOwner", "");
    }

    private void awaitMainQueue() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void clearAll() {
        for (String name : new String[]{"selfrun_drive", "selfrun_drive_rollover", "selfrun_drive_signal_transport",
                "selfrun_drive_chat_reasoning", "selfrun_drive_bootstrap_runs", "selfrun_drive_chat_picker_state",
                "selfrun_drive_history", "selfrun_drive_user_next_input"}) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
        }
    }
}
