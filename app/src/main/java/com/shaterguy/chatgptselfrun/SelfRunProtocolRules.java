package com.shaterguy.chatgptselfrun;

import java.util.regex.Pattern;

/** Pure protocol validation; dynamic Work profile validity is delegated to ProfileRegistry. */
final class SelfRunProtocolRules {
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9._:-]{1,80}");
    private static final Pattern RECOVERY_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private SelfRunProtocolRules() {}

    static boolean validRunId(String value) {
        return value != null && RUN_ID.matcher(value).matches();
    }

    static boolean validRecoveryId(String value) {
        return value != null && RECOVERY_ID.matcher(value).matches();
    }

    static boolean validWorkProfile(String model, String reasoning) {
        return ProfileRegistry.resolveWork(model, reasoning) != null;
    }
}
