package com.shaterguy.chatgptselfrun;

/** One-shot run-scoped latch for TURN_INFO_REWRITE requests. */
final class SelfRunTurnInfoRewriteGate {
    private String runId = "";

    synchronized void request(String candidateRunId) {
        if (SelfRunProtocolRules.validRunId(candidateRunId)) runId = candidateRunId;
    }

    synchronized boolean consume(String candidateRunId) {
        if (!SelfRunProtocolRules.validRunId(candidateRunId)
                || !candidateRunId.equals(runId)) return false;
        runId = "";
        return true;
    }
}
