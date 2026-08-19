package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
        assertTrue(codec.contains("prefs.edit().putString(KEY_PROFILE, validated.toString()).commit()"));
        assertFalse(codec.contains("KEY_LOG"));
        assertFalse(codec.contains("OAuth"));
        assertFalse(codec.contains("accessToken"));
    }

    @Test public void importIsValidatedBeforeAtomicProfileReplacement() throws Exception {
        String codec = src("WebUiCalibrationBackupCodec.java");
        String method = section(codec, "static boolean importInto", "private static JSONObject canonicalProfile");
        int validation = method.indexOf("JSONObject validated = importEnvelope(raw)");
        int write = method.indexOf("putString(KEY_PROFILE, validated.toString()).commit()");
        assertTrue(validation >= 0);
        assertTrue(write > validation);
        assertFalse(method.contains("remove(KEY_PROFILE)"));
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

    @Test public void dev7VersionIdentityIsBumped() throws Exception {
        String gradle = read("app/build.gradle", "build.gradle");
        assertTrue(gradle.contains("selfRunDriveVersionCode = 1000047"));
        assertTrue(gradle.contains("selfRunDriveVersionName = '1.3.0-dev7'"));
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
