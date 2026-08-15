package com.shaterguy.chatgptselfrun;

/** Safe Android-facing classification for parent-guard states. Never propagates page/network text. */
final class SelfRunParentGuardPolicy {
    private SelfRunParentGuardPolicy() {}

    static String safeFailureCode(String value) {
        String code = value == null ? "" : value.trim();
        if (code.matches("CANONICAL_HTTP_[1-5][0-9]{2}")) return code;
        return switch (code) {
            case "HOOK_UNAVAILABLE", "HOOK_LOST", "ARM_STORAGE_FAILED", "NO_POST_AFTER_CLICK",
                    "ENDPOINT_MISMATCH", "PAYLOAD_MISMATCH", "PARENT_ID_MISSING",
                    "BODY_UNREADABLE", "CANONICAL_MISSING", "CANONICAL_GENERATING",
                    "HANDSHAKE_TIMEOUT", "FORWARD_FAILED", "COMPOSER_TIMEOUT",
                    "GUARD_INTERNAL_FAILURE" -> code;
            default -> "GUARD_INTERNAL_FAILURE";
        };
    }

    static String errorCode(String value) {
        String code = safeFailureCode(value);
        return switch (code) {
            case "HOOK_UNAVAILABLE" -> "PARENT_GUARD_HOOK_UNAVAILABLE";
            case "HOOK_LOST" -> "PARENT_GUARD_HOOK_LOST";
            case "ARM_STORAGE_FAILED" -> "PARENT_GUARD_ARM_STORAGE_FAILED";
            case "NO_POST_AFTER_CLICK" -> "PARENT_GUARD_NO_POST";
            case "ENDPOINT_MISMATCH" -> "PARENT_GUARD_ENDPOINT_MISMATCH";
            case "PAYLOAD_MISMATCH", "PARENT_ID_MISSING", "BODY_UNREADABLE" -> "PARENT_GUARD_PAYLOAD_INVALID";
            case "CANONICAL_MISSING", "CANONICAL_GENERATING" -> "PARENT_GUARD_CANONICAL_INVALID";
            case "HANDSHAKE_TIMEOUT" -> "PARENT_GUARD_TIMEOUT";
            case "FORWARD_FAILED" -> "PARENT_GUARD_FORWARD_FAILED";
            case "COMPOSER_TIMEOUT" -> "CONTINUE_COMPOSER_TIMEOUT";
            default -> code.startsWith("CANONICAL_HTTP_")
                    ? "PARENT_GUARD_CANONICAL_LOOKUP_FAILED" : "PARENT_GUARD_INTERNAL_FAILURE";
        };
    }

    static String message(String value) {
        String code = safeFailureCode(value);
        return switch (code) {
            case "HOOK_UNAVAILABLE" -> "CONTINUE parent guard를 사용할 수 없어 안전을 위해 제출하지 않았습니다.";
            case "HOOK_LOST" -> "CONTINUE parent guard 연결이 유지되지 않아 안전을 위해 제출을 중단했습니다.";
            case "ARM_STORAGE_FAILED" -> "CONTINUE parent guard 시도 상태를 안전하게 저장하지 못해 제출을 중단했습니다.";
            case "NO_POST_AFTER_CLICK" -> "CONTINUE 클릭 후 보호 대상 요청이 관찰되지 않아 안전을 위해 제출 확인을 중단했습니다.";
            case "ENDPOINT_MISMATCH" -> "CONTINUE 요청 경로가 예상과 달라 안전을 위해 전송을 차단했습니다.";
            case "PAYLOAD_MISMATCH", "PARENT_ID_MISSING", "BODY_UNREADABLE" -> "CONTINUE 요청 형식이 예상과 달라 안전을 위해 전송을 차단했습니다.";
            case "CANONICAL_MISSING", "CANONICAL_GENERATING" -> "최신 canonical parent를 안전하게 확정하지 못해 CONTINUE를 전송하지 않았습니다.";
            case "HANDSHAKE_TIMEOUT" -> "canonical parent 확인이 제출 제한시간 안에 끝나지 않아 CONTINUE를 전송하지 않았습니다.";
            case "FORWARD_FAILED" -> "canonical parent 적용 후 CONTINUE forwarding을 확인하지 못했습니다.";
            case "COMPOSER_TIMEOUT" -> "CONTINUE 입력창 또는 전송 준비가 제한시간 안에 완료되지 않아 안전하게 중단했습니다.";
            default -> code.startsWith("CANONICAL_HTTP_")
                    ? "canonical parent 조회에 실패하여 CONTINUE를 전송하지 않았습니다."
                    : "CONTINUE parent guard 내부 상태를 안전하게 확인하지 못해 제출을 중단했습니다.";
        };
    }

    static String safeStage(String value) {
        String stage = value == null ? "" : value.trim();
        return switch (stage) {
            case "COMPOSER_WAIT", "COMPOSER_INPUT_WAIT", "SEND_BUTTON_WAIT", "READY_TO_SUBMIT",
                    "GUARD_ARMED", "POST_INTERCEPTION_WAIT", "POST_INTERCEPTED", "PAYLOAD_MATCHED",
                    "CANONICAL_FETCH_START", "CANONICAL_FETCH_OK", "PARENT_REWRITTEN",
                    "FORWARDING", "SUBMISSION_CONFIRMED" -> stage;
            default -> "HANDSHAKE_WAIT";
        };
    }

    static String waitMessage(String value) {
        return switch (safeStage(value)) {
            case "COMPOSER_WAIT" -> "CONTINUE 입력창 대기";
            case "COMPOSER_INPUT_WAIT" -> "CONTINUE 입력 반영 대기";
            case "SEND_BUTTON_WAIT" -> "CONTINUE 전송 버튼 대기";
            case "READY_TO_SUBMIT" -> "CONTINUE 제출 준비 완료";
            case "GUARD_ARMED" -> "parent guard arm 완료 · POST 관찰 대기";
            case "POST_INTERCEPTION_WAIT" -> "CONTINUE conversation POST 관찰 대기";
            case "POST_INTERCEPTED" -> "CONTINUE conversation POST 관찰 완료";
            case "PAYLOAD_MATCHED" -> "CONTINUE payload 확인 완료";
            case "CANONICAL_FETCH_START" -> "canonical conversation 조회 중";
            case "CANONICAL_FETCH_OK" -> "canonical current_node 확인 완료";
            case "PARENT_REWRITTEN" -> "canonical parent 적용 완료";
            case "FORWARDING" -> "CONTINUE forwarding 중";
            case "SUBMISSION_CONFIRMED" -> "CONTINUE 제출 확인 완료";
            default -> "CONTINUE 제출 handshake 대기";
        };
    }
}
