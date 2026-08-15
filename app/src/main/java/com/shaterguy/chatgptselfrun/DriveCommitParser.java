package com.shaterguy.chatgptselfrun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DriveSignalParser {
    enum Type { COMMAND_RECEIVED, TURN_COMPLETED, USER_ACTION_REQUIRED, PAUSED, DONE, INVALID }

    static final class Event {
        final Type type;
        final String timestamp;
        final String raw;
        final int cursor;
        final boolean hasNextInput;
        final String nextInput;
        final String nextInputFingerprint;
        final String protocolError;

        Event(Type type, String timestamp, String raw, int cursor) {
            this(type, timestamp, raw, cursor, false, "", "", "");
        }

        Event(Type type, String timestamp, String raw, int cursor, boolean hasNextInput,
              String nextInput, String nextInputFingerprint, String protocolError) {
            this.type = type;
            this.timestamp = timestamp;
            this.raw = raw;
            this.cursor = cursor;
            this.hasNextInput = hasNextInput;
            this.nextInput = nextInput;
            this.nextInputFingerprint = nextInputFingerprint;
            this.protocolError = protocolError;
        }
    }

    static final class Scan {
        final List<Event> unseen;
        final int totalCount;
        final Event latest;
        final boolean cursorRebased;
        Scan(List<Event> unseen, int totalCount, Event latest, boolean cursorRebased) {
            this.unseen = unseen;
            this.totalCount = totalCount;
            this.latest = latest;
            this.cursorRebased = cursorRebased;
        }
    }

    static final class WorkProfile {
        final String model;
        final String reasoning;
        final boolean valid;
        WorkProfile(String model, String reasoning, boolean valid) {
            this.model = model;
            this.reasoning = reasoning;
            this.valid = valid;
        }
    }

    private static final Pattern LINE = Pattern.compile(
            "^\\[(\\d{4}\\.\\d{2}\\.\\d{2} \\| \\d{2}:\\d{2}:\\d{2})] "
                    + "\\[(SELF_RUN_COMMAND_RECEIVED|SELF_RUN_TURN_COMPLETED|SELF_RUN_USER_ACTION_REQUIRED|SELF_RUN_PAUSED|SELF_RUN_DONE) "
                    + "([A-Za-z0-9._-]{1,128})(?:\\s+([^\\]]*))?]$");
    private static final Pattern NEXT_FIELD = Pattern.compile("NEXT_INPUT_B64URL=([A-Za-z0-9_-]+)");

    private DriveSignalParser() {}

    static Scan scan(String text, String jobId, int consumed) {
        return scan(text, jobId, consumed, SelfRunStore.MODE_CHAT);
    }

    static Scan scan(String text, String jobId, int consumed, String mode) {
        List<Event> all = new ArrayList<>();
        for (String sourceLine : (text == null ? "" : text).split("\\r?\\n")) {
            String line = sourceLine.trim();
            Matcher matcher = LINE.matcher(line);
            if (!matcher.matches() || !jobId.equals(matcher.group(3))) continue;
            Type type = type(matcher.group(2));
            String tail = matcher.group(4) == null ? "" : matcher.group(4).trim();
            int cursor = all.size() + 1;
            Event event = parseEvent(type, matcher.group(1), matcher.group(0), cursor, tail, mode);
            all.add(event);
        }
        int requested = Math.max(0, consumed);
        boolean rebased = requested > all.size();
        int base = Math.min(requested, all.size());
        List<Event> unseen = base >= all.size() ? Collections.emptyList()
                : new ArrayList<>(all.subList(base, all.size()));
        return new Scan(Collections.unmodifiableList(unseen), all.size(),
                all.isEmpty() ? null : all.get(all.size() - 1), rebased);
    }

    static WorkProfile workProfile(String raw) {
        Matcher matcher = LINE.matcher(raw == null ? "" : raw.trim());
        if (!matcher.matches() || !"SELF_RUN_TURN_COMPLETED".equals(matcher.group(2))) return invalidProfile();
        ParsedFields fields = parseFields(matcher.group(4) == null ? "" : matcher.group(4).trim());
        if (!fields.valid || hasUnknown(fields.values, true)) return invalidProfile();
        NextInputCodec.Decoded next = decodeNext(fields.values);
        if (next.present && !next.valid) return invalidProfile();
        String model = lower(fields.values.get("MODEL"));
        String reasoning = lower(fields.values.get("REASONING"));
        return new WorkProfile(model, reasoning, SelfRunProtocol.validWorkProfile(model, reasoning));
    }

    static NextInputCodec.Decoded nextInput(String raw) {
        Matcher matcher = LINE.matcher(raw == null ? "" : raw.trim());
        if (!matcher.matches() || !"SELF_RUN_TURN_COMPLETED".equals(matcher.group(2))) return NextInputCodec.absent();
        ParsedFields fields = parseFields(matcher.group(4) == null ? "" : matcher.group(4).trim());
        if (!fields.valid) return NextInputCodec.decodeToken("");
        return decodeNext(fields.values);
    }

    static String nextInputFingerprint(String raw) {
        NextInputCodec.Decoded decoded = nextInput(raw);
        return decoded.present && decoded.valid ? decoded.fingerprint : "";
    }

    static String completionFingerprint(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String normalized = raw.replaceFirst("^\\[\\d{4}\\.\\d{2}\\.\\d{2} \\| \\d{2}:\\d{2}:\\d{2}]\\s+", "");
        return NextInputCodec.fingerprintText(normalized);
    }

    static String mergeNextInputIfMissing(String newerCompletionRaw, String priorCompletionRaw) {
        NextInputCodec.Decoded newer = nextInput(newerCompletionRaw);
        if (newer.present) return newerCompletionRaw;
        Matcher prior = NEXT_FIELD.matcher(priorCompletionRaw == null ? "" : priorCompletionRaw);
        if (!prior.find()) return newerCompletionRaw;
        NextInputCodec.Decoded decoded = NextInputCodec.decodeToken(prior.group(1));
        if (!decoded.valid || !decoded.present) return newerCompletionRaw;
        int close = newerCompletionRaw == null ? -1 : newerCompletionRaw.lastIndexOf(']');
        if (close < 0) return newerCompletionRaw;
        return newerCompletionRaw.substring(0, close) + " NEXT_INPUT_B64URL=" + decoded.encoded
                + newerCompletionRaw.substring(close);
    }

    static String historySafeRaw(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw.replaceAll("(?i)NEXT_INPUT_B64URL=[^\\s\\]]+", "NEXT_INPUT_B64URL=<redacted>");
    }

    private static Event parseEvent(Type type, String timestamp, String raw, int cursor, String tail, String mode) {
        if (type != Type.TURN_COMPLETED) {
            if (!tail.isEmpty()) return invalid(timestamp, raw, cursor, "NON_COMPLETION_FIELDS_FORBIDDEN");
            return new Event(type, timestamp, raw, cursor);
        }
        ParsedFields fields = parseFields(tail);
        if (!fields.valid) return invalid(timestamp, raw, cursor, fields.error);
        boolean work = SelfRunStore.MODE_WORK.equals(mode);
        if (hasUnknown(fields.values, work)) return invalid(timestamp, raw, cursor, "TURN_COMPLETED_UNKNOWN_FIELD");
        NextInputCodec.Decoded next = decodeNext(fields.values);
        if (next.present && !next.valid) return invalid(timestamp, raw, cursor, next.error);
        return new Event(Type.TURN_COMPLETED, timestamp, raw, cursor,
                next.present, next.valid ? next.text : "", next.valid ? next.fingerprint : "", "");
    }

    private static boolean hasUnknown(Map<String, String> values, boolean work) {
        for (String key : values.keySet()) {
            if ("NEXT_INPUT_B64URL".equals(key)) continue;
            if (work && ("MODEL".equals(key) || "REASONING".equals(key))) continue;
            return true;
        }
        return false;
    }

    private static NextInputCodec.Decoded decodeNext(Map<String, String> values) {
        return values.containsKey("NEXT_INPUT_B64URL")
                ? NextInputCodec.decodeToken(values.get("NEXT_INPUT_B64URL")) : NextInputCodec.absent();
    }

    private static ParsedFields parseFields(String tail) {
        Map<String, String> values = new LinkedHashMap<>();
        if (tail == null || tail.isEmpty()) return new ParsedFields(values, true, "");
        for (String token : tail.split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq <= 0 || eq == token.length() - 1) return new ParsedFields(values, false, "TURN_COMPLETED_FIELD_MALFORMED");
            String key = token.substring(0, eq).toUpperCase(Locale.ROOT);
            String value = token.substring(eq + 1);
            if (values.containsKey(key)) return new ParsedFields(values, false, "TURN_COMPLETED_DUPLICATE_FIELD");
            values.put(key, value);
        }
        return new ParsedFields(values, true, "");
    }

    private static Event invalid(String timestamp, String raw, int cursor, String error) {
        return new Event(Type.INVALID, timestamp, raw, cursor, false, "", "", error);
    }

    private static WorkProfile invalidProfile() {
        return new WorkProfile("", "", false);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static Type type(String value) {
        return switch (value) {
            case "SELF_RUN_COMMAND_RECEIVED" -> Type.COMMAND_RECEIVED;
            case "SELF_RUN_TURN_COMPLETED" -> Type.TURN_COMPLETED;
            case "SELF_RUN_USER_ACTION_REQUIRED" -> Type.USER_ACTION_REQUIRED;
            case "SELF_RUN_PAUSED" -> Type.PAUSED;
            case "SELF_RUN_DONE" -> Type.DONE;
            default -> throw new IllegalArgumentException("unknown signal");
        };
    }

    private static final class ParsedFields {
        final Map<String, String> values;
        final boolean valid;
        final String error;
        ParsedFields(Map<String, String> values, boolean valid, String error) {
            this.values = values;
            this.valid = valid;
            this.error = error;
        }
    }
}
