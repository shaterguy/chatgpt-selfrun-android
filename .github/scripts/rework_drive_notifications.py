#!/usr/bin/env python3
from pathlib import Path
from textwrap import dedent
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    result, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one regex match, got {count}")
    return result


build = Path("app/build.gradle")
b = build.read_text()
b = replace_once(b, "def selfRunDriveVersionCode = 1000005", "def selfRunDriveVersionCode = 1000006", "versionCode")
b = replace_once(b, "def selfRunDriveVersionName = '1.1.0-dev1'", "def selfRunDriveVersionName = '1.1.0-dev2'", "versionName")
build.write_text(b)

Path("app/src/main/java/com/shaterguy/chatgptselfrun/NotificationHelper.java").write_text(dedent('''\
package com.shaterguy.chatgptselfrun;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class NotificationHelper {
    private static final String GROUP = "selfrun-drive";
    private static final String RUNNING_CHANNEL = "selfrun-drive-running-v2";
    private static final String ALERT_CHANNEL = "selfrun-drive-alerts-v2";
    private static final int ALERT_ID = 17022;

    private NotificationHelper() {}

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannelGroup(new android.app.NotificationChannelGroup(
                GROUP, "SelfRun Drive"));

        NotificationChannel running = new NotificationChannel(
                RUNNING_CHANNEL, "SelfRun Drive 실행 중", NotificationManager.IMPORTANCE_LOW);
        running.setGroup(GROUP);
        running.setDescription("SelfRun Drive가 실행 중임만 표시하는 무음 알림");
        running.setSound(null, null);
        running.enableVibration(false);
        running.setShowBadge(false);
        manager.createNotificationChannel(running);

        NotificationChannel alerts = new NotificationChannel(
                ALERT_CHANNEL, "SelfRun Drive 중요 알림", NotificationManager.IMPORTANCE_HIGH);
        alerts.setGroup(GROUP);
        alerts.setDescription("사용자 조치, 일시정지, 완료 시 표시하는 중요 알림");
        alerts.setShowBadge(true);
        manager.createNotificationChannel(alerts);
    }

    static Notification active(Context context) {
        ensureChannel(context);
        PendingIntent pending = activityIntent(context, 17030);
        PendingIntent pause = serviceAction(context, SelfRunService.ACTION_PAUSE, 17031);
        PendingIntent resume = serviceAction(context, SelfRunService.ACTION_RESUME, 17032);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, RUNNING_CHANNEL)
                : new Notification.Builder(context);
        return builder
                .setContentTitle("SelfRun Drive")
                .setContentText("SelfRun 작업 중")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW)
                .setContentIntent(pending)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "일시정지", pause).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_play, "재개", resume).build())
                .build();
    }

    static void notifyUser(Context context, String title, String text) {
        ensureChannel(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, ALERT_CHANNEL)
                : new Notification.Builder(context);
        manager.notify(ALERT_ID, builder
                .setContentTitle("SelfRun Drive · " + title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_EVENT)
                .setPriority(Notification.PRIORITY_HIGH)
                .setContentIntent(activityIntent(context, 17033))
                .build());
    }

    private static PendingIntent activityIntent(Context context, int requestCode) {
        Intent intent = new Intent(context, MainActivity.class);
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent serviceAction(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, SelfRunService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(context, requestCode, intent, flags)
                : PendingIntent.getService(context, requestCode, intent, flags);
    }
}
'''))

service_path = Path("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java")
s = service_path.read_text()
if s.count("NotificationHelper.active(this, store.status())") != 2:
    raise SystemExit("active notification call count mismatch")
