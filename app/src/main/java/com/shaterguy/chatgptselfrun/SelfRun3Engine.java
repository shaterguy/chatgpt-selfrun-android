package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.json.JSONTokener;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Deterministic task engine. No Android, DOM, network, clocks, or AI calls are allowed here. */
final class SelfRun3Engine {
    static final String STATE_SCHEMA = "selfrun-task-state-v3";
    static final String RESULT_SCHEMA = "selfrun-turn-result-v3";
    static final int MAX_RESULT_BYTES = 512 * 1024;
    enum Stage { SETUP, PREPARING, READY, DISPATCHING, WAITING, RECONCILING, PAUSED, DONE, STOPPED }
    enum Kind { RESOURCE, SETUP_DONE, TURN_READY, CLAIM_SEND, ACCEPTED, ENDED, RESULT, COMMIT,
        RECONCILE, PAUSE, RESUME, STOP, ERROR }
    enum Action { SETUP, PREPARE_TURN, PREPARE_WEB, WAIT, READ_RESULT, CHECK_RECEIPT, COMMIT, NONE }

    static final class State {
        private final JSONObject value;
        State(JSONObject value) {
            this.value = copy(value);
            if (!STATE_SCHEMA.equals(text("schema")) || !validId(taskId()) || !validId(turnId()))
                throw new IllegalArgumentException("invalid task identity");
            Stage.valueOf(text("stage"));
            if (turn() < 1) throw new IllegalArgumentException("invalid turn ordinal");
        }
        String text(String key) { return value.optString(key, ""); }
        boolean flag(String key) { return value.optBoolean(key, false); }
        int number(String key) { return value.optInt(key, 0); }
        long time(String key) { return value.optLong(key, 0L); }
        String taskId() { return text("taskId"); }
        String turnId() { return text("turnId"); }
        int turn() { return number("turn"); }
        Stage stage() { return Stage.valueOf(text("stage")); }
        JSONObject json() { return copy(value); }
        JSONObject config() { return copy(value.optJSONObject("config")); }
        String resource(String key) { JSONObject r = value.optJSONObject("resources"); return r == null ? "" : r.optString(key, ""); }
        boolean terminal() { return stage() == Stage.DONE || stage() == Stage.STOPPED; }
        boolean hasResult() { return !text("result").isEmpty(); }
    }

    static final class Event {
        final String id, taskId, turnId;
        final Kind kind;
        final JSONObject payload;
        Event(String id, Kind kind, String taskId, String turnId, JSONObject payload) {
            if (!validId(id) || !validId(taskId)) throw new IllegalArgumentException("invalid event identity");
            this.id = id; this.kind = java.util.Objects.requireNonNull(kind);
            this.taskId = taskId; this.turnId = turnId == null ? "" : turnId;
            this.payload = copy(payload);
        }
    }

    static State create(String taskId, String turnId, JSONObject config) {
        if (!validId(taskId) || !validId(turnId)) throw new IllegalArgumentException("task identity required");
        String mode = config.optString("mode");
        if (!Set.of("CHAT", "WORK").contains(mode)) throw new IllegalArgumentException("mode required");
        JSONObject v = new JSONObject();
        put(v, "schema", STATE_SCHEMA); put(v, "taskId", taskId); put(v, "turnId", turnId);
        put(v, "turn", 1); put(v, "stage", Stage.SETUP.name()); put(v, "phase", "PLAN");
        put(v, "config", copy(config)); put(v, "resources", new JSONObject());
        put(v, "accepted", false); put(v, "sendClaimed", false); put(v, "ended", false);
        return new State(v);
    }

