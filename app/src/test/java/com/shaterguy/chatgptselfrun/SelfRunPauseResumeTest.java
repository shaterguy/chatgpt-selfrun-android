package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelfRunPauseResumeTest {
    @Test
    public void userActionPausePreservesCurrentWebView() {
        assertTrue(SelfRunService.preservesWebViewOnPause(SelfRunProtocol.Type.USER_ACTION));
    }

    @Test
    public void protocolPauseUsesTheSamePreservedWebViewPolicy() {
        assertTrue(SelfRunService.preservesWebViewOnPause(SelfRunProtocol.Type.PAUSE));
    }

    @Test
    public void terminalSignalsDoNotUseResumablePausePolicy() {
        assertFalse(SelfRunService.preservesWebViewOnPause(SelfRunProtocol.Type.DONE));
        assertFalse(SelfRunService.preservesWebViewOnPause(SelfRunProtocol.Type.NEXT));
    }
}
