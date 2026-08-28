package com.shaterguy.chatgptselfrun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Capture-calibrated absolute request profile engine for SelfRun 2.0.
 *
 * <p>Only the four control paths proven by the 2026-08-28 calibration set are mutable. Every
 * profile is absolute: no previous turn or UI readback participates in profile construction.</p>
 */
final class RequestProfileEngine {
    static final String PROFILE_VERSION = "chatgpt-request-snapshot-calibration-v1@2026-08-28";
    static final Set<String> CONTROL_PATHS = Set.of(
            "model", "thinking_effort", "conversation_origin", "service_tier");

    enum Mode { CHAT, WORK }
    enum OperationKind { SET, REMOVE }

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

    static final class Operation {
        final OperationKind kind;
        final String path;
        final String value;

        private Operation(OperationKind kind, String path, String value) {
            if (!CONTROL_PATHS.contains(path)) throw new IllegalArgumentException("non-allowlisted control path: " + path);
            this.kind = kind;
            this.path = path;
            this.value = value;
        }

        static Operation set(String path, String value) {
            if (value == null) throw new IllegalArgumentException("SET value is null");
            return new Operation(OperationKind.SET, path, value);
        }

        static Operation remove(String path) { return new Operation(OperationKind.REMOVE, path, null); }

        @Override public String toString() {
            return kind == OperationKind.SET ? "SET(" + path + ")" : "REMOVE(" + path + ")";
        }
    }

    static final class ProfilePlan {
        final TargetProfile target;
        final List<Operation> operations;

        ProfilePlan(TargetProfile target, List<Operation> operations) {
            this.target = target;
            this.operations = Collections.unmodifiableList(new ArrayList<>(operations));
        }
    }

    private RequestProfileEngine() {}

    static ProfilePlan plan(TargetProfile target) {
        Objects.requireNonNull(target, "target");
        if (!PROFILE_VERSION.equals(target.profileVersion)) throw new IllegalArgumentException("unsupported profile version");
        return switch (target.mode) {
            case CHAT -> chatPlan(target);
            case WORK -> workPlan(target);
        };
    }

    private static ProfilePlan chatPlan(TargetProfile target) {
        List<Operation> ops = new ArrayList<>();
        switch (target.reasoning) {
            case "instant" -> {
                ops.add(Operation.set("model", "gpt-5-6"));
                ops.add(Operation.remove("thinking_effort"));
            }
            case "medium" -> {
                ops.add(Operation.set("model", "gpt-5-6-thinking"));
                ops.add(Operation.set("thinking_effort", "standard"));
            }
            case "high" -> {
                ops.add(Operation.set("model", "gpt-5-6-thinking"));
                ops.add(Operation.set("thinking_effort", "extended"));
            }
            case "xhigh", "extra_high", "extra high" -> {
                ops.add(Operation.set("model", "gpt-5-6-thinking"));
                ops.add(Operation.set("thinking_effort", "max"));
            }
            case "pro", "pro_standard", "pro_extended" ->
                    throw new IllegalArgumentException("Chat Pro request profile is not captured in dev1");
            default -> throw new IllegalArgumentException("unsupported Chat reasoning profile: " + target.reasoning);
        }
        ops.add(Operation.remove("conversation_origin"));
        ops.add(Operation.remove("service_tier"));
        return new ProfilePlan(target, ops);
    }

    private static ProfilePlan workPlan(TargetProfile target) {
        String model = switch (target.model) {
            case "sol", "5.6 sol" -> "gpt-5.6-sol-wm";
            case "terra", "5.6 terra" -> "gpt-5.6-terra-wm";
            case "luna", "5.6 luna" -> "gpt-5.6-luna-wm";
            default -> throw new IllegalArgumentException("unsupported Work model: " + target.model);
        };
        String effort = switch (target.reasoning) {
            case "light" -> "min";
            case "medium" -> "standard";
            case "high" -> "extended";
            case "xhigh", "extra_high", "extra high" -> "xhigh";
            case "max" -> "max";
            case "ultra" -> "ultra";
            default -> throw new IllegalArgumentException("unsupported Work reasoning: " + target.reasoning);
        };
        if ("gpt-5.6-luna-wm".equals(model) && "ultra".equals(effort)) {
            throw new IllegalArgumentException("Luna does not support Ultra");
        }
        return new ProfilePlan(target, List.of(
                Operation.set("model", model),
                Operation.set("thinking_effort", effort),
                Operation.set("conversation_origin", "tpp"),
                Operation.set("service_tier", "standard")));
    }

    /** Applies an absolute profile to a JSON-like top-level map and enforces the data-plane invariant. */
    static Map<String, Object> apply(Map<String, Object> nativeRequest, TargetProfile target) {
        validateSubmissionSchema(nativeRequest);
        Map<String, Object> before = new LinkedHashMap<>(nativeRequest);
        Map<String, Object> after = new LinkedHashMap<>(nativeRequest);
        for (Operation op : plan(target).operations) {
            if (op.kind == OperationKind.SET) after.put(op.path, op.value);
            else after.remove(op.path);
        }
        if (!nonControlEquivalent(before, after)) {
            throw new IllegalStateException("request mutation escaped control allowlist");
        }
        return after;
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
}
