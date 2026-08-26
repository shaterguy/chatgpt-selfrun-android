package com.shaterguy.chatgptselfrun;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

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
    }

    @After public void tearDown() { clearAll(); }

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
        String prompt = SelfRunProtocol.driveContinuation(runId);
        assertTrue(prompt.contains("[SELF_RUN_TURN_DOCUMENT_RETRY " + runId + "]"));
        assertFalse(prompt.contains("[SELF_RUN_CONTINUE " + runId + "]"));
        assertFalse(coordinator.hasPendingClaim());
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
        store.prepareTurnObserver(observer);
        store.beginTurnCompletionWait(observer, "문서 재생성 요청 제출 확인");
        coordinator = new SelfRunRolloverCoordinator(context);

        assertFalse(SelfRunRolloverCoordinator.turnDocumentRetryPromptPending(runId));
        String next = SelfRunProtocol.driveContinuation(runId);
        assertTrue(next.contains("[SELF_RUN_CONTINUE " + runId + "]"));
        assertFalse(next.contains("SELF_RUN_TURN_DOCUMENT_RETRY"));
    }

    @Test public void secondTimeoutRollsOverAndSuccessorGetsFreshRetryBudget() {
        SelfRunStore store = eligibleRun();
        String predecessor = store.runId();
        SelfRunRolloverCoordinator coordinator = new SelfRunRolloverCoordinator(context);
        assertEquals(SelfRunRolloverCoordinator.RESULT_TURN_DOCUMENT_RETRY,
                coordinator.beginOrResume(store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT).status);

        String observer = "retryobserver";
        store.prepareTurnObserver(observer);
        store.beginTurnCompletionWait(observer, "문서 재생성 요청 제출 확인");
        store.setPhase(SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC);
        assertFalse(SelfRunRolloverCoordinator.turnDocumentRetryPromptPending(predecessor));

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

    @Test public void nonTimeoutRolloverDoesNotSpendDocumentRetryBudget() {
        SelfRunStore store = eligibleRun();
        SelfRunRolloverCoordinator coordinator = new SelfRunRolloverCoordinator(context);
        SelfRunRolloverCoordinator.Result result = coordinator.beginOrResume(
                store, SelfRunRolloverPolicy.ROUTE_MISMATCH);
        assertEquals(SelfRunRolloverCoordinator.RESULT_STARTED, result.status);
        assertFalse(SelfRunRolloverCoordinator.turnDocumentRetryPromptPending(result.successorRunId));
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
        store.saveJobFolder(JOB);
        store.saveTurnDocument(TURN, "https://docs.google.com/document/d/" + TURN + "/edit");
        store.captureConversationUrl(CONVERSATION);
        assertEquals(CONVERSATION, store.conversationUrl());
        return store;
    }

    private void clearAll() {
        for (String name : new String[]{"selfrun_drive", "selfrun_drive_rollover", "selfrun_drive_signal_transport",
                "selfrun_drive_chat_reasoning", "selfrun_drive_bootstrap_runs", "selfrun_drive_chat_picker_state",
                "selfrun_drive_history", "selfrun_drive_user_next_input"}) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
        }
    }
}