s = s.replace("NotificationHelper.active(this, store.status())", "NotificationHelper.active(this)")
s = replace_once(
    s,
    'case "PAUSED"->finishPersistedTerminalPause("DRIVE_PAUSED",false,owner,raw,type);case "USER_ACTION_REQUIRED"->finishPersistedTerminalPause("DRIVE_USER_ACTION_REQUIRED",true,owner,raw,type);',
    'case "PAUSED"->finishPersistedTerminalPause("DRIVE_PAUSED","일시정지",owner,raw,type);case "USER_ACTION_REQUIRED"->finishPersistedTerminalPause("DRIVE_USER_ACTION_REQUIRED","확인 필요",owner,raw,type);',
    "terminal pause mapping",
)
s = regex_once(
    s,
    r'    private void finishPersistedTerminalPause\(String cause, boolean notify, String ownerRunId,\n\s+String commitId, String type\) \{.*?\n    \}\n',
    dedent('''\
        private void finishPersistedTerminalPause(String cause, String alertTitle, String ownerRunId,
                                                  String commitId, String type) {
            if (!store.terminalSideEffectOwnedBy(ownerRunId, commitId, type)) return;
            stopAutomationCallbacks();
            releaseWakeLock();
            pauseWebView();
            runLog.record(store, "PAUSED", cause + ";webview=preserved;drive_ids=preserved");
            NotificationHelper.notifyUser(this, alertTitle, store.status());
            store.acknowledgeTerminalSideEffect(ownerRunId, commitId, type);
        }
    '''),
    "persisted pause side effect",
)
s = regex_once(
    s,
    r'    private void transition\(String next, String status, String reason\) \{.*?\n    \}\n',
    dedent('''\
        private void transition(String next, String status, String reason) {
            String prior = store.phase(); store.setPhase(next); store.setStatus(status);
            runLog.record(store, "STATE_TRANSITION", "from=" + prior + ";to=" + next + ";reason=" + reason);
        }
    '''),
    "routine transition notification repost",
)
s = regex_once(
    s,
    r'    private void pauseFromUi\(\) \{.*?\n    \}\n',
    dedent('''\
        private void pauseFromUi() {
            if (!canRun()) return;
            startForegroundCompat();
            enterPreservedPause("UI_PAUSE", "사용자 일시정지", false);
            NotificationHelper.notifyUser(this, "일시정지", store.status());
        }
    '''),
    "manual pause alert",
)
s = replace_once(
    s,
    'private void commandSubmitted(String kind,String detail){if(!canRun())return;long due=System.currentTimeMillis()+SUBMISSION_RETRY_MS;store.markCommandSubmitted(kind,due);runLog.record(store,"COMMAND_SUBMITTED_DRIVE_WAIT","kind="+kind+";attempt="+store.submissionRetryAttempt()+";retryDueAt="+due+";detail="+detail);startForegroundCompat();releaseWakeLock();scheduleDrivePoll(0L);}',
    'private void commandSubmitted(String kind,String detail){if(!canRun())return;long due=System.currentTimeMillis()+SUBMISSION_RETRY_MS;store.markCommandSubmitted(kind,due);runLog.record(store,"COMMAND_SUBMITTED_DRIVE_WAIT","kind="+kind+";attempt="+store.submissionRetryAttempt()+";retryDueAt="+due+";detail="+detail);releaseWakeLock();scheduleDrivePoll(0L);}',
    "command submission notification repost",
)
s = replace_once(
    s,
    '        runLog.record(store, "PAUSED", cause + ";webview=preserved;drive_ids=preserved"); startForegroundCompat();\n',
    '        runLog.record(store, "PAUSED", cause + ";webview=preserved;drive_ids=preserved");\n',
    "generic preserved pause notification repost",
)
if s.count("startForegroundCompat();") != 4:
    raise SystemExit(f"unexpected foreground post call count: {s.count('startForegroundCompat();')}")
service_path.write_text(s)

