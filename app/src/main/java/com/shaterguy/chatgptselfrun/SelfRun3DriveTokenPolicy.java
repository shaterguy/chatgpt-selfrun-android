package com.shaterguy.chatgptselfrun;

/** Memory-only token freshness policy. The age bound is proactive; HTTP 401 remains authoritative. */
final class SelfRun3DriveTokenPolicy {
    static final long MAX_TOKEN_AGE_MS = 45L * 60_000L;

    enum UnauthorizedAction { REFRESH_AND_RETRY, BACKOFF }

    static boolean needsRefresh(String token, long issuedElapsed, long nowElapsed) {
        if (token == null || token.isEmpty()) return true;
        if (issuedElapsed < 0L || nowElapsed < issuedElapsed) return true;
        return nowElapsed - issuedElapsed >= MAX_TOKEN_AGE_MS;
    }

    static UnauthorizedAction onUnauthorized(int authRetryAttempt) {
        return authRetryAttempt <= 0 ? UnauthorizedAction.REFRESH_AND_RETRY : UnauthorizedAction.BACKOFF;
    }

    private SelfRun3DriveTokenPolicy() { }
}
