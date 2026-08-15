package com.shaterguy.chatgptselfrun;

/** Pure policy for deciding whether continuation must stop for user action. */
final class SelfRunContinuationCapability {
    private SelfRunContinuationCapability() {}

    static boolean requiresUserAction(boolean continuationPhase, boolean parentGuardAvailable) {
        return continuationPhase && !parentGuardAvailable;
    }
}
