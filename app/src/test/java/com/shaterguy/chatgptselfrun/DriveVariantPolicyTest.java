package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class DriveVariantPolicyTest {
    @Test public void driveIdentityDefaultChatAndKeyboardVisibility() throws Exception {
        String g=read("app/build.gradle","build.gradle"),a=src("SelfRunNewActivity.java");
        assertTrue(g.matches("(?s).*selfRunDriveVersionCode = [0-9]+.*"));
        assertTrue(g.matches("(?s).*selfRunDriveVersionName = '[0-9]+\\.[0-9]+\\.[0-9]+(-(dev|rc)[0-9]+)?'.*"));
        assertTrue(g.contains("com.shaterguy.chatgptselfrun.drive"));
        assertTrue(a.contains("MODE_VALUES = {SelfRunStore.MODE_CHAT, SelfRunStore.MODE_WORK}"));
        assertTrue(a.contains("requirement.setMinLines(Ui.isExpanded(this) ? 13 : 9)"));
        assertTrue(a.contains("setVerticalScrollBarEnabled(false)"));
        assertTrue(a.contains("descendantTopWithinScrollContent"));
        assertTrue(a.contains("outer.getPaddingBottom()"));
        assertTrue(a.contains("outer.scrollTo("));
        assertTrue(a.contains("addTextChangedListener"));
        assertFalse(a.contains("setMaxLines(24)"));
        assertFalse(a.contains("configureNestedCommandScrolling"));
        assertFalse(a.contains("requestRectangleOnScreen"));
        assertFalse(a.contains("getLocationOnScreen"));
        assertFalse(a.contains("WindowInsets"));
        assertFalse(a.contains("-editor.getScrollY()"));
        assertFalse(a.contains("Math.min(editor.getHeight()"));
        assertTrue(a.contains("RUN_SUFFIX_LENGTH = 6"));
        assertTrue(a.contains("Asia/Seoul"));
        assertFalse(a.contains("UUID.randomUUID"));
    }
    @Test public void globalRunsPathAndExistingFolderIdPolicyArePreserved() throws Exception {String setup=src("DriveSetupActivity.java"),service=src("SelfRunService.java"),auth=src("DriveAuthorization.java");assertTrue(setup.contains("/GPT/Self Run/Runs/"));assertFalse(setup.contains("/GPT/Project/Vibe Coding/00_System/SelfRun/Runs/"));assertTrue(service.contains("String base = driveOperationBaseFolderId;"));assertTrue(service.contains("drive.getMetadata(accessToken, base)"));assertTrue(service.contains("DRIVE_BASE_FOLDER_REBIND_REQUIRED"));assertTrue(auth.contains("static final String DRIVE_FILE_SCOPE = \"https://www.googleapis.com/auth/drive.file\";"));assertFalse(auth.contains("\"https://www.googleapis.com/auth/drive\";"));assertFalse(auth.contains("\"https://www.googleapis.com/auth/drive.readonly\";"));}
    @Test public void pickerOAuthScopeIsIsolatedFromRuntimeReadScopes() throws Exception {
        String auth=src("DriveAuthorization.java"),setup=src("DriveSetupActivity.java");
        String runtime=section(auth,"private static List<Scope> runtimeScopes()","private static List<Scope> pickerScopes()");
        String pickerScopes=section(auth,"private static List<Scope> pickerScopes()","static AuthorizationRequest silentRequest()");
        String pickerRequest=section(auth,"static AuthorizationRequest folderPickerRequest()","static void requestSilently");
        assertTrue(runtime.contains("DRIVE_FILE_SCOPE"));
        assertTrue(runtime.contains("DRIVE_METADATA_READONLY_SCOPE"));
        assertTrue(runtime.contains("DOCUMENTS_READONLY_SCOPE"));
        assertTrue(pickerScopes.contains("Collections.singletonList(new Scope(DRIVE_FILE_SCOPE))"));
        assertFalse(pickerScopes.contains("DRIVE_METADATA_READONLY_SCOPE"));
        assertFalse(pickerScopes.contains("DOCUMENTS_READONLY_SCOPE"));
        assertTrue(pickerRequest.contains("setRequestedScopes(pickerScopes())"));
        assertFalse(pickerRequest.contains("runtimeScopes()"));
        assertTrue(setup.contains("REQUEST_RUNTIME_AUTH"));
        assertTrue(setup.contains("DriveAuthorization.requestSilently(this"));
        assertTrue(setup.indexOf("DriveAuthorization.requestSilently(this") < setup.indexOf("DriveAuthorization.requestFolderPicker(this"));
        assertTrue(setup.contains("launchFolderPicker();"));
    }
    @Test public void signalCursorReplacesCommitMetadata() throws Exception {String s=src("SelfRunService.java"),st=src("SelfRunStore.java"),p=src("DriveCommitParser.java");assertTrue(s.contains("DriveSignalParser.scan"));assertTrue(st.contains("driveSignalCursor"));assertTrue(p.contains("SELF_RUN_TURN_COMPLETED"));assertFalse(p.contains("Type { COMMAND_RECEIVED"));assertFalse(s.contains("DriveCommitParser"));assertFalse(p.contains("EVENT_SEQ"));assertFalse(p.contains("PROTOCOL_VERSION"));}
    @Test public void continuationUsesDomVerificationWithoutAnyGuardPhase() throws Exception {String s=src("SelfRunService.java"),st=src("SelfRunStore.java");assertFalse(s.contains("checkDriveTurnSubmitted"));assertFalse(s.contains("observeAssistant"));assertFalse(s.contains("PHASE_READ_NEXT_CONTROL"));assertFalse(s.contains("CONTINUATION_GUARD_MS"));assertTrue(s.contains("CONTINUATION_VERIFY_INTERVAL_MS = 250L"));assertFalse(s.contains("CONTINUATION_FAILURE_MS"));assertFalse(s.contains("guardRunnable"));assertFalse(s.contains("scheduleGuard()"));assertFalse(s.contains("guardElapsed()"));assertFalse(s.contains("SelfRunContinuationDom.verifyDriveTurnSubmission"));assertTrue(st.contains("PHASE_WAIT_TURN_COMPLETION"));assertTrue(st.contains("PHASE_POST_DOM_DRIVE_SYNC"));assertTrue(s.contains("observeTurnCompletion"));assertFalse(s.contains("SelfRunContinuationDom.buttonState("));assertFalse(st.contains("\"DRIVE_COMMIT_GUARD\";"));}
    @Test public void workDocIdentityMetadataIsMinimal() throws Exception {String a=src("DriveApiClient.java");assertTrue(a.contains(".put(\"job_id\", name)"));assertTrue(a.contains(".put(\"selfrun_kind\", kind)"));assertFalse(a.contains(".put(\"protocol_version\""));assertFalse(a.contains(".put(\"client_id\""));}
    @Test public void routineNotificationIsSilentAndAlertsAreEventDriven() throws Exception {String n=src("NotificationHelper.java"),s=src("SelfRunService.java");assertTrue(n.contains("RUNNING_CHANNEL = \"selfrun-drive-running-v2\""));assertTrue(n.contains("ALERT_CHANNEL = \"selfrun-drive-alerts-v2\""));assertTrue(n.contains("NotificationManager.IMPORTANCE_LOW"));assertTrue(n.contains("running.setSound(null, null)"));assertTrue(n.contains("running.enableVibration(false)"));assertTrue(n.contains(".setContentText(\"SelfRun 작업 중\")"));assertTrue(n.contains("NotificationManager.IMPORTANCE_HIGH"));assertTrue(n.contains("static Notification active(Context context)"));assertFalse(n.contains("runtimeStatus"));assertFalse(n.contains("maybeNotifyPause"));assertFalse(n.contains("SystemClock"));assertTrue(s.contains("NotificationHelper.notifyUser(this, \"일시정지\", store.status())"));assertTrue(s.contains("case \"PAUSED\"->finishPersistedTerminalPause(\"DRIVE_PAUSED\",\"일시정지\""));assertTrue(s.contains("case \"USER_ACTION_REQUIRED\"->finishPersistedTerminalPause(\"DRIVE_USER_ACTION_REQUIRED\",\"확인 필요\""));assertFalse(section(s,"private void transition","private void pauseError").contains("startForegroundCompat();"));assertFalse(section(s,"private void bootstrapSubmitted","private String commandPrompt").contains("startForegroundCompat();"));}
    static String section(String s,String a,String b){int x=s.indexOf(a),y=s.indexOf(b,x);assertTrue(x>=0&&y>x);return s.substring(x,y);} static String src(String f)throws Exception{return read("app/src/main/java/com/shaterguy/chatgptselfrun/"+f,"src/main/java/com/shaterguy/chatgptselfrun/"+f);} static String read(String a,String b)throws Exception{Path p=Paths.get(a);if(!Files.exists(p))p=Paths.get(b);return new String(Files.readAllBytes(p),java.nio.charset.StandardCharsets.UTF_8);}
}
