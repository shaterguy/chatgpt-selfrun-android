package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * Runs against the formal target package before and after an in-place 3.2.6 -> 3.2.7-dev1 update.
 * The baseline seed path intentionally uses only APIs and persistence contracts already present in 3.2.6.
 */
@RunWith(AndroidJUnit4.class)
public final class SelfRun3OnDeviceFormalUpgradeAndroidTest {
    private static final String TASK_ID = "SR-FORMAL-UPGRADE";
    private static final String TURN_1 = TASK_ID + ":turn:1";
    private static final String RUNTIME_PREFS = "selfrun3_runtime_settings";
    private static final String WORK_MODE_KEY = "selfrun3WorkMode";
    private static final String MIGRATION_KEY = "selfrun3OnDeviceDefaultMigrationVersion";
    private static final String RESULT_POLL_KEY = "selfrun3ResultPollSeconds";
    private static final String SENTINEL_KEY = "formalUpgradeSentinel";
    private static final String SENTINEL_VALUE = "retain-across-formal-update";
    private static final String FIXTURE_PREFS = "selfrun3_formal_upgrade_fixture";
    private static final long RESULT_POLL_OVERRIDE = 47L;

    private Context context() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test public void formalUpgradePhase() {
        String phase = InstrumentationRegistry.getArguments().getString("formalUpgradePhase", "");
        switch (phase) {
            case "seed" -> seedFormal326ServerStateAndWaitingLedger();
            case "verify" -> verifyFormal327MigrationAndCommittedContinuation();
            case "restart" -> verifyFormal327AfterProcessRestart();
            default -> fail("valid formalUpgradePhase required");
        }
    }

    private void seedFormal326ServerStateAndWaitingLedger() {
        Context c = context();
        SharedPreferences runtime = c.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE);
        assertTrue(runtime.edit()
                .putString(WORK_MODE_KEY, "SERVER")
                .remove(MIGRATION_KEY)
                .putLong(RESULT_POLL_KEY, RESULT_POLL_OVERRIDE)
                .putString(SENTINEL_KEY, SENTINEL_VALUE)
                .commit());

        SelfRun3RuntimeSettings baselineSettings = new SelfRun3RuntimeSettings(c);
        assertEquals("SERVER", baselineSettings.workMode().name());
        assertEquals(RESULT_POLL_OVERRIDE, baselineSettings.resultPollSeconds());
        assertFalse(runtime.contains(MIGRATION_KEY));

