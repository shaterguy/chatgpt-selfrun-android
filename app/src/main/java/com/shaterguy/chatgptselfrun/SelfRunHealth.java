package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;

final class SelfRunHealthSnapshot {
    static final String NORMAL = "NORMAL";
    static final String WAITING = "WAITING";
    static final String RECOVERING = "RECOVERING";
    static final String ATTENTION = "ATTENTION";
    static final String ERROR = "ERROR";
    static final String TERMINAL = "TERMINAL";

    static final String CONFIRMED = "CONFIRMED";
    static final String LIKELY = "LIKELY";
    static final String UNKNOWN = "UNKNOWN";

    final String level;
    final String category;
    final String title;
    final String description;
    final String source;
    final String confidence;
    final String recommendedAction;
    final long observedAt;
    final String internalReason;
    final String phase;

    SelfRunHealthSnapshot(String level, String category, String title, String description,
                          String source, String confidence, String recommendedAction,
                          long observedAt, String internalReason, String phase) {
        this.level = safe(level);
        this.category = safe(category);
        this.title = safe(title);
        this.description = safe(description);
        this.source = safe(source);
        this.confidence = safe(confidence);
        this.recommendedAction = safe(recommendedAction);
        this.observedAt = Math.max(0L, observedAt);
        this.internalReason = safe(internalReason);
        this.phase = safe(phase);
    }

    static SelfRunHealthSnapshot fallback(long observedAt) {
        return new SelfRunHealthSnapshot(ATTENTION, "UNKNOWN", "상태 확인 중",
                "진단 정보를 확인할 수 없습니다.", "FALLBACK", UNKNOWN,
                "Run 상세 확인", observedAt, "", "");
    }

    SelfRunHealthSnapshot withObservedAt(long value) {
        return new SelfRunHealthSnapshot(level, category, title, description, source, confidence,
                recommendedAction, value, internalReason, phase);
    }

    boolean sameState(SelfRunHealthSnapshot other) {
        return other != null && level.equals(other.level) && category.equals(other.category)
                && title.equals(other.title) && description.equals(other.description)
                && source.equals(other.source) && confidence.equals(other.confidence)
                && recommendedAction.equals(other.recommendedAction)
                && internalReason.equals(other.internalReason) && phase.equals(other.phase);
    }

    boolean important() {
        return ERROR.equals(level) || ATTENTION.equals(level) || RECOVERING.equals(level)
                || TERMINAL.equals(level);
    }

    boolean terminal() { return TERMINAL.equals(level); }

    String rowLabel() {
        String prefix = switch (level) {
            case NORMAL -> "RUNNING";
            case WAITING -> "WAITING";
            case RECOVERING -> "RECOVERING";
            case ATTENTION -> "ATTENTION";
            case ERROR -> "ERROR";
            case TERMINAL -> "TERMINAL";
            default -> "STATE";
        };
        return prefix + " · " + title;
    }

    JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("level", level);
            out.put("category", category);
            out.put("title", title);
            out.put("description", description);
            out.put("source", source);
            out.put("confidence", confidence);
            out.put("recommendedAction", recommendedAction);
            out.put("observedAt", observedAt);
            out.put("internalReason", internalReason);
            out.put("phase", phase);
        } catch (Exception ignored) { }
        return out;
    }

    static SelfRunHealthSnapshot fromJson(JSONObject value) {
        if (value == null) return null;
        String level = value.optString("level");
        String category = value.optString("category");
        if (level.isEmpty() || category.isEmpty()) return null;
        return new SelfRunHealthSnapshot(level, category, value.optString("title"),
                value.optString("description"), value.optString("source"),
                value.optString("confidence"), value.optString("recommendedAction"),
                value.optLong("observedAt"), value.optString("internalReason"),
                value.optString("phase"));
    }

    private static String safe(String value) { return value == null ? "" : value; }
}

