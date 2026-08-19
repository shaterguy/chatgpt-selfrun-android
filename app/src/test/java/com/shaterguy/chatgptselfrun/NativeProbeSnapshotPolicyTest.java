package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class NativeProbeSnapshotPolicyTest {
    @Test public void nativeSnapshotOnlyRequestsExistingPageObserverState() {
        String script = ConversationSyncInstrumentation.requestSnapshotScript();
        assertTrue(script.contains("snapshotNow"));
        assertFalse(script.contains("fetch("));
        assertFalse(script.contains("reload"));
        assertFalse(script.contains("location="));
    }
}
