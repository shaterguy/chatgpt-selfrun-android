package com.shaterguy.chatgptselfrun;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Foreground shell for the single active ledger-driven SelfRun 3 coordinator. */
public final class SelfRunService extends Service {
    static final String ACTION_RUN = BuildConfig.APPLICATION_ID + ".RUN";
    static final String ACTION_PAUSE = BuildConfig.APPLICATION_ID + ".PAUSE";
    static final String ACTION_RESUME = BuildConfig.APPLICATION_ID + ".RESUME";
    static final String ACTION_RESUME_STOPPED = BuildConfig.APPLICATION_ID + ".RESUME_STOPPED";
    static final String ACTION_STOP = BuildConfig.APPLICATION_ID + ".STOP";
    static final String ACTION_PUSH_RESULT = BuildConfig.APPLICATION_ID + ".PUSH_RESULT";
    static final String ACTION_SERVER_RECOVERY = BuildConfig.APPLICATION_ID + ".SERVER_RECOVERY";
    static final String ACTION_SERVER_RESULT_RECHECK = BuildConfig.APPLICATION_ID + ".SERVER_RESULT_RECHECK";
    static final String ACTION_RESULT_POLL_WAKE = BuildConfig.APPLICATION_ID + ".RESULT_POLL_WAKE";
    static final String EXTRA_SERVER_RESULT_RECHECK_TURN_ID =
            BuildConfig.APPLICATION_ID + ".SERVER_RESULT_RECHECK_TURN_ID";
    static final String EXTRA_SERVER_RESULT_RECHECK_ATTEMPT =
            BuildConfig.APPLICATION_ID + ".SERVER_RESULT_RECHECK_ATTEMPT";
    private static final int NOTIFICATION_ID = 17021;

    private SelfRunStore store;
    private SelfRunRunLog runLog;
    private SelfRun3Coordinator coordinator;
    private SelfRunStoppedResume stoppedResume;
    private SelfRun3TurnStallNotifier turnStallNotifier;

    @Override public void onCreate() {
        super.onCreate();
        store = new SelfRunStore(this);
        runLog = new SelfRunRunLog(this);
        NotificationHelper.ensureChannel(this);
        coordinator = new SelfRun3Coordinator(this, store, runLog);
        stoppedResume = new SelfRunStoppedResume(this, store, runLog, coordinator);
        turnStallNotifier = new SelfRun3TurnStallNotifier(this, store);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null || intent.getAction() == null
                ? (stoppedResume.hasPending() ? ACTION_RESUME_STOPPED : ACTION_RUN)
                : intent.getAction();
        if (!ACTION_RUN.equals(action) && !ACTION_PAUSE.equals(action)
                && !ACTION_RESUME.equals(action) && !ACTION_RESUME_STOPPED.equals(action)
                && !ACTION_STOP.equals(action) && !ACTION_PUSH_RESULT.equals(action)
                && !ACTION_SERVER_RECOVERY.equals(action) && !ACTION_SERVER_RESULT_RECHECK.equals(action)
                && !ACTION_RESULT_POLL_WAKE.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (ACTION_PUSH_RESULT.equals(action)) {
            startForegroundCompat();
            turnStallNotifier.start();
            SelfRunPushEvent event;
            try {
                event = SelfRunPushEvent.fromIntent(intent, BuildConfig.APPLICATION_ID);
            } catch (Throwable invalid) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            if (!store.active() || store.userStopped() || !coordinator.ownsCurrentRun()) {
                try {
                    SelfRunPushAckOutbox.enqueue(this, event, SelfRunPushAckOutbox.AckState.PROCESSED);
                } catch (Throwable ignored) { }
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            coordinator.onPushResult(event);
            return START_STICKY;
        }

        if (ACTION_SERVER_RECOVERY.equals(action)) {
            if (!store.active() || store.paused() || store.userStopped() || !coordinator.ownsCurrentRun()) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            startForegroundCompat();
            turnStallNotifier.start();
            coordinator.onServerRecovery();
            return START_STICKY;
        }

        if (ACTION_SERVER_RESULT_RECHECK.equals(action)) {
            String turnId = intent == null ? ""
                    : intent.getStringExtra(EXTRA_SERVER_RESULT_RECHECK_TURN_ID);
            int attempt = intent == null ? -1
                    : intent.getIntExtra(EXTRA_SERVER_RESULT_RECHECK_ATTEMPT, -1);
            if (turnId == null) turnId = "";
            if (turnId.isEmpty() || attempt < 0 || !SelfRunServerFeaturePolicy.enabled(this)
                    || !store.active() || store.paused()
                    || store.userStopped() || !coordinator.ownsCurrentRun()) {
                SelfRunServerResultRecheckWorker.clear(this, turnId);
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            startForegroundCompat();
            turnStallNotifier.start();
            coordinator.onServerResultRecheck(turnId, attempt);
            return START_STICKY;
        }

        if (ACTION_RESULT_POLL_WAKE.equals(action)) {
            if (!store.active() || store.paused() || store.userStopped() || !coordinator.ownsCurrentRun()) {
                SelfRunFallbackWakeScheduler.cancel(this);
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            long actualAt = System.currentTimeMillis();
            long scheduledAt = intent.getLongExtra(
                    SelfRunFallbackWakeScheduler.EXTRA_SCHEDULED_AT_WALL_MS, actualAt);
            SelfRunFallbackWakeScheduler.onAlarmFired();
            recordResultPollTiming(scheduledAt, actualAt, "ALARM_MANAGER");
            startForegroundCompat();
            turnStallNotifier.start();
            return coordinator.onStart(ACTION_RUN);
        }

        if (ACTION_STOP.equals(action)) stoppedResume.cancelPending();

        if (ACTION_RESUME_STOPPED.equals(action)) {
            if (!stoppedResume.hasPending()) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            startForegroundCompat();
            turnStallNotifier.start();
            stoppedResume.resumePending();
            return START_STICKY;
        }

        // A startForegroundService request must publish its notification before any
        // ledger ownership or persistence work can delay this service.
        startForegroundCompat();
        if (!coordinator.ownsCurrentRun()) {
            if (!store.runId().isEmpty()) {
                if (ACTION_STOP.equals(action)) {
                    runLog.record(store, "V3_STOP", "user_stop");
                    SelfRunDebugLogSync.requestStored(this, store.runId(), "STOP");
                } else runLog.record(store, "V3_STALE_RUN_RETIRED", "action=" + action);
                store.stopByUser();
            }
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (!store.active() && !ACTION_RESUME.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        turnStallNotifier.start();
        return coordinator.onStart(action);
    }

    void recordResultPollTiming(long scheduledAt, long actualAt, String wakeReason) {
        if (runLog == null || store == null) return;
        PowerManager power = getSystemService(PowerManager.class);
        boolean interactive = power != null && power.isInteractive();
        boolean deviceIdle = power != null && power.isDeviceIdleMode();
        boolean powerSave = power != null && power.isPowerSaveMode();
        runLog.record(store, "V3_RESULT_POLL_WAKE",
                "scheduledAt=" + scheduledAt
                        + ";actualAt=" + actualAt
                        + ";delayMs=" + SelfRunFallbackWakePolicy.delayMs(scheduledAt, actualAt)
                        + ";wakeReason=" + wakeReason
                        + ";interactive=" + interactive
                        + ";deviceIdle=" + deviceIdle
                        + ";powerSave=" + powerSave);
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
        if (stoppedResume != null) stoppedResume.close();
        if (turnStallNotifier != null) turnStallNotifier.close();
        if (coordinator != null) coordinator.destroy();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
