package com.shaterguy.chatgptselfrun;

import java.util.*;
import java.util.regex.*;

final class DriveSignalParser {
    enum Type { COMMAND_RECEIVED, TURN_COMPLETED, USER_ACTION_REQUIRED, PAUSED, DONE }

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
        final boolean cursorRebased;
        Scan(List<Event> unseen, int totalCount, Event latest, boolean cursorRebased) {
  this.unseen = unseen;
  this.totalCount = totalCount;
  this.latest = latest;
  this.cursorRebased = cursorRebased;
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
          + "\\[(SELF_RUN_COMMAND_RECEIVED|SELF_RUN_TURN_COMPLETED|SELF_RUN_USER_ACTION_REQUIRED|SELF_RUN_PAUSED|SELF_RUN_DONE) "
          + "([A-Za-z0-9._-]{1,128})(?:\\s+([^\\]]*))?]$");
    private static final Pattern WORK_PROFILE = Pattern.compile(
  "^MODEL=([A-Za-z0-9._-]+)\\s+REASONING=([A-Za-z0-9._-]+)$", Pattern.CASE_INSENSITIVE);
    private static final String NEXT = "NEXT_INPUT_B64URL";

    private DriveSignalParser() {}

    static Scan scan(String text, String jobId, int consumed) {
        return scan(text, jobId, consumed, SelfRunStore.MODE_CHAT);
    }

    static Scan scan(String text, String jobId, int consumed, String mode) {
        boolean work = SelfRunStore.MODE_WORK.equals(mode);
        List<Event> all = new ArrayList<>();
        for (String source : (text == null ? "" : text).split("\\r?\\n")) {
  Matcher matcher = LINE.matcher(source.trim());
  if (!matcher.matches() || !jobId.equals(matcher.group(3))) continue;
  Type type = type(matcher.group(2));
  String tail = matcher.group(4) == null ? "" : matcher.group(4).trim();
  if (type != Type.TURN_COMPLETED) {
      if (!tail.isEmpty()) continue; // Preserve 1.2.1 non-completion behavior.
      all.add(new Event(type, matcher.group(1), matcher.group(0), all.size() + 1));
      continue;
  }
  Event completion = completion(matcher.group(1), matcher.group(0), all.size() + 1, tail, work);
  if (completion != null) all.add(completion);
        }
        int requested = Math.max(0, consumed);
        boolean rebased = requested > all.size();
        int base = Math.min(requested, all.size());
        List<Event> unseen = base >= all.size() ? Collections.emptyList()
      : new ArrayList<>(all.subList(base, all.size()));
        return new Scan(Collections.unmodifiableList(unseen), all.size(),
      all.isEmpty() ? null : all.get(all.size() - 1), rebased);
    }

    static Event latestCompletion(List<Event> events) {
        Event latest = null;
        if (events != null) for (Event event : events) if (event.type == Type.TURN_COMPLETED) latest = event;
        return latest;
    }

    static Event latestBlocking(List<Event> events) {
        Event latest = null;
        if (events != null) for (Event event : events) {
  if (event.type == Type.USER_ACTION_REQUIRED || event.type == Type.PAUSED || event.type == Type.DONE) latest = event;
        }
        return latest;
    }

    static WorkProfile workProfile(String raw) {
        Matcher line = LINE.matcher(raw == null ? "" : raw.trim());
        if (!line.matches() || !"SELF_RUN_TURN_COMPLETED".equals(line.group(2))) return invalidProfile();
        String tail = line.group(4) == null ? "" : line.group(4).trim();
        if (!mentionsNext(tail)) {
  Matcher profile = WORK_PROFILE.matcher(tail);
  if (!profile.matches()) return invalidProfile();
  String model = profile.group(1).toLowerCase(Locale.ROOT);
  String reasoning = profile.group(2).toLowerCase(Locale.ROOT);
  return new WorkProfile(model, reasoning, SelfRunProtocol.validWorkProfile(model, reasoning));
        }
        Fields fields = fields(tail);
        if (!fields.valid || hasUnknown(fields.values, true)) return invalidProfile();
        NextInputCodec.Decoded next = decodeNext(fields.values);
        if (next.present && !next.valid) return invalidProfile();
        String model = lower(fields.values.get("MODEL"));
        String reasoning = lower(fields.values.get("REASONING"));
        return new WorkProfile(model, reasoning, SelfRunProtocol.validWorkProfile(model, reasoning));
    }

