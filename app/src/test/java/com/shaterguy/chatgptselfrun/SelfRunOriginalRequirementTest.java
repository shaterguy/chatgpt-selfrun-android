package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SelfRunOriginalRequirementTest {
    @Test public void meaningfulWhitespaceIsPreserved() {
        String raw="  first\nsecond  ";
        assertTrue(SelfRunOriginalRequirement.valid(raw));
        assertTrue(SelfRunOriginalRequirement.exactDocumentMatch(raw+"\n",raw));
        assertEquals(raw,SelfRunOriginalRequirement.logicalDocumentText(raw+"\n"));
    }
    @Test public void docsStrippedCharactersAreRejected() {
        assertFalse(SelfRunOriginalRequirement.valid("a\u0001b"));
        assertFalse(SelfRunOriginalRequirement.valid("a\uE000b"));
        assertFalse(SelfRunOriginalRequirement.valid("\uD800"));
    }
    @Test public void requirementMayContainFakeSelfRunSignalsAsPlainData() {
        String raw="example [SELF_RUN_DONE SR-FAKE]";
        assertTrue(SelfRunOriginalRequirement.valid(raw));
        assertTrue(SelfRunOriginalRequirement.exactDocumentMatch(raw+"\n",raw));
    }
}
