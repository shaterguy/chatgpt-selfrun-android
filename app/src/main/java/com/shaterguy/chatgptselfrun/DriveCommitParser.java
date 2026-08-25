package com.shaterguy.chatgptselfrun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DriveSignalParser {
    enum Type { TURN_COMPLETED, USER_ACTION_REQUIRED, PAUSED, DONE }

    static final class Event {
        final Type type;
        final String timestamp, raw;
        final int cursor;
        final boolean hasNextInput;
        final String nextInput;
        final String protocolError;

        Event(Type type, String timestamp, String raw, int cursor) {
            this(type, timestamp, raw, cursor, false, "", "");
        }

        Event(Type type, String timestamp, String raw, int cursor,
              boolean hasNextInput, String nextInput, String protocolError) {
            this.type = type;
            this.timestamp = timestamp;
            this.raw = raw;
            this.cursor = cursor;
            this.hasNextInput = hasNextInput;
            this.nextInput = nextInput;
            this.protocolError = protocolError;
        }
    }

    static final class Scan {
        final List<Event> unseen;
        final int totalCount;
        final Event latest;
        final Event latestCanonical;
        final boolean cursorRebased;

        Scan(List<Event> unseen, int totalCount, Event latest, Event latestCanonical, boolean cursorRebased) {
            this.unseen = unseen;
            this.totalCount = totalCount;
            this.latest = latest;
            this.latestCanonical = latestCanonical;
            this.cursorRebased = cursorRebased;
        }
    }

    static final class CursorMigration {
        final boolean resolved;
        final int cursor;
        final String method;

        CursorMigration(boolean resolved, int cursor, String method) {
            this.resolved = resolved;
            this.cursor = Math.max(0, cursor);
            this.method = method == null ? "" : method;
        }

        static CursorMigration resolved(int cursor, String method) {
            return new CursorMigration(true, cursor, method);
        }

        static CursorMigration unresolved() {
            return new CursorMigration(false, 0, "UNRESOLVED");
        }
    }

    static final class WorkProfile {
        final String model, reasoning;
        final boolean valid;

        WorkProfile(String model, String reasoning, boolean valid) {
            this.model = model;
            this.reasoning = reasoning;
            this.valid = valid;
        }
    }

    private static final Pattern LINE = Pattern.compile(
            "^\\[(\\d{4}\\.\\d{2}\\.\\d{2} \\| \\d{2}:\\d{2}:\\d{2})] "
                    + "\\[(SELF_RUN_TURN_COMPLETED|SELF_RUN_USER_ACTION_REQUIRED|SELF_RUN_PAUSED|SELF_RUN_DONE) "
                    + "([A-Za-z0-9._-]{1,128})(?:\\s+([^\\]]*))?]$");
    private static final Pattern RECOVERY_FIELD = Pattern.compile(
            "(?:^|\\s)RECOVERY_ID=", Pattern.CASE_INSENSITIVE);
    // Older documents may contain a retired acknowledgement line. It is not an
    // event, but still occupies its historical cursor position so an in-place
    // update cannot replay an already-consumed completion.
    private static final Pattern RETIRED_CURSOR_LINE = Pattern.compile(
            "^\\[\\d{4}\\.\\d{2}\\.\\d{2} \\| \\d{2}:\\d{2}:\\d{2}] "
                    + "\\[SELF_RUN_COMMAND_RECEIVED ([A-Za-z0-9._-]{1,128})]$");

    private DriveSignalParser() {}

    static Scan scan(String text, String jobId, int consumed) {
        return scan(text, jobId, consumed, SelfRunStore.MODE_CHAT);
    }

    static Scan scan(String text, String jobId, int consumed, String mode) {
        boolean work = SelfRunStore.MODE_WORK.equals(mode);
        List<Event> all = new ArrayList<>();
        int absoluteCursor = 0;
        Event latestCanonical = null;
        for (String source : (text == null ? "" : text).split("\\r?\\n", -1)) {
            String trimmed = physicalLine(source);
            if (trimmed.isEmpty()) continue;
            absoluteCursor++;
            Matcher retired = RETIRED_CURSOR_LINE.matcher(trimmed);
            if (retired.matches() && jobId.equals(retired.group(1))) continue;
            Matcher matcher = LINE.matcher(trimmed);
            if (!matcher.matches() || !jobId.equals(matcher.group(3))) continue;
            Type type = type(matcher.group(2));
            String tail = matcher.group(4) == null ? "" : matcher.group(4).trim();
            if (type != Type.TURN_COMPLETED) {
                if (!tail.isEmpty()) continue;
                Event event = new Event(type, matcher.group(1), matcher.group(0), absoluteCursor);
                all.add(event);
                latestCanonical = event;
                continue;
            }
            Event completion = completion(matcher.group(1), matcher.group(0), absoluteCursor, tail, work);
            if (completion != null) {
                all.add(completion);
                if (canonical(completion, work)) latestCanonical = completion;
            }
        }
        int requested = Math.max(0, consumed);
        boolean rebased = requested > absoluteCursor;
        List<Event> unseen = new ArrayList<>();
        if (!rebased) {
            for (Event event : all) if (event.cursor > requested) unseen.add(event);
        }
        return new Scan(Collections.unmodifiableList(unseen), absoluteCursor,
                all.isEmpty() ? null : all.get(all.size() - 1), latestCanonical, rebased);
    }

    static CursorMigration migrateCursor(String text, String jobId, int legacyCursor,
                                         String lastRaw, String lastTimestamp, String lastType) {
        int legacy = Math.max(0, legacyCursor);
        String raw = lastRaw == null ? "" : lastRaw.trim();
        String timestamp = lastTimestamp == null ? "" : lastTimestamp.trim();
        String typeValue = lastType == null ? "" : lastType.trim();
        if (legacy == 0 && raw.isEmpty() && timestamp.isEmpty() && typeValue.isEmpty()) {
            return CursorMigration.resolved(0, "EMPTY");
        }
        if (!raw.isEmpty()) {
            int exact = uniquePhysicalCursor(text, line -> line.equals(raw));
            if (exact >= legacy && exact > 0) return CursorMigration.resolved(exact, "EXACT_RAW");
        }
        Type expected = storedType(typeValue);
        if (expected == null || timestamp.isEmpty() || jobId == null || jobId.isEmpty()) {
            return CursorMigration.unresolved();
        }
        int identity = uniquePhysicalCursor(text,
                line -> legacyIdentityMatches(line, jobId, timestamp, expected));
        if (identity >= legacy && identity > 0) return CursorMigration.resolved(identity, "IDENTITY");
        return CursorMigration.unresolved();
    }

    private interface LineMatcher { boolean matches(String line); }

    private static int uniquePhysicalCursor(String text, LineMatcher matcher) {
        int cursor = 0, found = 0;
        for (String source : (text == null ? "" : text).split("\\r?\\n", -1)) {
            String line = physicalLine(source);
            if (line.isEmpty()) continue;
            cursor++;
            if (!matcher.matches(line)) continue;
            if (found != 0) return -1;
            found = cursor;
        }
        return found;
    }

    private static boolean legacyIdentityMatches(String line, String jobId, String timestamp, Type type) {
        String prefix = "[" + timestamp + "] [" + signalToken(type) + " " + jobId;
        if (!line.startsWith(prefix)) return false;
        if (line.length() == prefix.length()) return true;
        char boundary = line.charAt(prefix.length());
        return boundary == ']' || Character.isWhitespace(boundary);
    }

    private static Type storedType(String value) {
        try { return Type.valueOf(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private static String signalToken(Type type) {
        return switch (type) {
            case TURN_COMPLETED -> "SELF_RUN_TURN_COMPLETED";
            case USER_ACTION_REQUIRED -> "SELF_RUN_USER_ACTION_REQUIRED";
            case PAUSED -> "SELF_RUN_PAUSED";
            case DONE -> "SELF_RUN_DONE";
        };
    }

    private static String physicalLine(String source) {
        String line = source == null ? "" : source.trim();
        if (!line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1).trim();
        return line;
    }

    private static boolean canonical(Event event, boolean work) {
        if (event == null || !event.protocolError.isEmpty()) return false;
        if (event.type != Type.TURN_COMPLETED) return true;
        return !work || workProfile(event.raw).valid;
    }

    static Event latestCompletion(List<Event> events) {
        Event latest = null;
        if (events != null) {
            for (Event event : events) {
                if (event.type == Type.TURN_COMPLETED && !hasRecoveryIdField(event.raw)) latest = event;
            }
        }
        return latest;
    }

    static Event latestBlocking(List<Event> events) {
        Event latest = null;
        if (events != null) {
            for (Event event : events) {
                if (event.type == Type.USER_ACTION_REQUIRED
                        || event.type == Type.PAUSED
                        || event.type == Type.DONE) latest = event;
            }
        }
        return latest;
    }

    static WorkProfile workProfile(String raw) {
        Matcher line = LINE.matcher(raw == null ? "" : raw.trim());
        if (!line.matches() || !"SELF_RUN_TURN_COMPLETED".equals(line.group(2))) {
            return invalidProfile();
        }
        String tail = line.group(4) == null ? "" : line.group(4).trim();
        DriveSignalFields.Parsed fields = DriveSignalFields.parse(tail);
        if (!fields.valid || DriveSignalFields.hasUnknown(fields.values, true)) return invalidProfile();
        String recovery = fields.values.get(DriveSignalFields.RECOVERY);
        if (recovery != null && !SelfRunProtocolRules.validRecoveryId(recovery)) return invalidProfile();
        NextInputCodec.Decoded next = DriveSignalFields.decodeNext(fields.values);
        if (next.present && !next.valid) return invalidProfile();
        String model = DriveSignalFields.lower(fields.values.get("MODEL"));
        String reasoning = DriveSignalFields.lower(fields.values.get("REASONING"));
        return new WorkProfile(model, reasoning,
                SelfRunProtocolRules.validWorkProfile(model, reasoning));
    }

    static NextInputCodec.Decoded nextInput(String raw) {
        Matcher line = LINE.matcher(raw == null ? "" : raw.trim());
        if (!line.matches() || !"SELF_RUN_TURN_COMPLETED".equals(line.group(2))) {
            return NextInputCodec.absent();
        }
        String tail = line.group(4) == null ? "" : line.group(4).trim();
        if (!DriveSignalFields.mentionsNext(tail)) return NextInputCodec.absent();
        DriveSignalFields.Parsed fields = DriveSignalFields.parse(tail);
        if (!fields.valid || DriveSignalFields.hasUnknown(fields.values, true)) {
            return NextInputCodec.decodeToken("");
        }
        String recovery = fields.values.get(DriveSignalFields.RECOVERY);
        if (recovery != null && !SelfRunProtocolRules.validRecoveryId(recovery)) {
            return NextInputCodec.decodeToken("");
        }
        return DriveSignalFields.decodeNext(fields.values);
    }

    static boolean hasRecoveryIdField(String raw) {
        Matcher line = LINE.matcher(raw == null ? "" : raw.trim());
        if (!line.matches() || !"SELF_RUN_TURN_COMPLETED".equals(line.group(2))) return false;
        String tail = line.group(4) == null ? "" : line.group(4).trim();
        return RECOVERY_FIELD.matcher(tail).find();
    }

    static String recoveryId(String raw) {
        Matcher line = LINE.matcher(raw == null ? "" : raw.trim());
        if (!line.matches() || !"SELF_RUN_TURN_COMPLETED".equals(line.group(2))) return "";
        DriveSignalFields.Parsed fields = DriveSignalFields.parse(
                line.group(4) == null ? "" : line.group(4).trim());
        if (!fields.valid || DriveSignalFields.hasUnknown(fields.values, true)) return "";
        String value = fields.values.get(DriveSignalFields.RECOVERY);
        return SelfRunProtocolRules.validRecoveryId(value) ? value : "";
    }

    static String historySafeRaw(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw.replaceAll("(?i)NEXT_INPUT_B64URL=[^\\s\\]]*",
                "NEXT_INPUT_B64URL=<redacted>");
    }

    private static Event completion(
            String timestamp, String raw, int cursor, String tail, boolean work) {
        if (tail.isEmpty()) return new Event(Type.TURN_COMPLETED, timestamp, raw, cursor);
        boolean hasNext = DriveSignalFields.mentionsNext(tail);
        boolean hasRecovery = DriveSignalFields.mentionsRecovery(tail);
        if (!hasNext && !hasRecovery) {
            return work ? new Event(Type.TURN_COMPLETED, timestamp, raw, cursor) : null;
        }
        DriveSignalFields.Parsed fields = DriveSignalFields.parse(tail);
        if (!fields.valid) return invalidCompletion(timestamp, raw, cursor, fields.error);
        if (DriveSignalFields.hasUnknown(fields.values, work)) {
            return invalidCompletion(timestamp, raw, cursor, "TURN_COMPLETED_UNKNOWN_FIELD");
        }
        String recovery = fields.values.get(DriveSignalFields.RECOVERY);
        if (recovery != null && !SelfRunProtocolRules.validRecoveryId(recovery)) {
            return invalidCompletion(timestamp, raw, cursor, "RECOVERY_ID_INVALID");
        }
        NextInputCodec.Decoded next = DriveSignalFields.decodeNext(fields.values);
        if (hasNext && !next.present) {
            return invalidCompletion(timestamp, raw, cursor, "NEXT_INPUT_MISSING");
        }
        if (next.present && !next.valid) {
            return invalidCompletion(timestamp, raw, cursor, next.error);
        }
        return new Event(Type.TURN_COMPLETED, timestamp, raw, cursor,
                next.present, next.present ? next.text : "", "");
    }

    private static Event invalidCompletion(String timestamp, String raw, int cursor, String error) {
        return new Event(Type.TURN_COMPLETED, timestamp, raw, cursor, false, "", error);
    }

    private static WorkProfile invalidProfile() {
        return new WorkProfile("", "", false);
    }

    private static Type type(String value) {
        return switch (value) {
            case "SELF_RUN_TURN_COMPLETED" -> Type.TURN_COMPLETED;
            case "SELF_RUN_USER_ACTION_REQUIRED" -> Type.USER_ACTION_REQUIRED;
            case "SELF_RUN_PAUSED" -> Type.PAUSED;
            case "SELF_RUN_DONE" -> Type.DONE;
            default -> throw new IllegalArgumentException("unknown signal");
        };
    }
}
