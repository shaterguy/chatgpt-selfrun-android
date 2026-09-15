package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/** App-install scoped routing identity. Never derived from device hardware identifiers. */
final class SelfRunInstallationIdentity {
    static final String PREFS = "selfrun_push_installation";
    private static final String KEY_ID = "installationId";
    private static final Object LOCK = new Object();

    private SelfRunInstallationIdentity() { }

    static String id(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        synchronized (LOCK) {
            String current = prefs.getString(KEY_ID, "");
            if (valid(current)) return current;
            String created = "INS-" + UUID.randomUUID().toString().replace("-", "");
            if (!prefs.edit().putString(KEY_ID, created).commit()) {
                throw new IllegalStateException("installation identity persist failed");
            }
            return created;
        }
    }

    private static boolean valid(String value) {
        return value != null && value.matches("INS-[A-Fa-f0-9]{32}");
    }
}
