package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Process-local Work protocol coverage ledger. It persists only sanitized counters and sources. */
final class WorkProtocolCoverageTracker {
    private static final long DUPLICATE_WINDOW_MS = 750L;
    private static final Map<String, State> STATES = new LinkedHashMap<>();

    private WorkProtocolCoverageTracker() {}

    static synchronized void observeNativeRequest(Context context, SelfRunStore store, String source) {
        if (!eligible(store)) return;
        State state = state(store);
        state.mainFrameSeen = true;
        if (WorkProtocolNativeObserver.SOURCE_SERVICE_WORKER.equals(source)) state.serviceWorkerRequestSeen = true;
        beginRequest(state, normalizeSource(source));
    }

    static synchronized void observeDiagnostic(
            Context context, SelfRunStore store, JSONObject item, boolean mainFrame) {
        if (!eligible(store) || item == null) return;
        State state = state(store);
        if (mainFrame) state.mainFrameSeen = true; else state.subframeSeen = true;
        String stage = safe(item.optString("stage", ""));
        String source = normalizeSource(item.optString("source", ""));
        String outcome = safe(item.optString("outcome", ""));
        if ("WORK_PROTOCOL_TRANSPORT".equals(stage)) {
            if ("canonical_request".equals(outcome) || "accepted".equals(outcome)) beginRequest(state, source);
            if ("created".equals(outcome)) counters(state, source).created++;
            if ("message_received".equals(outcome)) counters(state, source).messageReceived++;
            if (source.startsWith("service_worker_message")) state.serviceWorkerMessageSeen = true;
            if (WorkProtocolNativeObserver.SOURCE_SERVICE_WORKER.equals(source)) state.serviceWorkerRequestSeen = true;
        } else if ("WORK_PROTOCOL_FRAME".equals(stage)) {
            Counter counter = counters(state, source);
            if (!item.optString("dataType", "").isEmpty() && !source.startsWith("service_worker_message")) {
                counter.messageReceived++;
            }
            if (!item.optString("topKeys", "").isEmpty() || item.has("encodedItemFound")) {
                counter.frameDecoded++;
                if (state.frameSource.isEmpty()) state.frameSource = source;
            }
        } else if ("WORK_PROTOCOL_SIGNAL".equals(stage)) {
            String semantic = safe(item.optString("semantic", ""));
            if (meaningfulSemantic(semantic)) {
                counters(state, source).semanticCandidate++;
                if (state.semanticSource.isEmpty()) state.semanticSource = source;
            }
            if (item.optBoolean("staleRejected", false)) state.staleRejected = true;
        } else if ("WORK_PROTOCOL_TRANSITION".equals(stage)) {
            String transition = item.optString("transition", "");
            if (transition.endsWith(">ANSWERING") && state.answeringSource.isEmpty()) state.answeringSource = source;
            if (transition.endsWith(">COMPLETE") && state.completionSource.isEmpty()) {
                String completion = safe(item.optString("completion", ""));
                state.completionSource = completion.isEmpty() ? source : completion;
            }
        }
    }

    static synchronized void observeProtocol(
            Context context, SelfRunStore store, String stage, String source, String phase) {
        if (!eligible(store)) return;
        State state = state(store);
        state.mainFrameSeen = true;
        if ("turn_request".equals(stage)) {
            if (state.requestSource.isEmpty()) beginRequest(state, "protocol_canonical");
            return;
        }
        if ("answering_started".equals(stage) && state.answeringSource.isEmpty()) {
            state.answeringSource = safe(source);
            return;
        }
        if (("complete".equals(stage) || "completion_dispatch".equals(stage))
                && state.completionSource.isEmpty()) state.completionSource = safe(source);
    }

    static synchronized void observeCompletionNavigation(Context context, Uri uri) {
        if (context == null || uri == null) return;
        if (!SelfRunContinuationDom.TURN_COMPLETION_SCHEME.equals(uri.getScheme())
                || !SelfRunContinuationDom.TURN_COMPLETION_HOST.equals(uri.getHost())) return;
        SelfRunStore store = new SelfRunStore(context.getApplicationContext());
        if (!eligible(store)) return;
        State state = state(store);
        String source = safe(uri.getQueryParameter("source"));
        if (source.isEmpty()) emit(context, store, state, "stable_idle");
        else {
            if (state.completionSource.isEmpty()) state.completionSource = source;
            emit(context, store, state, "protocol");
        }
    }

    private static void beginRequest(State state, String source) {
        long now = SystemClock.elapsedRealtime();
        if (state.coverageEmitted || state.requestSource.isEmpty()
                || now - state.lastRequestAt > DUPLICATE_WINDOW_MS) {
            state.resetTurn();
            state.requestSource = safe(source);
            state.lastRequestAt = now;
        }
    }

