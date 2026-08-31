package com.shaterguy.chatgptselfrun;

import java.time.Instant;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure protocol helpers for one-signal-per-Google-Doc transport. */
final class DriveSignalDocumentTransport {
    static final String NEXT_INPUT_BODY_MARKER = "NEXT_INPUT_B64URL=BODY";
    private static final String NEXT_INPUT_FIELD_PREFIX = "NEXT_INPUT_B64URL=";
    private static final String VALIDATION_TOKEN = "QQ";
    private static final Pattern BODY_LINE = Pattern.compile("^NEXT_INPUT_B64URL=([A-Za-z0-9_-]+)$");

    private DriveSignalDocumentTransport() {}

    static boolean isCandidate(DriveApiClient.Metadata metadata, String runId, String parentId) {
        boolean candidate = metadata != null && isCandidateFields(metadata.id, metadata.name, metadata.mimeType,
                metadata.parentId, metadata.trashed, metadata.shared, metadata.createdTime, runId, parentId);
        if (candidate) {
            DriveSignalDocumentIdentity.observeCandidate(metadata.id, metadata.name, metadata.createdTime, runId);
        }
        return candidate;
    }

    static boolean isCandidateFields(String id, String name, String mimeType, String parentId,
                                     boolean trashed, boolean shared, String createdTime,
                                     String runId, String expectedParentId) {
        if (trashed || shared || !DriveApiClient.MIME_DOCUMENT.equals(mimeType)
                || expectedParentId == null || !expectedParentId.equals(parentId)
                || !DriveApiClient.validFileId(id) || createdMillis(createdTime) < 0L) return false;
        return isCanonicalTitle(name, runId);
    }

    static boolean isCanonicalTitle(String title, String runId) {
        if (title == null || title.isEmpty() || !SelfRunProtocolRules.validRunId(runId)) return false;
        int bodyMarkers = occurrences(title, NEXT_INPUT_BODY_MARKER);
        if (bodyMarkers > 1) return false;
        if (title.contains(NEXT_INPUT_FIELD_PREFIX) && bodyMarkers != 1) return false;
        String logical = bodyMarkers == 1
                ? title.replace(NEXT_INPUT_BODY_MARKER, NEXT_INPUT_FIELD_PREFIX + VALIDATION_TOKEN)
                : title;
        return canonicalInMode(logical, runId, SelfRunStore.MODE_CHAT)
                || canonicalInMode(logical, runId, SelfRunStore.MODE_WORK);
    }

    static boolean needsBodyRead(String title) {
        return occurrences(title, NEXT_INPUT_BODY_MARKER) == 1;
    }

    static String materialize(String title, String body, String runId) {
        if (!isCanonicalTitle(title, runId)) throw new IllegalArgumentException("invalid signal document title");
        if (!needsBodyRead(title)) return title;
        String normalized = normalizeBody(body);
        Matcher matcher = BODY_LINE.matcher(normalized);
        if (!matcher.matches()) throw new IllegalArgumentException("invalid signal document NEXT_INPUT body");
        String token = matcher.group(1);
        NextInputCodec.Decoded decoded = NextInputCodec.decodeToken(token);
        if (!decoded.present || !decoded.valid) {
            throw new IllegalArgumentException("signal document NEXT_INPUT payload is not canonical UTF-8 Base64URL");
        }
        String logical = title.replace(NEXT_INPUT_BODY_MARKER, NEXT_INPUT_FIELD_PREFIX + token);
        if (!(canonicalInMode(logical, runId, SelfRunStore.MODE_CHAT)
                || canonicalInMode(logical, runId, SelfRunStore.MODE_WORK))) {
            throw new IllegalArgumentException("materialized signal document is not canonical");
        }
        return logical;
    }

    static String titleTimestamp(String title, String runId) {
        if (!isCanonicalTitle(title, runId)) return "";
        String logical = needsBodyRead(title)
                ? title.replace(NEXT_INPUT_BODY_MARKER, NEXT_INPUT_FIELD_PREFIX + VALIDATION_TOKEN)
                : title;
        DriveSignalParser.Event event = canonicalEvent(logical, runId, SelfRunStore.MODE_CHAT);
        if (event == null) event = canonicalEvent(logical, runId, SelfRunStore.MODE_WORK);
        return event == null ? "" : event.timestamp;
    }

    static Comparator<DriveApiClient.Metadata> comparator(String runId) {
        DriveSignalDocumentIdentity.seal(runId);
        return (left, right) -> {
            boolean leftKnown = DriveSignalDocumentIdentity.recognizedForPollOrdering(runId, left.id);
            boolean rightKnown = DriveSignalDocumentIdentity.recognizedForPollOrdering(runId, right.id);
            if (leftKnown != rightKnown) return leftKnown ? -1 : 1;
            return compareFields(left.createdTime, left.name, left.id,
                    right.createdTime, right.name, right.id, runId);
        };
    }

    static int compareFields(String leftCreatedTime, String leftTitle, String leftId,
                             String rightCreatedTime, String rightTitle, String rightId, String runId) {
        int created = Long.compare(createdMillis(leftCreatedTime), createdMillis(rightCreatedTime));
        if (created != 0) return created;
        int title = titleTimestamp(leftTitle, runId).compareTo(titleTimestamp(rightTitle, runId));
        if (title != 0) return title;
        String safeLeftId = leftId == null ? "" : leftId;
        String safeRightId = rightId == null ? "" : rightId;
        return safeLeftId.compareTo(safeRightId);
    }

    static long createdMillis(String value) {
        if (value == null || value.isEmpty()) return -1L;
        try { return Instant.parse(value).toEpochMilli(); }
        catch (RuntimeException ignored) { return -1L; }
    }

    private static DriveSignalParser.Event canonicalEvent(String logical, String runId, String mode) {
        DriveSignalParser.Scan scan = DriveSignalParser.scanWithoutDocumentIdentity(logical, runId, 0, mode);
        if (scan.totalCount != 1 || scan.latestCanonical == null
                || !logical.equals(scan.latestCanonical.raw)) return null;
        return scan.latestCanonical;
    }

    private static boolean canonicalInMode(String logical, String runId, String mode) {
        return canonicalEvent(logical, runId, mode) != null;
    }

    private static String normalizeBody(String body) {
        String value = body == null ? "" : body.replace("\r\n", "\n").replace('\r', '\n');
        if (value.endsWith("\n")) value = value.substring(0, value.length() - 1);
        if (value.indexOf('\n') >= 0) throw new IllegalArgumentException("signal document body must contain exactly one line");
        return value;
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        for (int at = 0; value != null && (at = value.indexOf(token, at)) >= 0; at += token.length()) count++;
        return count;
    }
}
