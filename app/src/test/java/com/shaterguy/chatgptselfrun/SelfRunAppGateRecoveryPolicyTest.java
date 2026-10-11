package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import static org.junit.Assert.*;

/** Source-bound wiring checks supplement the isolated exact-consumer runtime fixtures. */
public final class SelfRunAppGateRecoveryPolicyTest {
    @Test public void ackPersistenceFailureCannotEncloseOrSkipServiceWake() throws Exception {
        String receiver = source("SelfRunFirebaseMessagingService");
        int ack = receiver.indexOf("AckState.RECEIVED");
        int handled = receiver.indexOf("catch (Throwable ackFailure)", ack);
        int wake = receiver.indexOf("Intent intent = event.serviceIntent(this);", handled);
        assertTrue(ack >= 0 && handled > ack && wake > handled);
        assertTrue(receiver.substring(handled, wake).contains("V3_PUSH_ACK_PERSIST_FAILED"));
        assertTrue(receiver.indexOf("event.installationId.equals") < ack);
    }

    @Test public void rejectedIdentityRetainsPollingWithoutConsumingForeignData() throws Exception {
        String remote = method(source("SelfRun4DriveWebAdapter"), "    private void handleRemote(");
        int identity = remote.indexOf("!SCHEMA.equals");
        int nextPoll = remote.indexOf("later(() -> poll(expectedGeneration, request), POLL_MS);", identity);
        int bodyAssignment = remote.indexOf("dispatchBody = body;");
        assertTrue(identity >= 0 && nextPoll > identity && nextPoll < bodyAssignment);
        assertFalse(remote.contains("server_status"));
    }

    @Test public void stopLocallyPersistsBeforeAnyExternalAcknowledgment() throws Exception {
        String stop = method(source("SelfRun3Coordinator"), "    private void stop()");
        assertTrue(stop.indexOf("store.markStopRequested();") < stop.indexOf("io.execute("));
        assertTrue(stop.indexOf("SelfRun3Engine.Kind.STOP") < stop.indexOf("web.publishControlStateConfirmed("));
        String store = source("SelfRunStore");
        assertTrue(store.contains("boolean stopControlPending() { return active() && userStopped(); }"));
        String mark = method(store, "    void markStopRequested()");
        assertTrue(mark.contains("commitOrThrow"));
        assertTrue(mark.contains(".putBoolean(\"userStopped\", true)"));
        assertFalse(mark.contains(".putBoolean(\"active\", false)"));
    }

    @Test public void stopDeliveryFailureRemainsVisibleWithoutReactivation() throws Exception {
        String failure = method(source("SelfRun3Coordinator"), "    private void onStopControlResult(");
        assertTrue(failure.contains("V3_STOP_CONTROL_PENDING"));
        assertTrue(failure.contains("main.postDelayed(this::stop, STOP_CONTROL_RETRY_MS)"));
        assertFalse(failure.contains("setUserStopped(false)"));
        assertFalse(failure.contains("startOrRecover"));
    }

    @Test public void restartAndDirectResumePreservePendingStopOwner() throws Exception {
        String coordinator = source("SelfRun3Coordinator");
        String start = method(coordinator, "    int onStart(");
        assertTrue(start.contains("if (store.stopControlPending()) action = BuildConfig.APPLICATION_ID + \".STOP\";"));
        assertTrue(start.contains("store.stopControlPending() ? Service.START_STICKY : Service.START_NOT_STICKY"));
        assertTrue(source("SelfRunService").contains("if (store.stopControlPending()) action = ACTION_STOP;"));
        assertTrue(method(coordinator, "    void onStoppedResumeAuthorized(").contains("STOP_CONTROL_PENDING"));
        assertTrue(method(source("SelfRunStoppedResume"), "    private boolean current(").contains("!store.stopControlPending()"));
    }

    @Test public void pendingStopCannotBeOverwrittenByNewTaskOrResume() throws Exception {
        assertTrue(method(source("SelfRunNewActivity"), "    private void startSelfRunWithCurrentProfiles()").contains("store.stopControlPending()"));
        String start = method(source("SelfRunStore"), "    private void startInternal(");
        int guard = start.indexOf("if (stopControlPending())");
        assertTrue(guard >= 0 && guard < start.indexOf(".putString(\"runId\", runId)"));
        assertTrue(method(source("SelfRunStoppedResume"), "    static boolean request(").contains("store.stopControlPending()"));
    }

    @Test public void stoppedLateConversationAndStartCannotMutateLedger() throws Exception {
        String coordinator = source("SelfRun3Coordinator");
        for (String signature : new String[]{"    @Override public void onConversation(", "    private void recordCallback("}) {
            String callback = method(coordinator, signature);
            int entry = callback.indexOf("if (!canRun()) return;");
            assertTrue(entry >= 0 && entry < callback.indexOf("io.execute("));
            int queued = callback.indexOf("!validEpoch(expectedEpoch) || !canRun()");
            assertTrue(queued >= 0 && queued < callback.indexOf("ledger.apply("));
        }
    }

    @Test public void lateCallbackErrorsCannotOverrideStopOrInvalidateItsConfirmation() throws Exception {
        String coordinator = source("SelfRun3Coordinator");
        for (String signature : new String[]{"    @Override public void onPrepared(", "    @Override public void onConversation(", "    private void recordCallback("}) {
            String callback = method(coordinator, signature);
            String failure = callback.substring(callback.lastIndexOf("catch (Throwable error)"));
            int fence = failure.indexOf("!validEpoch(expectedEpoch) || !canRun()");
            assertTrue(fence >= 0 && fence < failure.indexOf("hardPause("));
        }
    }

    private static String source(String name) throws Exception {
        String override = System.getProperty("selfrun.sourceRoot");
        if (override != null) return new String(Files.readAllBytes(Path.of(override, name + ".java")), StandardCharsets.UTF_8);
        Path root = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun");
        if (!Files.isDirectory(root)) root = Path.of("src/main/java/com/shaterguy/chatgptselfrun");
        return new String(Files.readAllBytes(root.resolve(name + ".java")), StandardCharsets.UTF_8);
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("missing method: " + signature, start >= 0);
        int at = source.indexOf('{', start), depth = 1, end = at + 1;
        while (depth > 0) {
            char c = source.charAt(end++);
            if (c == '{') depth++;
            if (c == '}') depth--;
        }
        return source.substring(start, end);
    }
}