    static State reduce(State before, Event event) {
        if (!before.taskId().equals(event.taskId) || before.terminal()) return before;
        boolean control = event.kind == Kind.PAUSE || event.kind == Kind.RESUME || event.kind == Kind.STOP;
        if (!control && !before.turnId().equals(event.turnId)) return before;
        JSONObject v = before.json(), p = event.payload;
        Stage stage = before.stage();
        switch (event.kind) {
            case RESOURCE -> {
                String key = p.optString("key"), value = p.optString("value");
                if (!Set.of("folderId", "requirementDocumentId", "resultDocumentId", "conversationUrl",
                        "resultCreateIntent", "requirementCreateIntent").contains(key))
                    throw new IllegalArgumentException("unknown resource");
                if (value.length() > 2048) throw new IllegalArgumentException("resource too long");
                JSONObject resources = copy(v.optJSONObject("resources"));
                String existing = resources.optString(key);
                if (!existing.isEmpty() && !existing.equals(value)) throw new IllegalStateException("pinned resource cannot change");
                put(resources, key, value); put(v, "resources", resources);
            }
            case SETUP_DONE -> {
                require(stage == Stage.SETUP, "setup stage required");
                require(!before.resource("folderId").isEmpty() && !before.resource("requirementDocumentId").isEmpty(), "setup resources required");
                put(v, "stage", Stage.PREPARING.name());
            }
            case TURN_READY -> {
                require(stage == Stage.PREPARING, "turn preparation required");
                require(!before.resource("resultDocumentId").isEmpty(), "result document required");
                String prompt = p.optString("prompt");
                require(!prompt.isEmpty() && utf8(prompt) <= 1024 * 1024, "bounded prompt required");
                put(v, "prompt", prompt); put(v, "inputText", p.optString("inputText"));
                put(v, "inputRevision", p.optLong("inputRevision", -1));
                put(v, "stage", Stage.READY.name());
            }
            case CLAIM_SEND -> {
                if (stage != Stage.READY || before.flag("sendClaimed")) return before;
                put(v, "sendClaimed", true); put(v, "stage", Stage.DISPATCHING.name());
                put(v, "submittedAt", p.optLong("at"));
            }
            case ACCEPTED -> {
                if (!before.flag("sendClaimed")) return before;
                put(v, "accepted", true);
                setActiveStage(v, stage, before.flag("ended") ? Stage.RECONCILING : Stage.WAITING);
            }
            case ENDED -> {
                if (!before.flag("sendClaimed")) return before;
                String source = p.optString("source");
                require(Set.of("message_stream_complete", "finished_successfully_end_turn", "receipt_readback").contains(source), "untrusted completion evidence");
                put(v, "accepted", true); put(v, "ended", true); put(v, "endSource", source);
                setActiveStage(v, stage, Stage.RECONCILING);
            }
            case RESULT -> {
                if (!before.flag("sendClaimed")) return before;
                JSONObject result = parseResult(p.optString("text"), before);
                if (result == null) return before;
                String canonical = result.toString();
                if (before.hasResult() && !jsonEquivalent(new JSONObject(before.text("result")), result))
                    throw new IllegalStateException("committed result changed");
                put(v, "result", canonical);
                setActiveStage(v, stage, Stage.RECONCILING);
            }
            case COMMIT -> {
                require(stage != Stage.PAUSED && before.hasResult() && before.flag("ended"), "result and transport evidence required");
                JSONObject result = parseResult(before.text("result"), before);
                require(result != null, "committed result required");
                String status = result.optString("status");
                put(v, "previousResultDocumentId", before.resource("resultDocumentId"));
                put(v, "checkpoint", result.toString());
                put(v, "lastCommittedTurn", before.turn());
                if ("DONE".equals(status)) {
                    put(v, "stage", Stage.DONE.name()); put(v, "phase", "DONE");
                    return new State(v);
                }
                String nextId = p.optString("nextTurnId");
                require(validId(nextId) && !nextId.equals(before.turnId()), "fresh turn identity required");
                put(v, "turn", before.turn() + 1); put(v, "turnId", nextId);
                put(v, "phase", result.optString("next_phase"));
                put(v, "nextInput", result.optString("next_input"));
                JSONObject config = before.config(), profile = result.optJSONObject("profile");
                if (profile != null && "WORK".equals(config.optString("mode"))) {
                    put(config, "model", profile.optString("model"));
                    put(config, "reasoning", profile.optString("reasoning"));
                }
                put(v, "config", config);
                JSONObject resources = copy(v.optJSONObject("resources"));
                resources.remove("resultDocumentId"); resources.remove("resultCreateIntent");
                put(v, "resources", resources);
                for (String key : new String[]{"prompt", "result", "inputText", "inputRevision", "submittedAt", "endSource", "error"}) v.remove(key);
                put(v, "sendClaimed", false); put(v, "accepted", false); put(v, "ended", false);
                put(v, "reconcileCount", 0);
                boolean pause = "PAUSED".equals(status) || "USER_ACTION_REQUIRED".equals(status);
                put(v, "stage", pause ? Stage.PAUSED.name() : Stage.PREPARING.name());
                if (pause) { put(v, "resumeStage", Stage.PREPARING.name()); put(v, "pauseReason", result.optString("reason")); }
            }
            case RECONCILE -> {
                if (!before.flag("sendClaimed")) return before;
                setActiveStage(v, stage, Stage.RECONCILING);
                put(v, "reconcileCount", before.number("reconcileCount") + 1);
            }
            case PAUSE -> {
                if (stage != Stage.PAUSED) put(v, "resumeStage", stage.name());
                put(v, "stage", Stage.PAUSED.name()); put(v, "pauseReason", p.optString("reason"));
            }
            case RESUME -> {
                if (stage != Stage.PAUSED) return before;
                Stage resume = Stage.valueOf(before.text("resumeStage"));
                if (before.flag("sendClaimed")) resume = Stage.RECONCILING;
                put(v, "stage", resume.name()); v.remove("pauseReason"); v.remove("error");
                put(v, "reconcileCount", 0);
            }
            case STOP -> { put(v, "stage", Stage.STOPPED.name()); put(v, "pauseReason", "USER_STOP"); }
            case ERROR -> {
                String error = p.optString("code");
                require(error.matches("[A-Z0-9_:-]{1,100}"), "safe error code required");
                put(v, "error", error);
            }
        }
        return new State(v);
    }

