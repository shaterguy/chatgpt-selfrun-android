package com.shaterguy.chatgptselfrun;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

final class NotificationHelper {
    private static final String GROUP = "selfrun-drive";
    private static final String RUNNING_CHANNEL = "selfrun-drive-running-v2";
    private static final String ALERT_CHANNEL = "selfrun-drive-alerts-v2";
    private static final int ALERT_ID = 17022;
    private static final long PAUSE_ALERT_DEDUP_MS = 10_000L;
    private static String lastPauseAlertText = "";
    private static long lastPauseAlertAt;

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

    static Notification active(Context context, String runtimeStatus) {
        ensureChannel(context);
        maybeNotifyPause(context, runtimeStatus);
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

    static boolean shouldAlertForPause(String runtimeStatus) {
        return runtimeStatus != null && runtimeStatus.contains("일시정지");
    }

    private static void maybeNotifyPause(Context context, String runtimeStatus) {
        if (!shouldAlertForPause(runtimeStatus)) return;
        long now = SystemClock.elapsedRealtime();
        synchronized (NotificationHelper.class) {
            if (runtimeStatus.equals(lastPauseAlertText)
                    && now - lastPauseAlertAt < PAUSE_ALERT_DEDUP_MS) return;
            lastPauseAlertText = runtimeStatus;
            lastPauseAlertAt = now;
        }
        notifyUser(context, "일시정지", runtimeStatus);
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
