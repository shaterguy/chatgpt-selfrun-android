package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Durable per-run bootstrap deadline and Chat reasoning evidence. */
final class BootstrapRunStateStore {
    static final long BOOTSTRAP_TIMEOUT_MS = 60_000L;
    static final String BOOTSTRAP_PENDING = "PENDING";
    static final String BOOTSTRAP_RUNNING = "RUNNING";
    static final String BOOTSTRAP_COMPLETED = "COMPLETED";
    static final String BOOTSTRAP_FAILED = "FAILED";
    static final String REASONING_KEEP = "KEEP";
    static final String REASONING_PENDING = "PENDING";
    static final String REASONING_APPLIED = "APPLIED";
    static final String REASONING_FAILED = "FAILED";

    private static final String PREFS = "selfrun_drive_bootstrap_runs";
    private static final String KEY_INDEX = "runIds";
    private static final String KEY_PREFIX = "run:";
    private static final int MAX_RUNS = 120;

    private BootstrapRunStateStore() {}

    static final class Window {
        final long startedAt;
        final long deadlineAt;
        final int attempts;
        final String status;
        final boolean persisted;

        Window(long startedAt, long deadlineAt, int attempts, String status, boolean persisted) {
  this.startedAt = Math.max(0L, startedAt);
  this.deadlineAt = Math.max(0L, deadlineAt);
  this.attempts = Math.max(0, attempts);
  this.status = safe(status, 32);
  this.persisted = persisted;
        }

        boolean expired(long now) { return deadlineAt > 0L && now >= deadlineAt; }
        long elapsedMs(long now) { return startedAt > 0L && now >= startedAt ? now - startedAt : 0L; }
    }

    static final class Snapshot {
        final boolean exists;
        final String requested;
        final String reasoningStatus;
        final String verified;
        final String reasoningFailureCode;
        final long reasoningUpdatedAt;
        final String bootstrapStatus;
        final long bootstrapStartedAt;
        final long bootstrapDeadlineAt;
        final int bootstrapAttempts;
        final String bootstrapLastStatus;
        final String bootstrapFailureCode;

        Snapshot(JSONObject state) {
  exists = state != null && state.length() > 0;
  requested = state == null ? "" : state.optString("requested", "");
  reasoningStatus = state == null ? "" : state.optString("reasoningStatus", "");
  verified = state == null ? "" : state.optString("verified", "");
  reasoningFailureCode = state == null ? "" : state.optString("reasoningFailureCode", "");
  reasoningUpdatedAt = state == null ? 0L : state.optLong("reasoningUpdatedAt", 0L);
  bootstrapStatus = state == null ? "" : state.optString("bootstrapStatus", "");
  bootstrapStartedAt = state == null ? 0L : state.optLong("bootstrapStartedAt", 0L);
  bootstrapDeadlineAt = state == null ? 0L : state.optLong("bootstrapDeadlineAt", 0L);
  bootstrapAttempts = state == null ? 0 : state.optInt("bootstrapAttempts", 0);
  bootstrapLastStatus = state == null ? "" : state.optString("bootstrapLastStatus", "");
  bootstrapFailureCode = state == null ? "" : state.optString("bootstrapFailureCode", "");
        }
    }

    static synchronized boolean startRun(Context context, String runId, String selection) {
        if (context == null || runId == null || runId.isEmpty()) return false;
        String requested = ChatReasoningPreferenceStore.normalize(selection);
        JSONObject state = new JSONObject();
        try {
  state.put("runId", runId);
  state.put("requested", requested);
  state.put("reasoningStatus", ChatReasoningPreferenceStore.KEEP.equals(requested)
          ? REASONING_KEEP : REASONING_PENDING);
  state.put("verified", "");
  state.put("reasoningFailureCode", "");
  state.put("reasoningUpdatedAt", System.currentTimeMillis());
  state.put("bootstrapStatus", BOOTSTRAP_PENDING);
  state.put("bootstrapStartedAt", 0L);
  state.put("bootstrapDeadlineAt", 0L);
  state.put("bootstrapAttempts", 0);
  state.put("bootstrapLastStatus", "");
  state.put("bootstrapLastDetail", "");
  state.put("bootstrapFailureCode", "");
  state.put("updatedAt", System.currentTimeMillis());
        } catch (Throwable error) { return false; }
        return write(context, runId, state);
    }

    static synchronized Window touchBootstrap(Context context, String runId, String selection, long now) {
        long current = now > 0L ? now : System.currentTimeMillis();
        JSONObject state = ensureState(context, runId, selection);
        if (state == null) return new Window(0L, 0L, 0, BOOTSTRAP_FAILED, false);
        try {
  long startedAt = state.optLong("bootstrapStartedAt", 0L);
  if (startedAt <= 0L) {
      startedAt = current;
      state.put("bootstrapStartedAt", startedAt);
      state.put("bootstrapDeadlineAt", safeAdd(startedAt, BOOTSTRAP_TIMEOUT_MS));
  }
  int attempts = state.optInt("bootstrapAttempts", 0);
  attempts = attempts == Integer.MAX_VALUE ? attempts : attempts + 1;
  state.put("bootstrapAttempts", attempts);
  state.put("bootstrapStatus", BOOTSTRAP_RUNNING);
  state.put("updatedAt", current);
  boolean persisted = write(context, runId, state);
  return window(state, persisted);
        } catch (Throwable error) {
  return new Window(0L, 0L, 0, BOOTSTRAP_FAILED, false);
        }
    }

