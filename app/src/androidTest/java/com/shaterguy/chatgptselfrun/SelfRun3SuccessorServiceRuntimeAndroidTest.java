package com.shaterguy.chatgptselfrun;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.Assert.*;

/**
 * Installed-runtime proof for the successor transition that previously could remain in
 * V3_RECONCILING after the predecessor Result was already committed.
 *
 * External Drive and ChatGPT network calls are replaced only at their transport boundaries.
 * SelfRunService, SelfRun3Coordinator, Alarm/service watchdog handling, ledger persistence and
 * the real SelfRun3WebAdapter/HeadlessWebView transport remain production code.
 */
@RunWith(AndroidJUnit4.class)
public final class SelfRun3SuccessorServiceRuntimeAndroidTest {
    private static final String ACCOUNT = "acct_123";
    private static final String BASE_FOLDER = "abcdefgh";
    private static final String PREDECESSOR_RESULT = "fixture-predecessor-result";
    private static final String PREDECESSOR_CONVERSATION =
            "https://chatgpt.com/c/predecessor-fixture";

    private Context context;
    private File ledgerFile;
    private String runId;
    private String predecessorTurnId;
    private FixtureDriveTransport drive;
    private FixturePageTransport page;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.stopService(new Intent(context, SelfRunService.class));
        SystemClock.sleep(150L);
        ledgerFile = new File(context.getNoBackupFilesDir(), "selfrun3-ledger.db");
        deleteLedgerFiles();
        new SelfRunStore(context).clear();
        context.getSharedPreferences("selfrun3_run_marker", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("selfrun_drive_user_next_input", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(SelfRun3RuntimeSettings.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        SelfRun3DriveAdapter.clearTestTransport();
        SelfRun3WebAdapter.clearTestPageTransport();
        SelfRun3Coordinator.clearRuntimeFaultInjectionForTest();
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @After public void tearDown() {
        context.stopService(new Intent(context, SelfRunService.class));
        SystemClock.sleep(200L);
        SelfRun3Coordinator.clearRuntimeFaultInjectionForTest();
        SelfRun3DriveAdapter.clearTestTransport();
        SelfRun3WebAdapter.clearTestPageTransport();
        HeadlessWebViewHost host = HeadlessWebViewHost.activeHost();
        if (host != null) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(host::destroy);
        }
        if (runId != null) SelfRun3RunMarker.clearIfCurrent(context, runId);
        new SelfRunStore(context).clear();
        context.getSharedPreferences("selfrun_drive_user_next_input", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(SelfRun3RuntimeSettings.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications != null) notifications.cancelAll();
        deleteLedgerFiles();
    }

    @Test public void lostDriveCompletionRecoversThroughServiceWatchdogAndRealWebAdapter()
            throws Exception {
        try (ActivityScenario<SelfRunNewActivity> ignored =
                     ActivityScenario.launch(SelfRunNewActivity.class)) {
            Fixture fixture = installFixture(3L, 1_000L);
            SelfRun3Coordinator.dropAcceptedResultCompletionOnceForTest();
            startService(SelfRunService.ACTION_RUN, null);

            SelfRun3Engine.State accepted = awaitState(state ->
                    state.turn() == 1
                            && state.hasResult()
                            && state.stage() == SelfRun3Engine.Stage.RECONCILING
                            && SelfRun3SuccessorTransitionPolicy.armed(state), 10_000L);
            assertEquals(predecessorTurnId,
                    accepted.successorTransition().optString("predecessorTurnId"));
            assertEquals(PREDECESSOR_RESULT,
                    accepted.successorTransition().optString("predecessorResultDocumentId"));
            assertEquals(3_000L, accepted.successorTransition().optLong("timeoutMs"));
            assertEquals(1, drive.predecessorReads.get());
            assertEquals(1, accepted.turn());

            long remaining = SelfRun3SuccessorTransitionPolicy.remainingMs(
                    accepted, SystemClock.elapsedRealtime(), System.currentTimeMillis(),
                    currentBootCount());
            SystemClock.sleep(Math.max(1L, remaining) + 250L);

            SelfRun3Engine.State afterDeadline = load();
            if (afterDeadline.turn() == 1) {
                JSONObject transition = afterDeadline.successorTransition();
                fireWatchdog(
                        transition.optString("predecessorTurnId"),
                        transition.optInt("recoveryAttempt", 0));
            }

            SelfRun3Engine.State successor = awaitState(state ->
                    state.turn() == 2
                            && predecessorTurnId.equals(
                            state.successorTransition().optString("predecessorTurnId")), 10_000L);
            String successorTurnId = successor.turnId();
            String successorRequestId = successor.requestId();
            String successorResultDocumentId = successor.resource("resultDocumentId");
            assertFalse(successorResultDocumentId.isEmpty());
            assertEquals(PREDECESSOR_RESULT, successor.text("previousResultDocumentId"));

            // Replay the stale attempt-0 wake while the recovered transition is in flight.
            fireWatchdog(predecessorTurnId, 0);
            SystemClock.sleep(150L);
            SelfRun3Engine.State afterStaleWake = load();
            assertEquals(successorTurnId, afterStaleWake.turnId());
            assertEquals(successorRequestId, afterStaleWake.requestId());
            assertEquals(successorResultDocumentId, afterStaleWake.resource("resultDocumentId"));

            SelfRun3Engine.State confirmed = awaitState(state ->
                    state.turn() == 2
                            && state.flag("accepted")
                            && !SelfRun3SuccessorTransitionPolicy.armed(state)
                            && "CANONICAL_CONFIRMED".equals(
                            state.successorTransition().optString("stage"))
                            && "https://chatgpt.com/c/fixture-successor".equals(
                            state.resource("conversationUrl")), 15_000L);

            assertEquals(successorTurnId, confirmed.turnId());
            assertEquals(successorRequestId, confirmed.requestId());
            assertEquals(successorResultDocumentId, confirmed.resource("resultDocumentId"));
            assertEquals(1, drive.predecessorReads.get());
            assertEquals("predecessor Result must not be re-read during timeout recovery",
                    1, drive.predecessorReads.get());
            assertEquals("successor result document must be externally allocated once",
                    1, drive.successorDocumentAllocations.get());
            assertEquals("real WebAdapter transport must issue one canonical POST",
                    1, page.canonicalPosts.get());
            assertTrue("fixture WebView must have been launched", page.pageLoads.get() >= 1);
            JSONObject confirmedProfile =
                    confirmed.successorTransition().optJSONObject("profile");
            assertNotNull(confirmedProfile);
            assertEquals(fixture.successorProfile.optString("mode"), confirmedProfile.optString("mode"));
            assertEquals(fixture.successorProfile.optString("model"), confirmedProfile.optString("model"));
            assertEquals(fixture.successorProfile.optString("reasoning"), confirmedProfile.optString("reasoning"));
        }
    }

    @Test public void acceptedPredecessorSurvivesServiceRestartWithoutResultRereadOrDuplicates()
            throws Exception {
        try (ActivityScenario<SelfRunNewActivity> ignored =
                     ActivityScenario.launch(SelfRunNewActivity.class)) {
            installFixture(10L, 250L);
            SelfRun3Coordinator.dropAcceptedResultCompletionOnceForTest();
            startService(SelfRunService.ACTION_RUN, null);

            SelfRun3Engine.State accepted = awaitState(state ->
                    state.turn() == 1
                            && state.hasResult()
                            && state.stage() == SelfRun3Engine.Stage.RECONCILING
                            && SelfRun3SuccessorTransitionPolicy.armed(state), 10_000L);
            assertEquals(1, drive.predecessorReads.get());
            JSONObject acceptedTransition = accepted.successorTransition();

            assertTrue(context.stopService(new Intent(context, SelfRunService.class)));
            SystemClock.sleep(350L);
            startService(SelfRunService.ACTION_RUN, null);

            SelfRun3Engine.State confirmed = awaitState(state ->
                    state.turn() == 2
                            && state.flag("accepted")
                            && "CANONICAL_CONFIRMED".equals(
                            state.successorTransition().optString("stage")), 15_000L);

            assertEquals(acceptedTransition.optString("predecessorTurnId"),
                    confirmed.successorTransition().optString("predecessorTurnId"));
            assertEquals(acceptedTransition.optString("predecessorResultDocumentId"),
                    confirmed.text("previousResultDocumentId"));
            assertEquals(1, drive.predecessorReads.get());
            assertEquals(1, drive.successorDocumentAllocations.get());
            assertEquals(1, page.canonicalPosts.get());
        }
    }

    private Fixture installFixture(long preparationSeconds, long revealComposerAfterMs)
            throws Exception {
        SelfRun3RuntimeSettings settings = new SelfRun3RuntimeSettings(context);
        assertTrue(settings.saveWorkMode(SelfRun3RuntimeSettings.WorkMode.ON_DEVICE));
        assertTrue(settings.saveWebPreparationSeconds(String.valueOf(preparationSeconds)));
        assertEquals(preparationSeconds * 1_000L, settings.webPreparationMs());

        runId = "SR-RUNTIME-" + UUID.randomUUID();
        predecessorTurnId = runId + ":turn:1";

        SelfRunStore store = new SelfRunStore(context);
        store.bindBaseFolder(ACCOUNT, BASE_FOLDER, "Runs", "", 1L);
        store.start(runId, SelfRunStore.MODE_CHAT, SelfRunScript.GENERAL_CHAT_URL,
                "runtime successor fixture");
        assertTrue(SelfRun3RunMarker.mark(context, runId));

        JSONObject config = new JSONObject()
                .put("mode", "CHAT")
                .put("taskMode", "CHAT")
                .put("model", "gpt-5-6-thinking")
                .put("reasoning", "medium")
                .put("projectUrl", SelfRunScript.GENERAL_CHAT_URL)
                .put("accountId", ACCOUNT)
                .put("baseFolderId", BASE_FOLDER)
                .put("requirement", "runtime successor fixture");

        SelfRun3Engine.State waiting;
        try (SelfRun3Ledger ledger = new SelfRun3Ledger(context)) {
            SelfRun3Engine.State state = ledger.ensure(
                    SelfRun3Engine.create(runId, predecessorTurnId, config));
            state = resource(ledger, state, "folderId", "fixture-folder");
            state = resource(ledger, state, "requirementDocumentId", "fixture-requirement");
            state = apply(ledger, state, SelfRun3Engine.Kind.SETUP_DONE, new JSONObject(),
                    "setup");
            state = resource(ledger, state, "resultDocumentId", PREDECESSOR_RESULT);
            state = apply(ledger, state, SelfRun3Engine.Kind.TURN_READY,
                    new JSONObject()
                            .put("prompt", "seed predecessor")
                            .put("inputText", "")
                            .put("inputRevision", 0L),
                    "ready");
            state = apply(ledger, state, SelfRun3Engine.Kind.CLAIM_SEND,
                    new JSONObject().put("at", System.currentTimeMillis()), "claim");
            waiting = resource(ledger, state, "conversationUrl", PREDECESSOR_CONVERSATION);
            assertEquals(SelfRun3Engine.Stage.DISPATCHING, waiting.stage());
            assertFalse(waiting.hasResult());
        }

        JSONObject successorProfile = new JSONObject()
                .put("mode", "CHAT")
                .put("model", "gpt-5-6-thinking")
                .put("reasoning", "medium");
        JSONObject committed = SelfRun3Engine.emptyResult(waiting)
                .put("committed", true)
                .put("status", "CONTINUE")
                .put("phase_completed", "WORK_PARTIAL")
                .put("next_phase", "WORK")
                .put("next_execution", new JSONObject()
                        .put("type", "SERIAL")
                        .put("objective", "automatic successor runtime fixture")
                        .put("profile", successorProfile))
                .put("next_profile", successorProfile);

        drive = new FixtureDriveTransport(predecessorTurnId, committed.toString());
        page = new FixturePageTransport(revealComposerAfterMs);
        SelfRun3DriveAdapter.installTestTransport(drive);
        SelfRun3WebAdapter.installTestPageTransport(page);
        return new Fixture(successorProfile);
    }

    private void startService(String action, JSONObject extras) {
        Intent intent = new Intent(context, SelfRunService.class).setAction(action);
        if (extras != null) {
            if (extras.has("predecessor")) {
                intent.putExtra(SelfRun3SuccessorWakeScheduler.EXTRA_PREDECESSOR_TURN_ID,
                        extras.optString("predecessor"));
            }
            if (extras.has("attempt")) {
                intent.putExtra(SelfRun3SuccessorWakeScheduler.EXTRA_RECOVERY_ATTEMPT,
                        extras.optInt("attempt", -1));
            }
        }
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    private void fireWatchdog(String predecessor, int attempt) {
        JSONObject extras = new JSONObject();
        SelfRun3Engine.put(extras, "predecessor", predecessor);
        SelfRun3Engine.put(extras, "attempt", attempt);
        startService(SelfRunService.ACTION_SUCCESSOR_WATCHDOG_WAKE, extras);
    }

    private SelfRun3Engine.State awaitState(
            Predicate<SelfRun3Engine.State> predicate, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        SelfRun3Engine.State last = null;
        do {
            last = load();
            if (last != null && predicate.test(last)) return last;
            SystemClock.sleep(50L);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("timed out waiting for runtime state; last="
                + (last == null ? "null" : last.json().toString()));
        return null;
    }

    private SelfRun3Engine.State load() {
        try (SelfRun3Ledger ledger = new SelfRun3Ledger(context)) {
            return ledger.load(runId);
        }
    }

    private int currentBootCount() {
        try {
            return android.provider.Settings.Global.getInt(
                    context.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT);
        } catch (Throwable unavailable) {
            return -1;
        }
    }

    private SelfRun3Engine.State resource(SelfRun3Ledger ledger, SelfRun3Engine.State state,
                                           String key, String value) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", key);
        SelfRun3Engine.put(payload, "value", value);
        return apply(ledger, state, SelfRun3Engine.Kind.RESOURCE,
                payload, "resource-" + key);
    }

    private SelfRun3Engine.State apply(SelfRun3Ledger ledger, SelfRun3Engine.State state,
                                        SelfRun3Engine.Kind kind, JSONObject payload, String suffix) {
        return ledger.apply(new SelfRun3Engine.Event(
                runId + ":seed:" + suffix,
                kind, state.taskId(), state.turnId(), payload));
    }

    private void deleteLedgerFiles() {
        if (ledgerFile == null) return;
        for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
            File file = new File(ledgerFile.getAbsolutePath() + suffix);
            if (file.exists()) assertTrue("failed to delete " + file, file.delete());
        }
    }

    private static final class Fixture {
        final JSONObject successorProfile;
        Fixture(JSONObject successorProfile) {
            this.successorProfile = SelfRun3Engine.copy(successorProfile);
        }
    }

    private static final class FixtureDriveTransport implements SelfRun3DriveAdapter.TestTransport {
        final String predecessorTurnId;
        final String predecessorResult;
        final AtomicInteger predecessorReads = new AtomicInteger();
        final AtomicInteger successorDocumentAllocations = new AtomicInteger();

        FixtureDriveTransport(String predecessorTurnId, String predecessorResult) {
            this.predecessorTurnId = predecessorTurnId;
            this.predecessorResult = predecessorResult;
        }

        @Override public String resultDocumentId(SelfRun3Engine.State state) {
            successorDocumentAllocations.incrementAndGet();
            return "fixture-result-turn-" + state.turn();
        }

        @Override public SelfRun3DriveAdapter.ResultObservation observeResult(
                SelfRun3Engine.State state) {
            if (predecessorTurnId.equals(state.turnId())) {
                int read = predecessorReads.incrementAndGet();
                return new SelfRun3DriveAdapter.ResultObservation(
                        "fixture-predecessor-v" + read, predecessorResult, predecessorResult);
            }
            String pending = SelfRun3Engine.emptyResult(state).toString();
            return new SelfRun3DriveAdapter.ResultObservation(
                    "fixture-successor-pending", pending, pending);
        }
    }

    private static final class FixturePageTransport
            implements SelfRun3WebAdapter.TestPageTransport {
        final long revealComposerAfterMs;
        final AtomicInteger pageLoads = new AtomicInteger();
        final AtomicInteger canonicalPosts = new AtomicInteger();

        FixturePageTransport(long revealComposerAfterMs) {
            this.revealComposerAfterMs = revealComposerAfterMs;
        }

        @Override public void load(WebView web, String url) {
            pageLoads.incrementAndGet();
            web.loadDataWithBaseURL("https://chatgpt.com/", html(revealComposerAfterMs),
                    "text/html", "UTF-8", null);
        }

        @Override public WebResourceResponse intercept(WebResourceRequest request) {
            if (request != null
                    && "POST".equalsIgnoreCase(request.getMethod())
                    && request.getUrl() != null
                    && "/backend-api/f/conversation".equals(request.getUrl().getPath())) {
                canonicalPosts.incrementAndGet();
                return new WebResourceResponse(
                        "application/json", "UTF-8",
                        new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
            }
            return null;
        }

        private static String html(long revealAfterMs) {
            return "<!doctype html><html><head><meta name='viewport' content='width=device-width'></head>"
                    + "<body><main><form id='composer'>"
                    + "<textarea id='prompt-textarea' data-testid='prompt-textarea' "
                    + "style='display:none'></textarea>"
                    + "<button type='submit' data-testid='send-button' aria-label='Send'>Send</button>"
                    + "</form></main><script>"
                    + "window.fixturePosts=[];"
                    + "const editor=document.querySelector('#prompt-textarea');"
                    + "setTimeout(()=>{editor.style.display='block';}," + Math.max(0L, revealAfterMs) + ");"
                    + "document.querySelector('#composer').addEventListener('submit',e=>{"
                    + "e.preventDefault();"
                    + "const prompt=editor.value;"
                    + "window.fixturePosts.push(prompt);"
                    + "fetch('/backend-api/f/conversation',{method:'POST',headers:{'Content-Type':'application/json'},"
                    + "body:JSON.stringify({messages:[{author:{role:'user'},content:{parts:[prompt]}}]})}).catch(()=>{});"
                    + "history.replaceState({},'', '/c/fixture-successor');"
                    + "});"
                    + "</script></body></html>";
        }
    }
}
