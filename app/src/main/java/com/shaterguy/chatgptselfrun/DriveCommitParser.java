package com.shaterguy.chatgptselfrun;

import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Strict parser for the untrusted, append-only Drive V1 control document. */
final class DriveCommitParser {
    static final int MAX_DOCUMENT_CHARS = 1_000_000;
    private static final int MAX_BLOCK_CHARS = 32_768;
    private static final int MAX_SIGNAL_CHARS = 2_048;
    private static final int MAX_BLOCKS = 128;
    private static final String COMMIT_OPEN = "[SELF_RUN_DRIVE_COMMIT_V1]";
    private static final String COMMIT_CLOSE = "[/SELF_RUN_DRIVE_COMMIT_V1]";
    private static final Set<String> COMMIT_KEYS = keys("PROTOCOL_VERSION", "CLIENT_ID", "JOB_ID",
            "TURN", "EVENT_SEQ", "COMMIT_KIND", "STATE", "COMMITTED_AT");
    private static final DateTimeFormatter STRICT_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .withResolverStyle(ResolverStyle.STRICT);

    enum Status { NONE, ACCEPTED, FUTURE_TURN, MALFORMED }

    static final class Result {
        final Status status;
        final Commit commit;
        final String reason;
        Result(Status status, Commit commit, String reason) {
            this.status = status;
            this.commit = commit;
            this.reason = reason;
        }
    }

    static final class Commit {
        final String jobId;
        final int turn;
        final long eventSeq;
        final String kind;
        final String state;
        final String committedAt;
        final String signalRaw;
        final SelfRunProtocol.Signal signal;

        Commit(String jobId, int turn, long eventSeq, String kind, String state,
               String committedAt, String signalRaw, SelfRunProtocol.Signal signal) {
            this.jobId = jobId;
            this.turn = turn;
            this.eventSeq = eventSeq;
            this.kind = kind;
            this.state = state;
            this.committedAt = committedAt;
            this.signalRaw = signalRaw;
            this.signal = signal;
        }

        String id() { return jobId + ":" + turn + ":" + eventSeq; }
    }

    private DriveCommitParser() {}

    static Result latest(String text, String jobId, int expectedTurn, long lastConsumedEventSeq,
                         String mode) {
        if (!validDocument(text) || !safeJobId(jobId) || expectedTurn < 1 || lastConsumedEventSeq < 0) {
            return new Result(Status.MALFORMED, null, "invalid document or local guard");
        }
        Commit candidate = null;
        boolean malformedForExpected = false;
        int blocks = 0;
        int cursor = 0;
        while (true) {
            int open = text.indexOf(COMMIT_OPEN, cursor);
            if (open < 0) break;
            if (++blocks > MAX_BLOCKS) return new Result(Status.MALFORMED, null, "too many commit markers");
            int nextOpen = text.indexOf(COMMIT_OPEN, open + COMMIT_OPEN.length());
            int close = text.indexOf(COMMIT_CLOSE, open + COMMIT_OPEN.length());
            // Ignore an earlier unclosed append when a later complete commit exists.
            if (nextOpen >= 0 && (close < 0 || nextOpen < close)) {
                cursor = nextOpen;
                continue;
            }
            if (close < 0) break;
            String body = text.substring(open + COMMIT_OPEN.length(), close);
            cursor = close + COMMIT_CLOSE.length();
            if (body.length() > MAX_BLOCK_CHARS) return new Result(Status.MALFORMED, null, "commit block too large");
            int signalStart = body.indexOf("SIGNAL_BEGIN");
            int signalEnd = body.indexOf("SIGNAL_END", Math.max(0, signalStart + 12));
            if (signalStart < 0 || signalEnd < 0
                    || signalStart != body.lastIndexOf("SIGNAL_BEGIN")
                    || signalEnd != body.lastIndexOf("SIGNAL_END") || signalStart >= signalEnd) {
                // A closed marker with no complete signal section is treated as a partial write and ignored.
                continue;
            }
            if (!body.substring(signalEnd + "SIGNAL_END".length()).trim().isEmpty()) {
                malformedForExpected = true;
                continue;
            }
            ParsedFields parsed = fields(body.substring(0, signalStart), COMMIT_KEYS);
            if (!parsed.valid) {
                malformedForExpected = true;
                continue;
            }
            Map<String, String> values = parsed.values;
            int turn = positiveInt(values.get("TURN"));
            long sequence = positiveLong(values.get("EVENT_SEQ"));
            if (turn < 1 || sequence < 1L) {
                malformedForExpected = true;
                continue;
            }
            if (turn > expectedTurn) return new Result(Status.FUTURE_TURN, null, "future turn " + turn);
            if (turn < expectedTurn || sequence <= lastConsumedEventSeq) continue;
            if (turn != expectedTurn
                    || !"1".equals(values.get("PROTOCOL_VERSION"))
                    || !"SELFRUN_DRIVE_ANDROID".equals(values.get("CLIENT_ID"))
                    || !jobId.equals(values.get("JOB_ID"))
                    || !validTime(values.get("COMMITTED_AT"))) {
                malformedForExpected = true;
                continue;
            }
            String raw = body.substring(signalStart + "SIGNAL_BEGIN".length(), signalEnd).trim();
            if (raw.isEmpty() || raw.length() > MAX_SIGNAL_CHARS || raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) {
                malformedForExpected = true;
                continue;
            }
            SelfRunProtocol.Signal signal = SelfRunProtocol.parseLatest(raw, jobId, mode);
            String kind = values.getOrDefault("COMMIT_KIND", "");
            String state = values.getOrDefault("STATE", "");
            if (signal.type == SelfRunProtocol.Type.NONE || !raw.equals(signal.raw)
                    || !consistent(kind, state, signal.type)) {
                malformedForExpected = true;
                continue;
            }
            Commit current = new Commit(jobId, turn, sequence, kind, state,
                    values.get("COMMITTED_AT"), raw, signal);
            if (candidate != null) {
                if (equivalent(candidate, current)) continue;
                return new Result(Status.MALFORMED, null, "multiple or conflicting unseen commits");
            }
            candidate = current;
        }
        if (malformedForExpected) return new Result(Status.MALFORMED, null, "invalid expected-turn commit");
        if (candidate != null) return new Result(Status.ACCEPTED, candidate, "");
        return new Result(Status.NONE, null, "no new commit");
    }