final class SelfRunHealthInput {
    String runId = "";
    String phase = "";
    String status = "";
    String mode = "";
    String lastErrorCode = "";
    String driveSignalType = "";
    long createdAt;
    long phaseStartedAt;
    long updatedAt;
    int turn;
    boolean active;
    boolean paused;
    boolean userStopped;
    boolean terminal;
    boolean retryPending;
    String webReason = "";
    String webPhase = "";
    long webObservedAt;
    boolean networkKnown;
    boolean networkValidated;
    long networkObservedAt;
    String processCategory = "";
    String processReason = "";
    long processObservedAt;

    static SelfRunHealthInput fromStore(SelfRunStore store, JSONObject observation) {
        SelfRunHealthInput in = new SelfRunHealthInput();
        in.runId = safe(store.runId());
        in.phase = safe(store.phase());
        in.status = safe(store.status());
        in.mode = safe(store.mode());
        in.lastErrorCode = safe(store.lastErrorCode());
        in.driveSignalType = latestSignal(store.pendingDriveSignalType(), store.lastDriveSignalType());
        in.createdAt = store.createdAt();
        in.phaseStartedAt = store.phaseStartedAt();
        in.turn = store.turn();
        in.active = store.active();
        in.paused = store.paused();
        in.userStopped = store.userStopped();
        in.terminal = SelfRunStore.PHASE_DONE.equals(in.phase) || in.userStopped;
        in.retryPending = !safe(store.submissionRetryKind()).isEmpty();
        applyObservation(in, observation);
        return in;
    }

    static SelfRunHealthInput fromHistory(JSONObject item, JSONObject observation) {
        SelfRunHealthInput in = new SelfRunHealthInput();
        if (item == null) return in;
        in.runId = item.optString("runId");
        in.phase = item.optString("phase");
        in.status = item.optString("status");
        in.mode = item.optString("mode");
        in.lastErrorCode = item.optString("lastErrorCode");
        in.driveSignalType = latestSignal(item.optString("pendingDriveSignalType"), item.optString("lastDriveSignalType"));
        in.createdAt = item.optLong("createdAt");
        in.phaseStartedAt = item.optLong("phaseStartedAt", in.createdAt);
        in.updatedAt = item.optLong("updatedAt");
        in.turn = item.optInt("turn");
        in.active = item.optBoolean("active");
        in.paused = item.optBoolean("paused");
        in.userStopped = item.optBoolean("userStopped");
        in.terminal = item.optBoolean("terminal") || SelfRunStore.PHASE_DONE.equals(in.phase) || in.userStopped;
        in.retryPending = !item.optString("submissionRetryKind").isEmpty();
        applyObservation(in, observation);
        return in;
    }

    private static void applyObservation(SelfRunHealthInput in, JSONObject observation) {
        if (observation == null) return;
        in.webReason = observation.optString("webReason");
        in.webPhase = observation.optString("webPhase");
        in.webObservedAt = observation.optLong("webObservedAt");
        in.networkKnown = observation.optBoolean("networkKnown", false);
        in.networkValidated = observation.optBoolean("networkValidated", false);
        in.networkObservedAt = observation.optLong("networkObservedAt");
        in.processCategory = observation.optString("processCategory");
        in.processReason = observation.optString("processReason");
        in.processObservedAt = observation.optLong("processObservedAt");
    }

    private static String latestSignal(String pending, String last) {
        String p = safe(pending);
        return p.isEmpty() ? safe(last) : p;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}

final class SelfRunHealthEvaluator {
    private SelfRunHealthEvaluator() { }

    static SelfRunHealthSnapshot evaluate(SelfRunHealthInput in, long now) {
        try { return evaluateUnsafe(in == null ? new SelfRunHealthInput() : in, Math.max(0L, now)); }
        catch (Throwable ignored) { return SelfRunHealthSnapshot.fallback(now); }
    }

