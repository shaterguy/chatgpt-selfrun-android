package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public class ConversationFreshnessBarrierTest {
    @Test public void manualResumeRefreshesCanonicalConversationBeforeContinue() throws Exception {
        String st=src("SelfRunStore.java"),s=src("SelfRunService.java"),b=between(st,"void baselineManualResume","static boolean canCaptureConversationUrl");
        assertTrue(b.contains("conversationSyncNextPhase"));assertTrue(b.contains("PHASE_SYNC_CONVERSATION"));assertTrue(b.contains("PHASE_SEND_CONTINUE"));
        assertTrue(s.contains("startConversationSyncNavigation"));assertTrue(s.contains("postVisualStateCallback"));
    }
    @Test public void everyContinuationPassesConversationFreshnessBarrier() throws Exception {
        String s=src("SelfRunService.java"),st=src("SelfRunStore.java");
        assertTrue(between(s,"private void guardElapsed","private void ensureWebView").contains("enterConversationSync"));
        assertTrue(between(st,"void prepareCommandRetry","void applyDriveSignals").contains("PHASE_SYNC_CONVERSATION"));
        assertTrue(s.contains("if(isContinuationPhase(phase)&&!freshnessValid())"));
    }
    @Test public void preservedWebViewIsNotDestroyedDuringPause() throws Exception {
        String s=src("SelfRunService.java"),p=between(s,"private void enterPreservedPause","private void removeAutomationCallbacks");
        assertTrue(p.contains("pauseWebView()"));assertFalse(p.contains("cleanupWebView()"));assertTrue(s.contains("webview=preserved"));
    }
    @Test public void staleConversationCannotSubmitBeforeRefresh() throws Exception {
        String s=src("SelfRunService.java"),d=src("SelfRunDom.java");
        assertTrue(s.contains("if(isContinuationPhase(phase)&&!freshnessValid())"));assertTrue(d.contains("FRESHNESS_STALE"));assertTrue(d.contains("__selfRunDriveFreshnessToken"));
    }
    @Test public void refreshCompletionAllowsExactlyOneContinue() throws Exception {
        String s=src("SelfRunService.java"),d=src("SelfRunDom.java");
        assertTrue(s.contains("String next=store.finishConversationSync()"));assertTrue(s.contains("CONVERSATION_SYNC_READY"));
        assertEquals(1,count(between(d,"static String clickPreparedDriveTurn(String conversationUrl,String prompt,String markerId,String freshnessToken)","private static String projectGuard"),"send.click()"));
    }
    @Test public void navigationDuringPreparedSubmissionInvalidatesAttempt() throws Exception {
        String s=src("SelfRunService.java"),d=src("SelfRunDom.java");
        assertTrue(s.contains("onMainFramePageStarted"));assertTrue(s.contains("invalidateConversationFreshness"));
        assertTrue(d.contains("pagehide"));assertTrue(d.contains("prepared continuation belongs to stale generation"));assertTrue(d.contains("__selfRunDrivePreparedContinuation=null"));
    }
    @Test public void latestComposerSafetyStillPasses() throws Exception {
        String script=SelfRunDom.prepareDriveTurn("https://chatgpt.com/c/conversation123","continue","m","1:2");
        assertTrue(script.contains("__srComposerPool"));assertTrue(script.contains("xs[xs.length-1]"));assertTrue(script.contains("scope.contains(calibrated)"));assertTrue(script.contains("__srTurnContained"));
    }
    @Test public void editComposerNeverWinsOverMainComposer() {
        String script=SelfRunDom.prepareDriveTurn("https://chatgpt.com/c/conversation123","continue","m","1:2");
        assertTrue(script.contains("__srEditContext"));assertTrue(script.contains("__srMainComposer"));assertTrue(script.contains("safeCalibratedComposer=__srMainComposer(calibratedComposer)?calibratedComposer:null"));
    }
    @Test public void generalChatAndProjectConversationBothRefreshCorrectlyWithoutReload() throws Exception {
        String s=src("SelfRunService.java"),sync=between(s,"private void startConversationSyncNavigation","private void onMainFramePageStarted");
        assertTrue(ProjectUrlPolicy.sameConversation("https://chatgpt.com/c/a","https://chatgpt.com/c/a"));
        assertTrue(ProjectUrlPolicy.sameConversation("https://chatgpt.com/g/g-p-test/c/a","https://chatgpt.com/g/g-p-test/c/a"));
        assertTrue(sync.contains("activeConversationSyncNavigation=\"reuse\""));
        assertTrue(sync.contains("requestConversationVisualReady(webView,activeConversationSyncEpoch,generation)"));
        assertFalse(sync.contains("webView.reload()"));
        assertTrue(sync.contains("webView.loadUrl(canonical)"));
    }
    @Test public void syncCapturesLiveConversationBeforeMissingTargetDecision() throws Exception {
        String s=src("SelfRunService.java"),ensure=between(s,"private void ensureWebView(){","private void launchWebView");
        int capture=ensure.indexOf("maybeCaptureConversationUrl(webView.getUrl())");
        int missing=ensure.indexOf("CONVERSATION_SYNC_TARGET_MISSING");
        assertTrue(capture>=0);assertTrue(missing>capture);
        assertTrue(ensure.contains("enterPreservedPause(\"CONVERSATION_SYNC_TARGET_MISSING\""));
        assertFalse(ensure.contains("handler.postDelayed(this::ensureWebView,2000L)"));
    }
    @Test public void matchingConversationSyncDoesNotAddNetworkReload() throws Exception {
        String s=src("SelfRunService.java"),sync=between(s,"private void startConversationSyncNavigation","private void onMainFramePageStarted");
        assertFalse(sync.contains("reload()"));
        assertTrue(sync.contains("if(match)"));
        assertTrue(sync.contains("requestConversationVisualReady(webView,activeConversationSyncEpoch,generation)"));
        assertEquals(1,count(sync,"webView.loadUrl(canonical)"));
    }
    @Test public void workAndChatModesBothPreserveBehavior() throws Exception {
        String s=src("SelfRunService.java"),g=between(s,"private void guardElapsed","private void ensureWebView");
        assertTrue(g.contains("MODE_WORK"));assertTrue(g.contains("PHASE_APPLY_PREFS"));assertTrue(g.contains("PHASE_SEND_CONTINUE"));
        assertTrue(s.contains("WorkPreferenceDom.modelForConversation"));assertTrue(s.contains("WorkPreferenceDom.reasoningForConversation"));
    }
    @Test public void commandAckAndDrivePollingRegression() throws Exception {
        String s=src("SelfRunService.java"),st=src("SelfRunStore.java");
        assertTrue(s.contains("SUBMISSION_RETRY_MS = 5 * 60_000L"));assertTrue(s.contains("DriveSignalParser.scan"));assertTrue(st.contains("COMMAND_RECEIVED_PENDING"));
        assertTrue(between(st,"void prepareCommandRetry","void applyDriveSignals").contains("RETRY_BOOTSTRAP"));assertTrue(st.contains("case USER_ACTION_REQUIRED"));assertTrue(st.contains("case PAUSED"));assertTrue(st.contains("case DONE"));
    }
    @Test public void repeatedRefreshCallbacksCannotDoubleSubmit() throws Exception {
        String s=src("SelfRunService.java");
        assertTrue(s.contains("sync!=activeConversationSyncEpoch"));assertTrue(s.contains("expectedGeneration!=generation"));assertTrue(s.contains("conversationSyncInFlight"));assertTrue(s.contains("CONVERSATION_SYNC_DISCARDED"));
        assertTrue(s.contains("requestId!=activeConversationVisualRequestId"));assertTrue(s.contains("activeConversationVisualRequestId=0L"));
    }
    @Test public void visualReadyCallbackAndTimeoutHaveSingleWinner() throws Exception {
        String s=src("SelfRunService.java");
        assertTrue(s.contains("activeConversationVisualRequestId"));
        assertTrue(s.contains("handler.postDelayed(()->onConversationVisualReady(view,sync,expectedGeneration,true,requestId),1500L)"));
        assertTrue(s.contains("if(requestId!=activeConversationVisualRequestId)"));
        assertTrue(s.contains("activeConversationVisualRequestId=0L"));
    }
    @Test public void editComposerNestedUnderGenericFormStillFailsClosed() {
        String script=SelfRunDom.prepareDriveTurn("https://chatgpt.com/c/conversation123","continue","m","1:2");
        assertTrue(script.contains("for(let n=e;n;n=n.parentElement)"));
        assertTrue(script.contains("role==='dialog'"));
        assertTrue(script.contains("message-edit"));
        assertFalse(script.contains("e.closest('[data-testid*=\"edit\"],[data-testid*=\"message-edit\"],[role=\"dialog\"],form')"));
    }
    @Test public void replacedPreparedComposerCannotSubmitEvenWhenTextMatches() {
        String prepare=SelfRunDom.prepareDriveTurn("https://chatgpt.com/c/conversation123","continue","m","1:2");
        String click=SelfRunDom.clickPreparedDriveTurn("https://chatgpt.com/c/conversation123","continue","m","1:2");
        assertTrue(prepare.contains("window.__selfRunDrivePreparedContinuation={markerKey:markerKey2,composer,freshnessToken:__srFreshnessToken,clicked:false}"));
        assertTrue(click.contains("prepared.composer!==composer"));
        assertTrue(click.contains("prepared.clicked"));
        assertTrue(click.contains("SUBMISSION_PENDING"));
        assertTrue(click.indexOf("prepared.composer!==composer")<click.indexOf("send.click()"));
    }
    @Test public void staleContinuationCallbackHasPrivacySafeAbortDiagnostic() throws Exception {
        String s=src("SelfRunService.java");
        assertTrue(s.contains("CONTINUE_SUBMIT_ABORT"));
        assertTrue(s.contains("SelfRunWebDiagnostics.abortDetail(\"stale_callback\""));
        String detail=SelfRunWebDiagnostics.abortDetail("stale_callback",false,false,false);
        assertEquals("abort=stale_callback;webview_match=0;generation_match=0;freshness_match=0",detail);
        assertFalse(detail.contains("chatgpt.com"));
        assertFalse(detail.contains("conversation123"));
    }
    private static int count(String s,String token){int n=0,i=0;while((i=s.indexOf(token,i))>=0){n++;i+=token.length();}return n;}
    private static String src(String f)throws Exception{Path p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/"+f);if(!Files.exists(p))p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun/"+f);return new String(Files.readAllBytes(p),StandardCharsets.UTF_8);}
    private static String between(String s,String a,String b){int x=s.indexOf(a),y=s.indexOf(b,x);assertTrue(x>=0&&y>x);return s.substring(x,y);}
}
