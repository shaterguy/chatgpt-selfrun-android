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
                    if (!isMainFrame || message.getType() != WebMessageCompat.TYPE_STRING) return;
                    String raw = message.getData();
                    if (raw == null || raw.length() > 1024) return;
                    try {
                        JSONObject item = new JSONObject(raw);
                        String eventRunId = item.optString("runId", "");
                        String stage = item.optString("stage", "");
                        String phase = item.optString("phase", "");
                        String source = normalizedSource(stage, item.optString("source", ""));
                        if (eventRunId.isEmpty() || !eventRunId.equals(store.runId())) return;
                        if (source.isEmpty() || !validPhaseForStage(stage, phase)) return;
                        TurnProtocolUiState.record(context, eventRunId, stage, phase);
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
