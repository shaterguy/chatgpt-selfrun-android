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
    private static final String KEY_CHAT_LKG = "canonical_chat_lkg";
    private static final String KEY_WORK_LKG = "canonical_work_lkg";
    private static final String KEY_CHAT_SOURCE_VERSION = "canonical_chat_source_version";
    private static final String KEY_WORK_SOURCE_VERSION = "canonical_work_source_version";
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
            String reasoningLabel = presentationLabel.isEmpty() ? signalReasoning : presentationLabel;
            if (mode == Mode.CHAT) return reasoningLabel;
            String modelLabel = titleSignalToken(signalModel);
            return modelLabel.isEmpty() ? reasoningLabel : modelLabel + " · " + reasoningLabel;
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
        State(List<Profile> profiles) {
            this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
        }
    }

    private static volatile SharedPreferences preferences;
    private static volatile State state = new State(List.of());
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
        throw new UnsupportedOperationException("수동 profile 등록은 Drive canonical registry 동기화로 대체되었습니다.");
    }

    static synchronized ImportResult importJson(Mode expectedMode, String raw) {
        throw new UnsupportedOperationException("수동 registry 가져오기는 Drive canonical registry 동기화로 대체되었습니다.");
    }

    static synchronized boolean delete(String fingerprint) {
        return false;
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

    private static String titleSignalToken(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
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
        ArrayList<Profile> all = new ArrayList<>();
        boolean healthy = true;
        String chat = prefs.getString(KEY_CHAT_LKG, "");
        String work = prefs.getString(KEY_WORK_LKG, "");
        if (chat != null && !chat.isEmpty()) {
            try { all.addAll(parseCanonicalRegistry(Mode.CHAT, chat)); }
            catch (Exception error) { healthy = false; }
        }
        if (work != null && !work.isEmpty()) {
            try { all.addAll(parseCanonicalRegistry(Mode.WORK, work)); }
            catch (Exception error) { healthy = false; }
        }
        state = new State(all);
        storageHealthy = healthy;
    }

    static synchronized void replaceCanonicalSnapshot(Mode mode, String raw, String sourceVersion) {
        Objects.requireNonNull(mode, "mode");
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("canonical registry is empty");
        List<Profile> replacement = parseCanonicalRegistry(mode, raw);
        if (replacement.isEmpty()) throw new IllegalArgumentException("canonical registry returned zero profiles");
        ArrayList<Profile> all = new ArrayList<>();
        for (Profile profile : state.profiles) if (profile.mode != mode) all.add(profile);
        all.addAll(replacement);

        SharedPreferences prefs = preferences;
        if (prefs != null) {
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(rawKey(mode), raw)
                    .putString(versionKey(mode), sourceVersion == null ? "" : sourceVersion)
                    .remove("state");
            if (!editor.commit()) throw new IllegalStateException("canonical registry persistence failed");
        }
        state = new State(all);
        storageHealthy = true;
    }

    static String sourceVersion(Mode mode) {
        SharedPreferences prefs = preferences;
        return prefs == null ? "" : prefs.getString(versionKey(mode), "");
    }

    static boolean hasUsableMode(Mode mode) {
        for (Profile profile : state.profiles) if (profile.mode == mode) return true;
        return false;
    }

    static int profileCount(Mode mode) {
        int count = 0;
        for (Profile profile : state.profiles) if (profile.mode == mode) count++;
        return count;
    }

    private static String rawKey(Mode mode) {
        return mode == Mode.CHAT ? KEY_CHAT_LKG : KEY_WORK_LKG;
    }

    private static String versionKey(Mode mode) {
        return mode == Mode.CHAT ? KEY_CHAT_SOURCE_VERSION : KEY_WORK_SOURCE_VERSION;
    }

    private static List<Profile> parseCanonicalRegistry(Mode mode, String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            requireOnlyKeys(root, EXPORT_ROOT_KEYS, "root");
            if (!exportSchema(mode).equals(root.getString("schema"))) {
                throw new IllegalArgumentException("canonical registry mode/schema mismatch");
            }
            if (root.getInt("registrySchemaVersion") != SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported canonical registry schema");
            }
            String appVersion = root.getString("appVersion");
            if (appVersion.length() > 128) throw new IllegalArgumentException("canonical appVersion too long");
            JSONArray profiles = root.getJSONArray("profiles");
            if (profiles.length() == 0 || profiles.length() > MAX_IMPORT_PROFILES) {
                throw new IllegalArgumentException("canonical registry profile count invalid");
            }
            ArrayList<Profile> parsed = new ArrayList<>();
            LinkedHashSet<String> fingerprints = new LinkedHashSet<>();
            LinkedHashSet<String> signals = new LinkedHashSet<>();
            for (int i = 0; i < profiles.length(); i++) {
                Profile profile = parseCanonicalProfile(profiles.getJSONObject(i), mode);
                if (!fingerprints.add(profile.fingerprint)) throw new IllegalArgumentException("duplicate canonical fingerprint");
                String signal = mode.name() + "|" + profile.signalModel + "|" + profile.signalReasoning;
                if (!signals.add(signal)) throw new IllegalArgumentException("duplicate canonical signal");
                validateStoredSignalCompatibility(parsed, profile);
                parsed.add(profile);
            }
            return Collections.unmodifiableList(parsed);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid canonical registry", error);
        }
    }

    private static Profile parseCanonicalProfile(JSONObject item, Mode mode) throws Exception {
        requireOnlyKeys(item, EXPORT_PROFILE_KEYS, "profile");
        JSONObject signal = item.getJSONObject("signal");
        requireOnlyKeys(signal, EXPORT_SIGNAL_KEYS, "signal");
        String reasoning = canonicalSignalToken(signal.getString("reasoning"));
        String model = "";
        if (mode == Mode.WORK) {
            model = canonicalSignalToken(signal.getString("model"));
        } else if (signal.has("model") && !signal.optString("model").isEmpty()) {
            throw new IllegalArgumentException("Chat canonical signal must not carry model");
        }
        ArrayList<Operation> operations = parseOperations(item.getJSONArray("operations"));
        validateExportRequest(item, operations);
        boolean builtIn = item.getBoolean("builtIn");
        Profile profile = new Profile(mode, model, reasoning, operations, builtIn, "");
        String fingerprint = item.getString("fingerprint");
        if (!fingerprint.matches("[0-9a-f]{64}") || !profile.fingerprint.equals(fingerprint)) {
            throw new IllegalArgumentException("canonical fingerprint mismatch");
        }
        return profile;
    }

    private static void validateStoredSignalCompatibility(List<Profile> existing, Profile profile) {
        for (Profile other : existing) {
            if (other.mode != profile.mode) continue;
            if (profile.mode == Mode.CHAT) {
                if (other.signalReasoning.equals(profile.signalReasoning)) {
                    throw new IllegalArgumentException("duplicate Chat signal");
                }
            } else {
                if (other.signalModel.equals(profile.signalModel)
                        && other.signalReasoning.equals(profile.signalReasoning)) {
                    throw new IllegalArgumentException("duplicate Work signal pair");
                }
                if (other.signalModel.equals(profile.signalModel)
                        && !other.requestValue("model").equals(profile.requestValue("model"))) {
                    throw new IllegalArgumentException("Work model signal collision");
                }
                if (other.signalReasoning.equals(profile.signalReasoning)
                        && !operationIdentity(other.operations, "thinking_effort")
                        .equals(operationIdentity(profile.operations, "thinking_effort"))) {
                    throw new IllegalArgumentException("Work reasoning signal collision");
                }
            }
        }
    }

    private static Profile findByFingerprint(List<Profile> profiles, Mode mode, String fingerprint) {
        for (Profile profile : profiles) {
            if (profile.mode == mode && profile.fingerprint.equals(fingerprint)) return profile;
        }
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
        state = new State(List.of());
        storageHealthy = true;
        try {
            ArrayList<Profile> fixtures = new ArrayList<>();
            String chat = testResource("/selfrun-chat-profile-registry-v1.json");
            String work = testResource("/selfrun-work-profile-registry-v1.json");
            if (!chat.isEmpty()) fixtures.addAll(parseCanonicalRegistry(Mode.CHAT, chat));
            if (!work.isEmpty()) fixtures.addAll(parseCanonicalRegistry(Mode.WORK, work));
            if (!fixtures.isEmpty()) state = new State(fixtures);
        } catch (Exception error) {
            state = new State(List.of());
            storageHealthy = false;
        }
    }

    private static String testResource(String name) throws Exception {
        try (java.io.InputStream input = ProfileRegistry.class.getResourceAsStream(name)) {
            if (input == null) return "";
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) bytes.write(buffer, 0, read);
            }
            return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
