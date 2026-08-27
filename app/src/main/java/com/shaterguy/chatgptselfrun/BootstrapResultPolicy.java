package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Locale;
import java.util.Set;

/** Pure parsing and classification for the initial ChatGPT bootstrap callback. */
final class BootstrapResultPolicy {
    static final String CALLBACK_INVALID = "CHAT_BOOTSTRAP_CALLBACK_INVALID";
    static final String SCRIPT_ERROR = "CHAT_BOOTSTRAP_SCRIPT_ERROR";
    static final String UNKNOWN_STATUS = "CHAT_BOOTSTRAP_UNKNOWN_STATUS";
    static final String TIMEOUT = "CHAT_BOOTSTRAP_TIMEOUT";
    static final String STATE_PERSIST_FAILED = "CHAT_BOOTSTRAP_STATE_PERSIST_FAILED";
    static final String READBACK_MISSING = "CHAT_BOOTSTRAP_READBACK_MISSING";

    private static final Set<String> NON_FATAL = Set.of(
  "READY", "UI_WAIT", "WAIT", "TARGET_ERROR", "AUTH_REQUIRED");
    /**
     * These failures are emitted only after the bootstrap DOM has confirmed that it is on the
     * new-conversation surface. Reclassifying them as TARGET_ERROR lets the service use its
     * existing canonical-route recovery (ChatGPT home or the selected project home) without
     * weakening the persistent bootstrap deadline.
     */
    private static final Set<String> CANONICAL_RECONNECT_FAILURES = Set.of(
  "CHAT_REASONING_TRIGGER_NOT_FOUND",
  "CHAT_REASONING_SLIDER_NOT_FOUND",
  "CHAT_REASONING_ADVANCED_CONTROL_NOT_FOUND",
  "CHAT_REASONING_OPTION_UNAVAILABLE",
  "CHAT_REASONING_READBACK_MISMATCH",
  "CHAT_REASONING_MENU_CLOSE_FAILED",
  "CHAT_BOOTSTRAP_MODE_CONTROL_NOT_FOUND",
  "CHAT_BOOTSTRAP_MODE_READBACK_FAILED",
  "CHAT_BOOTSTRAP_COMPOSER_NOT_FOUND");
    private static final String[] DIAGNOSTIC_KEYS = {
  "action", "requested", "observed", "verifiedValue", "currentMode",
  "targetFound", "targetSelected", "targetSource", "modeAttempts",
  "modeClickAttempts", "modeElapsedMs", "sliderFound", "sliderKind",
  "triggerFound", "searchElapsedMs", "stage", "sliderObserved",
  "advancedButtonFound", "calibratedTargetValid", "current", "source",
  "levelFound", "optionFound", "attempts", "elapsedMs", "timeoutMs",
  "errorName", "errorMessage", "reconnectCause"
    };

    private BootstrapResultPolicy() {}

    static final class Parsed {
        final JSONObject result;
        final String status;
        final String detail;
        final boolean valid;
        final String parseError;

        Parsed(JSONObject result, String status, String detail, boolean valid, String parseError) {
  this.result = result == null ? new JSONObject() : result;
  this.status = safe(status, 80);
  this.detail = safe(detail, 240);
  this.valid = valid;
  this.parseError = safe(parseError, 120);
        }
    }

    static Parsed parse(String raw) {
        if (raw == null || raw.trim().isEmpty() || "null".equals(raw.trim())) {
  return invalid("empty-callback");
        }
        try {
  Object outer = new JSONTokener(raw).nextValue();
  Object payload = outer instanceof String
          ? new JSONTokener((String) outer).nextValue() : outer;
  if (!(payload instanceof JSONObject object)) return invalid("non-object-callback");
  JSONObject result = new JSONObject(object.toString());
  String status = result.optString("status", "").trim();
  if (status.isEmpty()) return invalid("missing-status");
  if (requiresCanonicalReconnect(status)) {
      JSONObject diagnostics = result.optJSONObject("diagnostics");
      if (diagnostics == null) {
          diagnostics = new JSONObject();
          result.put("diagnostics", diagnostics);
      }
      diagnostics.put("reconnectCause", status);
      return new Parsed(result, "TARGET_ERROR", result.optString("detail", ""), true, "");
  }
  return new Parsed(result, status, result.optString("detail", ""), true, "");
        } catch (Throwable error) {
  return invalid(error.getClass().getSimpleName());
        }
    }

    private static Parsed invalid(String reason) {
        JSONObject result = new JSONObject();
        try {
  result.put("status", "SCRIPT_ERROR");
  result.put("detail", "WebView callback parse failed");
        } catch (Throwable ignored) {}
        return new Parsed(result, "SCRIPT_ERROR", "WebView callback parse failed", false, reason);
    }

    static boolean requiresCanonicalReconnect(String status) {
        return status != null && CANONICAL_RECONNECT_FAILURES.contains(status);
    }

    static String fatalStatus(Parsed parsed, long deadlineAt, long now) {
        if (deadlineAt > 0L && now >= deadlineAt) return TIMEOUT;
        if (parsed == null || !parsed.valid) return CALLBACK_INVALID;
        if ("SCRIPT_ERROR".equals(parsed.status)) return SCRIPT_ERROR;
        if (isExplicitFailure(parsed.status)) return parsed.status;
        return NON_FATAL.contains(parsed.status) ? "" : UNKNOWN_STATUS;
    }

    static boolean isExplicitFailure(String status) {
        if (status == null) return false;
        return status.startsWith("CHAT_REASONING_") || status.startsWith("CHAT_BOOTSTRAP_");
    }

    static String observedReasoning(JSONObject result) {
        JSONObject diagnostics = result == null ? null : result.optJSONObject("diagnostics");
        if (diagnostics == null) return "";
        String observed = diagnostics.optString("observed", "").trim();
        if (observed.isEmpty()) observed = diagnostics.optString("verifiedValue", "").trim();
        return observed.toLowerCase(Locale.ROOT);
    }

    static String logDetail(Parsed parsed, BootstrapRunStateStore.Window window,
                  int generation, String scope) {
        StringBuilder out = new StringBuilder("phase=BOOTSTRAP;status=")
      .append(safe(parsed == null ? "" : parsed.status, 80))
      .append(";valid=").append(parsed != null && parsed.valid ? 1 : 0)
      .append(";attempt=").append(window == null ? 0 : window.attempts)
      .append(";elapsedMs=").append(window == null ? 0L : window.elapsedMs(System.currentTimeMillis()))
      .append(";generation=").append(Math.max(0, generation))
      .append(";scope=").append(safe(scope, 24));
        if (parsed != null && !parsed.detail.isEmpty()) out.append(";detail=").append(safe(parsed.detail, 180));
        if (parsed != null && !parsed.parseError.isEmpty()) out.append(";parseError=").append(parsed.parseError);
        if (parsed != null) out.append(compactDiagnostics(parsed.result.optJSONObject("diagnostics")));
        return out.toString();
    }

    static String compactDiagnostics(JSONObject diagnostics) {
        if (diagnostics == null) return "";
        StringBuilder out = new StringBuilder();
        for (String key : DIAGNOSTIC_KEYS) {
  if (!diagnostics.has(key)) continue;
  Object value = diagnostics.opt(key);
  if (value == null || value == JSONObject.NULL) continue;
  String text = safe(String.valueOf(value), 120);
  if (!text.isEmpty()) out.append(';').append(key).append('=').append(text);
        }
        return out.toString();
    }

    static String safe(String value, int max) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ')
      .replace(';', ',').trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
