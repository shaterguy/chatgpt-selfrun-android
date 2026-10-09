package com.shaterguy.chatgptselfrun;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;

/** Bootstrap profile of the last accepted new task in each outer execution mode. */
final class LastUsedExecutionProfileStore {
    static final String PREFIX = "lastUsedExecutionProfile.";

    static final class Selection {
        final String mode;
        final String model;
        final String reasoning;

        Selection(String mode, String model, String reasoning) {
            this.mode = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
            this.model = normalize(model);
            this.reasoning = normalize(reasoning);
        }
    }

    private LastUsedExecutionProfileStore() {}

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean allowed(String taskMode, Selection selection) {
        if (selection == null || selection.model.isEmpty() || selection.reasoning.isEmpty()
                || ChatReasoningPreferenceStore.KEEP.equals(selection.reasoning)) return false;
        if (SelfRunStore.MODE_CHAT.equals(taskMode)) return SelfRunStore.MODE_CHAT.equals(selection.mode);
        if (SelfRunStore.MODE_WORK.equals(taskMode)) return SelfRunStore.MODE_WORK.equals(selection.mode);
        return SelfRunStore.MODE_HYBRID.equals(taskMode)
                && (SelfRunStore.MODE_CHAT.equals(selection.mode)
                || SelfRunStore.MODE_WORK.equals(selection.mode));
    }

    static String encode(String taskMode, Selection selection) {
        if (!allowed(taskMode, selection)) return null;
        JSONObject json = new JSONObject();
        try {
            json.put("mode", selection.mode);
            json.put("model", selection.model);
            json.put("reasoning", selection.reasoning);
        } catch (Exception error) {
            throw new IllegalStateException("last-used profile serialization failed", error);
        }
        return json.toString();
    }

    static Selection decode(String taskMode, String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(value);
            if (!(json.opt("mode") instanceof String)
                    || !(json.opt("model") instanceof String)
                    || !(json.opt("reasoning") instanceof String)) return null;
            Selection selection = new Selection(json.getString("mode"),
                    json.getString("model"), json.getString("reasoning"));
            return allowed(taskMode, selection) ? selection : null;
        } catch (Exception invalid) {
            return null;
        }
    }

    static Selection load(SharedPreferences prefs, String taskMode) {
        try {
            return decode(taskMode, prefs.getString(PREFIX + taskMode, null));
        } catch (ClassCastException invalid) {
            return null;
        }
    }

    static void put(SharedPreferences.Editor editor, String taskMode, Selection selection) {
        String encoded = encode(taskMode, selection);
        if (encoded != null) editor.putString(PREFIX + taskMode, encoded);
    }
}
