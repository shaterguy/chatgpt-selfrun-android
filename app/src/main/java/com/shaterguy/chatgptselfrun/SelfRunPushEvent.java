package com.shaterguy.chatgptselfrun;

import java.util.Map;
import java.util.Objects;

/** Strict FCM data envelope used only to wake the exact waiting SelfRun execution. */
final class SelfRunPushEvent {
    static final String SCHEMA = "selfrun-push-v1";
    static final String TYPE_RESULT_CHANGED = "RESULT_CHANGED";
    private static final int MAX_FIELD = 256;

    final String eventId;
    final String installationId;
    final String applicationId;
    final String taskId;
    final String turnId;
    final String resultDocumentId;

    private SelfRunPushEvent(String eventId, String installationId, String applicationId,
                             String taskId, String turnId, String resultDocumentId) {
        this.eventId = eventId;
        this.installationId = installationId;
        this.applicationId = applicationId;
        this.taskId = taskId;
        this.turnId = turnId;
        this.resultDocumentId = resultDocumentId;
    }

    static SelfRunPushEvent parse(Map<String, String> data, String expectedApplicationId) {
        if (data == null) throw new IllegalArgumentException("push data required");
        String schema = required(data, "schema");
        String type = required(data, "type");
        if (!SCHEMA.equals(schema)) throw new IllegalArgumentException("push schema mismatch");
        if (!TYPE_RESULT_CHANGED.equals(type)) throw new IllegalArgumentException("push type mismatch");

        String applicationId = required(data, "applicationId");
        if (expectedApplicationId == null || !applicationId.equals(expectedApplicationId)) {
            throw new IllegalArgumentException("application identity mismatch");
        }
        String eventId = required(data, "eventId");
        String installationId = required(data, "installationId");
        String taskId = required(data, "taskId");
        String turnId = required(data, "turnId");
        String resultDocumentId = required(data, "resultDocumentId");
        return new SelfRunPushEvent(eventId, installationId, applicationId,
                taskId, turnId, resultDocumentId);
    }

    boolean matches(String expectedInstallationId, String expectedTaskId,
                    String expectedTurnId, String expectedResultDocumentId) {
        return Objects.equals(installationId, expectedInstallationId)
                && Objects.equals(taskId, expectedTaskId)
                && Objects.equals(turnId, expectedTurnId)
                && Objects.equals(resultDocumentId, expectedResultDocumentId);
    }

    private static String required(Map<String, String> data, String key) {
        String value = data.get(key);
        if (value == null) throw new IllegalArgumentException(key + " required");
        value = value.trim();
        if (value.isEmpty()) throw new IllegalArgumentException(key + " required");
        if (value.length() > MAX_FIELD) throw new IllegalArgumentException(key + " too long");
        if (value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(key + " contains control characters");
        }
        return value;
    }
}
