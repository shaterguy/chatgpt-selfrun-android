package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class ConversationFreshnessBarrierTest {
    @Test public void manualResumeStillRoutesThroughConversationSync() throws Exception {
        String st=src("SelfRunStore.java"),s=src("SelfRunService.java"),b=between(st,"void baselineManualResume","static boolean canCaptureConversationUrl");
        assertTrue(b.contains("conversationSyncNextPhase"));
        assertTrue(b.contains("PHASE_SYNC_CONVERSATION"));
        assertTrue(b.contains("PHASE_SEND_CONTINUE"));
        assertTrue(s.contains("enterConversationSync"));
    }

    @Test public void normalFreshnessPathHasNoReloadOrSameRouteLoadUrl() throws Exception {
        String s=src("SelfRunService.java");
        String sync=between(s,"private void startConversationSyncNavigation","private void onMainFramePageStarted");
        assertFalse(sync.contains("webView.reload()"));
        assertFalse(sync.contains("if(match)webView.reload()"));
        assertTrue(sync.contains("activeConversationSyncNavigation=\"reuse\""));
        assertTrue(sync.contains("if(!match)"));
        assertTrue(sync.contains("loadUrl_recovery"));
    }

    @Test public void continuationFreshnessDependsOnNativeProbeProof() throws Exception {
        String s=src("SelfRunService.java");
        assertTrue(s.contains("ConversationSyncInstrumentation.Session"));
        assertTrue(s.contains("ConversationSyncInstrumentation.Proof"));
        assertTrue(s.contains("CONVERSATION_SYNC_PROVEN"));
        assertTrue(s.contains("CONVERSATION_SYNC_UNPROVEN"));
        assertTrue(s.contains("SUBMIT_BLOCKED_FRESHNESS"));
    }

    @Test public void probeIsInstalledBeforeInitialLoadUrl() throws Exception {
        String s=src("SelfRunService.java"),launch=between(s,"private void launchWebView","private boolean armBootstrapConversationCapture");
        int install=launch.indexOf("ConversationSyncInstrumentation.install");
        int load=launch.indexOf("webView.loadUrl(target)");
        assertTrue(install>=0 && load>install);
    }

    @Test public void preservedWebViewIsNotDestroyedDuringPause() throws Exception {
        String s=src("SelfRunService.java"),p=between(s,"private void enterPreservedPause","private void removeAutomationCallbacks");
        assertTrue(p.contains("pauseWebView()"));
        assertFalse(p.contains("cleanupWebView()"));
        assertTrue(s.contains("webview=preserved"));
    }

    @Test public void guardRemainsExactly45Seconds() throws Exception {
        String s=src("SelfRunService.java");
        assertTrue(s.contains("CONTINUATION_GUARD_MS = 45_000L"));
        assertTrue(s.contains("due-detected==CONTINUATION_GUARD_MS"));
    }

    @Test public void stopWaitIsExactlyTenSecondsAndNeverForced() throws Exception {
        String s=src("SelfRunService.java");
        assertTrue(s.contains("RESPONSE_ACTIVE_WAIT_MS = 10_000L"));
        assertTrue(s.contains("RESPONSE_ACTIVE_WAIT_10S"));
        assertFalse(s.contains("forceSend"));
        assertFalse(s.contains("clickStop"));
    }

    @Test public void rateLimitBackoffInvalidatesFreshnessWithoutNavigation() throws Exception {
        String s=src("SelfRunService.java"),rate=between(s,"private void handleWebRateLimit","private void onConversationProbeEvent");
        assertTrue(rate.contains("RATE_LIMIT_BACKOFF"));
        assertTrue(rate.contains("invalidateConversationFreshness"));
        assertTrue(rate.contains("forceDirty"));
        assertFalse(rate.contains("reload()"));
        assertFalse(rate.contains("loadUrl("));
    }

    @Test public void mainFrameRecoveryLoadUrlRemainsBoundedRecoveryOnly() throws Exception {
        String s=src("SelfRunService.java"),recovery=between(s,"private void handleMainFrameLoadError","private void postWebCallback");
        assertTrue(recovery.contains("MAX_MAIN_FRAME_RECOVERY_ATTEMPTS"));
        assertEquals(1,count(recovery,"view.loadUrl(target)"));
        assertFalse(recovery.contains("reload()"));
    }

    @Test public void commandAckDriveAndWorkRegressionRemain() throws Exception {
        String s=src("SelfRunService.java"),st=src("SelfRunStore.java");
        assertTrue(s.contains("DriveSignalParser.scan"));
        assertTrue(st.contains("COMMAND_RECEIVED_PENDING"));
        assertTrue(st.contains("case USER_ACTION_REQUIRED"));
        assertTrue(st.contains("case PAUSED"));
        assertTrue(st.contains("case DONE"));
        assertTrue(s.contains("WorkPreferenceDom.modelForConversation"));
        assertTrue(s.contains("WorkPreferenceDom.reasoningForConversation"));
    }

    @Test public void bootstrapConversationBindingStaysCausalAndLocal() throws Exception {
        String s=src("SelfRunService.java"),binding=between(s,"private boolean armBootstrapConversationCapture()","private void handleMainFrameLoadError");
        assertTrue(binding.contains("bootstrapConversationCaptureEpoch=automationEpoch"));
        assertTrue(binding.contains("bootstrapConversationCaptureRunId=store.runId()"));
        assertTrue(binding.contains("BOOTSTRAP_CONVERSATION_CAPTURE_WINDOW_MS"));
        assertFalse(binding.contains("reload("));
    }

    private static int count(String s,String token){int n=0,i=0;while((i=s.indexOf(token,i))>=0){n++;i+=token.length();}return n;}
    private static String src(String f)throws Exception{Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f);if(!Files.exists(p))p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f);return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);}
    private static String between(String s,String a,String b){int x=s.indexOf(a),y=s.indexOf(b,x);assertTrue(x>=0&&y>x);return s.substring(x,y);}
}
