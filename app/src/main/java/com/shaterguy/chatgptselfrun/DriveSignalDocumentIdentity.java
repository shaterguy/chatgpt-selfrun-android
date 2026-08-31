package com.shaterguy.chatgptselfrun;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps one-signal-per-Google-Doc snapshots to durable Drive file identities.
 *
 * <p>Modern signal consumption is keyed only by Drive file ID. The legacy numeric cursor is not
 * consulted by this class. A provider-created document is considered already handled only when its
 * own ID was recognized, or when it is strictly older by Drive createdTime than the exact signal ID
 * recorded after a previously completed poll. Equal-createdTime unknown IDs remain new.</p>
 */
final class DriveSignalDocumentIdentity {
    private static final String PREFS = "selfrun_drive_signal_identity";
    private static final String STORE_PREFS = "selfrun_drive";
    private static final String STORE_LAST_SEEN_VERSION = "lastSeenDriveVersion";
    private static final String SEEN_PREFIX = "seen:";
    private static final String SIGNAL_VERSION_PREFIX = "signal:";
    private static final Pattern NEXT_INPUT_VALUE = Pattern.compile(
            "(?i)NEXT_INPUT_B64URL=[A-Za-z0-9_-]+");
    private static final Object LOCK = new Object();

    private static Context activeContext;
    private static String activeRunId = "";
    private static boolean sealed;
    private static final ArrayList<Candidate> activeCandidates = new ArrayList<>();

    static final class Candidate {
        final String id;
        final String title;
        final String createdTime;
        Candidate(String id, String title, String createdTime) {
            this.id = safe(id);
            this.title = safe(title);
            this.createdTime = safe(createdTime);
        }
    }

    static final class Resolver {
        private final boolean enabled;
        private final Set<String> recognized;
        private final Map<String, List<Candidate>> byTitle;
        private final Map<String, Integer> occurrences = new HashMap<>();

        Resolver(boolean enabled, Set<String> recognized, Map<String, List<Candidate>> byTitle) {
            this.enabled = enabled;
            this.recognized = recognized == null ? Collections.emptySet() : recognized;
            this.byTitle = byTitle == null ? Collections.emptyMap() : byTitle;
        }

        boolean enabled() { return enabled; }

        String documentId(String logicalSignal) {
            if (!enabled) return "";
            String title = candidateTitle(logicalSignal);
            List<Candidate> candidates = byTitle.get(title);
            if (candidates == null || candidates.isEmpty()) return "";
            int index = occurrences.getOrDefault(title, 0);
            occurrences.put(title, index + 1);
            return index < candidates.size() ? candidates.get(index).id : "";
        }

        boolean recognized(String documentId) {
            return enabled && documentId != null && !documentId.isEmpty()
                    && recognized.contains(documentId);
        }
    }

    private DriveSignalDocumentIdentity() {}

    static void activate(Context context, String runId) {
        synchronized (LOCK) {
            activeContext = context == null ? null : context.getApplicationContext();
            activeRunId = safe(runId);
            activeCandidates.clear();
            sealed = false;
        }
    }

    static void observeCandidate(String id, String title, String createdTime, String runId) {
        synchronized (LOCK) {
            if (activeContext == null || !safe(runId).equals(activeRunId)
                    || !DriveApiClient.validFileId(id)) return;
            for (Candidate candidate : activeCandidates) if (candidate.id.equals(id)) return;
            activeCandidates.add(new Candidate(id, title, createdTime));
        }
    }

