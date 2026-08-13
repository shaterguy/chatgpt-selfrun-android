package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class SelfRunHistoryStore {
    private static final String PREFS = "selfrun_history";
    private static final String KEY_PRIMARY = "runs";
    private static final String KEY_BACKUP = "runsBackup";
    private static final int MAX_RUNS = 100;
    static final long SYNC_DEBOUNCE_MS = 250L;

    static final class Metrics {
        final long syncRequests;
        final long coalescedRequests;
        final long equivalentSnapshotsSkipped;
        final long staleSnapshotsSkipped;
        final long physicalWrites;

        Metrics(long syncRequests, long coalescedRequests, long equivalentSnapshotsSkipped,
                long staleSnapshotsSkipped, long physicalWrites) {
            this.syncRequests = syncRequests;
            this.coalescedRequests = coalescedRequests;
            this.equivalentSnapshotsSkipped = equivalentSnapshotsSkipped;
            this.staleSnapshotsSkipped = staleSnapshotsSkipped;
            this.physicalWrites = physicalWrites;
        }
    }

    private static final Object PENDING_LOCK = new Object();
    private static final Object WRITE_LOCK = new Object();
    private static final ScheduledExecutorService WRITER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SelfRunHistoryWriter");
        thread.setDaemon(true);
        return thread;
    });
    // pendingSnapshots are shared process-wide through this single static map.
    private static final Map<String, JSONObject> PENDING_SNAPSHOTS = new LinkedHashMap<>();
    private static ScheduledFuture<?> scheduledDrain;
    private static long syncRequests;
    private static long coalescedRequests;
    private static long equivalentSnapshotsSkipped;
    private static long staleSnapshotsSkipped;
    private static long physicalWrites;

    private final SharedPreferences prefs;

    SelfRunHistoryStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean sync(SelfRunStore store) {
        return schedule(store, false);
    }

    boolean syncCritical(SelfRunStore store) {
        return schedule(store, true);
    }

    Metrics metrics() {
        synchronized (PENDING_LOCK) {
            return new Metrics(syncRequests, coalescedRequests, equivalentSnapshotsSkipped,
                    staleSnapshotsSkipped, physicalWrites);
        }
    }

    private boolean schedule(SelfRunStore store, boolean critical) {
        if (store == null || store.runId().isEmpty()) return true;
        JSONObject nextSnapshot = snapshot(store);
        String runId = store.runId();
        synchronized (PENDING_LOCK) {
            syncRequests = increment(syncRequests);
            JSONObject pending = PENDING_SNAPSHOTS.get(runId);
            if (sameSnapshot(pending, nextSnapshot)) {
                equivalentSnapshotsSkipped = increment(equivalentSnapshotsSkipped);
                return true;
            }
            PENDING_SNAPSHOTS.put(runId, nextSnapshot);
            if (scheduledDrain != null && !scheduledDrain.isDone()) {
                coalescedRequests = increment(coalescedRequests);
                if (!critical) return true;
                scheduledDrain.cancel(false);
                scheduledDrain = null;
            }
            scheduledDrain = WRITER.schedule(this::drainPending, critical ? 0L : SYNC_DEBOUNCE_MS,
                    TimeUnit.MILLISECONDS);
        }
        return true;
    }

    private void drainPending() {
        List<JSONObject> batch = new ArrayList<>();
        synchronized (PENDING_LOCK) {
            batch.addAll(PENDING_SNAPSHOTS.values());
            PENDING_SNAPSHOTS.clear();
            scheduledDrain = null;
        }
        for (JSONObject snapshot : batch) writeSnapshot(snapshot);
    }

    private void writeSnapshot(JSONObject nextSnapshot) {
        if (nextSnapshot == null) return;
        synchronized (WRITE_LOCK) {
            JSONArray current = read();
            String runId = nextSnapshot.optString("runId");
            JSONObject previousSnapshot = find(current, runId);
            if (sameSnapshot(previousSnapshot, nextSnapshot)) {
                synchronized (PENDING_LOCK) {
                    equivalentSnapshotsSkipped = increment(equivalentSnapshotsSkipped);
                }
                return;
            }
            if (isOlderSnapshot(previousSnapshot, nextSnapshot)) {
                synchronized (PENDING_LOCK) {
                    staleSnapshotsSkipped = increment(staleSnapshotsSkipped);
                }
                return;
            }

            JSONArray next = new JSONArray();
            next.put(nextSnapshot);
            for (int i = 0; i < current.length() && next.length() < MAX_RUNS; i++) {
                JSONObject item = current.optJSONObject(i);
                if (item == null || runId.equals(item.optString("runId"))) continue;
                next.put(item);
            }
            String previous = prefs.getString(KEY_PRIMARY, "[]");
            prefs.edit().putString(KEY_BACKUP, previous).putString(KEY_PRIMARY, next.toString()).apply();
            synchronized (PENDING_LOCK) {
                physicalWrites = increment(physicalWrites);
            }
        }
    }

    JSONArray read() {
        JSONArray primary = parse(prefs.getString(KEY_PRIMARY, "[]"));
        if (primary != null) return primary;
        JSONArray backup = parse(prefs.getString(KEY_BACKUP, "[]"));
        return backup == null ? new JSONArray() : backup;
    }

    JSONObject get(String runId) {
        if (runId == null || runId.isEmpty()) return null;
        JSONObject item = find(read(), runId);
        if (item == null) return null;
        try { return new JSONObject(item.toString()); }
        catch (Exception ignored) { return null; }
    }

    private static JSONObject find(JSONArray array, String runId) {
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && runId.equals(item.optString("runId"))) return item;
        }
        return null;
    }

    static boolean sameSnapshot(JSONObject previous, JSONObject next) {
        if (previous == null || next == null) return false;
        String[] strings = {"runId", "mode", "projectUrl", "requirement", "conversationUrl", "phase", "status",
                "role", "pendingModel", "pendingReasoning", "lastSignal", "lastErrorCode", "lastErrorMessage"};
        for (String key : strings) {
            if (!previous.optString(key).equals(next.optString(key))) return false;
        }
        if (previous.optLong("createdAt") != next.optLong("createdAt")) return false;
        if (previous.optInt("turn") != next.optInt("turn")) return false;
        if (previous.optBoolean("active") != next.optBoolean("active")) return false;
        if (previous.optBoolean("paused") != next.optBoolean("paused")) return false;
        if (previous.optBoolean("userStopped") != next.optBoolean("userStopped")) return false;
        return previous.optBoolean("terminal") == next.optBoolean("terminal");
    }

    static boolean isOlderSnapshot(JSONObject previous, JSONObject next) {
        if (previous == null || next == null) return false;
        return previous.optLong("updatedAt", 0L) > next.optLong("updatedAt", 0L);
    }

    private static JSONObject snapshot(SelfRunStore store) {
        JSONObject item = new JSONObject();
        try {
            item.put("runId", store.runId());
            item.put("createdAt", store.createdAt());
            item.put("updatedAt", System.currentTimeMillis());
            item.put("mode", store.mode());
            item.put("projectUrl", store.projectUrl());
            item.put("requirement", bounded(store.requirement(), 4_000));
            item.put("conversationUrl", store.conversationUrl());
            item.put("phase", store.phase());
            item.put("status", bounded(store.status(), 1_000));
            item.put("role", store.role());
            item.put("pendingModel", store.pendingModel());
            item.put("pendingReasoning", store.pendingReasoning());
            item.put("lastSignal", bounded(store.lastSignal(), 1_000));
            item.put("turn", store.turn());
            item.put("active", store.active());
            item.put("paused", store.paused());
            item.put("userStopped", store.userStopped());
            item.put("lastErrorCode", store.lastErrorCode());
            item.put("lastErrorMessage", bounded(store.lastErrorMessage(), 1_000));
            item.put("terminal", SelfRunStore.PHASE_DONE.equals(store.phase()) || store.userStopped());
        } catch (Exception ignored) {
        }
        return item;
    }

    private static JSONArray parse(String value) {
        try { return new JSONArray(value == null ? "[]" : value); }
        catch (Exception ignored) { return null; }
    }

    private static String bounded(String value, int max) {
        String safe = value == null ? "" : value;
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private static long increment(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }
}
