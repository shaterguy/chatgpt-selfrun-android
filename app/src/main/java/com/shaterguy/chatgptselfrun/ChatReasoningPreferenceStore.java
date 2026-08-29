package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.Locale;

/** Durable current-run Chat protocol selection backed by the dynamic Profile Registry. */
final class ChatReasoningPreferenceStore {
    static final String KEEP = "keep";
    static final String INSTANT = "instant";
    static final String MEDIUM = "medium";
    static final String HIGH = "high";
    static final String EXTRA_HIGH = "xhigh";

    private static final String PREFS = "selfrun_drive_chat_reasoning";
    private static final String KEY_RUN_ID = "runId";
    private static final String KEY_SELECTION = "selection";
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
        initialize(context);
        SharedPreferences current = preferences;
        if (current == null || runId == null || runId.isEmpty()) return false;
        String normalized = normalize(selection);
        if (!KEEP.equals(normalized) && ProfileRegistry.resolveChat(normalized) == null) return false;
        if (!BootstrapRunStateStore.startRun(context, runId, normalized)) return false;
        return current.edit().putString(KEY_RUN_ID, runId).putString(KEY_SELECTION, normalized).commit();
    }

    static String selectionForRun(Context context, String runId) {
        if (context == null || runId == null || runId.isEmpty()) return KEEP;
        Context application = context.getApplicationContext();
        if (application == null) application = context;
        String durable = BootstrapRunStateStore.requested(application, runId);
        if (!durable.isEmpty()) return normalize(durable);
        initialize(application);
        SharedPreferences current = preferences;
        if (current == null || !runId.equals(current.getString(KEY_RUN_ID, ""))) return KEEP;
        return normalize(current.getString(KEY_SELECTION, KEEP));
    }

    static String selectionForRun(String runId) {
        Context context = appContext;
        return context == null ? KEEP : selectionForRun(context, runId);
    }

    static String summary(Context context, String runId, String phase, String lastErrorCode) {
        initialize(context);
        return BootstrapRunStateStore.summary(context, runId);
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
        ProfileRegistry.Profile profile = ProfileRegistry.resolveChat(normalize(selection));
        return profile == null ? (KEEP.equals(normalize(selection)) ? "현재 Chat 설정 유지" : "지원하지 않는 프로필")
                : profile.displayLabel();
    }

    static String normalize(String selection) {
        if (selection == null) return KEEP;
        String normalized = selection.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? KEEP : normalized;
    }
}