    /**
     * Reconstructs a safe handled-ID baseline from the exact ID persisted after the previous poll.
     * This is called only after the current Job-folder candidate list has been collected.
     */
    static void preparePollOrdering(String runId) {
        Context context;
        ArrayList<Candidate> ordered;
        synchronized (LOCK) {
            if (activeContext == null || !safe(runId).equals(activeRunId)) return;
            context = activeContext;
            ordered = new ArrayList<>(activeCandidates);
        }
        ordered.sort(CANDIDATE_ORDER);
        Set<String> stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(seenKey(runId), Collections.emptySet());
        HashSet<String> recognized = new HashSet<>(stored == null ? Collections.emptySet() : stored);
        String lastSeenId = signalIdFromVersion(context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE)
                .getString(STORE_LAST_SEEN_VERSION, ""));
        Candidate boundary = findById(ordered, lastSeenId);
        boolean changed = false;
        if (boundary != null) {
            long boundaryCreated = DriveSignalDocumentTransport.createdMillis(boundary.createdTime);
            for (Candidate candidate : ordered) {
                long created = DriveSignalDocumentTransport.createdMillis(candidate.createdTime);
                if (candidate.id.equals(lastSeenId)
                        || (created >= 0L && boundaryCreated >= 0L && created < boundaryCreated)) {
                    changed |= recognized.add(candidate.id);
                }
            }
        }
        if (changed) persistRecognized(context, runId, recognized);
    }

    static void seal(String runId) {
        synchronized (LOCK) {
            if (activeContext != null && safe(runId).equals(activeRunId)) sealed = true;
        }
    }

    static boolean recognizedForPollOrdering(String runId, String documentId) {
        Context context;
        synchronized (LOCK) {
            if (activeContext == null || !safe(runId).equals(activeRunId)) return false;
            context = activeContext;
        }
        String id = safe(documentId);
        if (id.isEmpty()) return false;
        Set<String> recognized = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(seenKey(runId), Collections.emptySet());
        return recognized != null && recognized.contains(id);
    }

    static Resolver resolver(String runId, int ignoredLegacyCursor) {
        Context context;
        ArrayList<Candidate> ordered;
        synchronized (LOCK) {
            if (!sealed || activeContext == null || !safe(runId).equals(activeRunId)) {
                return new Resolver(false, Collections.emptySet(), Collections.emptyMap());
            }
            context = activeContext;
            ordered = new ArrayList<>(activeCandidates);
        }
        Set<String> stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(seenKey(runId), Collections.emptySet());
        HashSet<String> recognized = new HashSet<>(stored == null ? Collections.emptySet() : stored);
        ordered.sort((left, right) -> {
            boolean leftKnown = recognized.contains(left.id);
            boolean rightKnown = recognized.contains(right.id);
            if (leftKnown != rightKnown) return leftKnown ? -1 : 1;
            return CANDIDATE_ORDER.compare(left, right);
        });

        HashMap<String, List<Candidate>> byTitle = new HashMap<>();
        for (Candidate candidate : ordered) {
            byTitle.computeIfAbsent(candidate.title, ignored -> new ArrayList<>()).add(candidate);
        }
        for (Map.Entry<String, List<Candidate>> entry : byTitle.entrySet()) {
            entry.setValue(Collections.unmodifiableList(entry.getValue()));
        }
        return new Resolver(true, Collections.unmodifiableSet(recognized),
                Collections.unmodifiableMap(byTitle));
    }

    static String signalIdFromVersion(String version) {
        String value = safe(version);
        if (!value.startsWith(SIGNAL_VERSION_PREFIX)) return "";
        int start = SIGNAL_VERSION_PREFIX.length();
        int end = value.indexOf(':', start);
        if (end <= start) return "";
        String id = value.substring(start, end);
        return DriveApiClient.validFileId(id) ? id : "";
    }

    static List<String> unseenIds(List<String> orderedIds, Set<String> recognizedIds) {
        ArrayList<String> result = new ArrayList<>();
        Set<String> recognized = recognizedIds == null ? Collections.emptySet() : recognizedIds;
        if (orderedIds != null) {
            for (String id : orderedIds) {
                if (DriveApiClient.validFileId(id) && !recognized.contains(id)) result.add(id);
            }
        }
        return Collections.unmodifiableList(result);
    }

    static String latestRecognizedSignalId(Context context) {
        if (context == null) return "";
        return signalIdFromVersion(context.getApplicationContext()
                .getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE)
                .getString(STORE_LAST_SEEN_VERSION, ""));
    }

    private static void persistRecognized(Context context, String runId, Set<String> recognized) {
        boolean committed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(seenKey(runId), new HashSet<>(recognized)).commit();
        if (!committed) throw new IllegalStateException("signal document identity persistence failed");
    }

    private static final Comparator<Candidate> CANDIDATE_ORDER = (left, right) -> {
        int created = Long.compare(DriveSignalDocumentTransport.createdMillis(left.createdTime),
                DriveSignalDocumentTransport.createdMillis(right.createdTime));
        if (created != 0) return created;
        int title = titleTimestamp(left.title).compareTo(titleTimestamp(right.title));
        if (title != 0) return title;
        return left.id.compareTo(right.id);
    };

    private static String titleTimestamp(String title) {
        String value = safe(title);
        int end = value.indexOf(']');
        return value.startsWith("[") && end > 1 ? value.substring(1, end) : "";
    }

    private static String candidateTitle(String logicalSignal) {
        String value = safe(logicalSignal).trim();
        return NEXT_INPUT_VALUE.matcher(value)
                .replaceFirst(DriveSignalDocumentTransport.NEXT_INPUT_BODY_MARKER);
    }

    private static Candidate findById(List<Candidate> candidates, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Candidate candidate : candidates) if (id.equals(candidate.id)) return candidate;
        return null;
    }

    private static String seenKey(String runId) { return SEEN_PREFIX + safe(runId); }
    private static String safe(String value) { return value == null ? "" : value; }
}
