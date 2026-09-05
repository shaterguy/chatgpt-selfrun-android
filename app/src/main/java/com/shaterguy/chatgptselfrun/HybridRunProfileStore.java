package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/** Durable two-stage request profile selection for MODE=HYBRID. */
final class HybridRunProfileStore {
    static final String MODE_HYBRID = "HYBRID";
    static final String STAGE_BOOTSTRAP = "BOOTSTRAP";
    static final String STAGE_CONTINUATION = "CONTINUATION";

    private static final String PREFS = "selfrun_drive_hybrid_profiles";
    private static final String KEY_DEFAULTS = "defaults";
    private static final String KEY_CURRENT_RUN = "currentRunId";
    private static final String RUN_PREFIX = "run:";
    private static final String RUN_STATE_PREFS = "selfrun_drive";

    private static SharedPreferences preferences;
    private static SharedPreferences runState;
    private static SharedPreferences.OnSharedPreferenceChangeListener runStateListener;

    static final class Endpoint {
        final String mode;
        final String model;
        final String reasoning;

        Endpoint(String mode, String model, String reasoning) {
            this.mode = normalizeMode(mode);
            this.model = lower(model);
            this.reasoning = lower(reasoning);
        }

        static Endpoint chat(String reasoning) { return new Endpoint(SelfRunStore.MODE_CHAT, "", reasoning); }
        static Endpoint work(String model, String reasoning) { return new Endpoint(SelfRunStore.MODE_WORK, model, reasoning); }

        static Endpoint fromProfile(ProfileRegistry.Profile profile) {
            if (profile == null) return new Endpoint("", "", "");
            return profile.mode == ProfileRegistry.Mode.WORK
                    ? work(profile.signalModel, profile.signalReasoning)
                    : chat(profile.signalReasoning);
        }

        boolean isChat() { return SelfRunStore.MODE_CHAT.equals(mode); }
        boolean isWork() { return SelfRunStore.MODE_WORK.equals(mode); }

        boolean valid() {
            if (isChat()) return model.isEmpty() && ProfileRegistry.resolveChat(reasoning) != null;
            return isWork() && ProfileRegistry.resolveWork(model, reasoning) != null;
        }

        String key() { return mode + "|" + model + "|" + reasoning; }

        String displayLabel() {
            if (isChat()) {
                ProfileRegistry.Profile profile = ProfileRegistry.resolveChat(reasoning);
                return profile == null ? "Chat · 지원하지 않는 프로필"
                        : "Chat · " + profile.displayLabel() + " · " + profile.actualCombination();
            }
            ProfileRegistry.Profile profile = ProfileRegistry.resolveWork(model, reasoning);
            String modelLabel = model.isEmpty() ? "Work"
                    : model.substring(0, 1).toUpperCase(Locale.ROOT) + model.substring(1);
            return profile == null ? modelLabel + " · 지원하지 않는 프로필"
                    : modelLabel + " · " + reasoning + " · " + profile.actualCombination();
        }

        JSONObject toJson() {
            JSONObject out = new JSONObject();
            try {
                out.put("mode", mode);
                out.put("model", model);
                out.put("reasoning", reasoning);
            } catch (Exception error) {
                throw new IllegalStateException("hybrid endpoint serialization failed", error);
            }
            return out;
        }

        static Endpoint fromJson(JSONObject value) {
            if (value == null) return new Endpoint("", "", "");
            return new Endpoint(value.optString("mode"), value.optString("model"), value.optString("reasoning"));
        }

        static Endpoint fromKey(String key) {
            String[] parts = (key == null ? "" : key).split("\\|", -1);
            return parts.length == 3 ? new Endpoint(parts[0], parts[1], parts[2]) : new Endpoint("", "", "");
        }
    }

    static final class Selection {
        final String runId;
        final String stage;
        final Endpoint bootstrap;
        final Endpoint continuation;

        Selection(String runId, String stage, Endpoint bootstrap, Endpoint continuation) {
            this.runId = safe(runId);
            this.stage = STAGE_CONTINUATION.equals(stage) ? STAGE_CONTINUATION : STAGE_BOOTSTRAP;
            this.bootstrap = bootstrap == null ? new Endpoint("", "", "") : bootstrap;
            this.continuation = continuation == null ? new Endpoint("", "", "") : continuation;
        }