    private static SelfRunHealthSnapshot evaluateUnsafe(SelfRunHealthInput in, long now) {
        long phaseAt = in.phaseStartedAt > 0L ? in.phaseStartedAt : (in.createdAt > 0L ? in.createdAt : now);
        if (SelfRunStore.PHASE_DONE.equals(in.phase) || "DONE".equals(in.driveSignalType)) {
            return snap(SelfRunHealthSnapshot.TERMINAL, "DONE", "SelfRun 완료",
                    "SelfRun 작업이 완료되었습니다.", "SELFRUN_STORE", SelfRunHealthSnapshot.CONFIRMED,
                    "아무 작업 필요 없음", phaseAt, "", in.phase);
        }
        if (in.paused && "USER_ACTION_REQUIRED".equals(in.driveSignalType)) {
            return snap(SelfRunHealthSnapshot.ATTENTION, "USER_ACTION_REQUIRED", "사용자 조치가 필요합니다.",
                    "현재 Run이 사용자 조치를 기다리고 있습니다.", "DRIVE", SelfRunHealthSnapshot.CONFIRMED,
                    "Run 상세 확인", phaseAt, "user_action_required", in.phase);
        }
        if (in.paused) {
            return snap(SelfRunHealthSnapshot.TERMINAL, "PAUSED", "SelfRun이 일시정지되었습니다.",
                    "현재 Run의 자동 실행이 일시정지되어 있습니다.", "SELFRUN_STORE", SelfRunHealthSnapshot.CONFIRMED,
                    "Run 상세 확인", phaseAt, "paused", in.phase);
        }
        if (in.userStopped) {
            return snap(SelfRunHealthSnapshot.TERMINAL, "STOPPED", "SelfRun이 중지되었습니다.",
                    "사용자 중지로 실행이 종료되었습니다.", "SELFRUN_STORE", SelfRunHealthSnapshot.CONFIRMED,
                    "중지 작업 재시작", phaseAt, "user_stopped", in.phase);
        }

        if (explicitFatal(in)) {
            String code = upper(in.lastErrorCode);
            if (code.contains("SCRIPT_ERROR")) return protocolError("SCRIPT_ERROR", "ChatGPT 화면 제어 오류",
                    "현재 ChatGPT 화면 구조를 정상적으로 판독하지 못했습니다.", "디버그 로그 열기", phaseAt, "script_error", in.phase);
            if (code.contains("SUBMISSION") || code.contains("REQUEST_PROFILE") || code.contains("REJECTED")) {
                return protocolError("SUBMISSION_FAILED", "요청 전송 실패",
                        code.contains("REQUEST_PROFILE") ? "모델·추론 설정을 적용하지 못했습니다." : "ChatGPT 요청 전송을 완료하지 못했습니다.",
                        "Run 상세 확인", phaseAt, "submission_failed", in.phase);
            }
            return snap(SelfRunHealthSnapshot.ERROR, "ERROR", "SelfRun 실행 오류",
                    "현재 Run에서 명시적인 실행 오류가 확인되었습니다.", "SELFRUN_STORE", SelfRunHealthSnapshot.CONFIRMED,
                    "Run 상세 확인", phaseAt, safeCode(code), in.phase);
        }

        if (freshProcessExit(in)) return processExit(in);

        if (freshWeb(in) && isRouteMismatch(in.webReason)) {
            return snap(in.active ? SelfRunHealthSnapshot.RECOVERING : SelfRunHealthSnapshot.ERROR,
                    "ROUTE_MISMATCH", "ChatGPT 대화 위치 불일치",
                    in.active ? "현재 실행 대상을 다시 확인하고 있습니다." : "현재 ChatGPT 대화 위치가 실행 대상과 일치하지 않습니다.",
                    "WEB_DIAGNOSTICS", SelfRunHealthSnapshot.LIKELY,
                    in.active ? "자동 복구 중" : "앱 열기", in.webObservedAt, in.webReason, in.phase);
        }
        if (freshWeb(in) && "script_error".equals(in.webReason)) {
            return protocolError("SCRIPT_ERROR", "ChatGPT 화면 제어 오류",
                    "현재 ChatGPT 화면 구조를 정상적으로 판독하지 못했습니다.", "디버그 로그 열기",
                    in.webObservedAt, in.webReason, in.phase);
        }
        if (freshWeb(in) && ("submission_failed".equals(in.webReason) || "request_profile_rejected".equals(in.webReason))) {
            return protocolError("SUBMISSION_FAILED", "요청 전송 실패",
                    "request_profile_rejected".equals(in.webReason) ? "모델·추론 설정을 적용하지 못했습니다." : "ChatGPT 요청 전송을 완료하지 못했습니다.",
                    "Run 상세 확인", in.webObservedAt, in.webReason, in.phase);
        }

        if (in.networkKnown && !in.networkValidated && in.active) {
            return snap(SelfRunHealthSnapshot.RECOVERING, "NETWORK_OFFLINE", "네트워크 연결 대기",
                    "네트워크가 복구되면 SelfRun이 자동으로 계속됩니다.", "NETWORK", SelfRunHealthSnapshot.CONFIRMED,
                    "네트워크 확인", in.networkObservedAt > 0L ? in.networkObservedAt : now, "network_offline", in.phase);
        }
        if (in.networkKnown && !in.networkValidated) {
            return snap(SelfRunHealthSnapshot.ATTENTION, "NETWORK_OFFLINE", "네트워크 연결 대기",
                    "현재 네트워크 연결을 확인할 수 없습니다.", "NETWORK", SelfRunHealthSnapshot.CONFIRMED,
                    "네트워크 확인", in.networkObservedAt > 0L ? in.networkObservedAt : now, "network_offline", in.phase);
        }

        if (recovering(in)) {
            return snap(SelfRunHealthSnapshot.RECOVERING, "RECOVERING", "자동 복구 중",
                    recoveryDescription(in), "SELFRUN_STORE", SelfRunHealthSnapshot.CONFIRMED,
                    "자동 복구 중", phaseAt, recoveryReason(in), in.phase);
        }

        if (freshWeb(in)) {
            SelfRunHealthSnapshot web = waitFromWeb(in);
            if (web != null) return web;
        }

        SelfRunHealthSnapshot phase = waitFromPhase(in, phaseAt);
        if (phase != null) return phase;

        if (in.active) {
            return snap(SelfRunHealthSnapshot.NORMAL, "NORMAL", "정상 실행 중",
                    "SelfRun이 정상적으로 작업을 수행하고 있습니다.", "SELFRUN_STORE", SelfRunHealthSnapshot.CONFIRMED,
                    "아무 작업 필요 없음", phaseAt, "", in.phase);
        }
        if (in.terminal) {
            return snap(SelfRunHealthSnapshot.TERMINAL, "STOPPED", "SelfRun 실행 종료",
                    "현재 Run은 더 이상 실행 중이 아닙니다.", "SELFRUN_STORE", SelfRunHealthSnapshot.CONFIRMED,
                    "Run 상세 확인", in.updatedAt > 0L ? in.updatedAt : phaseAt, "terminal", in.phase);
        }
        return SelfRunHealthSnapshot.fallback(now);
    }

