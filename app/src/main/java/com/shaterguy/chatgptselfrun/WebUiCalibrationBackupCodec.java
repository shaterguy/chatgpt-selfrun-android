package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Strict portable format for user-owned Web UI calibration backups. */
final class WebUiCalibrationBackupCodec {
    static final String FORMAT = "selfrun-drive-web-ui-calibration";
    static final int FORMAT_VERSION = 1;
    static final int PROFILE_VERSION = 2;
    static final int MAX_BACKUP_BYTES = 64 * 1024;
    static final String DEFAULT_FILE_NAME = "selfrun-drive-web-ui-calibration.json";

    private static final String PREFS = "selfrun_drive_ui_profile";
    private static final String KEY_PROFILE = "profile";
    private static final int MAX_DESCRIPTOR_STRING = 120;
    private static final int MAX_TARGETS = 18;

    private static final Set<String> ROOT_FIELDS = setOf("format", "formatVersion", "profileVersion", "profile");
    private static final Set<String> PROFILE_FIELDS = setOf("version", "targets", "viewport");
    private static final Set<String> VIEWPORT_FIELDS = setOf(
            "innerWidth", "innerHeight", "devicePixelRatio", "screenWidth", "screenHeight");
    private static final Set<String> DESCRIPTOR_FIELDS = setOf(
            "tag", "id", "role", "testid", "aria", "name", "type", "text", "href",
            "parentRole", "parentTestid", "parentAria", "layoutFamily");
    private static final Set<String> TARGET_KEYS = setOf(
            WebUiCalibrationStore.PURPOSE_MODE_CHAT,
            WebUiCalibrationStore.PURPOSE_MODE_WORK,
            WebUiCalibrationStore.PURPOSE_LEGACY_WORK_MODEL,
            WebUiCalibrationStore.PURPOSE_LEGACY_WORK_REASONING,
            WebUiCalibrationStore.PURPOSE_GENERAL_BOOTSTRAP_WORK_MODEL,
            WebUiCalibrationStore.PURPOSE_GENERAL_BOOTSTRAP_WORK_REASONING,
            WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_MODEL,
            WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_REASONING,
            WebUiCalibrationStore.PURPOSE_PROJECT_BOOTSTRAP_WORK_MODEL,
            WebUiCalibrationStore.PURPOSE_PROJECT_BOOTSTRAP_WORK_REASONING,
            WebUiCalibrationStore.PURPOSE_PROJECT_CONTINUATION_WORK_MODEL,
            WebUiCalibrationStore.PURPOSE_PROJECT_CONTINUATION_WORK_REASONING,
            WebUiCalibrationStore.PURPOSE_PROJECT_NEW_CHAT,
            WebUiCalibrationStore.PURPOSE_GENERAL_NEW_CHAT,
            WebUiCalibrationStore.TARGET_PROJECT_COMPOSER,
            WebUiCalibrationStore.TARGET_PROJECT_SEND,
            WebUiCalibrationStore.TARGET_GENERAL_COMPOSER,
            WebUiCalibrationStore.TARGET_GENERAL_SEND);

