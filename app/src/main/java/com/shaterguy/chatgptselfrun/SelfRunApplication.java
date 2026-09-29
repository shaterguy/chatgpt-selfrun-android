package com.shaterguy.chatgptselfrun;

import android.app.Application;
import android.content.Context;
import android.app.Activity;
import android.os.Bundle;

/** Restores process-local access to durable SelfRun settings before any component starts. */
public final class SelfRunApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        initializeProcess(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                ProfileRegistrySync.refresh(activity, null);
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    static void initializeProcess(Context context) {
        ProfileRegistry.initialize(context);
        ProfileRegistrySync.refresh(context, null);
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
