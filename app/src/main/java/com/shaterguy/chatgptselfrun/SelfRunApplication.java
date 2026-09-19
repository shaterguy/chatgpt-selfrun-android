package com.shaterguy.chatgptselfrun;

import android.app.Application;
import android.content.Context;

/** Restores process-local access to durable SelfRun settings before any component starts. */
public final class SelfRunApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        initializeProcess(this);
    }

    static void initializeProcess(Context context) {
        ProfileRegistry.initialize(context);
        ChatReasoningPreferenceStore.initialize(context);
        UserNextInputStore.initialize(context);
        WorkProtocolNativeObserver.installProcess(context);
        SelfRunProcessExitDiagnostics.capture(context);
        new SelfRun3RuntimeSettings(context);
        SelfRunDebugLogSync.recover(context);
        if (SelfRunServerFeaturePolicy.enabled(context)) {
            SelfRunFirebase.initialize(context);
            if (SelfRunPushAckOutbox.pendingCount(context) > 0) SelfRunPushAckWorker.schedule(context);
        } else {
            SelfRunPushAckWorker.cancel(context);
            SelfRunServerRecoveryWorker.cancel(context);
        }
    }
}
