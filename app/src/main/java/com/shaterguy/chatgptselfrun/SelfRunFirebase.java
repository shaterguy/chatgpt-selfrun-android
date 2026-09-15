package com.shaterguy.chatgptselfrun;

import android.content.Context;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

/** Programmatic Firebase initialization so no google-services.json or private material lives in source. */
final class SelfRunFirebase {
    interface TokenCallback {
        void onToken(String token);
        void onUnavailable(Throwable error);
    }

    private SelfRunFirebase() { }

    static boolean configured() {
        return present(BuildConfig.SELFRUN_FIREBASE_API_KEY)
                && present(BuildConfig.SELFRUN_FIREBASE_APPLICATION_ID)
                && present(BuildConfig.SELFRUN_FIREBASE_PROJECT_ID)
                && present(BuildConfig.SELFRUN_FIREBASE_SENDER_ID);
    }

    static synchronized boolean initialize(Context context) {
        if (!configured()) return false;
        Context app = context.getApplicationContext();
        try {
            FirebaseApp.getInstance();
            return true;
        } catch (IllegalStateException missing) {
            FirebaseOptions options = new FirebaseOptions.Builder()
                    .setApiKey(BuildConfig.SELFRUN_FIREBASE_API_KEY)
                    .setApplicationId(BuildConfig.SELFRUN_FIREBASE_APPLICATION_ID)
                    .setProjectId(BuildConfig.SELFRUN_FIREBASE_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.SELFRUN_FIREBASE_SENDER_ID)
                    .build();
            return FirebaseApp.initializeApp(app, options) != null;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    static void requestToken(Context context, TokenCallback callback) {
        if (!initialize(context)) {
            callback.onUnavailable(new IllegalStateException("Firebase not configured"));
            return;
        }
        try {
            FirebaseMessaging.getInstance().getToken()
                    .addOnSuccessListener(token -> {
                        if (token == null || token.trim().length() < 16) {
                            callback.onUnavailable(new IllegalStateException("FCM token unavailable"));
                        } else {
                            callback.onToken(token.trim());
                        }
                    })
                    .addOnFailureListener(callback::onUnavailable);
        } catch (Throwable error) {
            callback.onUnavailable(error);
        }
    }

    private static boolean present(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
