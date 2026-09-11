package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted SelfRun 3 operator-tunable timing policy. */
final class SelfRun3RuntimeSettings {
    static final String PREFS = "selfrun3_runtime_settings";
    static final String KEY_RESULT_REPAIR_MINUTES = "selfrun3ResultRepairMinutes";
    static final String KEY_STALL_ALERT_MINUTES = "selfrun3StallAlertMinutes";
    static final String KEY_RESULT_POLL_SECONDS = "selfrun3ResultPollSeconds";
    static final String KEY_WEB_PREPARATION_SECONDS = "selfrun3WebPreparationSeconds";

    static final long DEFAULT_RESULT_REPAIR_MINUTES = 10L;
    static final long DEFAULT_STALL_ALERT_MINUTES = 125L;
    static final long DEFAULT_RESULT_POLL_SECONDS = 30L;
    static final long DEFAULT_WEB_PREPARATION_SECONDS = 90L;

    private static final long MINUTE_MS = 60_000L;
    private static final long SECOND_MS = 1_000L;

    private final SharedPreferences prefs;

    SelfRun3RuntimeSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
        return readPositiveUnits(KEY_WEB_PREPARATION_SECONDS, DEFAULT_WEB_PREPARATION_SECONDS, SECOND_MS);
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
