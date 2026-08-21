package com.shaterguy.chatgptselfrun;

import java.util.regex.Pattern;

/** Pure validation rules shared by SelfRun control formatting and Drive signal parsing. */
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
        if (model == null || reasoning == null) return false;
        return switch (model) {
            case "sol" -> "high".equals(reasoning)
                    || "xhigh".equals(reasoning)
                    || "max".equals(reasoning)
                    || "ultra".equals(reasoning);
            case "terra" -> "high".equals(reasoning)
                    || "xhigh".equals(reasoning)
                    || "max".equals(reasoning);
            case "luna" -> "max".equals(reasoning);
            default -> false;
        };
    }
}
