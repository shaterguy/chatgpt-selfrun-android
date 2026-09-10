package com.shaterguy.chatgptselfrun;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Pure eligibility and body-identity policy for stale Drive result recovery. */
final class SelfRun3ResultWatchdog {
    static final long STALE_AFTER_MS = SelfRun3RuntimeSettings.DEFAULT_RESULT_REPAIR_MINUTES * 60_000L;

    static String normalizeDriveBody(String raw) {
        if (raw == null) return "";
        if (raw.endsWith("\r\n")) return raw.substring(0, raw.length() - 2);
        if (raw.endsWith("\n")) return raw.substring(0, raw.length() - 1);
        return raw;
    }

    static String fingerprint(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizeDriveBody(raw).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static boolean shouldRepair(SelfRun3Engine.State state, long nowElapsed, int currentBootCount) {
        return shouldRepair(state, nowElapsed, currentBootCount, STALE_AFTER_MS);
    }

    static boolean shouldRepair(SelfRun3Engine.State state, long nowElapsed, int currentBootCount,
                                long staleAfterMs) {
        if (staleAfterMs <= 0L || state == null || state.stage() != SelfRun3Engine.Stage.WAITING
                || !"WAITING".equals(state.text("stage"))) return false;
        if (!state.flag("sendClaimed") || state.flag("committed") || state.hasResult()
                || state.flag("superseded") || state.number("repairAttempt") != 0) return false;
        String resultDocumentId = state.resource("resultDocumentId");
        if (resultDocumentId.isEmpty() || !resultDocumentId.equals(state.text("resultSeedDocumentId"))
                || state.text("resultSeedFingerprint").isEmpty()
                || !state.flag("resultBodyMutationObserved")) return false;
        org.json.JSONObject snapshot = state.json();
        if (!snapshot.has("canonicalPostConfirmedElapsed") || !snapshot.has("canonicalPostBootCount")) return false;
        long submittedElapsed = state.time("canonicalPostConfirmedElapsed");
        int submittedBootCount = state.number("canonicalPostBootCount");
        if (submittedElapsed < 0L || currentBootCount < 0 || submittedBootCount != currentBootCount
                || nowElapsed < submittedElapsed) return false;
        return nowElapsed - submittedElapsed >= staleAfterMs;
    }

    private SelfRun3ResultWatchdog() { }
}
