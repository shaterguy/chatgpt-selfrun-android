package com.shaterguy.chatgptselfrun;

import android.content.SharedPreferences;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class WebUiCalibrationBackupPolicyTest {
    @Test public void portableBackupIsCalibrationOnlyAndVersioned() throws Exception {
        String codec = src("WebUiCalibrationBackupCodec.java");
        assertTrue(codec.contains("selfrun-drive-web-ui-calibration"));
        assertTrue(codec.contains("FORMAT_VERSION = 1"));
        assertTrue(codec.contains("PROFILE_VERSION = 2"));
        assertTrue(codec.contains("MAX_BACKUP_BYTES = 64 * 1024"));
        assertTrue(codec.contains("ROOT_FIELDS"));
        assertTrue(codec.contains("PROFILE_FIELDS"));
        assertTrue(codec.contains("DESCRIPTOR_FIELDS"));
        assertTrue(codec.contains("TARGET_KEYS"));
        assertTrue(codec.contains("requireOnlyFields"));
        assertTrue(codec.contains("unknown_target_key"));
        assertTrue(codec.contains("descriptor_field_too_long"));
        assertTrue(codec.contains("devicePixelRatio"));
        assertTrue(codec.contains("static boolean importInto(Context context, String raw)"));
        assertTrue(codec.contains("replaceProfile(prefs, validated.toString())"));
        assertFalse(codec.contains("KEY_LOG"));
        assertFalse(codec.contains("OAuth"));
        assertFalse(codec.contains("accessToken"));
    }

    @Test public void importIsValidatedBeforeProfileReplacement() throws Exception {
        String codec = src("WebUiCalibrationBackupCodec.java");
        String method = section(codec, "static boolean importInto", "/**\n     * Replaces only KEY_PROFILE");
        int validation = method.indexOf("JSONObject validated = importEnvelope(raw)");
        int write = method.indexOf("replaceProfile(prefs, validated.toString())");
        assertTrue(validation >= 0);
        assertTrue(write > validation);
    }

    @Test public void failedReplacementRestoresExistingProfile() {
        FailingFirstCommitPreferences prefs = new FailingFirstCommitPreferences("old-profile");
        assertFalse(WebUiCalibrationBackupCodec.replaceProfile(prefs, "new-profile"));
        assertTrue(prefs.contains("profile"));
        assertEquals("old-profile", prefs.getString("profile", null));
        assertEquals(2, prefs.commitCount);
    }

    @Test public void failedReplacementRestoresPriorAbsence() {
        FailingFirstCommitPreferences prefs = new FailingFirstCommitPreferences(null);
        assertFalse(WebUiCalibrationBackupCodec.replaceProfile(prefs, "new-profile"));
        assertFalse(prefs.contains("profile"));
        assertNull(prefs.getString("profile", null));
        assertEquals(2, prefs.commitCount);
    }

    @Test public void systemDocumentPickerNeedsNoBroadStoragePermission() throws Exception {
        String activity = src("WebUiCalibrationActivity.java");
        String manifest = read("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml");
        assertTrue(activity.contains("Intent.ACTION_CREATE_DOCUMENT"));
        assertTrue(activity.contains("Intent.ACTION_OPEN_DOCUMENT"));
        assertTrue(activity.contains("setType(\"application/json\")"));
        assertTrue(activity.contains("WebUiCalibrationBackupCodec.MAX_BACKUP_BYTES"));
        assertTrue(activity.contains("openOutputStream(uri, \"wt\")"));
        assertTrue(activity.contains("openInputStream(uri)"));
        assertTrue(activity.contains("seedProfile();"));
        assertTrue(activity.contains("PROFILE_IMPORT_REJECTED"));
        assertFalse(manifest.contains("READ_EXTERNAL_STORAGE"));
        assertFalse(manifest.contains("WRITE_EXTERNAL_STORAGE"));
        assertTrue(manifest.contains(".WebUiCalibrationActivity\" android:exported=\"false\""));
    }

    @Test public void developmentVersionIdentityIsAdvanced() throws Exception {
        String gradle = read("app/build.gradle", "build.gradle");
        assertTrue(gradle.contains("selfRunDriveVersionCode = 2000011"));
        assertTrue(gradle.contains("selfRunDriveVersionName = '2.0.0-dev11'"));
        assertTrue(gradle.contains("applicationId 'com.shaterguy.chatgptselfrun.v2'"));
    }

    private static final class FailingFirstCommitPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();
        int commitCount;

        FailingFirstCommitPreferences(String initialProfile) {
            if (initialProfile != null) values.put("profile", initialProfile);
        }

        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }
        @SuppressWarnings("unchecked")
        @Override public Set<String> getStringSet(String key, Set<String> defValues) {
            Object value = values.get(key);
            return value instanceof Set ? new HashSet<>((Set<String>) value) : defValues;
        }
        @Override public int getInt(String key, int defValue) {
            Object value = values.get(key); return value instanceof Integer ? (Integer) value : defValue;
        }
        @Override public long getLong(String key, long defValue) {
            Object value = values.get(key); return value instanceof Long ? (Long) value : defValue;
        }
        @Override public float getFloat(String key, float defValue) {
            Object value = values.get(key); return value instanceof Float ? (Float) value : defValue;
        }
        @Override public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key); return value instanceof Boolean ? (Boolean) value : defValue;
        }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() { return new MemoryEditor(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }

        private final class MemoryEditor implements Editor {
            private final Map<String, Object> pending = new HashMap<>();
            private final Set<String> removals = new HashSet<>();
            private boolean clear;

            @Override public Editor putString(String key, String value) { pending.put(key, value); removals.remove(key); return this; }
            @Override public Editor putStringSet(String key, Set<String> values) { pending.put(key, values == null ? null : new HashSet<>(values)); removals.remove(key); return this; }
            @Override public Editor putInt(String key, int value) { pending.put(key, value); removals.remove(key); return this; }
            @Override public Editor putLong(String key, long value) { pending.put(key, value); removals.remove(key); return this; }
            @Override public Editor putFloat(String key, float value) { pending.put(key, value); removals.remove(key); return this; }
            @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); removals.remove(key); return this; }
            @Override public Editor remove(String key) { removals.add(key); pending.remove(key); return this; }
            @Override public Editor clear() { clear = true; pending.clear(); removals.clear(); return this; }
            @Override public boolean commit() {
                applyChanges();
                commitCount++;
                return commitCount > 1;
            }
            @Override public void apply() { applyChanges(); }

            private void applyChanges() {
                if (clear) values.clear();
                for (String key : removals) values.remove(key);
                for (Map.Entry<String, Object> entry : pending.entrySet()) {
                    if (entry.getValue() == null) values.remove(entry.getKey());
                    else values.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private static String section(String value, String start, String end) {
        int a = value.indexOf(start), b = value.indexOf(end, a);
        assertTrue(a >= 0 && b > a);
        return value.substring(a, b);
    }

    private static String src(String file) throws Exception {
        return read("app/src/main/java/com/shaterguy/chatgptselfrun/" + file,
                "src/main/java/com/shaterguy/chatgptselfrun/" + file);
    }

    private static String read(String first, String fallback) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(fallback);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