    static synchronized Window recordBootstrapResult(Context context, String runId,
                                            String status, String detail, long now) {
        JSONObject state = read(context, runId);
        if (state == null) return new Window(0L, 0L, 0, BOOTSTRAP_FAILED, false);
        try {
  state.put("bootstrapLastStatus", safe(status, 80));
  state.put("bootstrapLastDetail", safe(detail, 240));
  state.put("updatedAt", now > 0L ? now : System.currentTimeMillis());
  boolean persisted = write(context, runId, state);
  return window(state, persisted);
        } catch (Throwable error) {
  return window(state, false);
        }
    }

    static synchronized boolean markReasoningApplied(Context context, String runId, String observed) {
        JSONObject state = read(context, runId);
        if (state == null) return false;
        String requested = ChatReasoningPreferenceStore.normalize(state.optString("requested", ""));
        String verified = ChatReasoningPreferenceStore.normalize(observed);
        if (ChatReasoningPreferenceStore.KEEP.equals(requested) || !requested.equals(verified)) return false;
        try {
  state.put("reasoningStatus", REASONING_APPLIED);
  state.put("verified", verified);
  state.put("reasoningFailureCode", "");
  state.put("reasoningUpdatedAt", System.currentTimeMillis());
  state.put("updatedAt", System.currentTimeMillis());
        } catch (Throwable error) { return false; }
        return write(context, runId, state);
    }

    static synchronized boolean markBootstrapCompleted(Context context, String runId, String status) {
        JSONObject state = read(context, runId);
        if (state == null) return false;
        try {
  state.put("bootstrapStatus", BOOTSTRAP_COMPLETED);
  state.put("bootstrapLastStatus", safe(status, 80));
  state.put("bootstrapFailureCode", "");
  state.put("updatedAt", System.currentTimeMillis());
        } catch (Throwable error) { return false; }
        return write(context, runId, state);
    }

    static synchronized boolean markBootstrapFailed(Context context, String runId,
                                           String code, String detail) {
        JSONObject state = read(context, runId);
        if (state == null) state = ensureState(context, runId, ChatReasoningPreferenceStore.KEEP);
        if (state == null) return false;
        try {
  state.put("bootstrapStatus", BOOTSTRAP_FAILED);
  state.put("bootstrapFailureCode", safe(code, 80));
  state.put("bootstrapLastStatus", safe(code, 80));
  state.put("bootstrapLastDetail", safe(detail, 240));
  String requested = ChatReasoningPreferenceStore.normalize(state.optString("requested", ""));
  if (!ChatReasoningPreferenceStore.KEEP.equals(requested)
          && !REASONING_APPLIED.equals(state.optString("reasoningStatus", ""))) {
      state.put("reasoningStatus", REASONING_FAILED);
      state.put("reasoningFailureCode", safe(code, 80));
      state.put("reasoningUpdatedAt", System.currentTimeMillis());
  }
  state.put("updatedAt", System.currentTimeMillis());
        } catch (Throwable error) { return false; }
        return write(context, runId, state);
    }

    static synchronized String requested(Context context, String runId) {
        JSONObject state = read(context, runId);
        return state == null ? "" : ChatReasoningPreferenceStore.normalize(
      state.optString("requested", ChatReasoningPreferenceStore.KEEP));
    }

    static synchronized Snapshot snapshot(Context context, String runId) {
        return new Snapshot(read(context, runId));
    }

    static void appendHistory(Context context, String runId, JSONObject item) {
        if (item == null) return;
        Snapshot value = snapshot(context, runId);
        if (!value.exists) return;
        try {
  item.put("chatReasoningRequested", value.requested);
  item.put("chatReasoningStatus", value.reasoningStatus);
  item.put("chatReasoningVerified", value.verified);
  item.put("chatReasoningFailureCode", value.reasoningFailureCode);
  item.put("chatReasoningUpdatedAt", value.reasoningUpdatedAt);
  item.put("bootstrapStatus", value.bootstrapStatus);
  item.put("bootstrapStartedAt", value.bootstrapStartedAt);
  item.put("bootstrapDeadlineAt", value.bootstrapDeadlineAt);
  item.put("bootstrapAttempts", value.bootstrapAttempts);
  item.put("bootstrapLastStatus", value.bootstrapLastStatus);
  item.put("bootstrapFailureCode", value.bootstrapFailureCode);
        } catch (Throwable ignored) {}
    }