    static Action nextAction(State s) {
        return switch (s.stage()) {
            case SETUP -> Action.SETUP;
            case PREPARING -> Action.PREPARE_TURN;
            case READY -> Action.PREPARE_WEB;
            case DISPATCHING, WAITING -> Action.WAIT;
            case RECONCILING -> s.hasResult() ? (s.flag("ended") ? Action.COMMIT : Action.CHECK_RECEIPT) : Action.READ_RESULT;
            default -> Action.NONE;
        };
    }

    /** Null means incomplete, never complete. Unknown required schema/identity always fails closed. */
    static JSONObject parseResult(String raw, State s) {
        if (raw == null || raw.trim().isEmpty()) return null;
        if (utf8(raw) > MAX_RESULT_BYTES) throw new IllegalArgumentException("result too large");
        JSONTokener tokener = new JSONTokener(raw.trim());
        Object parsed = tokener.nextValue();
        require(parsed instanceof JSONObject && tokener.nextClean() == 0, "single JSON object required");
        JSONObject r = (JSONObject) parsed;
        require(RESULT_SCHEMA.equals(r.optString("schema")), "result schema mismatch");
        require(s.taskId().equals(r.optString("task_id")) && s.turnId().equals(r.optString("turn_id"))
                && s.turn() == r.optInt("turn", -1), "result identity mismatch");
        require(s.resource("resultDocumentId").equals(r.optString("document_id")), "result document mismatch");
        require((s.turnId() + ":result").equals(r.optString("event_id")), "result event mismatch");
        Object committed = r.opt("committed");
        if (Boolean.FALSE.equals(committed)) return null;
        require(Boolean.TRUE.equals(committed), "boolean commit required");
        String status = r.optString("status"), completed = r.optString("phase_completed"), next = r.optString("next_phase");
        require(Set.of("CONTINUE", "DONE", "PAUSED", "USER_ACTION_REQUIRED").contains(status), "unknown result status");
        require(Set.of("PLAN", "WORK", "VERIFY", "DONE").contains(next), "unknown next phase");
        require(Set.of("PLAN", "PLAN_PARTIAL", "WORK", "WORK_PARTIAL", "VERIFY_PARTIAL", "VERIFY_WORK", "VERIFY_WORK_PARTIAL", "VERIFY_DONE").contains(completed), "unknown completed phase");
        if ("DONE".equals(status)) require("VERIFY_DONE".equals(completed) && "DONE".equals(next)
                && "VERIFY".equals(s.text("phase")), "independent verification required for DONE");
        else {
            require(!"DONE".equals(next) && !"VERIFY_DONE".equals(completed), "nonterminal result cannot claim DONE");
            if ("CONTINUE".equals(status)) {
                boolean pair = switch (completed) {
                    case "PLAN" -> "PLAN".equals(s.text("phase")) && "WORK".equals(next);
                    case "PLAN_PARTIAL" -> "PLAN".equals(s.text("phase")) && "PLAN".equals(next);
                    case "WORK" -> "WORK".equals(s.text("phase")) && "VERIFY".equals(next);
                    case "WORK_PARTIAL" -> "WORK".equals(s.text("phase")) && "WORK".equals(next);
                    case "VERIFY_PARTIAL" -> "VERIFY".equals(s.text("phase")) && ("WORK".equals(next) || "VERIFY".equals(next));
                    case "VERIFY_WORK" -> "VERIFY".equals(s.text("phase")) && "VERIFY".equals(next);
                    case "VERIFY_WORK_PARTIAL" -> "VERIFY".equals(s.text("phase")) && "WORK".equals(next);
                    default -> false;
                };
                require(pair, "invalid phase transition");
            }
        }
        JSONObject handoff = r.optJSONObject("handoff");
        require(handoff != null && !handoff.optString("objective").trim().isEmpty()
                && handoff.has("completed") && handoff.has("remaining") && handoff.has("evidence")
                && handoff.has("constraints") && handoff.has("next_action"), "complete handoff required");
        if (r.has("next_input")) require(r.opt("next_input") instanceof String && utf8(r.optString("next_input")) <= 64 * 1024, "bounded next input required");
        if ("WORK".equals(s.config().optString("mode")) && "CONTINUE".equals(status)) {
            JSONObject profile = r.optJSONObject("profile");
            require(profile != null && validProfileToken(profile.optString("model"))
                    && validProfileToken(profile.optString("reasoning")), "Work profile required");
        }
        return r;
    }

