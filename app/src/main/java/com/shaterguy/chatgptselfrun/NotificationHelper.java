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
    private static final String CHANNEL = "selfrun-drive-v1";
    private static final int ALERT_ID = 17022;
    private NotificationHelper() {}

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannelGroup(new android.app.NotificationChannelGroup(
                GROUP, "SelfRun Drive"));
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "SelfRun Drive 실행", NotificationManager.IMPORTANCE_LOW);
        channel.setGroup(GROUP);
        channel.setDescription("SelfRun Drive 실행 상태와 사용자 조치 알림");
        manager.createNotificationChannel(channel);
    }

    static Notification active(Context context, String text) {
        ensureChannel(context);
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = serviceAction(context, SelfRunService.ACTION_PAUSE, 17031);
        PendingIntent resume = serviceAction(context, SelfRunService.ACTION_RESUME, 17032);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        return builder
                .setContentTitle("SelfRun Drive")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setContentIntent(pending)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "일시정지", pause).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_play, "재개", resume).build())
                .build();
    }

    static void notifyUser(Context context, String title, String text) {
        ensureChannel(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        manager.notify(ALERT_ID, builder
                .setContentTitle("SelfRun Drive · " + title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .build());
    }

    private static PendingIntent serviceAction(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, SelfRunService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(context, requestCode, intent, flags)
                : PendingIntent.getService(context, requestCode, intent, flags);
    }
}