Path("app/src/test/java/com/shaterguy/chatgptselfrun/DriveVariantPolicyTest.java").write_text(dedent('''\
package com.shaterguy.chatgptselfrun;
import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;
public class DriveVariantPolicyTest {
 @Test public void developmentIdentityDefaultChatAndKeyboardVisibility() throws Exception {String g=read("app/build.gradle","build.gradle"),a=src("SelfRunNewActivity.java");assertTrue(g.contains("selfRunDriveVersionCode = 1000006"));assertTrue(g.contains("selfRunDriveVersionName = '1.1.0-dev2'"));assertTrue(g.contains("com.shaterguy.chatgptselfrun.drive"));assertTrue(a.contains("MODE_VALUES = {SelfRunStore.MODE_CHAT, SelfRunStore.MODE_WORK}"));assertTrue(a.contains("setMinLines(8)"));assertTrue(a.contains("setVerticalScrollBarEnabled(false)"));assertTrue(a.contains("descendantTopWithinScrollContent"));assertTrue(a.contains("outer.getPaddingBottom()"));assertTrue(a.contains("outer.scrollTo("));assertTrue(a.contains("addTextChangedListener"));assertFalse(a.contains("setMaxLines(24)"));assertFalse(a.contains("configureNestedCommandScrolling"));assertFalse(a.contains("requestRectangleOnScreen"));assertFalse(a.contains("getLocationOnScreen"));assertFalse(a.contains("WindowInsets"));assertFalse(a.contains("-editor.getScrollY()"));assertFalse(a.contains("Math.min(editor.getHeight()"));assertTrue(a.contains("RUN_SUFFIX_LENGTH = 6"));assertTrue(a.contains("Asia/Seoul"));assertFalse(a.contains("UUID.randomUUID"));}
 @Test public void signalCursorReplacesCommitMetadata() throws Exception {String s=src("SelfRunService.java"),st=src("SelfRunStore.java"),p=src("DriveCommitParser.java");assertTrue(s.contains("DriveSignalParser.scan"));assertTrue(st.contains("driveSignalCursor"));assertTrue(p.contains("SELF_RUN_COMMAND_RECEIVED"));assertTrue(p.contains("SELF_RUN_TURN_COMPLETED"));assertFalse(s.contains("DriveCommitParser"));assertFalse(p.contains("EVENT_SEQ"));assertFalse(p.contains("PROTOCOL_VERSION"));}
 @Test public void noCompletionDomGate() throws Exception {String s=src("SelfRunService.java");assertFalse(s.contains("checkDriveTurnSubmitted"));assertFalse(s.contains("observeAssistant"));assertTrue(s.contains("CONTINUATION_GUARD_MS = 45_000L"));assertTrue(s.contains("SUBMISSION_RETRY_MS = 5 * 60_000L"));}
 @Test public void workDocIdentityMetadataIsMinimal() throws Exception {String a=src("DriveApiClient.java");assertTrue(a.contains(".put(\\\"job_id\\\", name)"));assertTrue(a.contains(".put(\\\"selfrun_kind\\\", kind)"));assertFalse(a.contains(".put(\\\"protocol_version\\\""));assertFalse(a.contains(".put(\\\"client_id\\\""));}
 @Test public void routineNotificationIsSilentAndAlertsAreEventDriven() throws Exception {String n=src("NotificationHelper.java"),s=src("SelfRunService.java");assertTrue(n.contains("RUNNING_CHANNEL = \\\"selfrun-drive-running-v2\\\""));assertTrue(n.contains("ALERT_CHANNEL = \\\"selfrun-drive-alerts-v2\\\""));assertTrue(n.contains("NotificationManager.IMPORTANCE_LOW"));assertTrue(n.contains("running.setSound(null, null)"));assertTrue(n.contains("running.enableVibration(false)"));assertTrue(n.contains(".setContentText(\\\"SelfRun 작업 중\\\")"));assertTrue(n.contains("NotificationManager.IMPORTANCE_HIGH"));assertTrue(n.contains("static Notification active(Context context)"));assertFalse(n.contains("runtimeStatus"));assertFalse(n.contains("maybeNotifyPause"));assertFalse(n.contains("SystemClock"));assertTrue(s.contains("NotificationHelper.notifyUser(this, \\\"일시정지\\\", store.status())"));assertTrue(s.contains("case \\\"PAUSED\\\"->finishPersistedTerminalPause(\\\"DRIVE_PAUSED\\\",\\\"일시정지\\\""));assertTrue(s.contains("case \\\"USER_ACTION_REQUIRED\\\"->finishPersistedTerminalPause(\\\"DRIVE_USER_ACTION_REQUIRED\\\",\\\"확인 필요\\\""));assertFalse(section(s,"private void transition","private void pauseError").contains("startForegroundCompat();"));assertFalse(section(s,"private void commandSubmitted","private void applyNextControl").contains("startForegroundCompat();"));}
 static String section(String s,String a,String b){int x=s.indexOf(a),y=s.indexOf(b,x);assertTrue(x>=0&&y>x);return s.substring(x,y);} static String src(String f)throws Exception{return read("app/src/main/java/com/shaterguy/chatgptselfrun/"+f,"src/main/java/com/shaterguy/chatgptselfrun/"+f);} static String read(String a,String b)throws Exception{Path p=Paths.get(a);if(!Files.exists(p))p=Paths.get(b);return new String(Files.readAllBytes(p),java.nio.charset.StandardCharsets.UTF_8);}
}
'''))

