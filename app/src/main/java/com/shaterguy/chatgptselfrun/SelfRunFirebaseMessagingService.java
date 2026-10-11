package com.shaterguy.chatgptselfrun;

import android.content.Intent;
import android.os.Build;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/** Data-message receiver. ACK persistence must not prevent a valid Result wake. */
public final class SelfRunFirebaseMessagingService extends FirebaseMessagingService {
    @Override public void onMessageReceived(RemoteMessage message) {
        if (!SelfRunServerFeaturePolicy.enabled(this)) return;
        try {
            SelfRunPushEvent event = SelfRunPushEvent.parse(message.getData(), BuildConfig.APPLICATION_ID);
            if (!event.installationId.equals(SelfRunInstallationIdentity.id(this))) return;
            try {
                SelfRunPushAckOutbox.enqueue(this, event, SelfRunPushAckOutbox.AckState.RECEIVED);
            } catch (Throwable ackFailure) {
                try {
                    new SelfRunRunLog(this).record(new SelfRunStore(this),
                            "V3_PUSH_ACK_PERSIST_FAILED", "state=RECEIVED;error="
                                    + ackFailure.getClass().getSimpleName());
                } catch (Throwable ignored) { }
            }
            Intent intent = event.serviceIntent(this);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (Throwable ignored) { }
    }

    @Override public void onNewToken(String token) {
        if (!SelfRunServerFeaturePolicy.enabled(this)) return;
        SelfRunPushAckWorker.schedule(this);
    }
}