    private static SelfRunHealthSnapshot waitFromWeb(SelfRunHealthInput in) {
        String r = in.webReason;
        long at = in.webObservedAt;
        if ("model_wait".equals(r)) return waiting("WAITING_MODEL", "모델 설정 적용 중", "선택한 모델 설정이 적용되기를 기다리고 있습니다.", at, r, in.phase);
        if ("reasoning_wait".equals(r)) return waiting("WAITING_REASONING", "추론 설정 적용 중", "선택한 추론 설정이 적용되기를 기다리고 있습니다.", at, r, in.phase);
        if ("composer_wait".equals(r) || "input_wait".equals(r) || "composer_clearing".equals(r)
                || "composer_inputting".equals(r) || "input_reflection_wait".equals(r)) {
            return waiting("WAITING_COMPOSER", "입력창 준비 대기", "ChatGPT 입력창이 다음 작업을 받을 준비가 되기를 기다리고 있습니다.", at, r, in.phase);
        }
        if ("send_wait".equals(r) || "send_disabled".equals(r) || "submission_pending".equals(r)) {
            return waiting("WAITING_SEND", "전송 준비 대기", "ChatGPT 요청을 안전하게 전송할 수 있는 상태를 기다리고 있습니다.", at, r, in.phase);
        }
        if ("stop_visible".equals(r) || ("state_wait".equals(r) && SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(in.phase))) {
            return waiting("WAITING_CHATGPT", "ChatGPT 응답 대기", "요청 전송은 완료되었으며 현재 ChatGPT 응답을 기다리고 있습니다.", at, r, in.phase);
        }
        if ("evaluate_javascript".equals(r)) {
            return snap(SelfRunHealthSnapshot.RECOVERING, "RECOVERING", "자동 복구 중",
                    "ChatGPT 화면 상태를 다시 확인하고 있습니다.", "WEB_DIAGNOSTICS", SelfRunHealthSnapshot.LIKELY,
                    "자동 복구 중", at, r, in.phase);
        }
        if ("ui_wait".equals(r) || "control_unknown".equals(r) || "state_wait".equals(r)) {
            return waiting("WAITING_COMPOSER", "ChatGPT 화면 준비 대기", "현재 ChatGPT 화면 상태가 안정되기를 기다리고 있습니다.", at, r, in.phase);
        }
        return null;
    }

