package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SelfRunRolloverCoordinatorAndroidTest {
    private static final String ACCOUNT = "acct01";
    private static final String BASE = "BaseFolder12345";
    private static final String JOB = "JobFolder12345";
    private static final String TURN = "TurnDocument12345";
    private static final String CONVERSATION = "https://chatgpt.com/c/12345678-1234-1234-1234-123456789abc";
    private Context context;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        clearAll();
    }

    @After public void tearDown() { clearAll(); }

    @Test public void freshClaimStartsOneSuccessorAndPreservesExplicitChatPickerState() throws Exception {
        SelfRunStore store = predecessor();
        String predecessor = store.runId();
        SelfRunRolloverCoordinator coordinator = new SelfRunRolloverCoordinator(context);
        SelfRunRolloverCoordinator.Result first = coordinator.beginOrResume(store, SelfRunRolloverPolicy.ROUTE_MISMATCH);
        assertEquals(SelfRunRolloverCoordinator.RESULT_STARTED, first.status);
        assertFalse(first.successorRunId.isEmpty());
        assertNotEquals(predecessor, first.successorRunId);
        assertEquals(first.successorRunId, store.runId());
        assertEquals("", store.conversationUrl());
        assertEquals(ChatReasoningPreferenceStore.EXTRA_HIGH,
                ChatReasoningPreferenceStore.selectionForRun(context, first.successorRunId));
        JSONObject history = new SelfRunHistoryStore(context).get(predecessor);
        assertNotNull(history);
        assertEquals(SelfRunRolloverCoordinator.PHASE_ROLLED_OVER, history.optString("phase"));
        assertTrue(history.optBoolean("terminal"));
        String once = store.runId();
        coordinator.beginOrResume(store, SelfRunRolloverPolicy.ROUTE_MISMATCH);
        assertEquals(once, store.runId());
        assertFalse(coordinator.hasPendingClaim());
    }

    @Test public void processRecreationUsesReservedSuccessorIdAndClearsClaim() throws Exception {
        SelfRunStore store = predecessor();
        String predecessor = store.runId();
        String successor = SelfRunRunId.create();
        writeClaim(store, successor, false);
        assertTrue(new SelfRunRolloverCoordinator(context).hasPendingClaim());

        store = new SelfRunStore(context);
        SelfRunRolloverCoordinator coordinator = new SelfRunRolloverCoordinator(context);
        SelfRunRolloverCoordinator.Result resumed = coordinator.resumePending(store);
        assertTrue(resumed.started());
        assertEquals(successor, resumed.successorRunId);
        assertEquals(successor, store.runId());
        assertNotEquals(predecessor, store.runId());
        assertFalse(coordinator.hasPendingClaim());
    }

    @Test public void predecessorAlreadyTerminalStillResumesSameReservedSuccessor() throws Exception {
        SelfRunStore store = predecessor();
        String successor = SelfRunRunId.create();
        writeClaim(store, successor, false);
        context.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE).edit()
                .putBoolean("active", false).putBoolean("paused", false).putBoolean("userStopped", false)
                .putString("phase", SelfRunRolloverCoordinator.PHASE_ROLLED_OVER).commit();

        SelfRunRolloverCoordinator.Result resumed = new SelfRunRolloverCoordinator(context)
                .resumePending(new SelfRunStore(context));
        assertTrue(resumed.started());
        assertEquals(successor, resumed.successorRunId);
        assertEquals(successor, new SelfRunStore(context).runId());
    }

    @Test public void successorAlreadyStartedBeforeClaimCleanupIsAdoptedWithoutAnotherRun() throws Exception {
        SelfRunStore store = predecessor();
        String successor = SelfRunRunId.create();
        writeClaim(store, successor, false);
        String requirement = store.requirement();
        assertTrue(ChatReasoningPreferenceStore.save(context, successor, ChatReasoningPreferenceStore.EXTRA_HIGH));
        assertTrue(SelfRunSignalTransport.mark(context, successor));
        store.start(successor, SelfRunStore.MODE_CHAT, SelfRunScript.GENERAL_CHAT_URL,
                requirement, Collections.emptyList());

        SelfRunRolloverCoordinator coordinator = new SelfRunRolloverCoordinator(context);
        SelfRunRolloverCoordinator.Result resumed = coordinator.resumePending(store);
        assertEquals(SelfRunRolloverCoordinator.RESULT_ALREADY_STARTED, resumed.status);
        assertEquals(successor, store.runId());
        assertFalse(coordinator.hasPendingClaim());
    }

    @Test public void userStopCancelsPendingClaimWithoutStartingSuccessor() throws Exception {
        SelfRunStore store = predecessor();
        String predecessor = store.runId();
        String successor = SelfRunRunId.create();
        writeClaim(store, successor, false);
        store.stopByUser();
        SelfRunRolloverCoordinator coordinator = new SelfRunRolloverCoordinator(context);
        SelfRunRolloverCoordinator.Result resumed = coordinator.resumePending(store);
        assertEquals(SelfRunRolloverCoordinator.RESULT_FAILED, resumed.status);
        assertEquals(predecessor, store.runId());
        assertTrue(store.userStopped());
        assertFalse(coordinator.hasPendingClaim());
    }

    private SelfRunStore predecessor() {
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

    private void writeClaim(SelfRunStore store, String successor, boolean priorTerminal) throws Exception {
        JSONObject claim = new JSONObject();
        claim.put("predecessorRunId", store.runId());
        claim.put("successorRunId", successor);
        claim.put("predecessorJobFolderId", store.jobFolderId());
        claim.put("predecessorTurnDocumentId", store.turnDocumentId());
        claim.put("predecessorOriginalRequirementStored", true);
        claim.put("projectUrl", store.projectUrl());
        claim.put("mode", store.mode());
        claim.put("model", "");
        claim.put("reasoning", "");
        claim.put("chatPickerSelection", ChatReasoningPreferenceStore.EXTRA_HIGH);
        claim.put("cause", SelfRunRolloverPolicy.ROUTE_MISMATCH);
        claim.put("priorCauses", "");
        claim.put("claimedAt", System.currentTimeMillis());
        SharedPreferences prefs = context.getSharedPreferences("selfrun_drive_rollover", Context.MODE_PRIVATE);
        assertTrue(prefs.edit().putString("currentClaim", claim.toString()).commit());
        if (priorTerminal) context.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE).edit()
                .putBoolean("active", false).putString("phase", SelfRunRolloverCoordinator.PHASE_ROLLED_OVER).commit();
    }

    private void clearAll() {
        for (String name : new String[]{"selfrun_drive", "selfrun_drive_rollover", "selfrun_drive_signal_transport",
                "selfrun_drive_chat_reasoning", "selfrun_drive_bootstrap_runs", "selfrun_drive_chat_picker_state",
                "selfrun_drive_history"}) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
        }
    }
}
