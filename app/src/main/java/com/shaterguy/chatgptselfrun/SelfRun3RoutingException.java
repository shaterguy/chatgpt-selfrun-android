package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Set;

/** Only Result-owned routing failures carry this type; storage and task errors never do. */
final class SelfRun3RoutingException extends IllegalStateException {
    final JSONArray problems;
    SelfRun3RoutingException(JSONArray problems) {
        super("RESULT_ROUTING_INVALID");
        this.problems = SelfRun3Engine.array(problems);
    }

    static JSONObject resolve(JSONObject result, SelfRun3Engine.State state) {
        // Invalid local execution state cannot be repaired by rewriting a Result document.
        String sourceMode = state.config().optString("mode");
        SelfRun3Engine.require(Set.of("CHAT", "WORK").contains(sourceMode)
                && ("HYBRID".equals(state.taskMode()) || sourceMode.equals(state.taskMode())), "current execution mode invalid");
        JSONObject execution = result.optJSONObject("next_execution");
        JSONObject primary = execution == null ? null : execution.optJSONObject("profile");
        JSONObject secondary = result.optJSONObject("next_profile");
        // The existing machine-only result fallback is still usable without semantic hints.
        if (primary == null && secondary == null && result.optString("status").isEmpty())
            return SelfRun3Engine.executionProfile(state);
        JSONArray first = check(primary, "next_execution.profile", state);
        JSONArray second = check(secondary, "next_profile", state);
        if (primary != null && secondary != null && first.length() == 0 && second.length() == 0) {
            JSONArray conflict = new JSONArray();
            for (String field : new String[]{"mode", "model", "reasoning"}) {
                String a = effective(primary, field, state), b = effective(secondary, field, state);
                if (!a.equals(b)) add(conflict, "next_execution.profile." + field + " / next_profile." + field,
                        "CONFLICT", "두 후속 실행 profile 값이 서로 모순됩니다.");
            }
            if (conflict.length() > 0) throw new SelfRun3RoutingException(conflict);
        }
        if (primary != null && first.length() == 0) return completed(primary, state);
        if (secondary != null && second.length() == 0) return completed(secondary, state);
        JSONArray problems = new JSONArray();
        if (primary != null) append(problems, first);
        else add(problems, "next_execution.profile", execution != null && execution.has("profile") ? "INVALID" : "MISSING", "사용 가능한 후속 실행 profile 객체가 없습니다.");
        if (secondary == null && result.has("next_profile"))
            add(problems, "next_profile", "INVALID", "후속 실행 profile은 객체여야 합니다.");
        else append(problems, second);
        if (execution == null && result.has("next_execution"))
            add(problems, "next_execution", "INVALID", "후속 실행 routing 정보는 객체여야 합니다.");
        if (execution != null && !Set.of("SERIAL", "PARALLEL").contains(execution.optString("type")))
            add(problems, "next_execution.type", execution.has("type") ? "INVALID" : "MISSING", "후속 실행 방식이 유효하지 않습니다.");
        throw new SelfRun3RoutingException(problems);
    }

    private static JSONArray check(JSONObject profile, String path, SelfRun3Engine.State state) {
        JSONArray problems = new JSONArray();
        if (profile == null) { add(problems, path, "MISSING", "후속 실행 profile이 누락되었거나 객체가 아닙니다."); return problems; }
        String mode = effective(profile, "mode", state);
        if (!Set.of("CHAT", "WORK").contains(mode)) add(problems, path + ".mode", "INVALID", "mode는 CHAT 또는 WORK여야 합니다.");
        else if (!"HYBRID".equals(state.taskMode()) && !state.taskMode().equals(mode))
            add(problems, path + ".mode", "CONFLICT", "현재 Task mode와 다음 실행 mode가 충돌합니다.");
        for (String field : new String[]{"model", "reasoning"})
            if (!(profile.opt(field) instanceof String) || profile.optString(field).trim().isEmpty())
                add(problems, path + "." + field, profile.has(field) ? "INVALID" : "MISSING", field + " 값이 누락되었거나 유효하지 않습니다.");
        if (problems.length() == 0) {
            try { SelfRun3Engine.applyProfile(state.config(), completed(profile, state), state.taskMode()); }
            catch (IllegalStateException invalid) { add(problems, path, "INVALID_COMBINATION", "등록되지 않은 mode/model/reasoning 조합입니다."); }
        }
        return problems;
    }
    private static String effective(JSONObject p, String field, SelfRun3Engine.State state) {
        // Preserve the existing deterministic mode default. Never invent model/reasoning.
        return "mode".equals(field) && !p.has(field) ? state.config().optString("mode") : p.optString(field);
    }
    private static JSONObject completed(JSONObject p, SelfRun3Engine.State state) {
        JSONObject copy = SelfRun3Engine.copy(p);
        SelfRun3Engine.put(copy, "mode", effective(p, "mode", state));
        return copy;
    }
    private static void append(JSONArray to, JSONArray from) { for (int i=0;i<from.length();i++) to.put(from.opt(i)); }
    private static void add(JSONArray to, String field, String kind, String message) {
        JSONObject item = new JSONObject();
        SelfRun3Engine.put(item, "field", field); SelfRun3Engine.put(item, "kind", kind);
        SelfRun3Engine.put(item, "message", message); to.put(item);
    }
}
