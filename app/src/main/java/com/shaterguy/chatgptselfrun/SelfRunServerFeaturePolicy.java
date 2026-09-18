package com.shaterguy.chatgptselfrun;

import android.content.Context;

/** Explicit opt-in boundary for the dormant SERVER transport stack. */
final class SelfRunServerFeaturePolicy {
    private SelfRunServerFeaturePolicy() { }

    static boolean buildEnabled() {
        return BuildConfig.SELFRUN_SERVER_FEATURES_ENABLED;
    }

    static boolean enabled(Context context) {
        if (!buildEnabled() || context == null) return false;
        return serverPathEnabled(true, new SelfRun3RuntimeSettings(context).workMode());
    }

    static boolean useServerPush(Context context, boolean localFallback) {
        return enabled(context)
                && SelfRunServerWaitPolicy.useServerPush(
                new SelfRun3RuntimeSettings(context).workMode(), localFallback);
    }

    static boolean serverPathEnabled(boolean buildEnabled, SelfRun3RuntimeSettings.WorkMode mode) {
        return buildEnabled && mode == SelfRun3RuntimeSettings.WorkMode.SERVER;
    }
}
