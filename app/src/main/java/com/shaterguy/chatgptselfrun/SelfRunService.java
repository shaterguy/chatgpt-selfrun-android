package com.shaterguy.chatgptselfrun;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Foreground shell for the single active ledger-driven SelfRun 3 coordinator. */
public final class SelfRunService extends Service {
    static final String ACTION_RUN = BuildConfig.APPLICATION_ID + ".RUN";
    static final String ACTION_PAUSE = BuildConfig.APPLICATION_ID + ".PAUSE";
    static final String ACTION_RESUME = BuildConfig.APPLICATION_ID + ".RESUME";
    static final String ACTION_STOP = BuildConfig.APPLICATION_ID + ".STOP";
    private static final int NOTIFICATION_ID = 17021;

    private SelfRunStore store;
    private SelfRunRunLog runLog;
    private SelfRun3Coordinator coordinator;
    private SelfRun3TurnStallNotifier turnStallNotifier;

    @Override public void onCreate() {
        super.onCreate();
        store = new SelfRunStore(this);
        runLog = new SelfRunRunLog(this);
        NotificationHelper.ensureChannel(this);
        coordinator = new SelfRun3Coordinator(this, store, runLog);
        turnStallNotifier = new SelfRun3TurnStallNotifier(this, store);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null || intent.getAction() == null ? ACTION_RUN : intent.getAction();
        if (!ACTION_RUN.equals(action) && !ACTION_PAUSE.equals(action)
                && !ACTION_RESUME.equals(action) && !ACTION_STOP.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (!coordinator.ownsCurrentRun()) {
            if (!store.runId().isEmpty()) {
                runLog.record(store, "V3_STALE_RUN_RETIRED", "action=" + action);
                store.stopByUser();
            }
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (!store.active() && !ACTION_RESUME.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        turnStallNotifier.start();
        return coordinator.onStart(action);
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, NotificationHelper.active(this),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, NotificationHelper.active(this));
        }
    }

    @Override public void onDestroy() {
        if (turnStallNotifier != null) turnStallNotifier.close();
        if (coordinator != null) coordinator.destroy();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
