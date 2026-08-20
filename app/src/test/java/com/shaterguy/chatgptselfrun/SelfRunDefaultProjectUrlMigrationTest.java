package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SelfRunDefaultProjectUrlMigrationTest {
    private static final String PROJECT_ID = "g-p-6a582c824ba08191ac7e74e9bad721fc";
    private static final String CANONICAL = "https://chatgpt.com/g/" + PROJECT_ID + "/project";

    @Test public void legacySlugDefaultCanonicalizesToSameProject() {
        String legacy = "https://chatgpt.com/g/" + PROJECT_ID + "-vibe-coding/project";
        assertEquals(CANONICAL, SelfRunStore.canonicalStoredProjectUrl(legacy));
    }

    @Test public void canonicalDefaultRemainsStable() {
        assertEquals(CANONICAL, SelfRunStore.canonicalStoredProjectUrl(CANONICAL));
    }

    @Test public void legacyOpaqueProjectIdIsPreserved() {
        String opaque = "https://chatgpt.com/g/g-p-Ab_9/project";
        assertEquals(opaque, SelfRunStore.canonicalStoredProjectUrl(opaque));
    }

    @Test public void invalidLegacyValueIsNotDestroyed() {
        String invalid = "https://example.com/g/" + PROJECT_ID + "-vibe-coding/project";
        assertEquals(invalid, SelfRunStore.canonicalStoredProjectUrl(invalid));
    }

    @Test public void missingDefaultRemainsEmpty() {
        assertEquals("", SelfRunStore.canonicalStoredProjectUrl(null));
        assertEquals("", SelfRunStore.canonicalStoredProjectUrl(""));
    }
}
