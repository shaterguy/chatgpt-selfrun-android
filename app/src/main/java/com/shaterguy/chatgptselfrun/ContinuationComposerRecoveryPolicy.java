package com.shaterguy.chatgptselfrun;

/** Decides whether a detached-surface continuation needs one same-conversation SPA remount. */
final class ContinuationComposerRecoveryPolicy {
    private static final long MIN_AFTER_ATTACH_MS = 1_000L;

    private ContinuationComposerRecoveryPolicy() {}

    static boolean shouldReload(String phase, boolean visualReady, long afterAttachMs,
                                int attachGeneration, int recoveredGeneration, String diagnostics) {
        return SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)
                && visualReady
                && afterAttachMs >= MIN_AFTER_ATTACH_MS
                && attachGeneration > 0
                && recoveredGeneration != attachGeneration
                && diagnostics != null
                && diagnostics.contains("semantic=0");
    }
}
