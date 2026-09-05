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
    }
}
