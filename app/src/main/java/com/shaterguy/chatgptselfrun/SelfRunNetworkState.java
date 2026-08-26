package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

/** Event-driven default-network validation state. No periodic network polling. */
final class SelfRunNetworkState {
    private final ConnectivityManager connectivity;
    private final ConnectivityManager.NetworkCallback callback;
    private volatile boolean validated;
    private volatile long validatedSinceElapsed;
    private boolean registered;

    SelfRunNetworkState(Context context) {
        connectivity = context.getApplicationContext().getSystemService(ConnectivityManager.class);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { updateValidated(isValidated(capabilities)); }
            @Override public void onLost(Network network) { updateValidated(false); }
            @Override public void onUnavailable() { updateValidated(false); }
        };
    }

    void start() {
        if (registered || connectivity == null) return;
        try {
            Network active = connectivity.getActiveNetwork();
            updateValidated(active != null && isValidated(connectivity.getNetworkCapabilities(active)));
            connectivity.registerDefaultNetworkCallback(callback);
            registered = true;
        } catch (Throwable ignored) {
            updateValidated(false);
            registered = false;
        }
    }

    void stop() {
        if (!registered || connectivity == null) return;
        try { connectivity.unregisterNetworkCallback(callback); } catch (Throwable ignored) { }
        registered = false;
        updateValidated(false);
    }

    boolean isValidated() { return validated; }
    long validatedSinceElapsed() { return validated ? validatedSinceElapsed : 0L; }

    private void updateValidated(boolean next) {
        if (next) {
            if (!validated || validatedSinceElapsed <= 0L) validatedSinceElapsed = SystemClock.elapsedRealtime();
        } else {
            validatedSinceElapsed = 0L;
        }
        validated = next;
    }

    static boolean isValidated(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
