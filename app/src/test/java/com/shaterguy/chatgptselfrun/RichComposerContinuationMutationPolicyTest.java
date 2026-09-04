package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Locks the rich-contenteditable continuation path to one logical edit notification cycle. */
public final class RichComposerContinuationMutationPolicyTest {
    @Test public void richEditorObservesNativeInputBeforeUsingSyntheticFallback() {
        String script = SelfRunContinuationDom.prepareDriveTurn(
                "https://chatgpt.com/c/conversation123",
                "[SELF_RUN_CONTINUE SR-RICH-CONTINUATION]",
                "SR-RICH-CONTINUATION:continue:turn-2");

        assertTrue(script.contains("const observeInput=operation=>"));
        assertTrue(script.contains("observeInput(()=>editorDocument.execCommand('delete',false,null))"));
        assertTrue(script.contains("observeInput(()=>editorDocument.execCommand('insertText',false,expected))"));
        assertTrue(script.contains("fallbackChanged||!native.seen"));

        // Only textarea/value editing retains synthetic change events: clear + insert.
        assertEquals(2, count(script, "new Event('change'"));
        assertFalse(script.contains("composer.dispatchEvent(new Event('change',{bubbles:true}));}};const requestComposerSubmit"));
    }

    private static int count(String text, String needle) {
        int count = 0, from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) return count;
            count++;
            from = at + needle.length();
        }
    }
}
