package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SelfRun3ProjectDirectoryNavigationTest {
    private static final String PROJECT = "g-p-6a507cce80cc81919eeb9ba553b6ad9e";
    private static final String TARGET = "https://chatgpt.com/g/" + PROJECT + "/project";

    @Test public void projectEntryUsesProjectsDirectoryWithoutChangingGeneralEntry() {
        assertEquals(SelfRun3ProjectDirectoryNavigation.DIRECTORY_URL,
                SelfRun3ProjectDirectoryNavigation.entryUrl(TARGET));
        assertEquals(SelfRunScript.GENERAL_CHAT_URL,
                SelfRun3ProjectDirectoryNavigation.entryUrl(SelfRunScript.GENERAL_CHAT_URL));
        assertTrue(SelfRun3ProjectDirectoryNavigation.isDirectoryPage("https://chatgpt.com/projects"));
        assertTrue(SelfRun3ProjectDirectoryNavigation.isDirectoryPage("https://chatgpt.com/projects/"));
        assertFalse(SelfRun3ProjectDirectoryNavigation.isDirectoryPage(TARGET));
    }

    @Test public void sameProjectSlugRouteStopsDirectoryStepAndWrongProjectIsDetected() {
        assertTrue(SelfRun3ProjectDirectoryNavigation.needsDirectoryStep(
                TARGET, SelfRun3ProjectDirectoryNavigation.DIRECTORY_URL));
        assertFalse(SelfRun3ProjectDirectoryNavigation.needsDirectoryStep(
                TARGET, "https://chatgpt.com/g/" + PROJECT + "-vibe-coding/project"));
        assertTrue(SelfRun3ProjectDirectoryNavigation.isWrongProjectRoute(
                TARGET, "https://chatgpt.com/g/g-p-7a507cce80cc81919eeb9ba553b6ad9e/project"));
        assertFalse(SelfRun3ProjectDirectoryNavigation.isWrongProjectRoute(
                TARGET, "https://chatgpt.com/g/" + PROJECT + "-vibe-coding/project"));
    }

    @Test public void generatedScriptTargetsCapturedSelectableRowsAndInvokesRowClick() {
        String script = SelfRun3ProjectDirectoryNavigation.build("💾 Vibe Coding", 1);
        assertTrue(script.contains("location.pathname!=='/projects'"));
        assertTrue(script.contains("[role=\"row\"][data-page-table-selectable-row=\"true\"]"));
        assertTrue(script.contains("button[aria-label]"));
        assertTrue(script.contains("pick.row.click()"));
        assertTrue(script.contains("const ordinal=1"));
        assertTrue(script.contains("💾 Vibe Coding"));
    }
}
