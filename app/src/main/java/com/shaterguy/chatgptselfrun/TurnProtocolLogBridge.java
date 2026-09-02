package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.webkit.WebView;

import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.util.Set;

/** Receives trusted protocol-state events for run logging and the user-facing runtime status. */
final class TurnProtocolLogBridge {
    static final String JS_OBJECT = "selfRunTurnLog";
    private static final Set<String> CHATGPT_ORIGINS = Set.of(
            "https://chatgpt.com", "https://www.chatgpt.com");
    private static final Set<String> WORK_DIAGNOSTIC_STAGES = Set.of(
            "WORK_PROTOCOL_TRANSPORT", "WORK_PROTOCOL_FRAME", "WORK_PROTOCOL_SIGNAL",
            "WORK_PROTOCOL_TRANSITION", "WORK_PROTOCOL_DECODE_ERROR",
            "WORK_PROTOCOL_ENV", "WORK_PROTOCOL_COVERAGE");

    private TurnProtocolLogBridge() {}

    static boolean install(WebView webView) {
        Context context = webView.getContext().getApplicationContext();
        SelfRunStore store = new SelfRunStore(context);
        SelfRunRunLog log = new SelfRunRunLog(context);
        String runId = store.runId();
        boolean messageBridge = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
        boolean documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        if (!messageBridge || !documentStart) {
            if (!runId.isEmpty()) {
                TurnProtocolUiState.recordDetector(context, runId,
                        TurnProtocolUiState.DETECTOR_DOM_FALLBACK_ONLY);
                log.record(store, "TURN_DETECTOR", "path=DOM_FALLBACK_ONLY;reason="
                        + (!messageBridge ? "web_message_listener_unavailable" : "document_start_script_unavailable"));
            }
            return false;
        }
        WebViewCompat.addWebMessageListener(webView, JS_OBJECT, CHATGPT_ORIGINS,
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    if (message.getType() != WebMessageCompat.TYPE_STRING) return;
                    String raw = message.getData();
                    if (raw == null || raw.length() > 1024) return;
                    try {
                        JSONObject item = new JSONObject(raw);
                        String eventRunId = item.optString("runId", "");
                        String stage = item.optString("stage", "");
                        String phase = item.optString("phase", "");
                        if (eventRunId.isEmpty() || !eventRunId.equals(store.runId())) return;
                        if (WORK_DIAGNOSTIC_STAGES.contains(stage)) {
                            if (!SelfRunStore.MODE_WORK.equals(store.mode())) return;
                            item.put("frame", isMainFrame ? "main" : "subframe");
                            WorkProtocolCoverageTracker.observeDiagnostic(context, store, item, isMainFrame);
                            String details = workDiagnosticDetails(item);
                            if (!details.isEmpty()) log.record(store, stage, details);
                            return;
                        }
                        if (!isMainFrame) return;
                        String source = normalizedSource(stage, item.optString("source", ""));
                        if (source.isEmpty() || !validPhaseForStage(stage, phase)) return;
                        TurnProtocolUiState.record(context, eventRunId, stage, phase);
                        WorkProtocolCoverageTracker.observeProtocol(context, store, stage, source, phase);
                        log.record(store, "TURN_PROTOCOL", "stage=" + stage + ";source=" + source
                                + ";phase=" + phase);
                    } catch (Throwable ignored) {
                    }
                });
        if (!runId.isEmpty()) {
            TurnProtocolUiState.recordDetector(context, runId,
                    TurnProtocolUiState.DETECTOR_PROTOCOL_PRIMARY);
            log.record(store, "TURN_DETECTOR",
                    "path=PROTOCOL_PRIMARY;fallback=DOM;bridge=web_message_listener;document_start=1");
        }
        return true;
    }

    private static String workDiagnosticDetails(JSONObject item) {
        StringBuilder out = new StringBuilder();
        appendToken(out, "source", item.optString("source", ""));
        appendToken(out, "phase", item.optString("phase", ""));
        appendToken(out, "frame", item.optString("frame", ""));
        appendToken(out, "transport", item.optString("transport", ""));
        appendToken(out, "route", item.optString("route", ""));
        appendToken(out, "dataType", item.optString("dataType", ""));
        appendTokenList(out, "topKeys", item.optString("topKeys", ""));
        appendToken(out, "decoder", item.optString("decoder", ""));
        appendToken(out, "semantic", item.optString("semantic", ""));
        appendToken(out, "binding", item.optString("binding", ""));
        appendToken(out, "transition", item.optString("transition", ""));
        appendToken(out, "completion", item.optString("completion", ""));
        appendToken(out, "outcome", item.optString("outcome", ""));
        appendToken(out, "reason", item.optString("reason", ""));
        appendToken(out, "requestSource", item.optString("requestSource", ""));
        appendToken(out, "frameSource", item.optString("frameSource", ""));
        appendToken(out, "semanticSource", item.optString("semanticSource", ""));
        appendToken(out, "answeringSource", item.optString("answeringSource", ""));
        appendToken(out, "completionSource", item.optString("completionSource", ""));
        appendToken(out, "fallbackWinner", item.optString("fallbackWinner", ""));
        appendToken(out, "failureClass", item.optString("failureClass", ""));
        appendNumber(out, "frameCount", item.optLong("frameCount", -1L));
        appendNumber(out, "byteLength", item.optLong("byteLength", -1L));
        appendNumber(out, "createdCount", item.optLong("createdCount", -1L));
        appendNumber(out, "messageReceivedCount", item.optLong("messageReceivedCount", -1L));
        appendNumber(out, "frameDecodedCount", item.optLong("frameDecodedCount", -1L));
        appendNumber(out, "semanticCandidateCount", item.optLong("semanticCandidateCount", -1L));
        appendBoolean(out, "encodedItemFound", item, "encodedItemFound");
        appendBoolean(out, "websocketCreated", item, "websocketCreated");
        appendBoolean(out, "staleRejected", item, "staleRejected");
        appendBoolean(out, "mainFrameSeen", item, "mainFrameSeen");
        appendBoolean(out, "subframeSeen", item, "subframeSeen");
        appendBoolean(out, "serviceWorkerRequestSeen", item, "serviceWorkerRequestSeen");
        appendBoolean(out, "serviceWorkerMessageSeen", item, "serviceWorkerMessageSeen");
        appendBoolean(out, "serviceWorkerControllerSeen", item, "serviceWorkerControllerSeen");
        return out.toString();
    }

    private static void appendToken(StringBuilder out, String key, String value) {
        if (value == null || value.isEmpty()) return;
        String safe = value.replaceAll("[^A-Za-z0-9_.:/,>-]", "_");
        if (safe.length() > 160) safe = safe.substring(0, 160);
        append(out, key, safe);
    }

    private static void appendTokenList(StringBuilder out, String key, String value) {
        if (value == null || value.isEmpty()) return;
        String[] parts = value.split(",");
        StringBuilder safe = new StringBuilder();
        for (String part : parts) {
            String token = part.replaceAll("[^A-Za-z0-9_.:/-]", "_");
            if (token.isEmpty()) continue;
            if (token.length() > 48) token = token.substring(0, 48);
            if (safe.length() > 0) safe.append(',');
            safe.append(token);
            if (safe.length() >= 160) break;
        }
        if (safe.length() > 160) safe.setLength(160);
        append(out, key, safe.toString());
    }

    private static void appendNumber(StringBuilder out, String key, long value) {
        if (value < 0) return;
        append(out, key, Long.toString(value));
    }

    private static void appendBoolean(StringBuilder out, String key, JSONObject item, String field) {
        if (!item.has(field)) return;
        append(out, key, Boolean.toString(item.optBoolean(field, false)));
    }

    private static void append(StringBuilder out, String key, String value) {
        if (value == null || value.isEmpty()) return;
        if (out.length() > 0) out.append(';');
        out.append(key).append('=').append(value);
    }

    private static boolean validPhaseForStage(String stage, String phase) {
        return switch (stage) {
            case "turn_request", "completion_ignored" -> "THINKING".equals(phase);
            case "answering_started" -> "ANSWERING".equals(phase);
            case "complete", "completion_dispatch" -> "COMPLETE".equals(phase);
            case "error" -> "ERROR".equals(phase);
            default -> false;
        };
    }

    private static String normalizedSource(String stage, String source) {
        if ("turn_request".equals(stage)) return "canonical_post";
        if ("answering_started".equals(stage)) {
            return switch (source) {
                case "final_channel", "visible_answer", "assistant_final_text" -> source;
                default -> "";
            };
        }
        if ("completion_ignored".equals(stage)) {
            return switch (source) {
                case "message_stream_complete", "finished_successfully_end_turn" -> source;
                default -> "protocol_unknown";
            };
        }
        if ("error".equals(stage)) {
            if ("canonical_fetch_rejected".equals(source)) return source;
            return source != null && source.matches("canonical_http_[0-9]{1,3}") ? source : "protocol_unknown";
        }
        if (!("complete".equals(stage) || "completion_dispatch".equals(stage))) return "";
        return switch (source) {
            case "message_stream_complete", "finished_successfully_end_turn", "restored_complete" -> source;
            default -> "protocol_unknown";
        };
    }
}