        boolean valid() { return SelfRunProtocolRules.validRunId(runId) && bootstrap.valid() && continuation.valid(); }
        boolean validDefaults() { return bootstrap.valid() && continuation.valid(); }
        boolean continuationStage() { return STAGE_CONTINUATION.equals(stage); }
        Endpoint effectiveBootstrap() { return continuationStage() ? continuation : bootstrap; }
        Selection forRun(String nextRunId) { return new Selection(nextRunId, stage, bootstrap, continuation); }
        Selection withStage(String nextStage) { return new Selection(runId, nextStage, bootstrap, continuation); }

        JSONObject toJson(boolean includeRun) {
            JSONObject out = new JSONObject();
            try {
                if (includeRun) out.put("runId", runId);
                out.put("stage", stage);
                out.put("bootstrap", bootstrap.toJson());
                out.put("continuation", continuation.toJson());
            } catch (Exception error) {
                throw new IllegalStateException("hybrid selection serialization failed", error);
            }
            return out;
        }

        static Selection fromJson(String raw, String fallbackRunId) {
            try {
                JSONObject root = new JSONObject(raw == null ? "" : raw);
                String run = root.optString("runId", fallbackRunId == null ? "" : fallbackRunId);
                return new Selection(run, root.optString("stage", STAGE_BOOTSTRAP),
                        Endpoint.fromJson(root.optJSONObject("bootstrap")),
                        Endpoint.fromJson(root.optJSONObject("continuation")));
            } catch (Exception error) {
                return empty(fallbackRunId);
            }
        }
    }

    private HybridRunProfileStore() {}

    static void initialize(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        ProfileRegistry.initialize(app);
        synchronized (HybridRunProfileStore.class) {
            if (preferences == null) preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (runState == null) runState = app.getSharedPreferences(RUN_STATE_PREFS, Context.MODE_PRIVATE);
            if (runStateListener == null) {
                runStateListener = (sharedPreferences, key) -> {
                    if ("runId".equals(key) || "mode".equals(key) || "phase".equals(key)
                            || "pendingDriveSignalType".equals(key)) reconcileRunState();
                };
                runState.registerOnSharedPreferenceChangeListener(runStateListener);
            }
            reconcileRunStateLocked();
        }
    }

    static Selection loadDefaults(Context context) {
        initialize(context);
        synchronized (HybridRunProfileStore.class) {
            Selection stored = Selection.fromJson(preferences.getString(KEY_DEFAULTS, ""), "");
            return stored.validDefaults() ? stored : defaultSelection(context);
        }
    }

    static boolean startRun(Context context, String runId, Endpoint bootstrap, Endpoint continuation) {
        initialize(context);
        Selection selection = new Selection(runId, STAGE_BOOTSTRAP, bootstrap, continuation);
        if (!selection.valid()) return false;
        synchronized (HybridRunProfileStore.class) {
            Selection defaults = new Selection("", STAGE_BOOTSTRAP, bootstrap, continuation);
            return preferences.edit()
                    .putString(KEY_DEFAULTS, defaults.toJson(false).toString())
                    .putString(KEY_CURRENT_RUN, runId)
                    .putString(RUN_PREFIX + runId, selection.toJson(true).toString())
                    .commit();
        }
    }

    static Selection selectionForRun(String runId) {
        synchronized (HybridRunProfileStore.class) {
            return preferences == null ? empty(runId) : selectionForRunLocked(runId);
        }
    }

    /** Returns a selection only when the currently active SelfRun is actually MODE=HYBRID. */
    static Selection currentSelection() {
        synchronized (HybridRunProfileStore.class) {
            if (preferences == null || runState == null
                    || !MODE_HYBRID.equals(runState.getString("mode", ""))) return empty("");
            reconcileRunStateLocked();
            String runId = runState.getString("runId", "");
            return selectionForRunLocked(runId);
        }
    }

    static boolean isContinuationWork(String runId) {
        Selection selection = selectionForRun(runId);
        return selection.valid() && selection.continuationStage() && selection.continuation.isWork();
    }

    static boolean isBootstrapStage(String runId) {
        Selection selection = selectionForRun(runId);
        return selection.valid() && !selection.continuationStage();
    }

    static boolean matchesContinuationWork(String runId, String model, String reasoning) {
        Selection selection = selectionForRun(runId);
        return selection.valid() && selection.continuationStage() && selection.continuation.isWork()
                && selection.continuation.model.equals(lower(model))
                && selection.continuation.reasoning.equals(lower(reasoning));
    }

