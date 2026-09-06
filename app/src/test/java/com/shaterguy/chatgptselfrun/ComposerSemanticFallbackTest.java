package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class ComposerSemanticFallbackTest {
    @Test public void runtimeMatcherSupportsPlaintextOnlyAndFutureContenteditableValues() {
        String script = WebUiCalibrationDom.runtimePrelude();
        assertTrue(script.contains("#prompt-textarea"));
        assertTrue(script.contains("[data-testid=\"prompt-textarea\"]"));
        assertTrue(script.contains("[contenteditable]"));
        assertTrue(script.contains("[role=\"textbox\"]"));
        assertTrue(script.contains(".ProseMirror"));
        assertTrue(script.contains("[data-lexical-editor=\"true\"]"));
        assertTrue(script.contains("v!=='false'"));
        assertTrue(script.contains("__srCurrentComposer"));
        assertTrue(script.contains("source=current;profile=0"));
        assertTrue(script.contains("profile=1"));
    }

    @Test public void calibrationCaptureUsesSemanticComposerPool() {
        String script = WebUiCalibrationDom.install(WebUiCalibrationStore.PURPOSE_GENERAL_NEW_CHAT);
        assertTrue(script.contains("[contenteditable]"));
        assertTrue(script.contains("[role=\"textbox\"]"));
        assertTrue(script.contains("prompt-textarea"));
        assertTrue(script.contains("v!=='false'"));
    }

    @Test public void continuationInheritsSemanticMatcherBeforeLegacyFallback() {
        String script = SelfRunContinuationDom.prepareBootstrap(
                "https://chatgpt.com/", "probe", "semantic-test");
        int semantic = script.indexOf("const __srCurrentComposer");
        int legacy = script.indexOf("const composerSelectors=");
        assertTrue(semantic >= 0);
        assertTrue(legacy > semantic);
        assertTrue(script.contains("source=current;profile=0"));
    }

    @Test public void diagnosticsRecognizeAnyContenteditableWithoutReadingMessageText() {
        String script = SelfRunContinuationDiagnosticsDom.snapshot();
        assertTrue(script.contains("[contenteditable]"));
        assertTrue(script.contains("plaintext-only"));
        assertTrue(script.contains("ceattr="));
        assertTrue(script.contains("semantic="));
        assertFalse(script.contains("innerText"));
        assertFalse(script.contains("textContent"));
    }
}