    private static SelfRunHealthSnapshot waitFromPhase(SelfRunHealthInput in, long at) {
        String p = in.phase;
        if (SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(p)) return waiting("WAITING_CHATGPT", "ChatGPT 응답 대기", "요청 전송은 완료되었으며 현재 ChatGPT 응답을 기다리고 있습니다.", at, "", p);
        if (SelfRunStore.PHASE_APPLY_PREFS.equals(p) || SelfRunStore.PHASE_BOOTSTRAP_MODEL.equals(p)) return waiting("WAITING_MODEL", "모델 설정 적용 중", "선택한 모델 설정이 적용되기를 기다리고 있습니다.", at, "", p);
        if (SelfRunStore.PHASE_APPLY_REASONING.equals(p) || SelfRunStore.PHASE_BOOTSTRAP_REASONING.equals(p)) return waiting("WAITING_REASONING", "추론 설정 적용 중", "선택한 추론 설정이 적용되기를 기다리고 있습니다.", at, "", p);
        if (SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC.equals(p) || SelfRunStore.PHASE_RESUME_BASELINE.equals(p)) {
            return snap(SelfRunHealthSnapshot.WAITING, "WAITING_DRIVE", "Drive 응답 대기",
                    "SelfRun 실행 신호가 Google Drive에 반영되기를 기다리고 있습니다.", "DRIVE", SelfRunHealthSnapshot.CONFIRMED,
                    "아무 작업 필요 없음", at, "", p);
        }
        if (SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(p)) {
            return snap(SelfRunHealthSnapshot.NORMAL, "NORMAL", "ChatGPT에 작업 전송 중",
                    "SelfRun이 첫 작업을 ChatGPT에 전송하고 있습니다.", "SELFRUN_STORE", SelfRunHealthSnapshot.CONFIRMED,
                    "아무 작업 필요 없음", at, "", p);
        }
        if (SelfRunStore.PHASE_SEND_CONTINUE.equals(p)) {
            return snap(SelfRunHealthSnapshot.NORMAL, "NORMAL", "다음 턴 전송 준비 중",
                    "SelfRun이 다음 요청 전송을 준비하고 있습니다.", "SELFRUN_STORE", SelfRunHealthSnapshot.CONFIRMED,
                    "아무 작업 필요 없음", at, "", p);
        }
        if (drivePreparationPhase(p)) {
            return snap(SelfRunHealthSnapshot.NORMAL, "NORMAL", "SelfRun 준비 중",
                    "SelfRun 실행에 필요한 Drive 상태를 준비하고 있습니다.", "DRIVE", SelfRunHealthSnapshot.CONFIRMED,
                    "아무 작업 필요 없음", at, "", p);
        }
        return null;
    }