    static NextInputCodec.Decoded nextInput(String raw) {
        Matcher line = LINE.matcher(raw == null ? "" : raw.trim());
        if (!line.matches() || !"SELF_RUN_TURN_COMPLETED".equals(line.group(2))) return NextInputCodec.absent();
        String tail = line.group(4) == null ? "" : line.group(4).trim();
        if (!mentionsNext(tail)) return NextInputCodec.absent();
        Fields fields = fields(tail);
        if (!fields.valid || hasUnknown(fields.values, true)) return NextInputCodec.decodeToken("");
        return decodeNext(fields.values);
    }

    static String historySafeRaw(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw.replaceAll("(?i)NEXT_INPUT_B64URL=[^\\s\\]]*", "NEXT_INPUT_B64URL=<redacted>");
    }

    private static Event completion(String timestamp, String raw, int cursor, String tail, boolean work) {
        if (tail.isEmpty()) return new Event(Type.TURN_COMPLETED, timestamp, raw, cursor);
        if (!mentionsNext(tail)) {
  return work ? new Event(Type.TURN_COMPLETED, timestamp, raw, cursor) : null;
        }
        Fields fields = fields(tail);
        if (!fields.valid) return invalidCompletion(timestamp, raw, cursor, fields.error);
        if (hasUnknown(fields.values, work)) return invalidCompletion(timestamp, raw, cursor, "TURN_COMPLETED_UNKNOWN_FIELD");
        NextInputCodec.Decoded next = decodeNext(fields.values);
        if (!next.present) return invalidCompletion(timestamp, raw, cursor, "NEXT_INPUT_MISSING");
        if (!next.valid) return invalidCompletion(timestamp, raw, cursor, next.error);
        return new Event(Type.TURN_COMPLETED, timestamp, raw, cursor, true, next.text, "");
    }

    private static Fields fields(String tail) {
        Map<String, String> values = new LinkedHashMap<>();
        if (tail == null || tail.isEmpty()) return new Fields(values, true, "");
        for (String token : tail.split("\\s+")) {
  int eq = token.indexOf('=');
  if (eq <= 0 || eq == token.length() - 1) return new Fields(values, false, "TURN_COMPLETED_FIELD_MALFORMED");
  String key = token.substring(0, eq).toUpperCase(Locale.ROOT);
  if (values.containsKey(key)) return new Fields(values, false, "TURN_COMPLETED_DUPLICATE_FIELD");
  values.put(key, token.substring(eq + 1));
        }
        return new Fields(values, true, "");
    }

    private static boolean hasUnknown(Map<String, String> values, boolean work) {
        for (String key : values.keySet()) {
  if (NEXT.equals(key)) continue;
  if (work && ("MODEL".equals(key) || "REASONING".equals(key))) continue;
  return true;
        }
        return false;
    }

    private static NextInputCodec.Decoded decodeNext(Map<String, String> values) {
        return values.containsKey(NEXT) ? NextInputCodec.decodeToken(values.get(NEXT)) : NextInputCodec.absent();
    }

    private static Event invalidCompletion(String timestamp, String raw, int cursor, String error) {
        return new Event(Type.TURN_COMPLETED, timestamp, raw, cursor, false, "", error);
    }

    private static boolean mentionsNext(String tail) {
        return tail != null && tail.toUpperCase(Locale.ROOT).contains(NEXT);
    }

    private static WorkProfile invalidProfile() { return new WorkProfile("", "", false); }
    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }

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

    private static final class Fields {
        final Map<String, String> values;
        final boolean valid;
        final String error;
        Fields(Map<String, String> values, boolean valid, String error) {
  this.values = values;
  this.valid = valid;
  this.error = error;
        }
    }
}
