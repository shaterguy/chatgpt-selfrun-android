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

/** Durable, capture-backed source of truth for Chat and Work request profiles. */
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
    private static final String KEY_STATE = "state";
    private static final Pattern SIGNAL_TOKEN = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,79}");
    private static final Set<String> RESERVED_TOKENS = Set.of(
            "body", "none", "null", "true", "false", "keep", "chat", "work",
            "model", "reasoning", "recovery_id", "next_input_b64url", "self_run_turn_completed");
    private static final Set<String> EXPORT_ROOT_KEYS = Set.of(
            "schema", "registrySchemaVersion", "appVersion", "profiles");
    private static final Set<String> EXPORT_PROFILE_KEYS = Set.of(
            "signal", "request", "operations", "fingerprint", "builtIn");
    private static final Set<String> EXPORT_SIGNAL_KEYS = Set.of("model", "reasoning");

    enum Mode { CHAT, WORK }
    enum OperationKind { SET, REMOVE }

    static final class Operation {
        final OperationKind kind;
        final String path;
        final String value;

        private Operation(OperationKind kind, String path, String value) {
            if (!CONTROL_PATHS.contains(path)) {
                throw new IllegalArgumentException("non-allowlisted control path: " + path);
            }
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
            try {
                out.put("op", kind.name());
                out.put("path", path);
                if (kind == OperationKind.SET) out.put("value", value);
            } catch (Exception error) {
                throw new IllegalStateException("operation serialization failed", error);
            }
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
        final String presentationLabel;

        private Profile(Mode mode, String signalModel, String signalReasoning,
                        List<Operation> operations, boolean builtIn, String presentationLabel) {
            this.mode = Objects.requireNonNull(mode, "mode");
            this.signalModel = signalModel == null ? "" : signalModel;
            this.signalReasoning = Objects.requireNonNull(signalReasoning, "signalReasoning");
            this.operations = Collections.unmodifiableList(canonicalOperations(operations));
            this.fingerprint = fingerprint(mode, this.operations);
            this.builtIn = builtIn;
            this.presentationLabel = presentationLabel == null ? "" : presentationLabel;
            validateProfileShape(this);
        }

        String requestValue(String path) { return ProfileRegistry.requestValue(operations, path); }
        boolean requestHas(String path) { return ProfileRegistry.requestHas(operations, path); }
        String displayLabel() {
            if (mode == Mode.WORK) {
                return signalModel.substring(0, 1).toUpperCase(Locale.ROOT)
                        + signalModel.substring(1) + " / " + signalReasoning;
            }
            return presentationLabel.isEmpty() ? signalReasoning : presentationLabel;
        }
        String actualCombination() {
            return requestValue("model") + " / "
                    + (requestHas("thinking_effort") ? requestValue("thinking_effort") : "필드 없음");
        }

        JSONObject toStorageJson() {
            JSONObject out = new JSONObject();
            try {
                out.put("mode", mode.name());
                out.put("signalModel", signalModel);
                out.put("signalReasoning", signalReasoning);
                out.put("fingerprint", fingerprint);
                JSONArray ops = new JSONArray();
                for (Operation operation : operations) ops.put(operation.toJson());
                out.put("operations", ops);
            } catch (Exception error) {
                throw new IllegalStateException("profile serialization failed", error);
            }
            return out;
        }

        JSONObject toRuntimeJson() {
            JSONObject out = toStorageJson();
            try { out.put("builtIn", builtIn); }
            catch (Exception error) { throw new IllegalStateException("runtime profile serialization failed", error); }
            return out;
        }
    }

    static final class CapturedProfile {
        final Mode mode;
        final List<Operation> operations;
        final String fingerprint;

        private CapturedProfile(Mode mode, List<Operation> operations) {
            this.mode = Objects.requireNonNull(mode, "mode");
            this.operations = Collections.unmodifiableList(canonicalOperations(operations));
            this.fingerprint = fingerprint(mode, this.operations);
            if (ProfileRegistry.requestValue(this.operations, "model").isEmpty()) {
                throw new IllegalArgumentException("captured model missing");
            }
        }

        String requestValue(String path) { return ProfileRegistry.requestValue(operations, path); }
        boolean requestHas(String path) { return ProfileRegistry.requestHas(operations, path); }
        String actualCombination() {
            return requestValue("model") + " / "
                    + (requestHas("thinking_effort") ? requestValue("thinking_effort") : "필드 없음");
        }
    }

    static final class RegisterResult {
        static final String ADDED = "ADDED";
        static final String DUPLICATE_PROFILE = "DUPLICATE_PROFILE";
        final String status;
        final Profile profile;
        RegisterResult(String status, Profile profile) { this.status = status; this.profile = profile; }
    }

    static final class ImportResult {
        final Mode mode;
        final int added;
        final int skipped;
        ImportResult(Mode mode, int added, int skipped) {
            this.mode = mode;
            this.added = Math.max(0, added);
            this.skipped = Math.max(0, skipped);
        }
    }

    private static final class State {
        final List<Profile> profiles;
        final List<Profile> userProfiles;
        final Set<String> tombstones;
        State(List<Profile> profiles, List<Profile> userProfiles, Set<String> tombstones) {
            this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
            this.userProfiles = Collections.unmodifiableList(new ArrayList<>(userProfiles));
            this.tombstones = Collections.unmodifiableSet(new LinkedHashSet<>(tombstones));
        }
    }

    private static volatile SharedPreferences preferences;
    private static volatile State state = defaults(Set.of(), List.of());
    private static volatile boolean storageHealthy = true;

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
            JSONArray operations = root.getJSONArray("operations");
            ArrayList<Operation> parsed = parseOperations(operations);
            return new CapturedProfile(mode, parsed);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid captured profile", error);
        }
    }

    static synchronized RegisterResult registerCaptured(CapturedProfile captured,
                                                        String signalModel, String signalReasoning) {
        Objects.requireNonNull(captured, "captured");
        Profile duplicate = findByFingerprint(captured.mode, captured.fingerprint);
        if (duplicate != null) return new RegisterResult(RegisterResult.DUPLICATE_PROFILE, duplicate);

        String reasoning = canonicalSignalToken(signalReasoning);
        String model = captured.mode == Mode.WORK ? canonicalSignalToken(signalModel) : "";
        validateSignalCompatibility(captured, model, reasoning);
        Profile profile = new Profile(captured.mode, model, reasoning, captured.operations, false, "");

        ArrayList<Profile> users = new ArrayList<>(state.userProfiles);
        users.removeIf(existing -> existing.fingerprint.equals(profile.fingerprint));
        users.add(profile);
        State next = defaults(state.tombstones, users);
        if (!persistLocked(next.userProfiles, next.tombstones)) {
            throw new IllegalStateException("profile registry persistence failed");
        }
        state = next;
        storageHealthy = true;
        return new RegisterResult(RegisterResult.ADDED, profile);
    }

    static synchronized ImportResult importJson(Mode expectedMode, String raw) {
        Objects.requireNonNull(expectedMode, "expectedMode");
        if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("가져오기 파일이 비어 있습니다.");
        if (raw.length() > MAX_IMPORT_JSON_CHARS) throw new IllegalArgumentException("가져오기 파일 크기 제한을 초과했습니다.");
        try {
            JSONObject root = new JSONObject(raw);
            requireOnlyKeys(root, EXPORT_ROOT_KEYS, "root");
            String expectedSchema = exportSchema(expectedMode);
            if (!expectedSchema.equals(root.getString("schema"))) {
                throw new IllegalArgumentException("선택한 영역과 조합 파일 형식이 일치하지 않습니다.");
            }
            if (root.getInt("registrySchemaVersion") != SCHEMA_VERSION) {
                throw new IllegalArgumentException("지원하지 않는 Registry schema 버전입니다.");
            }
            if (root.has("appVersion")) {
                String appVersion = root.getString("appVersion");
                if (appVersion.length() > 128) throw new IllegalArgumentException("appVersion 값이 너무 깁니다.");
            }
            JSONArray profiles = root.getJSONArray("profiles");
            if (profiles.length() > MAX_IMPORT_PROFILES) {
                throw new IllegalArgumentException("가져오기 profile 개수 제한을 초과했습니다.");
            }

            ArrayList<Profile> combined = new ArrayList<>(state.profiles);
            ArrayList<Profile> users = new ArrayList<>(state.userProfiles);
            int added = 0, skipped = 0;
            for (int i = 0; i < profiles.length(); i++) {
                Profile candidate = parseImportedProfile(profiles.getJSONObject(i), expectedMode);
                if (findByFingerprint(combined, expectedMode, candidate.fingerprint) != null) {
                    skipped++;
                    continue;
                }
                validateStoredSignalCompatibility(combined, candidate);
                combined.add(candidate);
                users.add(candidate);
                added++;
            }

            State next = defaults(state.tombstones, users);
            if (!persistLocked(next.userProfiles, next.tombstones)) {
                throw new IllegalStateException("profile registry import persistence failed");
            }
            state = next;
            storageHealthy = true;
            return new ImportResult(expectedMode, added, skipped);
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("조합 파일을 안전하게 해석하지 못했습니다.", error);
        }
    }

    static synchronized boolean delete(String fingerprint) {
        Profile existing = null;
        for (Profile profile : state.profiles) {
            if (profile.fingerprint.equals(fingerprint)) { existing = profile; break; }
        }
        if (existing == null) return false;

        String deletingFingerprint = existing.fingerprint;
        ArrayList<Profile> users = new ArrayList<>(state.userProfiles);
        users.removeIf(profile -> profile.fingerprint.equals(deletingFingerprint));
        LinkedHashSet<String> tombstones = new LinkedHashSet<>(state.tombstones);
        if (existing.builtIn || builtInFingerprint(deletingFingerprint)) tombstones.add(deletingFingerprint);
        State next = defaults(tombstones, users);
        if (!persistLocked(next.userProfiles, next.tombstones)) return false;
        state = next;
        storageHealthy = true;
        return true;
    }

    static String runtimeJson() {
        JSONArray out = new JSONArray();
        for (Profile profile : state.profiles) out.put(profile.toRuntimeJson());
        return out.toString();
    }

    static String exportChatJson(String appVersion) { return exportJson(Mode.CHAT, appVersion); }
    static String exportWorkJson(String appVersion) { return exportJson(Mode.WORK, appVersion); }

    private static String exportJson(Mode mode, String appVersion) {
        JSONObject root = new JSONObject();
        JSONArray profiles = new JSONArray();
        try {
            root.put("schema", exportSchema(mode));
            root.put("registrySchemaVersion", SCHEMA_VERSION);
            root.put("appVersion", appVersion == null ? "" : appVersion);
            for (Profile profile : list(mode)) {
                JSONObject item = new JSONObject(), signal = new JSONObject(), request = new JSONObject();
                if (mode == Mode.WORK) signal.put("model", profile.signalModel);
                signal.put("reasoning", profile.signalReasoning);
                item.put("signal", signal);
                for (Operation operation : profile.operations) {
                    if (operation.kind == OperationKind.SET) request.put(operation.path, operation.value);
                }
                item.put("request", request);
                JSONArray operations = new JSONArray();
                for (Operation operation : profile.operations) operations.put(operation.toJson());
                item.put("operations", operations);
                item.put("fingerprint", profile.fingerprint);
                item.put("builtIn", profile.builtIn);
                profiles.put(item);
            }
            root.put("profiles", profiles);
            return root.toString(2);
        } catch (Exception error) {
            throw new IllegalStateException((mode == Mode.CHAT ? "Chat" : "Work") + " registry export failed", error);
        }
    }

    static String canonicalSignalToken(String value) {
        String token = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!SIGNAL_TOKEN.matcher(token).matches() || RESERVED_TOKENS.contains(token)) {
            throw new IllegalArgumentException("신호명은 소문자 영숫자로 시작하고 영숫자 . _ : - 만 80자 이내로 사용할 수 있습니다.");
        }
        return token;
    }

    private static String normalizeLookupToken(String value) {
        if (value == null) return "";
        String token = value.trim().toLowerCase(Locale.ROOT);
        return SIGNAL_TOKEN.matcher(token).matches() ? token : "";
    }

    private static Profile parseImportedProfile(JSONObject item, Mode mode) throws Exception {
        requireOnlyKeys(item, EXPORT_PROFILE_KEYS, "profile");
        JSONObject signal = item.getJSONObject("signal");
        requireOnlyKeys(signal, EXPORT_SIGNAL_KEYS, "signal");
        String reasoning = canonicalSignalToken(signal.getString("reasoning"));
        String model;
        if (mode == Mode.WORK) model = canonicalSignalToken(signal.getString("model"));
        else {
            model = "";
            if (signal.has("model") && !signal.getString("model").isEmpty()) {
                throw new IllegalArgumentException("Chat 조합에는 model 신호를 지정할 수 없습니다.");
            }
        }
        ArrayList<Operation> operations = parseOperations(item.getJSONArray("operations"));
        validateExportRequest(item, operations);
        if (item.has("fingerprint")) {
            String ignoredFingerprint = item.getString("fingerprint");
            if (ignoredFingerprint.length() > 128) throw new IllegalArgumentException("fingerprint 값이 너무 깁니다.");
        }
        if (item.has("builtIn")) item.getBoolean("builtIn");
        return new Profile(mode, model, reasoning, operations, false, "");
    }

    private static ArrayList<Operation> parseOperations(JSONArray operations) throws Exception {
        if (operations.length() != CONTROL_PATH_ORDER.size()) {
            throw new IllegalArgumentException("absolute profile operation 개수가 올바르지 않습니다.");
        }
        ArrayList<Operation> parsed = new ArrayList<>();
        for (int i = 0; i < operations.length(); i++) {
            JSONObject operation = operations.getJSONObject(i);
            requireOnlyKeys(operation, Set.of("op", "path", "value"), "operation");
            String kind = operation.getString("op").toUpperCase(Locale.ROOT);
            String path = operation.getString("path");
            if (OperationKind.SET.name().equals(kind)) parsed.add(Operation.set(path, operation.getString("value")));
            else if (OperationKind.REMOVE.name().equals(kind)) {
                if (operation.has("value")) throw new IllegalArgumentException("REMOVE operation에는 value를 둘 수 없습니다.");
                parsed.add(Operation.remove(path));
            } else throw new IllegalArgumentException("unknown operation");
        }
        return parsed;
    }

    private static void validateExportRequest(JSONObject item, List<Operation> operations) throws Exception {
        if (!item.has("request")) return;
        JSONObject request = item.getJSONObject("request");
        requireOnlyKeys(request, CONTROL_PATHS, "request");
        for (Operation operation : operations) {
            if (operation.kind == OperationKind.SET) {
                if (!request.has(operation.path)
                        || !operation.value.equals(request.getString(operation.path))) {
                    throw new IllegalArgumentException("request와 operations가 일치하지 않습니다.");
                }
            } else if (request.has(operation.path)) {
                throw new IllegalArgumentException("REMOVE operation의 request 값이 남아 있습니다.");
            }
        }
    }

    private static void requireOnlyKeys(JSONObject object, Set<String> allowed, String label) {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) throw new IllegalArgumentException(label + "에 허용되지 않은 field가 있습니다: " + key);
        }
    }

    private static String exportSchema(Mode mode) {
        return mode == Mode.CHAT ? CHAT_EXPORT_SCHEMA : WORK_EXPORT_SCHEMA;
    }

    private static void validateSignalCompatibility(CapturedProfile captured, String model, String reasoning) {
        String requestModel = captured.requestValue("model");
        String effortKey = operationIdentity(captured.operations, "thinking_effort");
        for (Profile profile : state.profiles) {
            if (profile.mode != captured.mode) continue;
            if (captured.mode == Mode.CHAT) {
                if (profile.signalReasoning.equals(reasoning)) throw new IllegalArgumentException("이미 사용 중인 Chat 추론 신호명입니다.");
            } else {
                if (profile.signalModel.equals(model) && profile.signalReasoning.equals(reasoning)) {
                    throw new IllegalArgumentException("이미 사용 중인 Work MODEL/REASONING 신호 조합입니다.");
                }
                if (profile.signalModel.equals(model) && !profile.requestValue("model").equals(requestModel)) {
                    throw new IllegalArgumentException("동일한 모델 신호명이 다른 실제 request model에 이미 연결되어 있습니다.");
                }
                if (profile.signalReasoning.equals(reasoning)
                        && !operationIdentity(profile.operations, "thinking_effort").equals(effortKey)) {
                    throw new IllegalArgumentException("동일한 추론 신호명이 다른 thinking_effort 동작에 이미 연결되어 있습니다.");
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

    private static void loadLocked() {
        SharedPreferences prefs = preferences;
        if (prefs == null) return;
        String raw = prefs.getString(KEY_STATE, "");
        if (raw == null || raw.isEmpty()) { state = defaults(Set.of(), List.of()); storageHealthy = true; return; }
        try {
            JSONObject root = new JSONObject(raw);
            if (!SCHEMA.equals(root.getString("schema")) || root.getInt("schemaVersion") != SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported registry schema");
            }
            LinkedHashSet<String> tombstones = new LinkedHashSet<>();
            JSONArray tombstoneArray = root.optJSONArray("tombstones");
            if (tombstoneArray != null) {
                for (int i = 0; i < tombstoneArray.length(); i++) {
                    String value = tombstoneArray.getString(i);
                    if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid tombstone");
                    tombstones.add(value);
                }
            }
            ArrayList<Profile> users = new ArrayList<>();
            JSONArray profiles = root.optJSONArray("profiles");
            if (profiles != null) for (int i = 0; i < profiles.length(); i++) users.add(parseStoredProfile(profiles.getJSONObject(i)));
            state = defaults(tombstones, users);
            storageHealthy = true;
        } catch (Exception error) {
            state = new State(List.of(), List.of(), Set.of());
            storageHealthy = false;
        }
    }

    private static Profile parseStoredProfile(JSONObject item) throws Exception {
        Mode mode = Mode.valueOf(item.getString("mode"));
        String model = mode == Mode.WORK ? canonicalSignalToken(item.getString("signalModel")) : "";
        String reasoning = canonicalSignalToken(item.getString("signalReasoning"));
        ArrayList<Operation> parsed = parseOperations(item.getJSONArray("operations"));
        Profile profile = new Profile(mode, model, reasoning, parsed, false, "");
        if (!profile.fingerprint.equals(item.getString("fingerprint"))) throw new IllegalArgumentException("stored fingerprint mismatch");
        return profile;
    }

    private static boolean persistLocked(List<Profile> users, Set<String> tombstones) {
        SharedPreferences prefs = preferences;
        if (prefs == null) return true;
        JSONObject root = new JSONObject();
        try {
            root.put("schema", SCHEMA); root.put("schemaVersion", SCHEMA_VERSION);
            JSONArray profiles = new JSONArray();
            for (Profile profile : users) profiles.put(profile.toStorageJson());
            root.put("profiles", profiles);
            JSONArray deleted = new JSONArray();
            for (String fingerprint : tombstones) deleted.put(fingerprint);
            root.put("tombstones", deleted);
        } catch (Exception error) { return false; }
        return prefs.edit().putString(KEY_STATE, root.toString()).commit();
    }

    private static State defaults(Set<String> tombstones, List<Profile> users) {
        ArrayList<Profile> all = new ArrayList<>();
        for (Profile profile : builtIns()) if (!tombstones.contains(profile.fingerprint)) all.add(profile);
        for (Profile profile : users) {
            if (findByFingerprint(all, profile.mode, profile.fingerprint) != null) {
                throw new IllegalArgumentException("duplicate stored profile fingerprint");
            }
            validateStoredSignalCompatibility(all, profile);
            all.add(profile);
        }
        return new State(all, users, tombstones);
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

    private static boolean builtInFingerprint(String fingerprint) {
        for (Profile profile : builtIns()) if (profile.fingerprint.equals(fingerprint)) return true;
        return false;
    }

    private static Profile findByFingerprint(List<Profile> profiles, Mode mode, String fingerprint) {
        for (Profile profile : profiles) if (profile.mode == mode && profile.fingerprint.equals(fingerprint)) return profile;
        return null;
    }

    private static List<Profile> builtIns() {
        ArrayList<Profile> out = new ArrayList<>();
        out.add(profile(Mode.CHAT, "", "instant", "Instant", set("model", "gpt-5-6"), remove("thinking_effort"), remove("conversation_origin"), remove("service_tier")));
        out.add(profile(Mode.CHAT, "", "medium", "Medium", set("model", "gpt-5-6-thinking"), set("thinking_effort", "standard"), remove("conversation_origin"), remove("service_tier")));
        out.add(profile(Mode.CHAT, "", "high", "High", set("model", "gpt-5-6-thinking"), set("thinking_effort", "extended"), remove("conversation_origin"), remove("service_tier")));
        out.add(profile(Mode.CHAT, "", "xhigh", "Extra High", set("model", "gpt-5-6-thinking"), set("thinking_effort", "max"), remove("conversation_origin"), remove("service_tier")));
        addWork(out, "sol", "high", "gpt-5.6-sol-wm", "extended"); addWork(out, "sol", "xhigh", "gpt-5.6-sol-wm", "xhigh");
        addWork(out, "sol", "max", "gpt-5.6-sol-wm", "max"); addWork(out, "sol", "ultra", "gpt-5.6-sol-wm", "ultra");
        addWork(out, "terra", "high", "gpt-5.6-terra-wm", "extended"); addWork(out, "terra", "xhigh", "gpt-5.6-terra-wm", "xhigh");
        addWork(out, "terra", "max", "gpt-5.6-terra-wm", "max"); addWork(out, "luna", "max", "gpt-5.6-luna-wm", "max");
        return out;
    }

    private static void addWork(List<Profile> out, String modelSignal, String reasoningSignal, String requestModel, String effort) {
        out.add(profile(Mode.WORK, modelSignal, reasoningSignal, "", set("model", requestModel), set("thinking_effort", effort), set("conversation_origin", "tpp"), set("service_tier", "standard")));
    }

    private static Profile profile(Mode mode, String model, String reasoning, String label, Operation... operations) {
        return new Profile(mode, model, reasoning, List.of(operations), true, label);
    }
    private static Operation set(String path, String value) { return Operation.set(path, value); }
    private static Operation remove(String path) { return Operation.remove(path); }

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
        } catch (Exception error) { throw new IllegalStateException("SHA-256 unavailable", error); }
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
        state = defaults(Set.of(), List.of());
        storageHealthy = true;
    }
}