    private static boolean drivePreparationPhase(String phase) {
        return SelfRunStore.PHASE_DRIVE_ACCOUNT_CHECK.equals(phase)
                || SelfRunStore.PHASE_DRIVE_BASE_FOLDER_CHECK.equals(phase)
                || SelfRunStore.PHASE_JOB_ID_CREATE.equals(phase)
                || SelfRunStore.PHASE_DRIVE_JOB_FOLDER_CREATE.equals(phase)
                || SelfRunStore.PHASE_DRIVE_ATTACHMENT_UPLOAD.equals(phase)
                || SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE.equals(phase)
                || SelfRunStore.PHASE_DRIVE_DOCUMENT_INIT.equals(phase)
                || SelfRunStore.PHASE_DRIVE_DOCUMENT_READBACK.equals(phase)
                || SelfRunStore.PHASE_BOOTSTRAP.equals(phase);
    }

    private static boolean freshWeb(SelfRunHealthInput in) {
        if (in.webReason.isEmpty() || in.webObservedAt <= 0L) return false;
        if (in.phaseStartedAt > 0L && in.webObservedAt < in.phaseStartedAt) return false;
        String currentKind = SelfRunWebDiagnostics.phaseKindForHealth(in.phase);
        return in.webPhase.isEmpty() || "other".equals(in.webPhase) || in.webPhase.equals(currentKind);
    }

    private static boolean freshProcessExit(SelfRunHealthInput in) {
        if (in.processCategory.isEmpty() || in.processObservedAt <= 0L) return false;
        if (in.createdAt > 0L && in.processObservedAt < in.createdAt) return false;
        if (in.phaseStartedAt > 0L && in.processObservedAt < in.phaseStartedAt) return false;
        return in.updatedAt <= 0L || in.updatedAt <= in.processObservedAt;
    }

    private static SelfRunHealthSnapshot processExit(SelfRunHealthInput in) {
        String category = in.processCategory;
        String title;
        String description;
        String action = "앱 열기";
        String level = SelfRunHealthSnapshot.ERROR;
        if ("APP_CRASH".equals(category)) {
            title = "SelfRun 앱이 비정상 종료되었습니다.";
            description = "Android가 SelfRun 프로세스의 crash 종료를 기록했습니다.";
        } else if ("APP_ANR".equals(category)) {
            title = "SelfRun 앱이 응답하지 않아 종료되었습니다.";
            description = "Android가 ANR 종료를 기록했습니다.";
        } else if ("LOW_MEMORY".equals(category)) {
            title = "메모리 부족으로 SelfRun이 종료되었습니다.";
            description = "Android가 low-memory 종료를 기록했습니다.";
        } else if ("EXCESSIVE_RESOURCE".equals(category)) {
            title = "시스템이 과도한 리소스 사용으로 SelfRun을 종료했습니다.";
            description = "Android가 excessive-resource 종료를 기록했습니다.";
        } else {
            level = SelfRunHealthSnapshot.ATTENTION;
            title = "SelfRun 프로세스 종료 이력 확인";
            description = processDescription(in.processReason);
            action = "Run 상세 확인";
        }
        return snap(level, category, title, description, "ANDROID_EXIT_INFO", SelfRunHealthSnapshot.CONFIRMED,
                action, in.processObservedAt, in.processReason, in.phase);
    }

