package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Canonical CHAT/WORK request-profile registry.
 *
 * Active profiles come only from the latest accepted Drive canonical snapshots.
 * The app never synthesizes or merges a fixed candidate list. Accepted snapshots
 * are persisted as last-known-good (LKG) data and survive refresh failures.
 */
final class ProfileRegistry {
    static final String SCHEMA = "selfrun-profile-registry-v1";
    static final int SCHEMA_VERSION = 1;
    static final String CHAT_EXPORT_SCHEMA = "selfrun-chat-profile-registry-v1";
    static final String WORK_EXPORT_SCHEMA = "selfrun-work-profile-registry-v1";
    static final int MAX_IMPORT_JSON_CHARS = 1_048_576;
    static final int MAX_IMPORT_PROFILES = 128;
    static final List<String> CONTROL_PATH_ORDER = List.of(
            "model", "thinking_effort", "conversation_origin", "service_tier");
    static final Set<String> CONTROL_PATHS = Collections.unmodifiableSet(
            new LinkedHashSet<>(CONTROL_PATH_ORDER));

    private static final String PREFS = "selfrun_drive_profile_registry";
    private static final String KEY_CHAT_RAW = "chat_raw";
    private static final String KEY_CHAT_VERSION = "chat_version";
    private static final String KEY_CHAT_MODIFIED = "chat_modified";
    private static final String KEY_WORK_RAW = "work_raw";
    private static final String KEY_WORK_VERSION = "work_version";
    private static final String KEY_WORK_MODIFIED = "work_modified";
    private static final Pattern SIGNAL_TOKEN = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,79}");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> RESERVED_TOKENS = Set.of(
            "body", "none", "null", "true", "false", "keep", "chat", "work",
            "model", "reasoning", "recovery_id", "next_input_b64url", "self_run_turn_completed");
    private static final Set<String> ROOT_KEYS = Set.of(
            "schema", "registrySchemaVersion", "appVersion", "profiles");
    private static final Set<String> PROFILE_KEYS = Set.of(
            "signal", "request", "operations", "fingerprint", "builtIn");
    private static final Set<String> CHAT_SIGNAL_KEYS = Set.of("reasoning");
    private static final Set<String> WORK_SIGNAL_KEYS = Set.of("model", "reasoning");

    enum Mode { CHAT, WORK }
    enum OperationKind { SET, REMOVE }

    static final class Operation {
        final OperationKind kind;
        final String path;
        final String value;

        private Operation(OperationKind kind, String path, String value) {
            if (!CONTROL_PATHS.contains(path)) throw new IllegalArgumentException("non-allowlisted control path: " + path);
            this.kind = Objects.requireNonNull(kind, "kind");
            this.path = path;
            this.value = value;
            if (kind == OperationKind.SET && value == null) throw new IllegalArgumentException("SET value is null");
            if (kind == OperationKind.REMOVE && value != null) throw new IllegalArgumentException("REMOVE value must be null");
        }

        static Operation set(String path, String value) {
            if (value == null || value.length() > 512) throw new IllegalArgumentException("invalid SET value");
            return new Operation(OperationKind.SET, path, value);
        }

        static Operation remove(String path) { return new Operation(OperationKind.REMOVE, path, null); }

        JSONObject toJson() {
            JSONObject out = new JSONObject();
            out.put("op", kind.name());
            out.put("path", path);
            if (kind == OperationKind.SET) out.put("value", value);
            return out;
        }
    }

    static final class Profile {
        final Mode mode;
        final String signalModel;
        final String signalReasoning;
        final List<Operation> operations;
        final String fingerprint;
        final boolean builtIn;

        private Profile(Mode mode, String signalModel, String signalReasoning,
                        List<Operation> operations, boolean builtIn) {
            this.mode = Objects.requireNonNull(mode, "mode");
            this.signalModel = signalModel == null ? "" : signalModel;
            this.signalReasoning = Objects.requireNonNull(signalReasoning, "signalReasoning");
            this.operations = Collections.unmodifiableList(canonicalOperations(operations));
            this.fingerprint = fingerprint(mode, this.operations);
            this.builtIn = builtIn;
            validateProfileShape(this);
        }

        String requestValue(String path) { return ProfileRegistry.requestValue(operations, path); }
        boolean requestHas(String path) { return ProfileRegistry.requestHas(operations, path); }

        String displayLabel() {
            if (mode == Mode.CHAT) return titleSignalToken(signalReasoning);
            return titleSignalToken(signalModel) + " · " + titleSignalToken(signalReasoning);
        }

        String actualCombination() {
            return requestValue("model") + " / "
                    + (requestHas("thinking_effort") ? requestValue("thinking_effort") : "필드 없음");
        }

        JSONObject toRuntimeJson() {
            JSONObject out = new JSONObject();
            out.put("mode", mode.name());
            out.put("signalModel", signalModel);
            out.put("signalReasoning", signalReasoning);
            out.put("fingerprint", fingerprint);
            JSONArray ops = new JSONArray();
            for (Operation operation : operations) ops.put(operation.toJson());
            out.put("operations", ops);
            out.put("builtIn", builtIn);
            return out;
        }
    }

    static final class CapturedProfile {
        final Mode mode;
        final List<Operation> operations;
        final String fingerprint;
        private CapturedProfile(Mode mode, List<Operation> operations) {
            this.mode = mode;
            this.operations = Collections.unmodifiableList(canonicalOperations(operations));
            this.fingerprint = fingerprint(mode, this.operations);
        }
        String requestValue(String path) { return ProfileRegistry.requestValue(operations, path); }
        boolean requestHas(String path) { return ProfileRegistry.requestHas(operations, path); }
    }

    static final class SnapshotInfo {
        final String driveVersion;
        final String modifiedTime;
        final String appVersion;
        final int count;

        SnapshotInfo(String driveVersion, String modifiedTime, String appVersion, int count) {
            this.driveVersion = driveVersion == null ? "" : driveVersion;
            this.modifiedTime = modifiedTime == null ? "" : modifiedTime;
            this.appVersion = appVersion == null ? "" : appVersion;
            this.count = Math.max(0, count);
        }
    }

    private static final class ParsedSnapshot {
        final List<Profile> profiles;
        final String appVersion;
        ParsedSnapshot(List<Profile> profiles, String appVersion) {
            this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
            this.appVersion = appVersion == null ? "" : appVersion;
        }
    }

    private static final class State {
        final List<Profile> profiles;
        final SnapshotInfo chat;
        final SnapshotInfo work;

        State(List<Profile> profiles, SnapshotInfo chat, SnapshotInfo work) {
            this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
            this.chat = chat;
            this.work = work;
        }
    }

    private static volatile SharedPreferences preferences;
    private static volatile State state = new State(List.of(),
            new SnapshotInfo("", "", "", 0), new SnapshotInfo("", "", "", 0));
    private static volatile boolean storageHealthy = true;
    private static volatile String lastRefreshError = "";

    private ProfileRegistry() {}

    static void initialize(Context context) {
        if (context == null) return;
        Context application = context.getApplicationContext();
        if (application == null) application = context;
        synchronized (ProfileRegistry.class) {
            if (preferences != null) return;
            preferences = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            loadLocked();
        }
    }

    static boolean storageHealthy() { return storageHealthy; }
    static String lastRefreshError() { return lastRefreshError; }
    static void markRefreshSuccess() { lastRefreshError = ""; }
    static void markRefreshFailure(Throwable error) {
        String message = error == null ? "registry refresh failed" : String.valueOf(error.getMessage());
        if (message == null || message.trim().isEmpty()) message = error == null ? "registry refresh failed" : error.getClass().getSimpleName();
        lastRefreshError = message;
    }

    static SnapshotInfo snapshotInfo(Mode mode) { return mode == Mode.CHAT ? state.chat : state.work; }
    static List<Profile> listChat() { return list(Mode.CHAT); }
    static List<Profile> listWork() { return list(Mode.WORK); }

    private static List<Profile> list(Mode mode) {
        ArrayList<Profile> out = new ArrayList<>();
        for (Profile profile : state.profiles) if (profile.mode == mode) out.add(profile);
        if (mode == Mode.WORK) out.sort(Comparator.comparing((Profile p) -> p.signalModel).thenComparing(p -> p.signalReasoning));
        return Collections.unmodifiableList(out);
    }

    static Profile resolveChat(String reasoning) {
        String signal = normalizeLookupToken(reasoning);
        if (signal.isEmpty()) return null;
        for (Profile profile : state.profiles) {
            if (profile.mode == Mode.CHAT && profile.signalReasoning.equals(signal)) return profile;
        }
        return null;
    }

    static Profile resolveChat(String model, String reasoning) {
        String requestModel = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        String signal = normalizeLookupToken(reasoning);
        if (requestModel.isEmpty() || signal.isEmpty()) return null;
        for (Profile profile : state.profiles) {
            if (profile.mode == Mode.CHAT
                    && profile.signalReasoning.equals(signal)
                    && profile.requestValue("model").equalsIgnoreCase(requestModel)) return profile;
        }
        return null;
    }

    static Profile resolveWork(String model, String reasoning) {
        String m = normalizeLookupToken(model), r = normalizeLookupToken(reasoning);
        if (m.isEmpty() || r.isEmpty()) return null;
        for (Profile profile : state.profiles) {
            if (profile.mode == Mode.WORK && profile.signalModel.equals(m) && profile.signalReasoning.equals(r)) return profile;
        }
        return null;
    }

    static Profile findByFingerprint(Mode mode, String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) return null;
        for (Profile profile : state.profiles) {
            if (profile.mode == mode && profile.fingerprint.equals(fingerprint)) return profile;
        }
        return null;
    }

    static CapturedProfile parseCaptured(String raw) {
        try {
            JSONObject root = new JSONObject(raw == null ? "" : raw);
            Mode mode = Mode.valueOf(root.getString("mode").toUpperCase(Locale.ROOT));
            return new CapturedProfile(mode, parseOperations(root.getJSONArray("operations")));
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid captured profile", error);
        }
    }

    static synchronized boolean acceptCanonicalSnapshot(Mode mode, String raw,
                                                        String driveVersion, String modifiedTime) {
        Objects.requireNonNull(mode, "mode");
        if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("canonical registry is empty");
        if (raw.length() > MAX_IMPORT_JSON_CHARS) throw new IllegalArgumentException("canonical registry is too large");
        ParsedSnapshot parsed = parseCanonicalSnapshot(mode, raw);
        if (parsed.profiles.isEmpty()) throw new IllegalArgumentException("canonical registry returned zero profiles");

        ArrayList<Profile> combined = new ArrayList<>();
        for (Profile profile : state.profiles) if (profile.mode != mode) combined.add(profile);
        for (Profile profile : parsed.profiles) {
            validateStoredSignalCompatibility(combined, profile);
            combined.add(profile);
        }
        SnapshotInfo info = new SnapshotInfo(driveVersion, modifiedTime, parsed.appVersion, parsed.profiles.size());
        SnapshotInfo chat = mode == Mode.CHAT ? info : state.chat;
        SnapshotInfo work = mode == Mode.WORK ? info : state.work;

        SharedPreferences prefs = preferences;
        if (prefs != null) {
            SharedPreferences.Editor editor = prefs.edit();
            if (mode == Mode.CHAT) {
                editor.putString(KEY_CHAT_RAW, raw);
                editor.putString(KEY_CHAT_VERSION, info.driveVersion);
                editor.putString(KEY_CHAT_MODIFIED, info.modifiedTime);
            } else {
                editor.putString(KEY_WORK_RAW, raw);
                editor.putString(KEY_WORK_VERSION, info.driveVersion);
                editor.putString(KEY_WORK_MODIFIED, info.modifiedTime);
            }
            if (!editor.commit()) throw new IllegalStateException("canonical registry cache commit failed");
        }
        state = new State(combined, chat, work);
        storageHealthy = true;
        lastRefreshError = "";
        return true;
    }

    static String runtimeJson() {
        JSONArray out = new JSONArray();
        for (Profile profile : state.profiles) out.put(profile.toRuntimeJson());
        return out.toString();
    }

    static String exportChatJson(String ignoredAppVersion) { return exportJson(Mode.CHAT); }
    static String exportWorkJson(String ignoredAppVersion) { return exportJson(Mode.WORK); }

    private static String exportJson(Mode mode) {
        JSONObject root = new JSONObject();
        root.put("schema", exportSchema(mode));
        root.put("registrySchemaVersion", SCHEMA_VERSION);
        root.put("appVersion", snapshotInfo(mode).appVersion);
        JSONArray profiles = new JSONArray();
        for (Profile profile : list(mode)) profiles.put(toCanonicalJson(profile));
        root.put("profiles", profiles);
        return root.toString(2);
    }

    private static JSONObject toCanonicalJson(Profile profile) {
        JSONObject item = new JSONObject();
        JSONObject signal = new JSONObject();
        if (profile.mode == Mode.WORK) signal.put("model", profile.signalModel);
        signal.put("reasoning", profile.signalReasoning);
        item.put("signal", signal);
        JSONObject request = new JSONObject();
        JSONArray operations = new JSONArray();
        for (Operation operation : profile.operations) {
            operations.put(operation.toJson());
            if (operation.kind == OperationKind.SET) request.put(operation.path, operation.value);
        }
        item.put("request", request);
        item.put("operations", operations);
        item.put("fingerprint", profile.fingerprint);
        item.put("builtIn", profile.builtIn);
        return item;
    }

    static String canonicalSignalToken(String value) {
        String token = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!SIGNAL_TOKEN.matcher(token).matches() || RESERVED_TOKENS.contains(token)) {
            throw new IllegalArgumentException("invalid signal token");
        }
        return token;
    }

    private static String normalizeLookupToken(String value) {
        if (value == null) return "";
        String token = value.trim().toLowerCase(Locale.ROOT);
        return SIGNAL_TOKEN.matcher(token).matches() ? token : "";
    }

    private static String titleSignalToken(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private static ParsedSnapshot parseCanonicalSnapshot(Mode mode, String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            requireExactKeys(root, ROOT_KEYS, "root");
            if (!exportSchema(mode).equals(root.getString("schema"))) throw new IllegalArgumentException("registry mode/schema mismatch");
            if (root.getInt("registrySchemaVersion") != SCHEMA_VERSION) throw new IllegalArgumentException("unsupported registry schema version");
            String appVersion = root.getString("appVersion");
            if (appVersion.length() > 128) throw new IllegalArgumentException("appVersion too long");
            JSONArray profiles = root.getJSONArray("profiles");
            if (profiles.length() > MAX_IMPORT_PROFILES) throw new IllegalArgumentException("profile count exceeds limit");
            ArrayList<Profile> parsed = new ArrayList<>();
            for (int i = 0; i < profiles.length(); i++) {
                Profile profile = parseCanonicalProfile(profiles.getJSONObject(i), mode);
                if (findByFingerprint(parsed, mode, profile.fingerprint) != null) throw new IllegalArgumentException("duplicate fingerprint");
                validateStoredSignalCompatibility(parsed, profile);
                parsed.add(profile);
            }
            return new ParsedSnapshot(parsed, appVersion);
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid canonical registry", error);
        }
    }

    private static Profile parseCanonicalProfile(JSONObject item, Mode mode) throws Exception {
        requireExactKeys(item, PROFILE_KEYS, "profile");
        JSONObject signal = item.getJSONObject("signal");
        requireExactKeys(signal, mode == Mode.CHAT ? CHAT_SIGNAL_KEYS : WORK_SIGNAL_KEYS, "signal");
        String reasoning = canonicalSignalToken(signal.getString("reasoning"));
        String model = mode == Mode.WORK ? canonicalSignalToken(signal.getString("model")) : "";
        ArrayList<Operation> operations = parseOperations(item.getJSONArray("operations"));
        validateExportRequest(item.getJSONObject("request"), operations);
        boolean builtIn = item.getBoolean("builtIn");
        String incomingFingerprint = item.getString("fingerprint");
        if (!FINGERPRINT.matcher(incomingFingerprint).matches()) throw new IllegalArgumentException("invalid fingerprint");
        Profile profile = new Profile(mode, model, reasoning, operations, builtIn);
        if (!profile.fingerprint.equals(incomingFingerprint)) throw new IllegalArgumentException("fingerprint mismatch");
        String requestModel = profile.requestValue("model");
        if (mode == Mode.CHAT) {
            if (requestModel.endsWith("-wm")) throw new IllegalArgumentException("Work model leaked into Chat registry");
            if (profile.requestHas("conversation_origin") || profile.requestHas("service_tier")) {
                throw new IllegalArgumentException("Chat registry contains Work-only request fields");
            }
        } else {
            if (!requestModel.endsWith("-wm")) throw new IllegalArgumentException("Work registry requires *-wm model");
            if (!"tpp".equals(profile.requestValue("conversation_origin"))) {
                throw new IllegalArgumentException("Work registry requires conversation_origin=tpp");
            }
        }
        return profile;
    }

    private static ArrayList<Operation> parseOperations(JSONArray operations) throws Exception {
        if (operations.length() != CONTROL_PATH_ORDER.size()) throw new IllegalArgumentException("absolute profile operation count mismatch");
        ArrayList<Operation> parsed = new ArrayList<>();
        for (int i = 0; i < operations.length(); i++) {
            JSONObject operation = operations.getJSONObject(i);
            String kind = operation.getString("op").toUpperCase(Locale.ROOT);
            String path = operation.getString("path");
            if (OperationKind.SET.name().equals(kind)) {
                requireExactKeys(operation, Set.of("op", "path", "value"), "operation");
                parsed.add(Operation.set(path, operation.getString("value")));
            } else if (OperationKind.REMOVE.name().equals(kind)) {
                requireExactKeys(operation, Set.of("op", "path"), "operation");
                parsed.add(Operation.remove(path));
            } else throw new IllegalArgumentException("unknown operation");
        }
        return parsed;
    }

    private static void validateExportRequest(JSONObject request, List<Operation> operations) throws Exception {
        requireOnlyKeys(request, CONTROL_PATHS, "request");
        for (Operation operation : operations) {
            if (operation.kind == OperationKind.SET) {
                if (!request.has(operation.path) || !operation.value.equals(request.getString(operation.path))) {
                    throw new IllegalArgumentException("request/operations mismatch");
                }
            } else if (request.has(operation.path)) {
                throw new IllegalArgumentException("REMOVE path remains in request");
            }
        }
    }

    private static void requireExactKeys(JSONObject object, Set<String> expected, String label) {
        if (object.length() != expected.size()) throw new IllegalArgumentException(label + " field count mismatch");
        requireOnlyKeys(object, expected, label);
        for (String key : expected) if (!object.has(key)) throw new IllegalArgumentException(label + " missing field: " + key);
    }

    private static void requireOnlyKeys(JSONObject object, Set<String> allowed, String label) {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) throw new IllegalArgumentException(label + " contains unsupported field: " + key);
        }
    }

    private static String exportSchema(Mode mode) {
        return mode == Mode.CHAT ? CHAT_EXPORT_SCHEMA : WORK_EXPORT_SCHEMA;
    }

    private static void loadLocked() {
        SharedPreferences prefs = preferences;
        if (prefs == null) return;
        ArrayList<Profile> combined = new ArrayList<>();
        SnapshotInfo chat = loadCachedMode(prefs, Mode.CHAT, combined);
        SnapshotInfo work = loadCachedMode(prefs, Mode.WORK, combined);
        state = new State(combined, chat, work);
    }

    private static SnapshotInfo loadCachedMode(SharedPreferences prefs, Mode mode, List<Profile> combined) {
        String raw = prefs.getString(mode == Mode.CHAT ? KEY_CHAT_RAW : KEY_WORK_RAW, "");
        String version = prefs.getString(mode == Mode.CHAT ? KEY_CHAT_VERSION : KEY_WORK_VERSION, "");
        String modified = prefs.getString(mode == Mode.CHAT ? KEY_CHAT_MODIFIED : KEY_WORK_MODIFIED, "");
        if (raw == null || raw.isEmpty()) return new SnapshotInfo(version, modified, "", 0);
        try {
            ParsedSnapshot parsed = parseCanonicalSnapshot(mode, raw);
            for (Profile profile : parsed.profiles) {
                validateStoredSignalCompatibility(combined, profile);
                combined.add(profile);
            }
            return new SnapshotInfo(version, modified, parsed.appVersion, parsed.profiles.size());
        } catch (RuntimeException invalid) {
            storageHealthy = false;
            return new SnapshotInfo("", "", "", 0);
        }
    }

    private static void validateStoredSignalCompatibility(List<Profile> existing, Profile profile) {
        for (Profile other : existing) {
            if (other.mode != profile.mode) continue;
            if (profile.mode == Mode.CHAT) {
                if (other.signalReasoning.equals(profile.signalReasoning)) throw new IllegalArgumentException("duplicate Chat signal");
            } else {
                if (other.signalModel.equals(profile.signalModel) && other.signalReasoning.equals(profile.signalReasoning)) {
                    throw new IllegalArgumentException("duplicate Work signal pair");
                }
                if (other.signalModel.equals(profile.signalModel) && !other.requestValue("model").equals(profile.requestValue("model"))) {
                    throw new IllegalArgumentException("Work model signal collision");
                }
                if (other.signalReasoning.equals(profile.signalReasoning)
                        && !operationIdentity(other.operations, "thinking_effort").equals(operationIdentity(profile.operations, "thinking_effort"))) {
                    throw new IllegalArgumentException("Work reasoning signal collision");
                }
            }
        }
    }

    private static String operationIdentity(List<Operation> operations, String path) {
        for (Operation operation : operations) {
            if (operation.path.equals(path)) return operation.kind.name() + ":" + (operation.value == null ? "" : operation.value);
        }
        return "MISSING";
    }

    private static Profile findByFingerprint(List<Profile> profiles, Mode mode, String fingerprint) {
        for (Profile profile : profiles) if (profile.mode == mode && profile.fingerprint.equals(fingerprint)) return profile;
        return null;
    }

    private static List<Operation> canonicalOperations(List<Operation> operations) {
        if (operations == null) throw new IllegalArgumentException("operations required");
        LinkedHashMap<String, Operation> byPath = new LinkedHashMap<>();
        for (Operation operation : operations) {
            if (operation == null || byPath.put(operation.path, operation) != null) throw new IllegalArgumentException("duplicate operation path");
        }
        if (!byPath.keySet().equals(CONTROL_PATHS)) throw new IllegalArgumentException("absolute profile must define every control path exactly once");
        ArrayList<Operation> out = new ArrayList<>();
        for (String path : CONTROL_PATH_ORDER) out.add(byPath.get(path));
        return out;
    }

    private static void validateProfileShape(Profile profile) {
        if (profile.mode == Mode.CHAT) {
            if (!profile.signalModel.isEmpty()) throw new IllegalArgumentException("Chat signalModel must be empty");
        } else if (profile.signalModel.isEmpty()) throw new IllegalArgumentException("Work signalModel required");
        if (profile.signalReasoning.isEmpty()) throw new IllegalArgumentException("signalReasoning required");
        if (profile.requestValue("model").isEmpty()) throw new IllegalArgumentException("request model required");
    }

    static String fingerprint(Mode mode, List<Operation> operations) {
        List<Operation> canonical = canonicalOperations(operations);
        StringBuilder source = new StringBuilder(SCHEMA).append('\n').append(mode.name()).append('\n');
        for (Operation operation : canonical) {
            source.append(operation.kind.name()).append('|').append(operation.path).append('|');
            if (operation.kind == OperationKind.SET) source.append(operation.value);
            source.append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String requestValue(List<Operation> operations, String path) {
        for (Operation operation : operations) if (operation.path.equals(path) && operation.kind == OperationKind.SET) return operation.value;
        return "";
    }

    private static boolean requestHas(List<Operation> operations, String path) {
        for (Operation operation : operations) if (operation.path.equals(path)) return operation.kind == OperationKind.SET;
        return false;
    }

    static synchronized void resetForTests() {
        preferences = null;
        state = new State(List.of(), new SnapshotInfo("", "", "", 0), new SnapshotInfo("", "", "", 0));
        storageHealthy = true;
        lastRefreshError = "";
    }
}