        SelfRunStore store = new SelfRunStore(c);
        store.bindBaseFolder("acct_123", "abcdefgh", "Runs", "", 1L);
        store.start(TASK_ID, SelfRunStore.MODE_CHAT, "https://chatgpt.com/", "formal upgrade fixture");
        store.setPhase("V3_WAITING");
        UserNextInputStore.initialize(c);

        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "reasoning", "medium");
        SelfRun3Engine.put(config, "projectUrl", "https://chatgpt.com/");

        try (SelfRun3Ledger ledger = new SelfRun3Ledger(c)) {
            SelfRun3Engine.State state = ledger.ensure(SelfRun3Engine.create(TASK_ID, TURN_1, config));
            state = resource(ledger, state, "folderId", "formal-folder");
            state = resource(ledger, state, "requirementDocumentId", "formal-requirement");
            state = ledger.apply(event(state, "setup", SelfRun3Engine.Kind.SETUP_DONE, new JSONObject()));
            state = resource(ledger, state, "resultDocumentId", "formal-result-1");

            JSONObject ready = new JSONObject();
            SelfRun3Engine.put(ready, "prompt", "formal upgrade continuation fixture");
            SelfRun3Engine.put(ready, "inputText", "");
            SelfRun3Engine.put(ready, "inputRevision", 0L);
            state = ledger.apply(event(state, "ready", SelfRun3Engine.Kind.TURN_READY, ready));

            JSONObject claim = new JSONObject();
            SelfRun3Engine.put(claim, "at", 100L);
            state = ledger.apply(event(state, "claim", SelfRun3Engine.Kind.CLAIM_SEND, claim));

            JSONObject started = new JSONObject();
            SelfRun3Engine.put(started, "requestId", state.requestId());
            state = ledger.apply(event(state, "started", SelfRun3Engine.Kind.STARTED, started));

            assertEquals(SelfRun3Engine.Stage.WAITING, state.stage());
            assertEquals(1, state.turn());
            assertEquals(TURN_1, state.turnId());
            assertEquals("formal-result-1", state.resource("resultDocumentId"));
            assertTrue(state.flag("sendClaimed"));
            assertTrue(state.flag("accepted"));
            assertTrue(store.active());
            assertEquals(TASK_ID, store.runId());

            assertTrue(c.getSharedPreferences(FIXTURE_PREFS, Context.MODE_PRIVATE).edit()
                    .clear()
                    .putInt("targetUid", c.getApplicationInfo().uid)
                    .putString("waitingTurnId", state.turnId())
                    .putInt("seedEventCount", ledger.eventCount(TASK_ID))
                    .commit());
        }
    }

    private void verifyFormal327MigrationAndCommittedContinuation() {
        Context c = context();
        SharedPreferences runtime = c.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE);
        SharedPreferences fixture = c.getSharedPreferences(FIXTURE_PREFS, Context.MODE_PRIVATE);

        assertEquals(fixture.getInt("targetUid", -1), c.getApplicationInfo().uid);
        assertEquals(TURN_1, fixture.getString("waitingTurnId", ""));
        assertEquals(RESULT_POLL_OVERRIDE, runtime.getLong(RESULT_POLL_KEY, -1L));
        assertEquals(SENTINEL_VALUE, runtime.getString(SENTINEL_KEY, ""));

        SelfRun3RuntimeSettings migrated = new SelfRun3RuntimeSettings(c);
        assertEquals("ON_DEVICE", migrated.workMode().name());
        assertEquals("ON_DEVICE", runtime.getString(WORK_MODE_KEY, ""));
        assertEquals(1, runtime.getInt(MIGRATION_KEY, 0));
        assertEquals(RESULT_POLL_OVERRIDE, migrated.resultPollSeconds());
        assertEquals(SENTINEL_VALUE, runtime.getString(SENTINEL_KEY, ""));

        SelfRunStore store = new SelfRunStore(c);
        UserNextInputStore.initialize(c);
        assertTrue(store.active());
        assertEquals(TASK_ID, store.runId());

        try (SelfRun3Ledger ledger = new SelfRun3Ledger(c)) {
            SelfRun3Engine.State waiting = ledger.load(TASK_ID);
            assertNotNull(waiting);
            assertEquals(SelfRun3Engine.Stage.WAITING, waiting.stage());
            assertEquals(1, waiting.turn());
            assertEquals(TURN_1, waiting.turnId());
            assertEquals("formal-result-1", waiting.resource("resultDocumentId"));
            assertEquals(fixture.getInt("seedEventCount", -1), ledger.eventCount(TASK_ID));

            JSONObject result = SelfRun3Engine.emptyResult(waiting);
            SelfRun3Engine.put(result, "committed", true);
            SelfRun3Engine.put(result, "status", "CONTINUE");
            SelfRun3Engine.put(result, "phase_completed", "WORK");
            SelfRun3Engine.put(result, "next_phase", "WORK");
            SelfRun3Engine.put(result, "next_input", "");
            JSONObject handoff = new JSONObject();
            SelfRun3Engine.put(handoff, "objective", "formal upgrade continuation");
            SelfRun3Engine.put(handoff, "completed", "formal 3.2.6 state retained and migrated");
            SelfRun3Engine.put(handoff, "remaining", "next logical turn");
            SelfRun3Engine.put(handoff, "evidence", "fixture-backed formal target");
            SelfRun3Engine.put(handoff, "constraints", "ON_DEVICE");
            SelfRun3Engine.put(handoff, "next_action", "continue");
            SelfRun3Engine.put(result, "handoff", handoff);

            JSONObject payload = new JSONObject();
            SelfRun3Engine.put(payload, "text", result.toString());
            SelfRun3Engine.State reconciled = ledger.apply(
                    event(waiting, "formal-result", SelfRun3Engine.Kind.RESULT, payload));
            assertEquals(SelfRun3Engine.Stage.RECONCILING, reconciled.stage());
            assertFalse(reconciled.flag("committed"));
            assertEquals("", ledger.committedResult(TASK_ID, 1));

            SelfRun3Engine.State next = SelfRun3UserInput.commit(c, store, ledger, reconciled);
            assertEquals(2, next.turn());
            assertNotEquals(TURN_1, next.turnId());
            assertEquals(SelfRun3Engine.Stage.PREPARING, next.stage());
            assertEquals("WORK", next.text("phase"));
            assertEquals("AUTO_NEXT_TURN", next.text("signalType"));
            assertTrue(store.active());
            assertFalse(ledger.committedResult(TASK_ID, 1).isEmpty());

            int eventCountAfterCommit = ledger.eventCount(TASK_ID);
            SelfRun3Engine.State duplicate = SelfRun3UserInput.commit(c, store, ledger, reconciled);
            assertEquals(2, duplicate.turn());
            assertEquals(next.turnId(), duplicate.turnId());
            assertEquals(eventCountAfterCommit, ledger.eventCount(TASK_ID));

            assertTrue(fixture.edit()
                    .putString("nextTurnId", next.turnId())
                    .putInt("eventCountAfterCommit", eventCountAfterCommit)
                    .commit());
        }

        try (SelfRun3Ledger reopened = new SelfRun3Ledger(c)) {
            SelfRun3Engine.State recovered = reopened.load(TASK_ID);
            assertNotNull(recovered);
            assertEquals(2, recovered.turn());
            assertEquals(fixture.getString("nextTurnId", ""), recovered.turnId());
            assertEquals(SelfRun3Engine.Stage.PREPARING, recovered.stage());
            assertEquals(fixture.getInt("eventCountAfterCommit", -1), reopened.eventCount(TASK_ID));
        }
    }

    private void verifyFormal327AfterProcessRestart() {
        Context c = context();
        SharedPreferences runtime = c.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE);
        SharedPreferences fixture = c.getSharedPreferences(FIXTURE_PREFS, Context.MODE_PRIVATE);

        assertEquals(fixture.getInt("targetUid", -1), c.getApplicationInfo().uid);
        assertEquals("ON_DEVICE", new SelfRun3RuntimeSettings(c).workMode().name());
        assertEquals("ON_DEVICE", runtime.getString(WORK_MODE_KEY, ""));
        assertEquals(1, runtime.getInt(MIGRATION_KEY, 0));
        assertEquals(RESULT_POLL_OVERRIDE, runtime.getLong(RESULT_POLL_KEY, -1L));
        assertEquals(SENTINEL_VALUE, runtime.getString(SENTINEL_KEY, ""));

        SelfRunStore store = new SelfRunStore(c);
        assertTrue(store.active());
        assertEquals(TASK_ID, store.runId());

        try (SelfRun3Ledger ledger = new SelfRun3Ledger(c)) {
            SelfRun3Engine.State recovered = ledger.load(TASK_ID);
            assertNotNull(recovered);
            assertEquals(2, recovered.turn());
            assertEquals(fixture.getString("nextTurnId", ""), recovered.turnId());
            assertEquals(SelfRun3Engine.Stage.PREPARING, recovered.stage());
            assertEquals(fixture.getInt("eventCountAfterCommit", -1), ledger.eventCount(TASK_ID));
            assertFalse(ledger.committedResult(TASK_ID, 1).isEmpty());
        }
    }

    private static SelfRun3Engine.State resource(SelfRun3Ledger ledger, SelfRun3Engine.State state,
                                                  String key, String value) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", key);
        SelfRun3Engine.put(payload, "value", value);
        return ledger.apply(event(state, "resource-" + key, SelfRun3Engine.Kind.RESOURCE, payload));
    }

    private static SelfRun3Engine.Event event(SelfRun3Engine.State state, String suffix,
                                               SelfRun3Engine.Kind kind, JSONObject payload) {
        return new SelfRun3Engine.Event(
                state.turnId() + ":" + suffix, kind, state.taskId(), state.turnId(), payload);
    }
}
