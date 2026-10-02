package com.shaterguy.chatgptselfrun;

import android.content.Context;

/** Selects only submission transport. Completion always uses the shared Vercel path. */
final class SelfRunSubmissionAdapter {
    private final SelfRun3WebAdapter onDevice;
    private final SelfRun4DriveWebAdapter server;
    private SelfRun3Engine.State controlState;

    SelfRunSubmissionAdapter(Context context, SelfRun3WebAdapter.Listener listener) {
        onDevice = new SelfRun3WebAdapter(context, listener);
        server = new SelfRun4DriveWebAdapter(context, listener);
    }

    static SelfRun3RuntimeSettings.WorkMode mode(SelfRun3Engine.State state) {
        // Existing v4 requests had no marker and were submitted by the server.
        // Never recover them through the phone and risk a duplicate submission.
        return state != null && "ON_DEVICE".equals(state.text("submissionMode"))
                ? SelfRun3RuntimeSettings.WorkMode.ON_DEVICE
                : SelfRun3RuntimeSettings.WorkMode.SERVER;
    }

    void prepare(SelfRun3Engine.State state) {
        controlState = state;
        if (mode(state) == SelfRun3RuntimeSettings.WorkMode.ON_DEVICE) onDevice.prepare(state);
        else server.prepare(state);
    }

    void submit(SelfRun3Engine.State state) {
        controlState = state;
        if (mode(state) == SelfRun3RuntimeSettings.WorkMode.ON_DEVICE) onDevice.submit(state);
        else server.submit(state);
    }

    void restoreAccessToken(String token) { server.restoreAccessToken(token); }

    void syncControlState(SelfRun3Engine.State state) {
        controlState = state;
        server.syncControlState(state);
    }

    void publishControlState(String control, String reason) {
        server.publishControlState(controlState, control, reason);
    }

    void publishControlStateConfirmed(SelfRun3Engine.State state, String control, String reason,
                                      SelfRun4DriveWebAdapter.ControlWriteCallback callback) {
        server.publishControlStateConfirmed(state, control, reason, callback);
    }

    void detach() {
        onDevice.detach();
        server.detach();
    }

    void quiesce() {
        onDevice.quiesce();
        server.quiesce();
    }

    boolean disposeForConfirmedResultWait(SelfRun3Engine.State state) {
        return mode(state) == SelfRun3RuntimeSettings.WorkMode.ON_DEVICE
                ? onDevice.disposeForConfirmedResultWait(state)
                : server.disposeForConfirmedResultWait(state);
    }

    void close() {
        onDevice.close();
        server.close();
    }
}
