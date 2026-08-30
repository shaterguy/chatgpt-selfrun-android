package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.webkit.WebView;

import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.util.Set;

/** Receives protocol-state diagnostics from the trusted ChatGPT main frame and writes them to the run log. */
final class TurnProtocolLogBridge {
    static final String JS_OBJECT = "selfRunTurnLog";
    private static final Set<String> CHATGPT_ORIGINS = Set.of(
            "https://chatgpt.com", "https://www.chatgpt.com");

    private TurnProtocolLogBridge() {}

    static void install(WebView webView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        Context context = webView.getContext().getApplicationContext();
        SelfRunStore store = new SelfRunStore(context);
        SelfRunRunLog log = new SelfRunRunLog(context);
        WebViewCompat.addWebMessageListener(webView, JS_OBJECT, CHATGPT_ORIGINS,
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    if (!isMainFrame || message.getType() != WebMessageCompat.TYPE_STRING) return;
                    String raw = message.getData();
                    if (raw == null || raw.length() > 1024) return;
                    try {
                        JSONObject item = new JSONObject(raw);
                        String stage = item.optString("stage", "");
                        String phase = item.optString("phase", "");
                        String kind = item.optString("kind", "");
                        int sequence = item.optInt("sequence", -1);
                        String source = normalizedSource(stage, item.optString("source", ""));
                        if (source.isEmpty() || sequence < 1 || sequence > 999999) return;
                        if (!("FIRST_TURN".equals(kind) || "FOLLOWUP_TURN".equals(kind))) return;
                        if (!validPhaseForStage(stage, phase)) return;
                        log.record(store, "TURN_PROTOCOL", "stage=" + stage + ";source=" + source
                                + ";phase=" + phase + ";sequence=" + sequence + ";kind=" + kind);
                    } catch (Throwable ignored) {
                    }
                });
    }

    private static boolean validPhaseForStage(String stage, String phase) {
        return switch (stage) {
            case "turn_request", "completion_ignored" -> "THINKING".equals(phase);
            case "answering_started" -> "ANSWERING".equals(phase);
            case "complete", "completion_delegate" -> "COMPLETE".equals(phase);
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
            if ("canonical_fetch_rejected".equals(source)
                    || "completion_delegate_failed".equals(source)) return source;
            return source != null && source.matches("canonical_http_[0-9]{1,3}")
                    ? source : "protocol_unknown";
        }
        if (!("complete".equals(stage) || "completion_delegate".equals(stage))) return "";
        return switch (source) {
            case "message_stream_complete", "finished_successfully_end_turn" -> source;
            default -> "protocol_unknown";
        };
    }
}
