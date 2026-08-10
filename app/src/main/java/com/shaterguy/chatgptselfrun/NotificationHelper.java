package com.shaterguy.chatgptselfrun;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class NotificationHelper {
    private static final String CHANNEL = "selfrun";
    private NotificationHelper() {}

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "SelfRun", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("ChatGPT SelfRun 실행 상태");
        manager.createNotificationChannel(channel);
    }

    static Notification active(Context context, String text) {
        ensureChannel(context);
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        return builder
                .setContentTitle("ChatGPT SelfRun")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setContentIntent(pending)
                .build();
    }

    static void notifyUser(Context context, String title, String text) {
        ensureChannel(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        manager.notify(7022, builder
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .build());
    }
}
