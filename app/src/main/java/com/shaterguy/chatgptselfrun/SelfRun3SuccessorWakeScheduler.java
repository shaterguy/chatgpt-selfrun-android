package com.shaterguy.chatgptselfrun;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import org.json.JSONObject;

/** AlarmManager backstop for the persisted automatic-successor transition deadline. */
final class SelfRun3SuccessorWakeScheduler {
    static final String EXTRA_PREDECESSOR_TURN_ID =
            BuildConfig.APPLICATION_ID + ".SUCCESSOR_PREDECESSOR_TURN_ID";
    static final String EXTRA_RECOVERY_ATTEMPT =
            BuildConfig.APPLICATION_ID + ".SUCCESSOR_RECOVERY_ATTEMPT";
    private static final int REQUEST_CODE = 17027;

    private SelfRun3SuccessorWakeScheduler() { }

    static void schedule(Context context, SelfRun3Engine.State state, long nowElapsed,
                         long nowWall, int currentBootCount) {
        if (context == null || !SelfRun3SuccessorTransitionPolicy.armed(state)) {
            cancel(context);
            return;
        }
        long remaining = SelfRun3SuccessorTransitionPolicy.remainingMs(
                state, nowElapsed, nowWall, currentBootCount);
        if (remaining < 0L) {
            cancel(context);
            return;
        }
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        JSONObject transition = state.successorTransition();
        Intent intent = new Intent(context, SelfRunService.class)
                .setAction(SelfRunService.ACTION_SUCCESSOR_WATCHDOG_WAKE)
                .putExtra(EXTRA_PREDECESSOR_TURN_ID, transition.optString("predecessorTurnId"))
                .putExtra(EXTRA_RECOVERY_ATTEMPT, transition.optInt("recoveryAttempt", 0));
        PendingIntent pending = PendingIntent.getService(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long trigger = saturatingAdd(SystemClock.elapsedRealtime(), Math.max(1L, remaining));
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending);
    }

    static void cancel(Context context) {
        if (context == null) return;
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        Intent intent = new Intent(context, SelfRunService.class)
                .setAction(SelfRunService.ACTION_SUCCESSOR_WATCHDOG_WAKE);
        PendingIntent pending = PendingIntent.getService(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pending == null) return;
        alarms.cancel(pending);
        pending.cancel();
    }

    private static long saturatingAdd(long base, long delta) {
        long safeBase = Math.max(0L, base);
        long safeDelta = Math.max(0L, delta);
        return safeBase > Long.MAX_VALUE - safeDelta ? Long.MAX_VALUE : safeBase + safeDelta;
    }
}
