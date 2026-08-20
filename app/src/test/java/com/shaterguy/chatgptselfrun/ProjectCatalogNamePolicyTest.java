package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    @Test public void storedSlugAliasCollapsesWithoutMergingDifferentProjects() {
        String first = "g-p-6a582c824ba08191ac7e74e9bad721fc";
        String second = "g-p-7b582c824ba08191ac7e74e9bad721fc";
        Set<String> raw = new LinkedHashSet<>();
        raw.add("https://chatgpt.com/g/" + first + "-vibe-coding/project");
        raw.add("https://chatgpt.com/g/" + first + "/project");
        raw.add("https://chatgpt.com/g/" + second + "/project");
        Set<String> canonical = ProjectCatalog.canonicalizeStoredUrls(raw);
        assertEquals(2, canonical.size());
        assertTrue(canonical.contains("https://chatgpt.com/g/" + first + "/project"));
        assertTrue(canonical.contains("https://chatgpt.com/g/" + second + "/project"));
    }

    @Test public void legacySluggedNameIsRecoveredForCanonicalProjectId() {
        String id = "g-p-6a582c824ba08191ac7e74e9bad721fc";
        Map<String, Object> values = new HashMap<>();
        values.put("project_name:" + id + "-vibe-coding", "Vibe Coding");
        assertEquals("Vibe Coding", ProjectCatalog.legacyDisplayName(values, id));
        values.put("project_name:" + id, "Canonical Name");
        assertEquals("Canonical Name", ProjectCatalog.legacyDisplayName(values, id));
    }
}
