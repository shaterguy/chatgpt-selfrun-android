package com.shaterguy.chatgptselfrun;

import java.util.ArrayList;
import java.util.List;

/** Pure completion-recovery decisions shared by runtime wiring and unit tests. */
final class TurnCompletionRecoveryPolicy {
    static final String REBIND_HOST = "turn-watchdog-rebind";
    static final String DRIVE_PROBE_HOST = "turn-watchdog-probe";
    static final String RECOVER_HOST = "turn-watchdog-recover";

    private TurnCompletionRecoveryPolicy() {}

    static boolean validWatchdogCallback(SelfRunStore store, String runId, String token) {
        return store != null && SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase())
                && runId != null && runId.equals(store.runId())
                && token != null && !token.isEmpty() && token.equals(store.turnObserverToken());
    }

    static boolean hasUsableDriveCompletion(DriveSignalParser.Scan scan) {
        if (scan == null || scan.cursorRebased) return false;
        List<DriveSignalParser.Event> normal = new ArrayList<>();
        for (DriveSignalParser.Event event : scan.unseen) {
            if (event.type == DriveSignalParser.Type.TURN_COMPLETED
                    && DriveSignalParser.hasRecoveryIdField(event.raw)) continue;
            normal.add(event);
        }
        DriveSignalParser.Event completion = DriveSignalParser.latestCompletion(normal);
        DriveSignalParser.Event blocking = DriveSignalParser.latestBlocking(scan.unseen);
        return completion != null && completion.protocolError.isEmpty()
                && (blocking == null || completion.cursor > blocking.cursor);
    }
}
