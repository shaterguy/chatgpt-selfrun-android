package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Map;

/** Persisted SelfRun 3 operator-tunable runtime policy. */
final class SelfRun3RuntimeSettings {
    enum WorkMode { SERVER, ON_DEVICE }

    static final String PREFS = "selfrun3_runtime_settings";
    static final String KEY_RESULT_REPAIR_MINUTES = "selfrun3ResultRepairMinutes";
    static final String KEY_STALL_ALERT_MINUTES = "selfrun3StallAlertMinutes";
    static final String KEY_RESULT_POLL_SECONDS = "selfrun3ResultPollSeconds";
    static final String KEY_WEB_PREPARATION_SECONDS = "selfrun3WebPreparationSeconds";
    static final String KEY_WORK_MODE = "selfrun3WorkMode";
    static final String KEY_ON_DEVICE_DEFAULT_MIGRATION_VERSION = "selfrun3OnDeviceDefaultMigrationVersion";
    static final int ON_DEVICE_DEFAULT_MIGRATION_VERSION = 1;

    static final long DEFAULT_RESULT_REPAIR_MINUTES = 10L;
    static final long DEFAULT_STALL_ALERT_MINUTES = 125L;
    static final long DEFAULT_RESULT_POLL_SECONDS = 30L;
    static final long DEFAULT_WEB_PREPARATION_SECONDS = 90L;
    static final WorkMode DEFAULT_WORK_MODE = WorkMode.ON_DEVICE;

    private static final long MINUTE_MS = 60_000L;
    private static final long SECOND_MS = 1_000L;

    private final SharedPreferences prefs;

    SelfRun3RuntimeSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateOnDeviceDefaultIfNeeded();
    }

    long resultRepairMinutes() {
        return readPositiveUnits(KEY_RESULT_REPAIR_MINUTES, DEFAULT_RESULT_REPAIR_MINUTES, MINUTE_MS);
    }

    long stallAlertMinutes() {
        return readPositiveUnits(KEY_STALL_ALERT_MINUTES, DEFAULT_STALL_ALERT_MINUTES, MINUTE_MS);
    }

    long resultPollSeconds() {
        return readPositiveUnits(KEY_RESULT_POLL_SECONDS, DEFAULT_RESULT_POLL_SECONDS, SECOND_MS);
    }

    long webPreparationSeconds() {
        return Math.max(DEFAULT_WEB_PREPARATION_SECONDS,
                readPositiveUnits(KEY_WEB_PREPARATION_SECONDS, DEFAULT_WEB_PREPARATION_SECONDS, SECOND_MS));
    }

    WorkMode workMode() {
        Map<String, ?> all = safeAll();
        return effectiveWorkMode(all.get(KEY_WORK_MODE), all.get(KEY_ON_DEVICE_DEFAULT_MIGRATION_VERSION));
    }

    long resultRepairMs() { return toMillis(resultRepairMinutes(), MINUTE_MS); }
    long stallAlertMs() { return toMillis(stallAlertMinutes(), MINUTE_MS); }
    long resultPollMs() { return toMillis(resultPollSeconds(), SECOND_MS); }
    long webPreparationMs() { return toMillis(webPreparationSeconds(), SECOND_MS); }

    boolean saveResultRepairMinutes(String raw) {
        return savePositiveUnits(KEY_RESULT_REPAIR_MINUTES, raw, MINUTE_MS);
    }

    boolean saveStallAlertMinutes(String raw) {
        return savePositiveUnits(KEY_STALL_ALERT_MINUTES, raw, MINUTE_MS);
    }

    boolean saveResultPollSeconds(String raw) {
        return savePositiveUnits(KEY_RESULT_POLL_SECONDS, raw, SECOND_MS);
    }

    boolean saveWebPreparationSeconds(String raw) {
        return savePositiveUnits(KEY_WEB_PREPARATION_SECONDS, raw, SECOND_MS);
    }

    boolean saveWorkMode(WorkMode mode) {
        if (mode == null) return false;
        return prefs.edit().putString(KEY_WORK_MODE, mode.name()).commit();
    }

    private void migrateOnDeviceDefaultIfNeeded() {
        synchronized (SelfRun3RuntimeSettings.class) {
            Map<String, ?> all = safeAll();
            if (migrationVersion(all.get(KEY_ON_DEVICE_DEFAULT_MIGRATION_VERSION))
                    >= ON_DEVICE_DEFAULT_MIGRATION_VERSION) return;
            prefs.edit()
                    .putString(KEY_WORK_MODE, WorkMode.ON_DEVICE.name())
                    .putInt(KEY_ON_DEVICE_DEFAULT_MIGRATION_VERSION, ON_DEVICE_DEFAULT_MIGRATION_VERSION)
                    .commit();
        }
    }

    static WorkMode effectiveWorkMode(Object storedMode, Object storedMigrationVersion) {
        if (migrationVersion(storedMigrationVersion) < ON_DEVICE_DEFAULT_MIGRATION_VERSION) {
            return WorkMode.ON_DEVICE;
        }
        if (!(storedMode instanceof String)) return DEFAULT_WORK_MODE;
        try {
            return WorkMode.valueOf((String) storedMode);
        } catch (IllegalArgumentException invalid) {
            return DEFAULT_WORK_MODE;
        }
    }

    private static int migrationVersion(Object stored) {
        if (!(stored instanceof Number)) return 0;
        int value = ((Number) stored).intValue();
        return Math.max(0, value);
    }

    private Map<String, ?> safeAll() {
        try {
            return prefs.getAll();
        } catch (Throwable unreadable) {
            return Collections.emptyMap();
        }
    }

    private long readPositiveUnits(String key, long fallback, long multiplier) {
        Object stored;
        try {
            stored = prefs.getAll().get(key);
        } catch (Throwable unreadable) {
            return fallback;
        }
        if (stored == null) return fallback;
        if (!(stored instanceof Number)) return fallback;
        long value = ((Number) stored).longValue();
        return validPositiveUnits(value, multiplier) ? value : fallback;
    }

    private boolean savePositiveUnits(String key, String raw, long multiplier) {
        Long value = parsePositiveUnits(raw, multiplier);
        if (value == null) return false;
        return prefs.edit().putLong(key, value).commit();
    }

    static Long parsePositiveUnits(String raw, long multiplier) {
        if (raw == null || multiplier <= 0L) return null;
        String value = raw.trim();
        if (value.isEmpty() || !value.matches("[0-9]+")) return null;
        try {
            long parsed = Long.parseLong(value);
            return validPositiveUnits(parsed, multiplier) ? parsed : null;
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    static boolean validPositiveUnits(long value, long multiplier) {
        return value > 0L && multiplier > 0L && value <= Long.MAX_VALUE / multiplier;
    }

    private static long toMillis(long value, long multiplier) {
        if (!validPositiveUnits(value, multiplier)) {
            throw new IllegalStateException("validated runtime setting required");
        }
        return value * multiplier;
    }
}
