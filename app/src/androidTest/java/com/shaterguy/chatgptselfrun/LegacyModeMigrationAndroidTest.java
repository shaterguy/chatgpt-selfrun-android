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
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class LegacyModeMigrationAndroidTest {
    private Context context;
    private final Map<String, Map<String, ?>> saved = new HashMap<>();
    private static final String RUN = "SR-20260905-000000-MIG001";
    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        for (String name : new String[]{"selfrun_drive", "selfrun_drive_hybrid_profiles", "selfrun_drive_mode_migration",
                "selfrun_drive_history", "selfrun_drive_user_next_input"}) {
            SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            saved.put(name, new HashMap<>(prefs.getAll()));
            assertTrue(prefs.edit().clear().commit());
        }
        ProfileRegistry.initialize(context);
        ChatReasoningPreferenceStore.initialize(context);
        UserNextInputStore.initialize(context);
    }
    @After public void tearDown() {
        for (Map.Entry<String, Map<String, ?>> entry : saved.entrySet()) {
            SharedPreferences.Editor edit = context.getSharedPreferences(entry.getKey(), Context.MODE_PRIVATE).edit().clear();
            for (Map.Entry<String, ?> value : entry.getValue().entrySet()) {
                Object v = value.getValue(); String key = value.getKey();
                if (v instanceof String) edit.putString(key, (String) v);
                else if (v instanceof Boolean) edit.putBoolean(key, (Boolean) v);
                else if (v instanceof Integer) edit.putInt(key, (Integer) v);
                else if (v instanceof Long) edit.putLong(key, (Long) v);
                else if (v instanceof Float) edit.putFloat(key, (Float) v);
            }
            assertTrue(edit.commit());
        }
    }
    private SharedPreferences state() { return context.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE); }
    private JSONObject profile(ProfileRegistry.Profile profile) throws Exception {
        return new JSONObject().put("mode", profile.mode == ProfileRegistry.Mode.WORK ? "WORK" : "CHAT")
                .put("model", profile.signalModel).put("reasoning", profile.signalReasoning);
    }
    private void seed(String stage, JSONObject bootstrap, JSONObject continuation) throws Exception {
        JSONObject selection = new JSONObject().put("runId", RUN).put("stage", stage)
                .put("bootstrap", bootstrap).put("continuation", continuation);
        assertTrue(context.getSharedPreferences("selfrun_drive_hybrid_profiles", Context.MODE_PRIVATE).edit()
                .putString("run:" + RUN, selection.toString()).commit());
        assertTrue(state().edit().putString("runId", RUN).putString("mode", "HYBRID")
                .putString("phase", SelfRunStore.PHASE_WAIT_TURN_COMPLETION)
                .putString("pausedFromPhase", SelfRunStore.PHASE_WAIT_TURN_COMPLETION)
                .putString("projectUrl", SelfRunScript.GENERAL_CHAT_URL)
                .putString("conversationUrl", "https://chatgpt.com/c/migration-fixture")
                .putString("requirement", "원본 요청\n공백 유지  ")
                .putString("turnProtocolToken", RUN + ":turn:4").putInt("turn", 4)
                .putInt("driveSignalCursor", 23).putInt("driveSignalCursorSchemaVersion", 2)
                .putBoolean("active", true).putBoolean("paused", true).putBoolean("userStopped", false).commit());
    }
    @Test public void bootstrapEndpointAndAllDurableIdentitySurviveRecreation() throws Exception {
        ProfileRegistry.Profile work = ProfileRegistry.listWork().get(0);
        ProfileRegistry.Profile chat = ProfileRegistry.listChat().get(0);
        seed("BOOTSTRAP", profile(work), profile(chat));
        String bare = "[2026.09.05 | 00:00:00] [SELF_RUN_TURN_COMPLETED " + RUN + "]";
        assertTrue(state().edit().putString("pendingDriveSignalRaw", bare)
                .putString("pendingDriveSignalType", "TURN_COMPLETED").commit());
        SelfRunStore first = new SelfRunStore(context);
        assertEquals("WORK", first.mode());
        assertEquals(work.signalModel, first.pendingModel());
        assertEquals(work.signalReasoning, first.pendingReasoning());
        assertEquals(RUN, first.runId());
        assertEquals(23, first.driveSignalCursor());
        assertTrue(first.paused());
        assertEquals("https://chatgpt.com/c/migration-fixture", first.conversationUrl());
        assertEquals("원본 요청\n공백 유지  ", first.requirement());
        assertEquals(RUN + ":turn:4", first.turnProtocolToken());
        assertTrue(LegacyRunModeMigration.appendNotice(RUN, "payload").startsWith("payload"));
        assertTrue(DriveSignalParser.workProfile(bare).valid);
        first.beginTurnCompletionWait(first.turnProtocolToken(), "waiting");
        assertNotNull(LegacyRunModeMigration.pendingEndpoint(RUN));
        SelfRunStore recreated = new SelfRunStore(context);
        assertEquals("WORK", recreated.mode());
        recreated.beginTurnCompletionWait(recreated.turnProtocolToken(), "waiting", true);
        assertNull(LegacyRunModeMigration.pendingEndpoint(RUN));
        assertTrue(state().getBoolean("legacyModeNoticeConsumed:" + RUN, false));
        assertEquals(SelfRunStore.PHASE_WAIT_TURN_COMPLETION, state().getString("phase", ""));
        assertEquals("", state().getString("pendingDriveSignalRaw", ""));
        assertFalse(DriveSignalParser.workProfile(bare).valid);
        assertEquals("payload", LegacyRunModeMigration.appendNotice(RUN, "payload"));
    }
    @Test public void continuationEndpointNormalizesCopyWithoutRewritingHistory() throws Exception {
        ProfileRegistry.Profile work = ProfileRegistry.listWork().get(0);
        ProfileRegistry.Profile chat = ProfileRegistry.listChat().get(0);
        seed("CONTINUATION", profile(work), profile(chat));
        JSONObject snapshot = new JSONObject().put("runId", RUN).put("mode", "HYBRID")
                .put("requirement", "원본").put("conversationUrl", "https://chatgpt.com/c/fixture");
        String original = snapshot.toString();
        JSONObject normalized = LegacyRunModeMigration.normalizedSnapshot(context, snapshot);
        assertEquals(original, snapshot.toString());
        assertEquals("CHAT", normalized.optString("mode"));
        SelfRunStore migrated = new SelfRunStore(context);
        assertEquals("CHAT", migrated.mode());
        assertTrue(migrated.paused());
        assertEquals(chat.signalReasoning, ChatReasoningPreferenceStore.selectionForRun(RUN));
    }
    @Test public void invalidEndpointCannotStartAnOrdinaryRunByGuessing() throws Exception {
        seed("CONTINUATION", profile(ProfileRegistry.listWork().get(0)), new JSONObject());
        SelfRunStore unresolved = new SelfRunStore(context);
        assertEquals("HYBRID", unresolved.mode());
        assertEquals("LEGACY_MODE_UNRESOLVED", unresolved.lastErrorCode());
        assertTrue(unresolved.paused());
        assertEquals(23, unresolved.driveSignalCursor());
        assertNull(LegacyRunModeMigration.pendingEndpoint(RUN));
        try { unresolved.start(RUN, "HYBRID", SelfRunScript.GENERAL_CHAT_URL, "request"); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("CHAT or WORK")); }
    }
}
