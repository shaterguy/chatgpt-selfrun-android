package com.shaterguy.chatgptselfrun;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/** Low-frequency wake backstop for Handler delays when the CPU sleeps. */
final class SelfRunFallbackWakeScheduler {
    static final String EXTRA_SCHEDULED_AT_WALL_MS =
            BuildConfig.APPLICATION_ID + ".FALLBACK_SCHEDULED_AT_WALL_MS";
    private static final int REQUEST_CODE = 17025;

    private SelfRunFallbackWakeScheduler() { }

    static void schedule(Context context, long requestedDelayMs) {
        if (context == null) return;
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        long delayMs = SelfRunFallbackWakePolicy.effectiveDelayMs(requestedDelayMs);
        long triggerElapsed = SelfRunFallbackWakePolicy.saturatingAdd(
                SystemClock.elapsedRealtime(), delayMs);
        long scheduledAtWall = SelfRunFallbackWakePolicy.scheduledAt(
                System.currentTimeMillis(), requestedDelayMs);
        Intent intent = new Intent(context, SelfRunService.class)
                .setAction(SelfRunService.ACTION_RESULT_POLL_WAKE)
                .putExtra(EXTRA_SCHEDULED_AT_WALL_MS, scheduledAtWall);
        PendingIntent pending = PendingIntent.getService(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pending);
    }

    static void cancel(Context context) {
        if (context == null) return;
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        Intent intent = new Intent(context, SelfRunService.class)
                .setAction(SelfRunService.ACTION_RESULT_POLL_WAKE);
        PendingIntent pending = PendingIntent.getService(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pending == null) return;
        alarms.cancel(pending);
        pending.cancel();
    }
}