    private static boolean equivalent(Commit left, Commit right) {
        return left.turn == right.turn && left.eventSeq == right.eventSeq
                && left.jobId.equals(right.jobId) && left.kind.equals(right.kind)
                && left.state.equals(right.state) && left.committedAt.equals(right.committedAt)
                && left.signalRaw.equals(right.signalRaw);
    }

    private static boolean consistent(String kind, String state, SelfRunProtocol.Type type) {
        return switch (type) {
            case NEXT -> "CONTINUE".equals(kind) && "TURN_COMMITTED".equals(state);
            case DONE -> "DONE".equals(kind) && "RUN_DONE".equals(state);
            case PAUSE -> "PAUSE".equals(kind) && "RUN_PAUSED".equals(state);
            case USER_ACTION -> "USER_ACTION_REQUIRED".equals(kind)
                    && "USER_ACTION_REQUIRED".equals(state);
            case NONE -> false;
        };
    }

    private static ParsedFields fields(String body, Set<String> allowed) {
        Map<String, String> values = new LinkedHashMap<>();
        if (body == null || body.length() > MAX_BLOCK_CHARS) return new ParsedFields(false, values);
        for (String original : body.split("\\R", -1)) {
            String line = original.trim();
            if (line.isEmpty()) continue;
            if (line.length() > 4_096) return new ParsedFields(false, values);
            int equals = line.indexOf('=');
            if (equals <= 0) return new ParsedFields(false, values);
            String key = line.substring(0, equals);
            String value = line.substring(equals + 1);
            if (!key.matches("[A-Z][A-Z0-9_]{0,63}") || !allowed.contains(key)
                    || value.isEmpty() || value.length() > 2_048 || values.putIfAbsent(key, value) != null) {
                return new ParsedFields(false, values);
            }
        }
        return new ParsedFields(values.keySet().equals(allowed), values);
    }

    private static int positiveInt(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,6}")) return -1;
        try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return -1; }
    }

    private static long positiveLong(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return -1L;
        try { return Long.parseLong(value); } catch (RuntimeException ignored) { return -1L; }
    }

    private static boolean validTime(String value) {
        if (value == null || value.length() < 20 || value.length() > 64
                || !(value.endsWith("Z") || value.matches(".*[+-][0-9]{2}:[0-9]{2}$"))) return false;
        try {
            Instant parsed = OffsetDateTime.parse(value, STRICT_OFFSET).toInstant();
            Instant now = Instant.now();
            return !parsed.isBefore(Instant.parse("2020-01-01T00:00:00Z"))
                    && !parsed.isAfter(now.plus(10, ChronoUnit.MINUTES));
        }
        catch (RuntimeException ignored) { return false; }
    }

    private static boolean validDocument(String text) {
        return text != null && text.length() <= MAX_DOCUMENT_CHARS && text.indexOf('\u0000') < 0;
    }

    private static boolean safeJobId(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,80}");
    }

    private static Set<String> keys(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    private record ParsedFields(boolean valid, Map<String, String> values) {}
}
