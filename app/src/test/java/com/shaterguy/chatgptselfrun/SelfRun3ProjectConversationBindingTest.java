package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public final class SelfRun3ProjectConversationBindingTest {
    @Test public void projectConversationIsNormalizedAfterSameProjectRouteValidation() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("String conversationId = SelfRunScript.conversationId(url);"));
        assertTrue(web.contains("if (allowedRoute(url) && !conversationId.isEmpty())"));
        assertTrue(web.contains("\"https://chatgpt.com/c/\" + conversationId"));
        assertTrue(web.contains("SelfRunScript.projectId(state.config().optString(\"projectUrl\"))"));
        assertTrue(web.contains(".equals(SelfRunScript.projectId(url))"));
    }

    @Test public void engineStillPinsOnlyCanonicalConversationResource() throws Exception {
        String engine = source("SelfRun3Engine.java");
        assertTrue(engine.contains("val.matches(\"https://chatgpt\\\\.com/c/[A-Za-z0-9-]+\")"));
        assertTrue(engine.contains("pinned resource cannot change"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
