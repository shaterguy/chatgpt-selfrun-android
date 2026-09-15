package com.shaterguy.chatgptselfrun;

import android.content.Intent;
import android.os.Build;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/** Data-message receiver. RECEIVED ACK is durably persisted before the foreground service is woken. */
public final class SelfRunFirebaseMessagingService extends FirebaseMessagingService {
    @Override public void onMessageReceived(RemoteMessage message) {
        try {
            SelfRunPushEvent event = SelfRunPushEvent.parse(message.getData(), BuildConfig.APPLICATION_ID);
            if (!event.installationId.equals(SelfRunInstallationIdentity.id(this))) return;
            SelfRunPushAckOutbox.enqueue(this, event, SelfRunPushAckOutbox.AckState.RECEIVED);
            Intent intent = event.serviceIntent(this);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (Throwable ignored) { }
    }

    @Override public void onNewToken(String token) {
        SelfRunPushAckWorker.schedule(this);
    }
}
