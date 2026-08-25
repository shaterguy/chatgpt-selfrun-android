package com.shaterguy.chatgptselfrun;

/** Exact-roundtrip policy for the immutable original requirement document. */
final class SelfRunOriginalRequirement {
    static final int MAX_DOCUMENT_CHARS = 1_000_000;
    static final int MAX_REQUIREMENT_CHARS = MAX_DOCUMENT_CHARS - 1;

    private SelfRunOriginalRequirement() {}

    static String validationError(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "작업 요구사항을 입력하세요.";
        if (raw.length() > MAX_REQUIREMENT_CHARS) {
            return "작업 요구사항이 너무 깁니다. 최대 " + MAX_REQUIREMENT_CHARS + " UTF-16 문자까지 사용할 수 있습니다.";
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= raw.length() || !Character.isLowSurrogate(raw.charAt(i + 1))) {
                    return "작업 요구사항에 올바르지 않은 UTF-16 문자가 포함되어 있습니다.";
                }
                i++;
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                return "작업 요구사항에 올바르지 않은 UTF-16 문자가 포함되어 있습니다.";
            }
            if ((c >= 0x0000 && c <= 0x0008) || (c >= 0x000C && c <= 0x001F)
                    || (c >= 0xE000 && c <= 0xF8FF)) {
                return "Google Docs에서 원문 그대로 보존할 수 없는 문자가 작업 요구사항에 포함되어 있습니다.";
            }
        }
        return "";
    }

    static boolean valid(String raw) {
        return validationError(raw).isEmpty();
    }

    /** Google Docs paragraphs always contribute one structural terminal newline. */
    static String logicalDocumentText(String documentText) {
        String value = documentText == null ? "" : documentText;
        return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
    }

    static boolean exactDocumentMatch(String documentText, String rawRequirement) {
        return rawRequirement != null && rawRequirement.equals(logicalDocumentText(documentText));
    }
}