    static String metadata(String runId) {
        Selection selection = selectionForRun(runId);
        if (!selection.valid()) throw new IllegalStateException("valid HYBRID run profile required");
        Endpoint continuation = selection.continuation;
        return "SELF_RUN_HYBRID_STAGE=" + selection.stage + "\n"
                + "SELF_RUN_HYBRID_BOOTSTRAP_MODE=" + selection.bootstrap.mode + "\n"
                + "SELF_RUN_HYBRID_CONTINUATION_MODE=" + continuation.mode + "\n"
                + "SELF_RUN_HYBRID_CONTINUATION_MODEL=" + (continuation.isWork() ? continuation.model : "NONE") + "\n"
                + "SELF_RUN_HYBRID_CONTINUATION_REASONING=" + (continuation.isWork() ? continuation.reasoning : "NONE") + "\n";
    }

    private static void reconcileRunState() {
        synchronized (HybridRunProfileStore.class) { reconcileRunStateLocked(); }
    }

    private static void reconcileRunStateLocked() {
        if (preferences == null || runState == null
                || !MODE_HYBRID.equals(runState.getString("mode", ""))) return;
        String runId = runState.getString("runId", "");
        if (!SelfRunProtocolRules.validRunId(runId)) return;
        Selection selection = selectionForRunLocked(runId);
        if (!selection.valid()) {
            String priorRun = preferences.getString(KEY_CURRENT_RUN, "");
            Selection prior = selectionForRunLocked(priorRun);
            if (!prior.valid()) return;
            selection = prior.forRun(runId);
            if (!preferences.edit().putString(RUN_PREFIX + runId, selection.toJson(true).toString())
                    .putString(KEY_CURRENT_RUN, runId).commit()) return;
        } else if (!runId.equals(preferences.getString(KEY_CURRENT_RUN, ""))) {
            if (!preferences.edit().putString(KEY_CURRENT_RUN, runId).commit()) return;
        }
        if (!selection.continuationStage()
                && SelfRunStore.PHASE_SEND_CONTINUE.equals(runState.getString("phase", ""))) {
            Selection continued = selection.withStage(STAGE_CONTINUATION);
            preferences.edit().putString(RUN_PREFIX + runId, continued.toJson(true).toString()).commit();
        }
    }

    private static Selection selectionForRunLocked(String runId) {
        if (preferences == null || !SelfRunProtocolRules.validRunId(runId)) return empty(runId);
        Selection selection = Selection.fromJson(preferences.getString(RUN_PREFIX + runId, ""), runId);
        return selection.valid() ? selection : empty(runId);
    }

    private static Selection defaultSelection(Context context) {
        Endpoint chat = preferredChat();
        Endpoint work = preferredWork(context);
        if (work.valid() && chat.valid()) return new Selection("", STAGE_BOOTSTRAP, work, chat);
        if (chat.valid()) return new Selection("", STAGE_BOOTSTRAP, chat, chat);
        if (work.valid()) return new Selection("", STAGE_BOOTSTRAP, work, work);
        return empty("");
    }

    private static Endpoint preferredChat() {
        ProfileRegistry.Profile preferred = ProfileRegistry.resolveChat(ChatReasoningPreferenceStore.EXTRA_HIGH);
        if (preferred != null) return Endpoint.fromProfile(preferred);
        List<ProfileRegistry.Profile> profiles = ProfileRegistry.listChat();
        return profiles.isEmpty() ? new Endpoint("", "", "") : Endpoint.fromProfile(profiles.get(0));
    }

    private static Endpoint preferredWork(Context context) {
        WorkBootstrapPreferenceStore.Selection preferred = WorkBootstrapPreferenceStore.load(context);
        ProfileRegistry.Profile profile = ProfileRegistry.resolveWork(preferred.model, preferred.reasoning);
        if (profile != null) return Endpoint.fromProfile(profile);
        List<ProfileRegistry.Profile> profiles = ProfileRegistry.listWork();
        return profiles.isEmpty() ? new Endpoint("", "", "") : Endpoint.fromProfile(profiles.get(0));
    }

    private static Selection empty(String runId) {
        return new Selection(runId, STAGE_BOOTSTRAP,
                new Endpoint("", "", ""), new Endpoint("", "", ""));
    }

    private static String normalizeMode(String value) {
        String mode = safe(value).trim().toUpperCase(Locale.ROOT);
        return SelfRunStore.MODE_CHAT.equals(mode) || SelfRunStore.MODE_WORK.equals(mode) ? mode : "";
    }

    private static String lower(String value) { return safe(value).trim().toLowerCase(Locale.ROOT); }
    private static String safe(String value) { return value == null ? "" : value; }
}