    static String summary(Context context, String runId) {
        Snapshot value = snapshot(context, runId);
        if (!value.exists) return "요청값 기록 없음";
        return summary(value.requested, value.reasoningStatus, value.verified,
      value.reasoningFailureCode);
    }

    static String summary(JSONObject historyItem) {
        if (historyItem == null || !historyItem.has("chatReasoningRequested")) return "요청값 기록 없음";
        return summary(historyItem.optString("chatReasoningRequested", ""),
      historyItem.optString("chatReasoningStatus", ""),
      historyItem.optString("chatReasoningVerified", ""),
      historyItem.optString("chatReasoningFailureCode", ""));
    }

    private static String summary(String requestedValue, String status, String verifiedValue, String failureCode) {
        String requested = ChatReasoningPreferenceStore.normalize(requestedValue);
        if (ChatReasoningPreferenceStore.KEEP.equals(requested)) return "현재 Chat 설정 유지";
        String requestedLabel = ChatReasoningPreferenceStore.label(requested);
        if (REASONING_APPLIED.equals(status)) {
  String verified = ChatReasoningPreferenceStore.normalize(verifiedValue);
  String verifiedLabel = ChatReasoningPreferenceStore.KEEP.equals(verified)
          ? "-" : ChatReasoningPreferenceStore.label(verified);
  return "요청: " + requestedLabel + " / 적용: 확인 완료 / 확인: " + verifiedLabel;
        }
        if (REASONING_FAILED.equals(status)) {
  return "요청: " + requestedLabel + " / 적용: 실패 / 확인: -"
          + (failureCode == null || failureCode.isEmpty() ? "" : " / 오류: " + failureCode);
        }
        return "요청: " + requestedLabel + " / 적용: 진행 중 / 확인: -";
    }

    private static JSONObject ensureState(Context context, String runId, String selection) {
        if (context == null || runId == null || runId.isEmpty()) return null;
        JSONObject current = read(context, runId);
        if (current != null) return current;
        String requested = ChatReasoningPreferenceStore.normalize(selection);
        JSONObject state = new JSONObject();
        try {
  state.put("runId", runId);
  state.put("requested", requested);
  state.put("reasoningStatus", ChatReasoningPreferenceStore.KEEP.equals(requested)
          ? REASONING_KEEP : REASONING_PENDING);
  state.put("verified", "");
  state.put("reasoningFailureCode", "");
  state.put("reasoningUpdatedAt", System.currentTimeMillis());
  state.put("bootstrapStatus", BOOTSTRAP_PENDING);
  state.put("bootstrapStartedAt", 0L);
  state.put("bootstrapDeadlineAt", 0L);
  state.put("bootstrapAttempts", 0);
  state.put("bootstrapLastStatus", "");
  state.put("bootstrapLastDetail", "");
  state.put("bootstrapFailureCode", "");
  state.put("updatedAt", System.currentTimeMillis());
        } catch (Throwable error) { return null; }
        return state;
    }

    private static Window window(JSONObject state, boolean persisted) {
        if (state == null) return new Window(0L, 0L, 0, BOOTSTRAP_FAILED, false);
        return new Window(state.optLong("bootstrapStartedAt", 0L),
      state.optLong("bootstrapDeadlineAt", 0L),
      state.optInt("bootstrapAttempts", 0),
      state.optString("bootstrapStatus", ""), persisted);
    }

    private static JSONObject read(Context context, String runId) {
        if (context == null || runId == null || runId.isEmpty()) return null;
        try {
  String raw = prefs(context).getString(key(runId), "");
  return raw == null || raw.isEmpty() ? null : new JSONObject(raw);
        } catch (Throwable error) { return null; }
    }

    private static boolean write(Context context, String runId, JSONObject state) {
        if (context == null || runId == null || runId.isEmpty() || state == null) return false;
        SharedPreferences preferences = prefs(context);
        SharedPreferences.Editor editor = preferences.edit();
        List<String> ids = readIndex(preferences);
        ids.remove(runId);
        ids.add(0, runId);
        while (ids.size() > MAX_RUNS) editor.remove(key(ids.remove(ids.size() - 1)));
        JSONArray index = new JSONArray();
        for (String id : ids) index.put(id);
        return editor.putString(key(runId), state.toString())
      .putString(KEY_INDEX, index.toString()).commit();
    }

    private static List<String> readIndex(SharedPreferences preferences) {
        ArrayList<String> result = new ArrayList<>();
        try {
  JSONArray array = new JSONArray(preferences.getString(KEY_INDEX, "[]"));
  for (int index = 0; index < array.length(); index++) {
      String id = array.optString(index, "");
      if (!id.isEmpty() && !result.contains(id)) result.add(id);
  }
        } catch (Throwable ignored) {}
        return result;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String runId) { return KEY_PREFIX + runId; }
    private static long safeAdd(long value, long delta) {
        return value > Long.MAX_VALUE - delta ? Long.MAX_VALUE : value + delta;
    }
    private static String safe(String value, int max) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