    private WebUiCalibrationBackupCodec() {}

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    static String exportEnvelope(JSONObject sourceProfile) {
        try {
            JSONObject root = new JSONObject();
            root.put("format", FORMAT);
            root.put("formatVersion", FORMAT_VERSION);
            root.put("profileVersion", PROFILE_VERSION);
            root.put("profile", canonicalProfile(sourceProfile, false));
            String value = root.toString();
            if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BACKUP_BYTES) {
                throw invalid("backup_too_large");
            }
            return value;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Throwable error) {
            throw invalid("export_failed");
        }
    }

    static JSONObject importEnvelope(String raw) {
        if (raw == null || raw.isEmpty()) throw invalid("empty_backup");
        if (raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BACKUP_BYTES) {
            throw invalid("backup_too_large");
        }
        try {
            JSONObject root = new JSONObject(raw);
            requireOnlyFields(root, ROOT_FIELDS, "root");
            if (!FORMAT.equals(requireString(root, "format", 64))) throw invalid("unsupported_format");
            if (requireInt(root, "formatVersion") != FORMAT_VERSION) throw invalid("unsupported_format_version");
            if (requireInt(root, "profileVersion") != PROFILE_VERSION) throw invalid("unsupported_profile_version");
            Object profileValue = root.opt("profile");
            if (!(profileValue instanceof JSONObject)) throw invalid("profile_not_object");
            return canonicalProfile((JSONObject) profileValue, true);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Throwable error) {
            throw invalid("invalid_json");
        }
    }

    static boolean importInto(Context context, String raw) {
        JSONObject validated = importEnvelope(raw);
        try {
            validated.put("updatedAt", System.currentTimeMillis());
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return replaceProfile(prefs, validated.toString());
        } catch (Throwable error) {
            return false;
        }
    }

    /**
     * Replaces only KEY_PROFILE. SharedPreferences commit() updates its in-memory map before disk I/O,
     * so a false/throwing commit must explicitly restore the prior in-memory state before returning failure.
     */
    static boolean replaceProfile(SharedPreferences prefs, String newProfileRaw) {
        if (prefs == null || newProfileRaw == null) return false;
        final boolean hadPrevious;
        final String previousRaw;
        try {
            hadPrevious = prefs.contains(KEY_PROFILE);
            previousRaw = hadPrevious ? prefs.getString(KEY_PROFILE, null) : null;
        } catch (Throwable error) {
            return false;
        }

        try {
            if (prefs.edit().putString(KEY_PROFILE, newProfileRaw).commit()) return true;
        } catch (Throwable ignored) {
            // A throwing implementation may still have mutated its in-memory map. Always roll back below.
        }
        restorePreviousProfile(prefs, hadPrevious, previousRaw);
        return false;
    }

    private static boolean restorePreviousProfile(SharedPreferences prefs, boolean hadPrevious, String previousRaw) {
        try {
            SharedPreferences.Editor rollback = prefs.edit();
            if (hadPrevious) rollback.putString(KEY_PROFILE, previousRaw);
            else rollback.remove(KEY_PROFILE);
            // Even if this disk write also fails, commit() applies the rollback to SharedPreferences' in-memory map first.
            rollback.commit();
            if (hadPrevious) {
                return prefs.contains(KEY_PROFILE) && same(previousRaw, prefs.getString(KEY_PROFILE, null));
            }
            return !prefs.contains(KEY_PROFILE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static JSONObject canonicalProfile(JSONObject source, boolean strictTopLevel) {
        if (source == null) throw invalid("profile_missing");
        try {
            if (strictTopLevel) requireOnlyFields(source, PROFILE_FIELDS, "profile");
            int version = requireInt(source, "version");
            if (version != PROFILE_VERSION) throw invalid("profile_version_mismatch");

            JSONObject out = new JSONObject();
            out.put("version", PROFILE_VERSION);

            Object targetsValue = source.opt("targets");
            if (!(targetsValue instanceof JSONObject)) throw invalid("targets_not_object");
            JSONObject targets = (JSONObject) targetsValue;
            if (targets.length() > MAX_TARGETS) throw invalid("too_many_targets");
            JSONObject cleanTargets = new JSONObject();
            Iterator<String> keys = targets.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!TARGET_KEYS.contains(key)) throw invalid("unknown_target_key");
                Object descriptorValue = targets.opt(key);
                if (!(descriptorValue instanceof JSONObject)) throw invalid("descriptor_not_object");
                cleanTargets.put(key, canonicalDescriptor((JSONObject) descriptorValue));
            }
            out.put("targets", cleanTargets);

            Object viewportValue = source.opt("viewport");
            if (viewportValue != null && viewportValue != JSONObject.NULL) {
                if (!(viewportValue instanceof JSONObject)) throw invalid("viewport_not_object");
                out.put("viewport", canonicalViewport((JSONObject) viewportValue));
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Throwable error) {
            throw invalid("invalid_profile");
        }
    }

    private static JSONObject canonicalDescriptor(JSONObject source) {
        requireOnlyFields(source, DESCRIPTOR_FIELDS, "descriptor");
        try {
            JSONObject out = new JSONObject();
            Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = source.opt(key);
                if (!(value instanceof String)) throw invalid("descriptor_field_not_string");
                String text = (String) value;
                if (text.length() > MAX_DESCRIPTOR_STRING) throw invalid("descriptor_field_too_long");
                if (containsControlCharacter(text)) throw invalid("descriptor_control_character");
                out.put(key, text);
            }
            if (!descriptor(out)) throw invalid("descriptor_without_identity");
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Throwable error) {
            throw invalid("invalid_descriptor");
        }
    }

    private static JSONObject canonicalViewport(JSONObject source) {
        requireOnlyFields(source, VIEWPORT_FIELDS, "viewport");
        try {
            int innerWidth = requireBoundedInt(source, "innerWidth", 240, 1200);
            int innerHeight = requireBoundedInt(source, "innerHeight", 320, 2600);
            double dpr = requireBoundedDouble(source, "devicePixelRatio", 0.75d, 5d);
            JSONObject out = new JSONObject();
            out.put("innerWidth", innerWidth);
            out.put("innerHeight", innerHeight);
            out.put("devicePixelRatio", dpr);
            if (source.has("screenWidth")) {
                int value = requireOptionalScreenDimension(source, "screenWidth", 240, 1200);
                if (value > 0) out.put("screenWidth", value);
            }
            if (source.has("screenHeight")) {
                int value = requireOptionalScreenDimension(source, "screenHeight", 320, 2600);
                if (value > 0) out.put("screenHeight", value);
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Throwable error) {
            throw invalid("invalid_viewport");
        }
    }

    private static void requireOnlyFields(JSONObject value, Set<String> allowed, String scope) {
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            if (!allowed.contains(keys.next())) throw invalid("unknown_" + scope + "_field");
        }
    }

    private static String requireString(JSONObject value, String key, int max) {
        Object raw = value.opt(key);
        if (!(raw instanceof String)) throw invalid(key + "_not_string");
        String text = (String) raw;
        if (text.isEmpty() || text.length() > max || containsControlCharacter(text)) throw invalid(key + "_invalid");
        return text;
    }

    private static int requireInt(JSONObject value, String key) {
        Object raw = value.opt(key);
        if (!(raw instanceof Number)) throw invalid(key + "_not_number");
        double number = ((Number) raw).doubleValue();
        if (!Double.isFinite(number) || Math.rint(number) != number
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw invalid(key + "_not_integer");
        return (int) number;
    }

    private static int requireBoundedInt(JSONObject value, String key, int min, int max) {
        int number = requireInt(value, key);
        if (number < min || number > max) throw invalid(key + "_out_of_range");
        return number;
    }

    private static int requireOptionalScreenDimension(JSONObject value, String key, int min, int max) {
        int number = requireInt(value, key);
        if (number == 0) return 0;
        if (number < min || number > max) throw invalid(key + "_out_of_range");
        return number;
    }

    private static double requireBoundedDouble(JSONObject value, String key, double min, double max) {
        Object raw = value.opt(key);
        if (!(raw instanceof Number)) throw invalid(key + "_not_number");
        double number = ((Number) raw).doubleValue();
        if (!Double.isFinite(number) || number < min || number > max) throw invalid(key + "_out_of_range");
        return number;
    }

    private static boolean descriptor(JSONObject value) {
        return !value.optString("id").isEmpty() || !value.optString("testid").isEmpty()
                || !value.optString("aria").isEmpty() || !value.optString("text").isEmpty()
                || !value.optString("role").isEmpty() || !value.optString("tag").isEmpty();
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 && c != '\t') return true;
        }
        return false;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(reason);
    }
}
