package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.Intent;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.core.app.ActivityScenario;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
                "selfrun_drive_history", "selfrun_drive_user_next_input", "selfrun_drive_chat_reasoning",
                "selfrun_drive_bootstrap_runs", "selfrun_drive_restart"}) {
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

    @Test public void currentChatMigrationPreservesBootstrapEvidenceAndLaterSelections() throws Exception {
        ProfileRegistry.Profile selected = ProfileRegistry.listChat().get(0);
        ProfileRegistry.Profile later = ProfileRegistry.listChat().get(1);
        seed("CONTINUATION", profile(ProfileRegistry.listWork().get(0)), profile(selected));
        seedBootstrapEvidence(RUN, later.signalReasoning);
        Map<String, ?> bootstrapBefore = values("selfrun_drive_bootstrap_runs");
        SelfRunStore migrated = new SelfRunStore(context);
        assertEquals("CHAT", migrated.mode());
        assertEquals(selected.signalReasoning, ChatReasoningPreferenceStore.selectionForRun(RUN));
        assertEquals(selected.signalReasoning, ChatReasoningPreferenceStore.continuationSelectionForRun(RUN));
        assertEquals(bootstrapBefore, values("selfrun_drive_bootstrap_runs"));
        assertFalse(state().getBoolean("legacyChatSelectionPending:" + RUN, false));

        // An acknowledged migration must never overwrite a later legitimate ordinary selection.
        assertTrue(ChatReasoningPreferenceStore.save(context, RUN, later.signalReasoning, selected.signalReasoning));
        Map<String, ?> laterChat = values("selfrun_drive_chat_reasoning");
        Map<String, ?> laterBootstrap = values("selfrun_drive_bootstrap_runs");
        new SelfRunStore(context);
        assertEquals(laterChat, values("selfrun_drive_chat_reasoning"));
        assertEquals(laterBootstrap, values("selfrun_drive_bootstrap_runs"));
    }

    @Test public void historicalChatNormalizationAndRejectedRestartDoNotMutateActiveRun() throws Exception {
        ProfileRegistry.Profile selected = ProfileRegistry.listChat().get(0);
        ProfileRegistry.Profile other = ProfileRegistry.listChat().get(1);
        seed("CONTINUATION", profile(ProfileRegistry.listWork().get(0)), profile(selected));
        seedBootstrapEvidence(RUN, selected.signalReasoning);
        JSONObject historical = new JSONObject().put("runId", RUN).put("mode", "HYBRID")
                .put("phase", SelfRunStore.PHASE_IDLE).put("userStopped", true)
                .put("requirement", "원본\n공백 유지  ").put("projectUrl", SelfRunScript.GENERAL_CHAT_URL)
                .put("conversationUrl", "https://chatgpt.com/c/migration-fixture").put("turn", 4);
        String rawHistory = new org.json.JSONArray().put(historical).toString();
        assertTrue(context.getSharedPreferences("selfrun_drive_history", 0).edit().putString("runs", rawHistory).commit());
        String activeRun = "SR-20260905-000000-MIG002";
        assertTrue(state().edit().putString("runId", activeRun).putString("mode", "CHAT")
                .putString("phase", SelfRunStore.PHASE_WAIT_TURN_COMPLETION).putBoolean("active", true)
                .putBoolean("paused", false).putBoolean("userStopped", false).commit());
        assertTrue(ChatReasoningPreferenceStore.save(context, activeRun, other.signalReasoning, selected.signalReasoning));
        seedBootstrapEvidence(activeRun, other.signalReasoning);
        Map<String, ?> chatBefore = values("selfrun_drive_chat_reasoning");
        Map<String, ?> bootstrapBefore = values("selfrun_drive_bootstrap_runs");
        String original = historical.toString();
        JSONObject normalized = LegacyRunModeMigration.normalizedSnapshot(context, historical);
        assertEquals("CHAT", normalized.optString("mode"));
        assertEquals(original, historical.toString());
        assertEquals(chatBefore, values("selfrun_drive_chat_reasoning"));
        assertEquals(bootstrapBefore, values("selfrun_drive_bootstrap_runs"));
        assertEquals(rawHistory, context.getSharedPreferences("selfrun_drive_history", 0).getString("runs", ""));

        try (ActivityScenario<SelfRunRestartActivity> scenario = ActivityScenario.launch(
                new Intent(context, SelfRunRestartActivity.class).putExtra(SelfRunRestartActivity.EXTRA_RUN_ID, RUN))) {
            scenario.onActivity(activity -> {
                assertEquals(activeRun, state().getString("runId", ""));
                assertTrue(state().getBoolean("active", false));
                assertEquals("", context.getSharedPreferences("selfrun_drive_restart", 0).getString("claimToken", ""));
                assertEquals(chatBefore, values("selfrun_drive_chat_reasoning"));
                assertEquals(bootstrapBefore, values("selfrun_drive_bootstrap_runs"));
                assertEquals(rawHistory, context.getSharedPreferences("selfrun_drive_history", 0).getString("runs", ""));
                // Exercise the real admitted restore boundary without authorization/network callbacks.
                assertTrue(state().edit().putBoolean("active", false).commit());
                assertEquals(Boolean.TRUE, invoke(activity, "claimRestart", new Class<?>[0]));
                try {
                    DriveApiClient.Metadata folder = new DriveApiClient.Metadata(new JSONObject().put("id", "fixture-folder"));
                    DriveApiClient.Metadata document = new DriveApiClient.Metadata(new JSONObject().put("id", "fixture-document").put("version", "1"));
                    DriveSignalParser.Scan scan = new DriveSignalParser.Scan(java.util.Collections.emptyList(), 23, null, null, false);
                    invoke(activity, "restoreRun", new Class<?>[]{String.class, String.class, DriveApiClient.Metadata.class,
                            DriveApiClient.Metadata.class, DriveSignalParser.Scan.class, DriveSignalParser.Event.class, String.class},
                            "fixture-base", "fixture-account", folder, document, scan, null, "fixture continuation");
                } catch (Exception error) { throw new AssertionError(error); }
                assertEquals(RUN, state().getString("runId", ""));
                assertTrue(state().getBoolean("active", false));
                assertEquals("CHAT", state().getString("mode", ""));
                assertEquals(selected.signalReasoning, ChatReasoningPreferenceStore.selectionForRun(RUN));
                assertEquals(selected.signalReasoning, ChatReasoningPreferenceStore.continuationSelectionForRun(RUN));
                assertEquals(bootstrapBefore, values("selfrun_drive_bootstrap_runs"));
                assertEquals(original, historical.toString());
                assertFalse(state().getBoolean("legacyChatSelectionPending:" + RUN, false));
            });
        }
    }

    @Test public void interruptedAdmittedChatPreferenceCommitRecoversOnlyMarkedCurrentRun() throws Exception {
        ProfileRegistry.Profile selected = ProfileRegistry.listChat().get(0);
        seed("CONTINUATION", profile(ProfileRegistry.listWork().get(0)), profile(selected));
        seedBootstrapEvidence(RUN, selected.signalReasoning);
        JSONObject normalized = LegacyRunModeMigration.normalizedSnapshot(context,
                new JSONObject().put("runId", RUN).put("mode", "HYBRID"));
        assertTrue(normalized.optBoolean("legacyChatSelectionPending"));
        Map<String, ?> bootstrapBefore = values("selfrun_drive_bootstrap_runs");
        // Exact durable state after admitted run commit, before the separate Chat preference commit.
        assertTrue(state().edit().putString("mode", "CHAT").putBoolean("legacyChatSelectionPending:" + RUN, true).commit());
        new SelfRunStore(context);
        assertEquals(selected.signalReasoning, ChatReasoningPreferenceStore.selectionForRun(RUN));
        assertEquals(selected.signalReasoning, ChatReasoningPreferenceStore.continuationSelectionForRun(RUN));
        assertEquals(bootstrapBefore, values("selfrun_drive_bootstrap_runs"));
        assertFalse(state().contains("legacyChatSelectionPending:" + RUN));
    }

    @Test public void finalComposerPreflightAndClickRetainExactlyOneMigrationNotice() throws Exception {
        ProfileRegistry.Profile work = ProfileRegistry.listWork().get(0);
        ProfileRegistry.Profile chat = ProfileRegistry.listChat().get(0);
        int attempt = 0;
        for (String drive : new String[]{"", "Drive 원문\n  들여쓰기와 공백  "}) {
            for (String user : new String[]{"", "사용자 원문\n  줄바꿈과 공백 유지  "}) {
                seed("BOOTSTRAP", profile(work), profile(chat));
                assertTrue(context.getSharedPreferences("selfrun_drive_user_next_input", 0).edit().clear().commit());
                assertTrue(state().edit().remove("legacyModeNoticeConsumed:" + RUN)
                        .putString("phase", SelfRunStore.PHASE_SEND_CONTINUE).putBoolean("paused", false)
                        .putLong("phaseStartedAt", 1000L + attempt++).commit());
                SelfRunStore migrated = new SelfRunStore(context);
                if (!user.isEmpty()) assertTrue(UserNextInputStore.save(RUN, user));
                String prompt = SelfRunProtocol.driveContinuation(RUN, drive);
                String canonical = UserNextInputStore.composePrompt(prompt, UserNextInputStore.mergeText(drive, user));
                String expected = LegacyRunModeMigration.appendNotice(RUN, canonical);
                String original = prompt + "\nDISCARDED_EARLY_SUFFIX";
                String identity = UserNextInputStore.continuationIdentity(23, state().getLong("phaseStartedAt", 0L));
                String marker = RUN + ":continue:" + identity;
                String url = "https://chatgpt.com/c/migration-fixture";
                try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
                    AtomicReference<WebView> web = fixture(scenario, url);
                    try {
                        for (int step = 0; step < 3; step++)
                            evaluate(scenario, web, SelfRunContinuationDom.prepareDriveTurn(url, original, marker));
                        String prepared = evaluate(scenario, web, "document.getElementById('prompt-textarea').value");
                        assertFinalPayload(expected, prepared, drive, user);
                        assertNotNull(LegacyRunModeMigration.pendingEndpoint(RUN));
                        evaluate(scenario, web, "document.getElementById('send').disabled=true");
                        JSONObject preflight = new JSONObject(evaluate(scenario, web,
                                SelfRunContinuationDom.clickPreparedDriveTurn(url, original, marker, RUN)));
                        assertEquals("SEND_DISABLED", preflight.getString("status"));
                        assertFinalPayload(expected, evaluate(scenario, web, "document.getElementById('prompt-textarea').value"), drive, user);
                        assertNotNull("Failed preflight must retain compatibility", LegacyRunModeMigration.pendingEndpoint(RUN));
                        evaluate(scenario, web, "document.getElementById('send').disabled=false");
                        JSONObject click = new JSONObject(evaluate(scenario, web,
                                SelfRunContinuationDom.clickPreparedDriveTurn(url, original, marker, RUN)));
                        assertEquals("SUBMISSION_PENDING", click.getString("status"));
                        assertEquals("1", evaluate(scenario, web, "String(window.sent.length)"));
                        assertFinalPayload(expected, evaluate(scenario, web, "window.sent[0]"), drive, user);
                        JSONObject waiting = new JSONObject(evaluate(scenario, web,
                                SelfRunContinuationDom.prepareDriveTurn(url, original, marker)));
                        assertEquals("SUBMISSION_PENDING", waiting.getString("status"));
                        String bare = "[2026.09.05 | 00:00:00] [SELF_RUN_TURN_COMPLETED " + RUN + "]";
                        assertTrue("Unconfirmed submit keeps bare legacy completion compatibility", DriveSignalParser.workProfile(bare).valid);
                        evaluate(scenario, web, "document.body.insertAdjacentHTML('beforeend', '<div data-message-author-role=\"user\">sent</div>')");
                        JSONObject confirmed = new JSONObject(evaluate(scenario, web,
                                SelfRunContinuationDom.prepareDriveTurn(url, original, marker)));
                        assertEquals("SUBMISSION_CONFIRMED", confirmed.getString("status"));
                        migrated.beginTurnCompletionWait(migrated.turnProtocolToken(), "confirmed fixture", true);
                        assertNull(LegacyRunModeMigration.pendingEndpoint(RUN));
                        assertFalse(DriveSignalParser.workProfile(bare).valid);
                        assertEquals("", UserNextInputStore.current(RUN));
                        assertTrue(state().edit().putString("phase", SelfRunStore.PHASE_SEND_CONTINUE)
                                .putLong("phaseStartedAt", 9000L + attempt).commit());
                        String later = SelfRunProtocol.driveContinuation(RUN, "");
                        String laterMarker = RUN + ":continue:" + UserNextInputStore.continuationIdentity(23, 9000L + attempt);
                        for (int step = 0; step < 3; step++)
                            evaluate(scenario, web, SelfRunContinuationDom.prepareDriveTurn(url, later, laterMarker));
                        String laterText = evaluate(scenario, web, "document.getElementById('prompt-textarea').value");
                        assertEquals(later, laterText);
                        assertFalse(laterText.contains("[실행 모드 갱신]"));
                    } finally { scenario.onActivity(activity -> web.get().destroy()); }
                }
            }
        }
    }

    private void seedBootstrapEvidence(String runId, String reasoning) {
        assertTrue(BootstrapRunStateStore.startRun(context, runId, reasoning));
        assertTrue(BootstrapRunStateStore.touchBootstrap(context, runId, reasoning, 1000L).persisted);
        assertTrue(BootstrapRunStateStore.touchBootstrap(context, runId, reasoning, 2000L).persisted);
        assertTrue(BootstrapRunStateStore.recordBootstrapResult(context, runId, "FIXTURE_RETRY", "keep diagnostic", 3000L).persisted);
        assertTrue(BootstrapRunStateStore.markBootstrapFailed(context, runId, "FIXTURE_FAILURE", "preserve failure detail"));
    }
    private Map<String, ?> values(String name) {
        return new HashMap<>(context.getSharedPreferences(name, 0).getAll());
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try {
            java.lang.reflect.Method method = target.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private static void assertFinalPayload(String expected, String actual, String drive, String user) {
        assertEquals(expected, actual);
        assertEquals(1, actual.split(java.util.regex.Pattern.quote("[실행 모드 갱신]"), -1).length - 1);
        assertFalse(actual.contains("DISCARDED_EARLY_SUFFIX"));
        if (!drive.isEmpty()) assertTrue(actual.contains(drive));
        if (!user.isEmpty()) assertTrue(actual.contains(user));
    }
    private static AtomicReference<WebView> fixture(ActivityScenario<SelfRunNewActivity> scenario, String url) throws Exception {
        AtomicReference<WebView> web = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            activity.setContentView(view);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView current, String currentUrl) { loaded.countDown(); }
            });
            web.set(view);
            view.loadDataWithBaseURL(url, "<!doctype html><html><body><main><form onsubmit='return false'>"
                    + "<textarea id='prompt-textarea' style='width:280px;height:150px'></textarea>"
                    + "<button id='send' type='button' data-testid='send-button' "
                    + "onclick='window.sent.push(document.getElementById(\"prompt-textarea\").value)'>Send</button>"
                    + "</form></main><script>window.sent=[];</script></body></html>", "text/html", "UTF-8", null);
        });
        assertTrue("Local WebView fixture timed out", loaded.await(15, TimeUnit.SECONDS));
        evaluate(scenario, web, "localStorage.clear(); sessionStorage.clear()");
        return web;
    }
    private static String evaluate(ActivityScenario<SelfRunNewActivity> scenario, AtomicReference<WebView> web, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> output = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(script, value -> {
            try {
                Object parsed = new org.json.JSONTokener(value).nextValue();
                output.set(parsed instanceof String ? (String) parsed : String.valueOf(parsed));
            } catch (Exception error) { output.set(value); }
            done.countDown();
        }));
        assertTrue("Local JavaScript fixture timed out", done.await(15, TimeUnit.SECONDS));
        return output.get();
    }
}
