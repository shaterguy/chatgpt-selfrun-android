package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class ProjectDisplayNameWiringTest {
    @Test public void registrationCapturesNameFromTheVisitedProjectPage() throws Exception {
        String source = src("LoginActivity.java");
        assertTrue(source.contains("document.querySelectorAll('a[href]')"));
        assertTrue(source.contains("u.pathname===canonical"));
        assertTrue(source.contains("onReceivedTitle"));
        assertTrue(source.contains("catalog.addVisitedProject(ref.canonicalUrl, visit.name)"));
    }

    @Test public void pickerUsesStoredNameAndCatalogKeepsLegacySchemaReadable() throws Exception {
        String picker = src("SelfRunNewActivity.java");
        String catalog = src("ProjectCatalog.java");
        assertTrue(picker.contains("labels.add(catalog.displayName(entry))"));
        assertTrue(catalog.contains("LEGACY_SCHEMA = 4"));
        assertTrue(catalog.contains("SCHEMA = 5"));
        assertTrue(catalog.contains("KEY_NAME_PREFIX = \"project_name:\""));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
