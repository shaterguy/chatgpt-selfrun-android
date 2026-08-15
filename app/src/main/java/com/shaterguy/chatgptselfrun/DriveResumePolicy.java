package com.shaterguy.chatgptselfrun;

import java.util.Collections;
import java.util.List;

final class DriveResumePolicy {
    enum Origin { AI_USER_ACTION_REQUIRED, AI_PAUSED, UI_MANUAL, EXTERNAL_MANUAL, UNKNOWN }
    enum Action { APPLY_COMPLETION, CONTINUE, RESTORE_PHASE, KEEP_PAUSED, DONE, PROTOCOL_ERROR }

    static final class Decision {
        final Action action;
        final String reason;
        final DriveSignalParser.Event event;
        Decision(Action action, String reason, DriveSignalParser.Event event) {
            this.action = action;
            this.reason = reason;
            this.event = event;
        }
    }

    private DriveResumePolicy() {}

    static Decision decide(Origin origin, int anchorCursor, int totalCount,
                           List<DriveSignalParser.Event> postAnchorEvents) {
        return decide(origin, true, anchorCursor, totalCount, postAnchorEvents);
    }

    static Decision decide(Origin origin, boolean needsContinuation, int anchorCursor, int totalCount,
                           List<DriveSignalParser.Event> postAnchorEvents) {
        if (origin == null) origin = Origin.UNKNOWN;
        if (anchorCursor < 0 || totalCount < anchorCursor) return error("RESUME_ANCHOR_CURSOR_INVALID", null);
        List<DriveSignalParser.Event> events = postAnchorEvents == null
                ? Collections.emptyList() : postAnchorEvents;
        DriveSignalParser.Event material = null;
        for (DriveSignalParser.Event event : events) {
            if (event == null || event.cursor <= anchorCursor || event.cursor > totalCount) {
                return error("RESUME_POST_ANCHOR_SIGNAL_INVALID", event);
            }
            if (event.type == DriveSignalParser.Type.INVALID) {
                return error(event.protocolError.isEmpty() ? "RESUME_PROTOCOL_INVALID" : event.protocolError, event);
            }
            if (event.type != DriveSignalParser.Type.COMMAND_RECEIVED) material = event;
        }
        if (material == null) {
            return switch (origin) {
                case UI_MANUAL -> new Decision(Action.RESTORE_PHASE, "UI_PAUSE_NO_NEW_MATERIAL_SIGNAL", null);
                case EXTERNAL_MANUAL -> needsContinuation ? new Decision(Action.CONTINUE, "EXTERNAL_MANUAL_ACTION_COMPLETE", null) : new Decision(Action.RESTORE_PHASE, "EXTERNAL_MANUAL_NO_CONTINUATION_REQUIRED", null);
                case AI_USER_ACTION_REQUIRED -> new Decision(Action.KEEP_PAUSED, "USER_ACTION_RESUME_PREPARATION_REQUIRED", null);
                case AI_PAUSED -> new Decision(Action.KEEP_PAUSED, "AI_PAUSE_REMAINS_LATCHED", null);
                case UNKNOWN -> error("RESUME_ORIGIN_UNKNOWN", null);
            };
        }
        return switch (material.type) {
            case DONE -> new Decision(Action.DONE, "POST_ANCHOR_DONE", material);
            case USER_ACTION_REQUIRED, PAUSED ->
                    new Decision(Action.KEEP_PAUSED, "POST_ANCHOR_BLOCKING_SIGNAL", material);
            case TURN_COMPLETED -> {
                if (origin == Origin.AI_USER_ACTION_REQUIRED && !material.hasNextInput) {
                    yield error("USER_CHOICE_NEXT_INPUT_REQUIRED", material);
                }
                yield new Decision(Action.APPLY_COMPLETION, "POST_ANCHOR_COMPLETION", material);
            }
            case COMMAND_RECEIVED -> throw new IllegalStateException("COMMAND_RECEIVED cannot be material");
            case INVALID -> error(material.protocolError, material);
        };
    }

    static Decision decide(Origin origin, int anchorCursor, int totalCount, DriveSignalParser.Event latest) {
        return decide(origin, anchorCursor, totalCount,
                latest == null || latest.cursor <= anchorCursor ? Collections.emptyList() : Collections.singletonList(latest));
    }

    static Origin parseOrigin(String value) {
        if (value == null) return Origin.UNKNOWN;
        try { return Origin.valueOf(value); }
        catch (IllegalArgumentException error) { return Origin.UNKNOWN; }
    }

    private static Decision error(String reason, DriveSignalParser.Event event) {
        return new Decision(Action.PROTOCOL_ERROR, reason, event);
    }
}
