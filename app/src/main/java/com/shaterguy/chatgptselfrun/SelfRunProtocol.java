package com.shaterguy.chatgptselfrun;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SelfRunProtocol {
    enum Type { NEXT, DONE, USER_ACTION, PAUSE, NONE }

    static final class Signal {
        final Type type;
        final String raw;
        final String runId;
        final String role;
        final String model;
        final String reasoning;
        final String actionId;

        Signal(Type type, String raw, String runId, String role, String model,
               String reasoning, String actionId) {
            this.type = type;
            this.raw = raw;
            this.runId = runId;
            this.role = role;
            this.model = model;
            this.reasoning = reasoning;
            this.actionId = actionId;
        }
    }

    private static final Pattern BRACKET = Pattern.compile("\\[SELF_RUN_(NEXT|DONE|USER_ACTION_REQUIRED|PAUSE)\\s+([^\\]]+)]");
    private SelfRunProtocol() {}

    static Signal parseLatest(String assistantText, String expectedRunId, String mode) {
        if (assistantText == null) return none();
        Matcher matcher = BRACKET.matcher(assistantText);
        Signal last = none();
        while (matcher.find()) {
            String kind = matcher.group(1);
            String payload = matcher.group(2).trim();
            String[] parts = payload.split("\\s+");
            if (parts.length == 0 || !expectedRunId.equals(parts[0])) continue;
            String raw = matcher.group(0);
            if ("DONE".equals(kind)) {
                last = new Signal(Type.DONE, raw, parts[0], "", "", "", "");
            } else if ("USER_ACTION_REQUIRED".equals(kind)) {
                String action = parts.length > 1 ? parts[1] : "ACTION";
                last = new Signal(Type.USER_ACTION, raw, parts[0], "", "", "", action);
            } else if ("PAUSE".equals(kind)) {
                last = new Signal(Type.PAUSE, raw, parts[0], value(payload, "ROLE"), "", "", "");
            } else {
                String role = value(payload, "ROLE").toUpperCase(Locale.ROOT);
                String model = value(payload, "MODEL").toLowerCase(Locale.ROOT);
                String reasoning = value(payload, "REASONING").toLowerCase(Locale.ROOT);
                if (SelfRunStore.MODE_CHAT.equals(mode)) {
                    model = "";
                    reasoning = "";
                } else if (!validWorkProfile(model, reasoning)) {
                    continue;
                }
                if (role.isEmpty()) role = "BUILDER";
                last = new Signal(Type.NEXT, raw, parts[0], role, model, reasoning, "");
            }
        }
        return last;
    }

    static boolean validWorkProfile(String model, String reasoning) {
        if (!("sol".equals(model) || "terra".equals(model) || "luna".equals(model))) return false;
        if (!("high".equals(reasoning) || "xhigh".equals(reasoning)
                || "max".equals(reasoning) || "ultra".equals(reasoning))) return false;
        return !"luna".equals(model) || "max".equals(reasoning) || "ultra".equals(reasoning);
    }

    static String bootstrap(String runId, String mode, String requirement) {
        return "[SELF_RUN_BOOTSTRAP 0.1.0 " + runId + " MODE=" + mode + "]\n\n" + requirement.trim();
    }

    static String continuation(String runId) {
        return "[SELF_RUN_CONTINUE " + runId + "]";
    }

    static String signalRecovery(String runId) {
        return "[SELF_RUN_SIGNAL_RECOVERY " + runId + "]";
    }

    private static String value(String payload, String key) {
        Matcher matcher = Pattern.compile("(?:^|\\s)" + key + "=([^\\s]+)", Pattern.CASE_INSENSITIVE).matcher(payload);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static Signal none() {
        return new Signal(Type.NONE, "", "", "", "", "", "");
    }
}
