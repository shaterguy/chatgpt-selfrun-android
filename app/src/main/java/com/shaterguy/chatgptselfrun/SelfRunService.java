package com.shaterguy.chatgptselfrun;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Foreground shell for the ledger-driven SelfRun 3 coordinator. */
public final class SelfRunService extends Service {
    static final String ACTION_RUN = BuildConfig.APPLICATION_ID + ".RUN";
    static final String ACTION_PAUSE = BuildConfig.APPLICATION_ID + ".PAUSE";
    static final String ACTION_RESUME = BuildConfig.APPLICATION_ID + ".RESUME";
    static final String ACTION_STOP = BuildConfig.APPLICATION_ID + ".STOP";
    private static final int NOTIFICATION_ID = 17021;

    private SelfRunStore store;
    private SelfRunRunLog runLog;
    private SelfRun3Coordinator coordinator;

    @Override public void onCreate() {
        super.onCreate();
        store = new SelfRunStore(this);
        runLog = new SelfRunRunLog(this);
        NotificationHelper.ensureChannel(this);
        coordinator = new SelfRun3Coordinator(this, store, runLog);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null || intent.getAction() == null ? ACTION_RUN : intent.getAction();
        if (!ACTION_RUN.equals(action) && !ACTION_PAUSE.equals(action)
                && !ACTION_RESUME.equals(action) && !ACTION_STOP.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(action) && !coordinator.ownsCurrentRun()) {
            if (!store.runId().isEmpty()) store.stopByUser();
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (!coordinator.ownsCurrentRun()) {
            // Never reinterpret an in-progress 2.x run as a 3.x task. Preserve it until the user
            // explicitly stops/restarts it; all newly-created 3.x runs carry an explicit marker.
            if (store.active() && !store.userStopped()
                    && !SelfRunStore.PHASE_DONE.equals(store.phase())
                    && !SelfRunStore.PHASE_IDLE.equals(store.phase())) {
                startForegroundCompat();
                store.setPaused(true);
                store.setLastError("V3_LEGACY_RUN_PRESERVED",
                        "업그레이드 전 실행은 자동 변환하지 않았습니다. 기존 상태를 보존했습니다.");
                runLog.record(store, "V3_LEGACY_RUN_PRESERVED", "action=" + action);
                return START_STICKY;
            }
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (!store.active() && !ACTION_RESUME.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startForegroundCompat();
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
        if (coordinator != null) coordinator.destroy();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
