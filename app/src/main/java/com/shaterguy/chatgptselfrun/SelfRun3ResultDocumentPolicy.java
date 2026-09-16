package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;

import java.util.List;

/** Result consumption policy: exact pinned identity plus one unambiguous committed candidate. */
final class SelfRun3ResultDocumentPolicy {
    static final class Selection {
        final String rawBody;
        final String candidateBody;
        final boolean committed;
        final boolean recovered;

        Selection(String rawBody, String candidateBody, boolean committed, boolean recovered) {
            this.rawBody = rawBody == null ? "" : rawBody;
            this.candidateBody = candidateBody == null ? "" : candidateBody;
            this.committed = committed;
            this.recovered = recovered;
        }
    }

    static boolean acceptReadableResult(DriveApiClient.Metadata metadata, String expectedId, String expectedParentId) {
        return metadata != null
                && expectedId != null && expectedId.equals(metadata.id)
                && expectedParentId != null && expectedParentId.equals(metadata.parentId)
                && DriveApiClient.MIME_DOCUMENT.equals(metadata.mimeType)
                && !metadata.trashed;
    }

    static Selection select(List<String> bodies, SelfRun3Engine.State state) {
        if (state == null) throw new IllegalArgumentException("state required");
        String pending = "";
        String committedJson = "";
        String committedRaw = "";
        String committedCandidate = "";
        boolean recovered = false;
        if (bodies != null) for (String body : bodies) {
            String raw = body == null ? "" : body;
            String trimmed = raw.trim();
            if (pending.isEmpty() && !trimmed.isEmpty()) pending = raw;

            Candidate candidate = committedCandidate(raw, state);
            if (candidate == null) continue;
            String normalized = candidate.parsed.toString();
            if (committedJson.isEmpty()) {
                committedJson = normalized;
                committedRaw = raw;
                committedCandidate = candidate.body;
                recovered = candidate.recovered;
            } else if (!committedJson.equals(normalized)) {
                throw new IllegalStateException("RESULT_DOCUMENT_AMBIGUOUS");
            }
        }
        if (!committedJson.isEmpty()) {
            return new Selection(committedRaw, committedCandidate, true, recovered);
        }

        String seed = SelfRun3Engine.emptyResult(state).toString();
        String seedFingerprint = SelfRun3ResultWatchdog.fingerprint(seed);
        if (bodies != null) for (String body : bodies) {
            String raw = body == null ? "" : body;
            if (seedFingerprint.equals(SelfRun3ResultWatchdog.fingerprint(raw))) {
                return new Selection(raw, raw, false, false);
            }
        }
        return new Selection(pending, pending, false, false);
    }

    private static Candidate committedCandidate(String raw, SelfRun3Engine.State state) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) return null;
        try {
            JSONObject parsed = SelfRun3Engine.parseResult(trimmed, state);
            return parsed == null ? null : new Candidate(trimmed, parsed, false);
        } catch (RuntimeException malformed) {
            if (trimmed.length() < 2 || !trimmed.endsWith("}")) return null;
            String recovered = trimmed.substring(0, trimmed.length() - 1).trim();
            if (recovered.isEmpty() || !recovered.endsWith("}")) return null;
            try {
                JSONObject parsed = SelfRun3Engine.parseResult(recovered, state);
                return parsed == null ? null : new Candidate(recovered, parsed, true);
            } catch (RuntimeException stillMalformed) {
                return null;
            }
        }
    }

    private static final class Candidate {
        final String body;
        final JSONObject parsed;
        final boolean recovered;
        Candidate(String body, JSONObject parsed, boolean recovered) {
            this.body = body;
            this.parsed = parsed;
            this.recovered = recovered;
        }
    }

    private SelfRun3ResultDocumentPolicy() { }
}
