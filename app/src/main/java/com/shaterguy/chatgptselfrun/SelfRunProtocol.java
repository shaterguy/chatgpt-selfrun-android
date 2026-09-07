package com.shaterguy.chatgptselfrun;

/** Shared validation facade retained for UI/profile call sites; V2 transport generation is retired. */
final class SelfRunProtocol {
    static final String SELF_RUN_SKILL_DOCUMENT_ID = SelfRun3Protocol.SELF_RUN_SKILL_DOCUMENT_ID;

    private SelfRunProtocol() {}

    static boolean validWorkProfile(String model, String reasoning) {
        return ProfileRegistry.resolveWork(model, reasoning) != null;
    }

    static boolean safeRecoveryId(String value) {
        return SelfRunProtocolRules.validRecoveryId(value);
    }
}