    private static void emit(Context context, SelfRunStore store, State state, String winner) {
        if (state.coverageEmitted) return;
        state.coverageEmitted = true;
        StringBuilder out = new StringBuilder();
        add(out, "requestSource", state.requestSource);
        add(out, "frameSource", state.frameSource);
        add(out, "semanticSource", state.semanticSource);
        add(out, "answeringSource", state.answeringSource);
        add(out, "completionSource", state.completionSource);
        add(out, "fallbackWinner", winner);
        add(out, "failureClass", failureClass(state, winner));
        add(out, "mainFrameSeen", Boolean.toString(state.mainFrameSeen));
        add(out, "subframeSeen", Boolean.toString(state.subframeSeen));
        add(out, "serviceWorkerRequestSeen", Boolean.toString(state.serviceWorkerRequestSeen));
        add(out, "serviceWorkerMessageSeen", Boolean.toString(state.serviceWorkerMessageSeen));
        add(out, "counters", counterSummary(state));
        new SelfRunRunLog(context.getApplicationContext()).record(store, "WORK_PROTOCOL_COVERAGE", out.toString());
    }

    private static String failureClass(State state, String winner) {
        if ("protocol".equals(winner) && !state.completionSource.isEmpty()) return "protocol_complete";
        if (state.requestSource.isEmpty()) return "canonical_request_missing";
        if (state.frameSource.isEmpty() && state.requestSource.startsWith("native_")) return "native_request_only";
        if (state.frameSource.isEmpty()) return "response_transport_missing";
        if (state.semanticSource.isEmpty()) return "frame_decoder_failed";
        if (state.answeringSource.isEmpty() || state.completionSource.isEmpty()) {
            return state.staleRejected ? "stale_rejection" : "semantic_state_rejected";
        }
        return "response_transport_missing";
    }

    private static boolean meaningfulSemantic(String semantic) {
        return !semantic.isEmpty() && !semantic.contains("done_ignored")
                && !semantic.startsWith("opaque") && !semantic.startsWith("unparsed");
    }

    private static Counter counters(State state, String source) {
        String key = normalizeSource(source);
        if (key.isEmpty()) key = "unknown";
        return state.counters.computeIfAbsent(key, ignored -> new Counter());
    }

    private static String normalizeSource(String value) {
        String source = safe(value);
        if (source.startsWith("subframe_work-")) return "subframe_" + source.substring("subframe_work-".length()).replace('-', '_');
        if (source.startsWith("work-")) return source.substring(5).replace('-', '_');
        return source;
    }

    private static String counterSummary(State state) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Counter> entry : state.counters.entrySet()) {
            if (out.length() > 0) out.append('|');
            Counter c = entry.getValue();
            out.append(entry.getKey()).append(':').append(c.created).append('/')
                    .append(c.messageReceived).append('/').append(c.frameDecoded).append('/')
                    .append(c.semanticCandidate);
            if (out.length() > 320) break;
        }
        return out.toString();
    }

    private static State state(SelfRunStore store) {
        String runId = safe(store.runId());
        State result = STATES.computeIfAbsent(runId, ignored -> new State());
        while (STATES.size() > 4) STATES.remove(STATES.keySet().iterator().next());
        return result;
    }

    private static boolean eligible(SelfRunStore store) {
        return store != null && store.active() && SelfRunStore.MODE_WORK.equals(store.mode());
    }

    private static String safe(String value) {
        String text = value == null ? "" : value.replaceAll("[^A-Za-z0-9_.:/,>|-]", "_");
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    private static void add(StringBuilder out, String key, String value) {
        if (out.length() > 0) out.append(';');
        out.append(key).append('=').append(value == null ? "" : value);
    }

    private static final class State {
        String requestSource = "";
        String frameSource = "";
        String semanticSource = "";
        String answeringSource = "";
        String completionSource = "";
        long lastRequestAt;
        boolean coverageEmitted;
        boolean mainFrameSeen;
        boolean subframeSeen;
        boolean serviceWorkerRequestSeen;
        boolean serviceWorkerMessageSeen;
        boolean staleRejected;
        final LinkedHashMap<String, Counter> counters = new LinkedHashMap<>();

        void resetTurn() {
            requestSource = ""; frameSource = ""; semanticSource = "";
            answeringSource = ""; completionSource = ""; lastRequestAt = 0L;
            coverageEmitted = false; mainFrameSeen = false; subframeSeen = false;
            serviceWorkerRequestSeen = false; serviceWorkerMessageSeen = false;
            staleRejected = false; counters.clear();
        }
    }

    private static final class Counter {
        int created;
        int messageReceived;
        int frameDecoded;
        int semanticCandidate;
    }
}
