package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.Locale;

/** Durable current-run Chat bootstrap and continuation profiles backed by ProfileRegistry. */
final class ChatReasoningPreferenceStore {
    static final String KEEP = "keep";
    static final String INSTANT = "instant";
    static final String MEDIUM = "medium";
    static final String HIGH = "high";
    static final String EXTRA_HIGH = "xhigh";

    private static final String PREFS = "selfrun_drive_chat_reasoning";
    private static final String KEY_RUN_ID = "runId";
    private static final String KEY_SELECTION = "selection"; // legacy single-profile key
    private static final String KEY_BOOTSTRAP_SELECTION = "bootstrapSelection";
    private static final String KEY_CONTINUATION_SELECTION = "continuationSelection";
    private static volatile SharedPreferences preferences;
    private static volatile Context appContext;

    private ChatReasoningPreferenceStore() {}

    static void initialize(Context context) {
        if (context == null || (preferences != null && appContext != null)) return;
        Context application = context.getApplicationContext();
        if (application == null) application = context;
        ProfileRegistry.initialize(application);
        synchronized (ChatReasoningPreferenceStore.class) {
            if (appContext == null) appContext = application;
            if (preferences == null) preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    static boolean save(Context context, String runId, String selection) {
        return save(context, runId, selection, selection);
    }

    static boolean save(Context context, String runId,
                        String bootstrapSelection, String continuationSelection) {
        initialize(context);
        SharedPreferences current = preferences;
        if (current == null || runId == null || runId.isEmpty()) return false;
        String bootstrap = normalize(bootstrapSelection);
        String continuation = normalize(continuationSelection);
        if (!validSelection(bootstrap) || !validSelection(continuation)) return false;
        if (!BootstrapRunStateStore.startRun(context, runId, bootstrap)) return false;
        return current.edit()
                .putString(KEY_RUN_ID, runId)
                .putString(KEY_SELECTION, bootstrap)
                .putString(KEY_BOOTSTRAP_SELECTION, bootstrap)
                .putString(KEY_CONTINUATION_SELECTION, continuation)
                .commit();
    }

    static String selectionForRun(Context context, String runId) {
        if (context == null || runId == null || runId.isEmpty()) return KEEP;
        initialize(context);
        SharedPreferences current = preferences;
        if (current != null && runId.equals(current.getString(KEY_RUN_ID, ""))) {
            String legacy = current.getString(KEY_SELECTION, KEEP);
            String stored = normalize(current.getString(KEY_BOOTSTRAP_SELECTION, legacy));
            if (validSelection(stored)) return stored;
        }
        Context application = context.getApplicationContext();
        if (application == null) application = context;
        String durable = normalize(BootstrapRunStateStore.requested(application, runId));
        return validSelection(durable) ? durable : KEEP;
    }

    static String selectionForRun(String runId) {
        Context context = appContext;
        return context == null ? KEEP : selectionForRun(context, runId);
    }

    static String continuationSelectionForRun(Context context, String runId) {
        if (context == null || runId == null || runId.isEmpty()) return KEEP;
        initialize(context);
        SharedPreferences current = preferences;
        if (current != null && runId.equals(current.getString(KEY_RUN_ID, ""))) {
            String bootstrap = current.getString(KEY_BOOTSTRAP_SELECTION,
                    current.getString(KEY_SELECTION, KEEP));
            String stored = normalize(current.getString(KEY_CONTINUATION_SELECTION, bootstrap));
            if (validSelection(stored)) return stored;
        }
        return selectionForRun(context, runId);
    }

    static String continuationSelectionForRun(String runId) {
        Context context = appContext;
        return context == null ? KEEP : continuationSelectionForRun(context, runId);
    }

    static String summary(Context context, String runId, String phase, String lastErrorCode) {
        initialize(context);
        String bootstrap = selectionForRun(context, runId);
        String continuation = continuationSelectionForRun(context, runId);
        String state = BootstrapRunStateStore.summary(context, runId);
        if (bootstrap.equals(continuation)) return state;
        return "첫 PLAN: " + label(bootstrap) + " / 이후: " + label(continuation) + " · " + state;
    }

    static boolean shouldApply(String selection) {
        String normalized = normalize(selection);
        return !KEEP.equals(normalized) && ProfileRegistry.resolveChat(normalized) != null;
    }

    static int ordinal(String selection) {
        String normalized = normalize(selection);
        List<ProfileRegistry.Profile> profiles = ProfileRegistry.listChat();
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).signalReasoning.equals(normalized)) return i;
        }
        return -1;
    }

    static String label(String selection) {
        String normalized = normalize(selection);
        ProfileRegistry.Profile profile = ProfileRegistry.resolveChat(normalized);
        return profile == null ? (KEEP.equals(normalized) ? "현재 Chat 설정 유지" : "지원하지 않는 프로필")
                : profile.displayLabel();
    }

    static String normalize(String selection) {
        if (selection == null) return KEEP;
        String normalized = selection.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? KEEP : normalized;
    }

    private static boolean validSelection(String selection) {
        String normalized = normalize(selection);
        return KEEP.equals(normalized) || ProfileRegistry.resolveChat(normalized) != null;
    }
}
