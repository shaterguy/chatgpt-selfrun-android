package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class WebViewDocumentContextProbeTest {
    @Test public void routeSignatureClassifiesCanonicalAndProjectRoutesWithoutIds() {
        assertEquals("root:0", WebViewDocumentContextProbe.routeSignature("https://chatgpt.com/"));
        assertEquals("c:2", WebViewDocumentContextProbe.routeSignature("https://chatgpt.com/c/abc"));
        assertEquals("g:3", WebViewDocumentContextProbe.routeSignature("https://chatgpt.com/g/g-p-abc/project"));
        assertEquals("o:2", WebViewDocumentContextProbe.routeSignature("https://chatgpt.com/foo/bar"));
    }

    @Test public void scriptIsPassiveAndCapturesDocumentIdentityOnly() {
        String script = WebViewDocumentContextProbe.script();
        assertTrue(script.contains("document.documentURI"));
        assertTrue(script.contains("performance.timeOrigin"));
        assertTrue(script.contains("history.state"));
        assertTrue(script.contains("#prompt-textarea"));
        assertTrue(script.contains("form[data-type=\"unified-composer\"]"));
        assertTrue(script.contains("[role=\"textbox\"]"));
        assertTrue(script.contains("[contenteditable]"));
        assertTrue(script.contains(".ProseMirror"));
        assertFalse(script.contains("innerText"));
        assertFalse(script.contains("textContent"));
        assertFalse(script.contains("localStorage"));
        assertFalse(script.contains("sessionStorage"));
        assertFalse(script.contains("dispatchEvent"));
        assertFalse(script.contains(".click()"));
        assertFalse(script.contains(".reload()"));
    }
}
