package com.shaterguy.chatgptselfrun;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SelfRunStoppedResumeAndroidTest {
    private Context context;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("selfrun3_run_marker", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("selfrun3_stopped_resume", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void stoppedUncertainDispatchSurvivesProjectionRestartAndResumeEventIsIdempotent() throws Exception {
        String taskId = "resume-test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String turnId = taskId + ":turn:1";
        String resultId = "result-" + taskId;
        SelfRunStore firstStore = new SelfRunStore(context);
        firstStore.bindBaseFolder("acct_123", "abcdefgh", "Runs", "", 1L);
        firstStore.start(taskId, SelfRunStore.MODE_CHAT, SelfRunScript.GENERAL_CHAT_URL,
                "resume requirement", new ArrayList<>(), SelfRunStore.MODE_CHAT);

        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "taskMode", "CHAT");
        SelfRun3Engine.put(config, "projectUrl", SelfRunScript.GENERAL_CHAT_URL);
        SelfRun3Engine.put(config, "requirement", "resume requirement");
        SelfRun3Engine.put(config, "accountId", "acct_123");
        SelfRun3Engine.put(config, "baseFolderId", "abcdefgh");
        SelfRun3Engine.put(config, "reasoning", "medium");

        SelfRun3Engine.State initial = SelfRun3Engine.create(taskId, turnId, config);
        long expectedDeadline = 100L + SelfRun3Engine.SUBMISSION_RECEIPT_TIMEOUT_MS;
        try (SelfRun3Ledger ledger = new SelfRun3Ledger(context)) {
            SelfRun3Engine.State state = ledger.ensure(initial);
            state = applyResource(ledger, state, "folderId", "jobfolder");
            state = applyResource(ledger, state, "requirementDocumentId", "requirementdoc");
            state = ledger.apply(event(state, "setup", SelfRun3Engine.Kind.SETUP_DONE, new JSONObject()));
            state = applyResource(ledger, state, "resultDocumentId", resultId);
            JSONObject ready = new JSONObject();
            SelfRun3Engine.put(ready, "prompt", "continue");
            SelfRun3Engine.put(ready, "inputText", "");
            SelfRun3Engine.put(ready, "inputRevision", 0L);
            state = ledger.apply(event(state, "ready", SelfRun3Engine.Kind.TURN_READY, ready));
            JSONObject claim = new JSONObject();
            SelfRun3Engine.put(claim, "at", 1_000L);
            SelfRun3Engine.put(claim, "atElapsed", 100L);
            SelfRun3Engine.put(claim, "atWall", 1_000L);
            SelfRun3Engine.put(claim, "bootCount", 7);
            state = ledger.apply(event(state, "claim", SelfRun3Engine.Kind.CLAIM_SEND, claim));
            JSONObject started = new JSONObject();
            SelfRun3Engine.put(started, "requestId", state.requestId());
            SelfRun3Engine.put(started, "source", "canonical_post");
            SelfRun3Engine.put(started, "protocolStage", "turn_request");
            SelfRun3Engine.put(started, "atElapsed", 200L);
            SelfRun3Engine.put(started, "atWall", 1_100L);
            SelfRun3Engine.put(started, "bootCount", 7);
            state = ledger.apply(event(state, "started", SelfRun3Engine.Kind.STARTED, started));
            assertEquals(SelfRun3Engine.Stage.DISPATCHING, state.stage());
            assertFalse(state.flag("accepted"));
            state = ledger.apply(event(state, "stop", SelfRun3Engine.Kind.STOP, new JSONObject()));
            assertEquals(SelfRun3Engine.Stage.STOPPED, state.stage());
            assertEquals(resultId, state.resource("resultDocumentId"));
            assertEquals(expectedDeadline, state.time("receiptDeadlineElapsed"));
        }
        firstStore.setTurn(1);
        firstStore.stopByUser();
        assertTrue(new SelfRunHistoryStore(context).get(taskId).optBoolean("userStopped"));

        SelfRunStore restartedStore = new SelfRunStore(context);
        assertFalse(restartedStore.active());
        assertTrue(restartedStore.userStopped());
        assertEquals(taskId, restartedStore.runId());

        try (SelfRun3Ledger ledger = new SelfRun3Ledger(context)) {
            SelfRun3Engine.State stopped = ledger.load(taskId);
            assertNotNull(stopped);
            assertEquals(SelfRun3Engine.Stage.STOPPED, stopped.stage());
            int before = ledger.eventCount(taskId);
            String resumeEventId = "resume-stopped:" + taskId;
            SelfRun3Engine.State resumed = ledger.apply(event(stopped, resumeEventId,
                    SelfRun3Engine.Kind.RESUME_STOPPED, new JSONObject()));
            int afterFirst = ledger.eventCount(taskId);
            SelfRun3Engine.State duplicate = ledger.apply(event(resumed, resumeEventId,
                    SelfRun3Engine.Kind.RESUME_STOPPED, new JSONObject()));
            int afterSecond = ledger.eventCount(taskId);

            assertEquals(before + 1, afterFirst);
            assertEquals(afterFirst, afterSecond);
            assertEquals(SelfRun3Engine.Stage.DISPATCHING, duplicate.stage());
            assertFalse(duplicate.flag("taskStopped"));
            assertTrue(duplicate.flag("sendClaimed"));
            assertTrue(duplicate.flag("canonicalPostStarted"));
            assertFalse(duplicate.flag("accepted"));
            assertEquals(expectedDeadline, duplicate.time("receiptDeadlineElapsed"));
            assertEquals(taskId, duplicate.taskId());
            assertEquals(turnId, duplicate.turnId());
            assertEquals(taskId + ":turn:1-request", duplicate.requestId());
            assertEquals(resultId, duplicate.resource("resultDocumentId"));
            assertEquals(SelfRun3Engine.Action.CHECK_RECEIPT, SelfRun3Engine.nextAction(duplicate));

            restartedStore.start(taskId, SelfRunStore.MODE_CHAT, SelfRunScript.GENERAL_CHAT_URL,
                    "resume requirement", new ArrayList<>(), SelfRunStore.MODE_CHAT);
            restartedStore.setTurn(duplicate.turn());
            assertTrue(SelfRun3RunMarker.mark(context, taskId));
            assertTrue(restartedStore.active());
            assertFalse(restartedStore.userStopped());
            assertEquals(1, restartedStore.turn());
            assertTrue(SelfRun3RunMarker.current(context, taskId));
        }
    }

    private static SelfRun3Engine.State applyResource(SelfRun3Ledger ledger,
                                                       SelfRun3Engine.State state,
                                                       String key, String value) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", key);
        SelfRun3Engine.put(payload, "value", value);
        return ledger.apply(event(state, "resource:" + key, SelfRun3Engine.Kind.RESOURCE, payload));
    }

    private static SelfRun3Engine.Event event(SelfRun3Engine.State state, String id,
                                               SelfRun3Engine.Kind kind, JSONObject payload) {
        return new SelfRun3Engine.Event(id, kind, state.taskId(), state.turnId(), payload);
    }
}
