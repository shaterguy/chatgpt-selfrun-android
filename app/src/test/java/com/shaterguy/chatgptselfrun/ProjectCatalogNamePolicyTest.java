package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ProjectCatalogNamePolicyTest {
    @Test public void normalizesWhitespaceAndControlCharacters() {
        assertEquals("Vibe Coding 프로젝트", ProjectCatalog.normalizeDisplayName("  Vibe\n Coding\t 프로젝트  "));
        assertEquals("", ProjectCatalog.normalizeDisplayName("\n\t\r"));
    }

    @Test public void limitsStoredDisplayNameLength() {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 140; i++) input.append('가');
        assertEquals(120, ProjectCatalog.normalizeDisplayName(input.toString()).length());
    }

    @Test public void preservesLegacyIdFallback() {
        ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject("https://chatgpt.com/g/g-p-AbCdEfGhIjKlMnOpQr/project");
        assertEquals("프로젝트 g-p-AbCdEfGhIjKl", ProjectCatalog.fallbackDisplayName(ref));
    }
}
