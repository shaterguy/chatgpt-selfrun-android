package com.shaterguy.chatgptselfrun;

import android.content.Context;

/** V3 launch-marker facade retained for existing UI call sites. */
final class SelfRunSignalTransport {
    private SelfRunSignalTransport() {}

    static boolean mark(Context context, String runId) {
        return SelfRun3RunMarker.mark(context, runId);
    }

    static boolean isSignalDocumentRun(Context context, String runId) {
        return SelfRun3RunMarker.current(context, runId);
    }
}
