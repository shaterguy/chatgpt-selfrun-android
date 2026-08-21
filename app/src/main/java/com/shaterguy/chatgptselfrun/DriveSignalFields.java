package com.shaterguy.chatgptselfrun;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Parses and validates the optional field tail of SELF_RUN_TURN_COMPLETED. */
final class DriveSignalFields {
    static final String NEXT_INPUT = "NEXT_INPUT_B64URL";
    static final String RECOVERY = "RECOVERY_ID";

    private DriveSignalFields() {}

    static Parsed parse(String tail) {
        Map<String, String> values = new LinkedHashMap<>();
        if (tail == null || tail.isEmpty()) return new Parsed(values, true, "");
        for (String token : tail.split("\\s+")) {
            int equals = token.indexOf('=');
            if (equals <= 0 || equals == token.length() - 1) {
                return new Parsed(values, false, "TURN_COMPLETED_FIELD_MALFORMED");
            }
            String key = token.substring(0, equals).toUpperCase(Locale.ROOT);
            if (values.containsKey(key)) {
                return new Parsed(values, false, "TURN_COMPLETED_DUPLICATE_FIELD");
            }
            values.put(key, token.substring(equals + 1));
        }
        return new Parsed(values, true, "");
    }

    static boolean hasUnknown(Map<String, String> values, boolean work) {
        for (String key : values.keySet()) {
            if (NEXT_INPUT.equals(key) || RECOVERY.equals(key)) continue;
            if (work && ("MODEL".equals(key) || "REASONING".equals(key))) continue;
            return true;
        }
        return false;
    }

    static NextInputCodec.Decoded decodeNext(Map<String, String> values) {
        return values.containsKey(NEXT_INPUT)
                ? NextInputCodec.decodeToken(values.get(NEXT_INPUT))
                : NextInputCodec.absent();
    }

    static boolean mentionsNext(String tail) {
        return tail != null && tail.toUpperCase(Locale.ROOT).contains(NEXT_INPUT);
    }

    static boolean mentionsRecovery(String tail) {
        return tail != null && tail.toUpperCase(Locale.ROOT).contains(RECOVERY);
    }

    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    static final class Parsed {
        final Map<String, String> values;
        final boolean valid;
        final String error;

        Parsed(Map<String, String> values, boolean valid, String error) {
            this.values = values;
            this.valid = valid;
            this.error = error;
        }
    }
}