    static JSONObject emptyResult(State s) {
        JSONObject r = new JSONObject();
        put(r, "schema", RESULT_SCHEMA); put(r, "task_id", s.taskId()); put(r, "turn_id", s.turnId());
        put(r, "turn", s.turn()); put(r, "document_id", s.resource("resultDocumentId"));
        put(r, "event_id", s.turnId() + ":result"); put(r, "committed", false);
        return r;
    }
    static boolean validId(String v) { return v != null && v.matches("[A-Za-z0-9._:-]{1,256}"); }
    static boolean validProfileToken(String v) { return v != null && v.matches("[a-z0-9][a-z0-9._:-]{0,79}"); }
    static int utf8(String v) { return v.getBytes(StandardCharsets.UTF_8).length; }
    static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    static JSONObject copy(JSONObject v) { try { return v == null ? new JSONObject() : new JSONObject(v.toString()); } catch (Exception e) { throw new IllegalArgumentException("invalid JSON object", e); } }
    static void put(JSONObject v, String key, Object value) { try { v.put(key, value); } catch (Exception e) { throw new IllegalArgumentException("JSON value rejected", e); } }
    private static void setActiveStage(JSONObject v, Stage current, Stage target) { put(v, current == Stage.PAUSED ? "resumeStage" : "stage", target.name()); }
    private static boolean jsonEquivalent(JSONObject a, JSONObject b) { return a.toString().equals(b.toString()); }
    private SelfRun3Engine() {}
}
