package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Event-driven default-network validation state. No periodic network polling. */
final class SelfRunNetworkState {
    private final ConnectivityManager connectivity;
    private final ConnectivityManager.NetworkCallback callback;
    private volatile boolean validated;
    private boolean registered;

    SelfRunNetworkState(Context context) {
        connectivity = context.getApplicationContext().getSystemService(ConnectivityManager.class);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                validated = isValidated(capabilities);
            }
            @Override public void onLost(Network network) {
                validated = false;
            }
            @Override public void onUnavailable() {
                validated = false;
            }
        };
    }

    void start() {
        if (registered || connectivity == null) return;
        try {
            Network active = connectivity.getActiveNetwork();
            validated = active != null && isValidated(connectivity.getNetworkCapabilities(active));
            connectivity.registerDefaultNetworkCallback(callback);
            registered = true;
        } catch (Throwable ignored) {
            validated = false;
            registered = false;
        }
    }

    void stop() {
        if (!registered || connectivity == null) return;
        try { connectivity.unregisterNetworkCallback(callback); }
        catch (Throwable ignored) { }
        registered = false;
    }

    boolean isValidated() {
        return validated;
    }

    static boolean isValidated(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
