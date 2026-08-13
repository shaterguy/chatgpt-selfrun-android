package com.shaterguy.chatgptselfrun;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class SelfRunLogSampler {
    static final long SUMMARY_INTERVAL_MS = 60_000L;
    static final long BURST_WINDOW_MS = 10_000L;
    static final long BURST_THRESHOLD = 25L;

    private static final Set<String> REPEATABLE_EVENTS = Set.of(
            "DOM_EVALUATE",
            "DOM_RESULT",
            "DOM_WAIT",
            "ASSISTANT_BASELINE_WAIT",
            "ASSISTANT_EVALUATION",
            "DOM_OBSERVER_HEALTH_EVALUATE",
            "DOM_WATCHDOG_HEALTH",
            "DOM_OBSERVER_STATE",
            "DOM_OBSERVER_DUPLICATE",
            "DOM_EVALUATION_COALESCED",
            "DOM_WATCHDOG_SKIPPED",
            "STALE_CALLBACK",
            "WEBVIEW_EVENT_IGNORED",
            "WEBVIEW_NAVIGATION",
            "SERVICE_PAUSED"
    );

    private static final Pattern VOLATILE_COUNTERS = Pattern.compile(
            "(?i)(count|watchdog|suppressed|fullChecks|maintenanceEvaluations|stateEvents|"
                    + "nativeDuplicates|watchdogs|watchdogRecoveries|totalHeldMs|acquires|releases|"
                    + "phase_age_ms|activity_age_ms)=\\d+");

    static final class Context {
        final int generation;
        final int observerEpoch;
        final String webViewId;
        final String wakeLockState;

        Context(int generation, int observerEpoch, String webViewId, String wakeLockState) {
            this.generation = generation;
            this.observerEpoch = observerEpoch;
            this.webViewId = safe(webViewId, "none");
            this.wakeLockState = safe(wakeLockState, "UNKNOWN");
        }

        private String key() {
            return generation + "|" + observerEpoch + "|" + webViewId + "|" + wakeLockState;
        }
    }

    static final class Emission {
        final String event;
        final String detail;
        final long firstAtMs;
        final long lastAtMs;
        final long repeatCount;
        final boolean summary;
        final boolean abnormalBurst;
        final String summaryCause;
        final Context context;

        Emission(String event, String detail, long firstAtMs, long lastAtMs, long repeatCount,
                boolean summary, boolean abnormalBurst, String summaryCause, Context context) {
            this.event = event;
            this.detail = detail;
            this.firstAtMs = firstAtMs;
            this.lastAtMs = lastAtMs;
            this.repeatCount = repeatCount;
            this.summary = summary;
            this.abnormalBurst = abnormalBurst;
            this.summaryCause = summaryCause == null ? "" : summaryCause;
            this.context = context;
        }
    }

    static final class Metrics {
        final long repeatableCalls;
        final long aggregatedRepeats;
        final long emittedSamples;
        final long summaryEmissions;

        Metrics(long repeatableCalls, long aggregatedRepeats, long emittedSamples, long summaryEmissions) {
            this.repeatableCalls = repeatableCalls;
            this.aggregatedRepeats = aggregatedRepeats;
            this.emittedSamples = emittedSamples;
            this.summaryEmissions = summaryEmissions;
        }
    }

    private static final class Bucket {
        final String event;
        final String detail;
        final String phase;
        final Context context;
        final long firstAtMs;
        long lastAtMs;
        long count;
        long lastReportedCount;
        long lastSummaryAtMs;
        boolean burstReported;

        Bucket(String event, String detail, String phase, Context context, long nowMs) {
            this.event = event;
            this.detail = detail;
            this.phase = phase;
            this.context = context;
            this.firstAtMs = nowMs;
            this.lastAtMs = nowMs;
            this.count = 1L;
            this.lastReportedCount = 1L;
            this.lastSummaryAtMs = nowMs;
        }
    }

    private final Map<String, Bucket> buckets = new LinkedHashMap<>();
    private long repeatableCalls;
    private long aggregatedRepeats;
    private long emittedSamples;
    private long summaryEmissions;

    synchronized List<Emission> accept(String event, String detail, String phase, Context context, long nowMs) {
        if (!isRepeatable(event)) {
            emittedSamples = increment(emittedSamples);
            return List.of(single(event, detail, context, nowMs));
        }
        repeatableCalls = increment(repeatableCalls);
        String normalized = normalizeDetail(detail);
        String key = event + "|" + safe(phase, "") + "|" + normalized + "|" + context.key();
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            bucket = new Bucket(event, normalized, safe(phase, ""), context, nowMs);
            buckets.put(key, bucket);
            emittedSamples = increment(emittedSamples);
            return List.of(single(event, normalized, context, nowMs));
        }

        bucket.count = increment(bucket.count);
        bucket.lastAtMs = Math.max(bucket.lastAtMs, nowMs);
        aggregatedRepeats = increment(aggregatedRepeats);

        boolean abnormalBurst = !bucket.burstReported
                && bucket.count >= BURST_THRESHOLD
                && bucket.lastAtMs - bucket.firstAtMs <= BURST_WINDOW_MS;
        boolean periodic = bucket.lastAtMs - bucket.lastSummaryAtMs >= SUMMARY_INTERVAL_MS;
        if (!abnormalBurst && !periodic) return List.of();

        if (abnormalBurst) bucket.burstReported = true;
        bucket.lastSummaryAtMs = bucket.lastAtMs;
        bucket.lastReportedCount = bucket.count;
        emittedSamples = increment(emittedSamples);
        summaryEmissions = increment(summaryEmissions);
        return List.of(summary(bucket, abnormalBurst, abnormalBurst ? "burst" : "interval"));
    }

    synchronized List<Emission> flush(String cause, long nowMs) {
        if (buckets.isEmpty()) return List.of();
        List<Emission> emissions = new ArrayList<>();
        for (Bucket bucket : buckets.values()) {
            if (bucket.count <= bucket.lastReportedCount) continue;
            bucket.lastAtMs = Math.max(bucket.lastAtMs, nowMs);
            bucket.lastReportedCount = bucket.count;
            emissions.add(summary(bucket, false, safe(cause, "flush")));
            emittedSamples = increment(emittedSamples);
            summaryEmissions = increment(summaryEmissions);
        }
        buckets.clear();
        return emissions;
    }

    synchronized Metrics metrics() {
        return new Metrics(repeatableCalls, aggregatedRepeats, emittedSamples, summaryEmissions);
    }

    static boolean isRepeatable(String event) {
        return event != null && REPEATABLE_EVENTS.contains(event);
    }

    static String normalizeDetail(String detail) {
        String safe = detail == null ? "" : detail.trim();
        return VOLATILE_COUNTERS.matcher(safe).replaceAll("$1=*");
    }

    private static Emission single(String event, String detail, Context context, long nowMs) {
        return new Emission(event, detail, nowMs, nowMs, 1L, false, false, "first", context);
    }

    private static Emission summary(Bucket bucket, boolean abnormalBurst, String cause) {
        return new Emission("REPEAT_SUMMARY",
                "source_event=" + bucket.event + ";cause=" + bucket.detail + ";phase=" + bucket.phase,
                bucket.firstAtMs, bucket.lastAtMs, bucket.count, true, abnormalBurst, cause, bucket.context);
    }

    private static long increment(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
