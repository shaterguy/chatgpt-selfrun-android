package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.Intent;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Strict FCM data envelope used only to wake the exact waiting SelfRun execution. */
final class SelfRunPushEvent {
    static final String SCHEMA = "selfrun-push-v1";
    static final String TYPE_RESULT_CHANGED = "RESULT_CHANGED";
    static final String EXTRA_EVENT_ID = "selfrun_push_event_id";
    static final String EXTRA_INSTALLATION_ID = "selfrun_push_installation_id";
    static final String EXTRA_APPLICATION_ID = "selfrun_push_application_id";
    static final String EXTRA_TASK_ID = "selfrun_push_task_id";
    static final String EXTRA_TURN_ID = "selfrun_push_turn_id";
    static final String EXTRA_RESULT_DOCUMENT_ID = "selfrun_push_result_document_id";
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
        return new SelfRunPushEvent(
                required(data, "eventId"), required(data, "installationId"), applicationId,
                required(data, "taskId"), required(data, "turnId"), required(data, "resultDocumentId"));
    }

    static SelfRunPushEvent fromIntent(Intent intent, String expectedApplicationId) {
        if (intent == null) throw new IllegalArgumentException("push intent required");
        Map<String, String> data = new HashMap<>();
        data.put("schema", SCHEMA);
        data.put("type", TYPE_RESULT_CHANGED);
        data.put("eventId", intent.getStringExtra(EXTRA_EVENT_ID));
        data.put("installationId", intent.getStringExtra(EXTRA_INSTALLATION_ID));
        data.put("applicationId", intent.getStringExtra(EXTRA_APPLICATION_ID));
        data.put("taskId", intent.getStringExtra(EXTRA_TASK_ID));
        data.put("turnId", intent.getStringExtra(EXTRA_TURN_ID));
        data.put("resultDocumentId", intent.getStringExtra(EXTRA_RESULT_DOCUMENT_ID));
        return parse(data, expectedApplicationId);
    }

    Intent serviceIntent(Context context) {
        return new Intent(context, SelfRunService.class)
                .setAction(BuildConfig.APPLICATION_ID + ".PUSH_RESULT")
                .putExtra(EXTRA_EVENT_ID, eventId)
                .putExtra(EXTRA_INSTALLATION_ID, installationId)
                .putExtra(EXTRA_APPLICATION_ID, applicationId)
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_TURN_ID, turnId)
                .putExtra(EXTRA_RESULT_DOCUMENT_ID, resultDocumentId);
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
