package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class SelfRunContinuationDiagnosticsDomTest {
    @Test public void snapshotIsPassiveAndDoesNotReadMessageText() {
        String script = SelfRunContinuationDiagnosticsDom.snapshot();
        assertTrue(script.contains("document.visibilityState"));
        assertTrue(script.contains("offsetParent"));
        assertTrue(script.contains("getBoundingClientRect"));
        assertTrue(script.contains("isContentEditable"));
        assertFalse(script.contains("innerText"));
        assertFalse(script.contains("textContent"));
        assertFalse(script.contains("localStorage"));
        assertFalse(script.contains("sessionStorage"));
        assertFalse(script.contains("dispatchEvent"));
        assertFalse(script.contains(".click()"));
    }
}
