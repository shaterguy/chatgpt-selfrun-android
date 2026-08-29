package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.Locale;

/** Durable last-used Work bootstrap profile for new SelfRun tasks. */
final class WorkBootstrapPreferenceStore {
    private static final String PREFS = "selfrun_drive_work_bootstrap";
    private static final String KEY_MODEL = "model";
    private static final String KEY_REASONING = "reasoning";
    private static final String LEGACY_DEFAULT_MODEL = "sol";
    private static final String LEGACY_DEFAULT_REASONING = "xhigh";

    static final class Selection {
        final String model;
        final String reasoning;

        Selection(String model, String reasoning) {
            this.model = normalize(model);
            this.reasoning = normalize(reasoning);
        }

        boolean valid() { return ProfileRegistry.resolveWork(model, reasoning) != null; }
    }

    private WorkBootstrapPreferenceStore() {}

    static Selection load(Context context) {
        ProfileRegistry.initialize(context);
        SharedPreferences prefs = preferences(context);
        Selection stored = new Selection(prefs.getString(KEY_MODEL, ""),
                prefs.getString(KEY_REASONING, ""));
        if (stored.valid()) return stored;

        ProfileRegistry.Profile legacy = ProfileRegistry.resolveWork(
                LEGACY_DEFAULT_MODEL, LEGACY_DEFAULT_REASONING);
        if (legacy != null) return new Selection(legacy.signalModel, legacy.signalReasoning);

        List<ProfileRegistry.Profile> profiles = ProfileRegistry.listWork();
        if (!profiles.isEmpty()) {
            ProfileRegistry.Profile first = profiles.get(0);
            return new Selection(first.signalModel, first.signalReasoning);
        }
        return new Selection("", "");
    }

    static boolean save(Context context, String model, String reasoning) {
        ProfileRegistry.initialize(context);
        Selection selection = new Selection(model, reasoning);
        if (!selection.valid()) return false;
        return preferences(context).edit()
                .putString(KEY_MODEL, selection.model)
                .putString(KEY_REASONING, selection.reasoning)
                .commit();
    }

    private static SharedPreferences preferences(Context context) {
        Context application = context == null ? null : context.getApplicationContext();
        if (application == null) application = context;
        if (application == null) throw new IllegalArgumentException("context required");
        return application.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
