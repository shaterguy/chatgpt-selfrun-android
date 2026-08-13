package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import static org.junit.Assert.*;

public class SelfRunDomTest {
    @Test public void controlReadIsBestEffortOnly() {
        String script = SelfRunDom.readLatestSelfRunControl(
                "https://chatgpt.com/g/g-p-test/c/conversation123", "SR-20260813-220315-A1B2C3");
        assertTrue(script.contains("SELF_RUN_NEXT"));
        assertTrue(script.contains("CONTROL_MISSING"));
        assertFalse(script.contains("stop-button"));
        assertFalse(script.contains("GENERATING"));
        assertFalse(script.contains("data-is-streaming"));
    }

    @Test public void continuationOnlyStagesComposerAndMarker() {
        String script = SelfRunDom.prepareDriveTurn(
                "https://chatgpt.com/g/g-p-test/c/conversation123",
                "[2026.08.13 | 22:03:15] [SELF_RUN_CONTINUE SR-20260813-220315-A1B2C3]", "m1");
        assertTrue(script.contains("READY_TO_SUBMIT"));
        assertFalse(script.contains("CONFIRMED"));
        assertFalse(script.contains("assistant"));
    }
}
