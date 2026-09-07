package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class SelfRunContinuationDiagnosticsDomTest {
    @Test public void snapshotIsPassivePrivacySafeAndDeep() {
        String script = SelfRunContinuationDiagnosticsDom.snapshot();
        assertTrue(script.contains("document.visibilityState"));
        assertTrue(script.contains("document.documentURI"));
        assertTrue(script.contains("performance.timeOrigin"));
        assertTrue(script.contains("history.state"));
        assertTrue(script.contains("document.elementsFromPoint"));
        assertTrue(script.contains("contentDocument"));
        assertTrue(script.contains("shadowRoot"));
        assertTrue(script.contains("new MutationObserver"));
        assertTrue(script.contains("requestAnimationFrame"));
        assertTrue(script.contains("[contenteditable]"));
        assertTrue(script.contains("[role=\"textbox\"]"));
        assertTrue(script.contains(".ProseMirror"));
        assertTrue(script.contains("data-lexical-editor"));
        assertTrue(script.contains("stage===0"));
        assertTrue(script.contains("stage===1"));
        assertTrue(script.contains("s=2"));
        assertTrue(script.contains("v=5"));
        assertTrue(script.contains("replace(/prompt/gi,'p~')"));
        assertFalse(script.contains("innerText"));
        assertFalse(script.contains("textContent"));
        assertFalse(script.contains("placeholder"));
        assertFalse(script.contains("localStorage"));
        assertFalse(script.contains("sessionStorage"));
        assertFalse(script.contains("dispatchEvent"));
        assertFalse(script.contains(".click()"));
        assertFalse(script.contains(".reload()"));
    }
}