verify_path = Path("tools/verify_drive_variant.sh")
v = verify_path.read_text()
v = replace_once(v, "grep -Fq 'selfRunDriveVersionCode = 1000005' \"$BUILD\"", "grep -Fq 'selfRunDriveVersionCode = 1000006' \"$BUILD\"", "policy versionCode")
v = replace_once(v, "grep -Fq \"selfRunDriveVersionName = '1.1.0-dev1'\" \"$BUILD\"", "grep -Fq \"selfRunDriveVersionName = '1.1.0-dev2'\" \"$BUILD\"", "policy versionName")
v = replace_once(v, "grep -Fq 'runtimeStatus.contains(\"일시정지\")' \"$NOTIFICATION\"", dedent('''\
grep -Fq 'static Notification active(Context context)' "$NOTIFICATION"
! grep -Fq 'runtimeStatus' "$NOTIFICATION"
! grep -Fq 'maybeNotifyPause' "$NOTIFICATION"
! grep -Fq 'SystemClock' "$NOTIFICATION"
grep -Fq 'NotificationHelper.notifyUser(this, "일시정지", store.status())' "$SERVICE"
grep -Fq 'case "PAUSED"->finishPersistedTerminalPause("DRIVE_PAUSED","일시정지"' "$SERVICE"
grep -Fq 'case "USER_ACTION_REQUIRED"->finishPersistedTerminalPause("DRIVE_USER_ACTION_REQUIRED","확인 필요"' "$SERVICE"
TRANSITION_BLOCK="$(sed -n '/private void transition/,/private void pauseError/p' "$SERVICE")"
if grep -Fq 'startForegroundCompat();' <<<"$TRANSITION_BLOCK"; then
  echo 'routine transitions must not repost the foreground notification' >&2
  exit 1
fi
COMMAND_BLOCK="$(grep -F 'private void commandSubmitted' "$SERVICE")"
if grep -Fq 'startForegroundCompat();' <<<"$COMMAND_BLOCK"; then
  echo 'command submission must not repost the foreground notification' >&2
  exit 1
fi
FG_POST_COUNT="$(grep -o 'startForegroundCompat();' "$SERVICE" | wc -l | tr -d ' ')"
[[ "$FG_POST_COUNT" == '4' ]]
''').rstrip(), "policy event checks")
v = replace_once(v, "echo 'SelfRun Drive v1.1.0-dev1 policy checks passed.'", "echo 'SelfRun Drive v1.1.0-dev2 policy checks passed.'", "policy success label")
verify_path.write_text(v)