    private static String processDescription(String reason) {
        return switch (reason) {
            case "dependency_died" -> "Android가 의존 프로세스 종료로 SelfRun 프로세스를 종료한 이력을 기록했습니다.";
            case "package_updated" -> "Android가 앱 업데이트로 SelfRun 프로세스를 종료한 이력을 기록했습니다.";
            case "permission_change" -> "Android가 권한 변경으로 SelfRun 프로세스를 종료한 이력을 기록했습니다.";
            case "user_requested_or_update" -> "Android가 종료 요청 계열 이력을 기록했습니다. Android 버전 특성상 앱 업데이트와 사용자 요청을 구분하지 않습니다.";
            default -> "Android가 SelfRun 프로세스 종료 이력을 기록했지만 구체 원인을 임의로 추정하지 않습니다.";
        };
    }

    private static boolean explicitFatal(SelfRunHealthInput in) {
        String code = upper(in.lastErrorCode);
        if (code.isEmpty() || recoveryCode(code)) return false;
        String status = upper(in.status);
        return status.contains("오류") || status.contains("실패") || status.contains("ERROR") || status.contains("FAILED")
                || status.contains("확인 필요") || code.contains("SCRIPT_ERROR") || code.contains("SUBMISSION_FAILED")
                || code.contains("REQUEST_PROFILE_REJECTED");
    }

    private static boolean recovering(SelfRunHealthInput in) {
        return in.retryPending || recoveryCode(upper(in.lastErrorCode))
                || in.status.contains("복구") || in.status.contains("재시도") || in.status.contains("다시 확인");
    }

    private static boolean recoveryCode(String code) {
        return code.contains("RETRY") || code.contains("TRANSIENT") || code.contains("RECOVER") || code.contains("ROLLOVER");
    }

    private static String recoveryReason(SelfRunHealthInput in) {
        if (upper(in.lastErrorCode).contains("NETWORK")) return "network_recovery";
        if (SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC.equals(in.phase) || SelfRunStore.PHASE_RESUME_BASELINE.equals(in.phase)) return "drive_recovery";
        return "automatic_recovery";
    }

    private static String recoveryDescription(SelfRunHealthInput in) {
        if (upper(in.lastErrorCode).contains("NETWORK")) return "네트워크 연결을 기다리고 있습니다.";
        if (SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC.equals(in.phase) || SelfRunStore.PHASE_RESUME_BASELINE.equals(in.phase)) return "Drive signal을 다시 확인하고 있습니다.";
        return "ChatGPT 화면과 실행 상태를 다시 확인하고 있습니다.";
    }

    private static boolean isRouteMismatch(String reason) {
        return "host_mismatch".equals(reason) || "project_mismatch".equals(reason)
                || "conversation_mismatch".equals(reason) || "general_target_mismatch".equals(reason)
                || "route_mismatch".equals(reason);
    }

    private static SelfRunHealthSnapshot waiting(String category, String title, String description,
                                                 long at, String reason, String phase) {
        return snap(SelfRunHealthSnapshot.WAITING, category, title, description, "WEB_DIAGNOSTICS",
                SelfRunHealthSnapshot.CONFIRMED, "아무 작업 필요 없음", at, reason, phase);
    }

    private static SelfRunHealthSnapshot protocolError(String category, String title, String description,
                                                       String action, long at, String reason, String phase) {
        return snap(SelfRunHealthSnapshot.ERROR, category, title, description, "WEB_DIAGNOSTICS",
                SelfRunHealthSnapshot.CONFIRMED, action, at, reason, phase);
    }

    private static SelfRunHealthSnapshot snap(String level, String category, String title, String description,
                                              String source, String confidence, String action, long at,
                                              String reason, String phase) {
        return new SelfRunHealthSnapshot(level, category, title, description, source, confidence, action,
                at, reason, phase);
    }

    private static String safeCode(String code) {
        String value = upper(code).replaceAll("[^A-Z0-9_]", "_");
        return value.length() <= 64 ? value.toLowerCase() : value.substring(0, 64).toLowerCase();
    }

    private static String upper(String value) { return value == null ? "" : value.toUpperCase(java.util.Locale.ROOT); }
}
