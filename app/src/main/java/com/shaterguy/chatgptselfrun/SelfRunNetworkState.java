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
    private final SelfRunHealthObservationStore health;
    private volatile boolean validated;
    private volatile long validatedSinceElapsed;
    private boolean registered;
    private boolean healthKnown;

    SelfRunNetworkState(Context context) {
        Context app = context.getApplicationContext();
        connectivity = app.getSystemService(ConnectivityManager.class);
        health = new SelfRunHealthObservationStore(app);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { updateValidated(isValidated(capabilities), true); }
            @Override public void onLost(Network network) { updateValidated(false, true); }
            @Override public void onUnavailable() { updateValidated(false, true); }
        };
    }

    void start() {
        if (registered) return;
        if (connectivity == null) {
            updateValidated(false, false);
            return;
        }
        try {
            Network active = connectivity.getActiveNetwork();
            updateValidated(active != null && isValidated(connectivity.getNetworkCapabilities(active)), true);
            connectivity.registerDefaultNetworkCallback(callback);
            registered = true;
        } catch (Throwable ignored) {
            updateValidated(false, false);
            registered = false;
        }
    }

    void stop() {
        if (!registered || connectivity == null) return;
        try { connectivity.unregisterNetworkCallback(callback); } catch (Throwable ignored) { }
        registered = false;
        updateValidated(false, false);
    }

    boolean isValidated() { return validated; }
    long validatedSinceElapsed() { return validated ? validatedSinceElapsed : 0L; }

    private void updateValidated(boolean next, boolean known) {
        boolean previous = validated;
        boolean previousKnown = healthKnown;
        if (next) {
            if (!validated || validatedSinceElapsed <= 0L) validatedSinceElapsed = SystemClock.elapsedRealtime();
        } else {
            validatedSinceElapsed = 0L;
        }
        validated = next;
        healthKnown = known;
        if (previous != next || previousKnown != known) {
            try { health.observeNetwork(known, next); }
            catch (Throwable ignored) { }
        }
    }

    static boolean isValidated(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
