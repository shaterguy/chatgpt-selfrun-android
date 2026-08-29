package com.shaterguy.chatgptselfrun;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Applies an absolute request profile resolved only from the durable Profile Registry. */
final class RequestProfileEngine {
    static final String PROFILE_VERSION = ProfileRegistry.SCHEMA;
    static final Set<String> CONTROL_PATHS = ProfileRegistry.CONTROL_PATHS;

    enum Mode { CHAT, WORK }

    static final class TargetProfile {
        final Mode mode;
        final String model;
        final String reasoning;
        final String profileVersion;

        TargetProfile(Mode mode, String model, String reasoning) {
            this(mode, model, reasoning, PROFILE_VERSION);
        }

        TargetProfile(Mode mode, String model, String reasoning, String profileVersion) {
            this.mode = Objects.requireNonNull(mode, "mode");
            this.model = model == null ? "" : model.trim().toLowerCase();
            this.reasoning = reasoning == null ? "" : reasoning.trim().toLowerCase();
            this.profileVersion = Objects.requireNonNull(profileVersion, "profileVersion");
        }
    }

    static final class ProfilePlan {
        final TargetProfile target;
        final List<ProfileRegistry.Operation> operations;
        final String fingerprint;

        ProfilePlan(TargetProfile target, ProfileRegistry.Profile profile) {
            this.target = target;
            this.operations = profile.operations;
            this.fingerprint = profile.fingerprint;
        }
    }

    private RequestProfileEngine() {}

    static ProfilePlan plan(TargetProfile target) {
        Objects.requireNonNull(target, "target");
        if (!PROFILE_VERSION.equals(target.profileVersion)) {
            throw new IllegalArgumentException("unsupported profile version");
        }
        ProfileRegistry.Profile profile = target.mode == Mode.CHAT
                ? ProfileRegistry.resolveChat(target.reasoning)
                : ProfileRegistry.resolveWork(target.model, target.reasoning);
        if (profile == null) {
            throw new IllegalArgumentException("unsupported or deleted request profile");
        }
        return new ProfilePlan(target, profile);
    }

    /** Applies only allowlisted control operations and preserves the native data plane exactly. */
    static Map<String, Object> apply(Map<String, Object> nativeRequest, TargetProfile target) {
        validateSubmissionSchema(nativeRequest);
        Map<String, Object> before = new LinkedHashMap<>(nativeRequest);
        Map<String, Object> after = new LinkedHashMap<>(nativeRequest);
        for (ProfileRegistry.Operation operation : plan(target).operations) {
            if (operation.kind == ProfileRegistry.OperationKind.SET) {
                after.put(operation.path, operation.value);
            } else {
                after.remove(operation.path);
            }
        }
        if (!nonControlEquivalent(before, after)) {
            throw new IllegalStateException("request mutation escaped control allowlist");
        }
        return after;
    }

    static ProfileRegistry.CapturedProfile canonicalizeCapture(Mode mode, Map<String, Object> request) {
        validateSubmissionSchema(request);
        ArrayList<ProfileRegistry.Operation> operations = new ArrayList<>();
        for (String path : ProfileRegistry.CONTROL_PATH_ORDER) {
            if (!request.containsKey(path)) {
                operations.add(ProfileRegistry.Operation.remove(path));
                continue;
            }
            Object value = request.get(path);
            if (!(value instanceof String)) {
                throw new IllegalArgumentException("non-string control field: " + path);
            }
            operations.add(ProfileRegistry.Operation.set(path, (String) value));
        }
        StringBuilder json = new StringBuilder("{\"mode\":\"")
                .append(mode == Mode.CHAT ? "chat" : "work")
                .append("\",\"operations\":[");
        for (int i = 0; i < operations.size(); i++) {
            if (i > 0) json.append(',');
            ProfileRegistry.Operation operation = operations.get(i);
            json.append("{\"op\":\"").append(operation.kind.name())
                    .append("\",\"path\":\"").append(operation.path).append('"');
            if (operation.kind == ProfileRegistry.OperationKind.SET) {
                json.append(",\"value\":\"").append(escapeJson(operation.value)).append('"');
            }
            json.append('}');
        }
        return ProfileRegistry.parseCaptured(json.append("]}").toString());
    }

    static void validateSubmissionSchema(Map<String, Object> request) {
        if (request == null || !(request.get("messages") instanceof List<?>)) {
            throw new IllegalArgumentException("unknown conversation submission schema");
        }
    }

    static boolean nonControlEquivalent(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> left = new LinkedHashMap<>(before);
        Map<String, Object> right = new LinkedHashMap<>(after);
        CONTROL_PATHS.forEach(left::remove);
        CONTROL_PATHS.forEach(right::remove);
        return left.equals(right);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
