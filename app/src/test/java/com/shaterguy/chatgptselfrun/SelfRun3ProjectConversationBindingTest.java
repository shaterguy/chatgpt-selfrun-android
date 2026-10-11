package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class SelfRun3ProjectConversationBindingTest {
    private static final String RAW = "https://chatgpt.com/g/g-p-6a582c824ba08191ac7e74e9bad721fc-vibe-coding/c/6acb731a-b770-83ec-81e8-f5f4ac3fb1fc";

    @Test public void onDeviceProjectRouteOwnershipValidationRemainsUnchanged() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("String conversationId = SelfRunScript.conversationId(url);"));
        assertTrue(web.contains("if (allowedRoute(url) && !conversationId.isEmpty())"));
        assertTrue(web.contains("\"https://chatgpt.com/c/\" + conversationId"));
        assertTrue(web.contains("SelfRunScript.projectId(state.config().optString(\"projectUrl\"))"));
        assertTrue(web.contains(".equals(SelfRunScript.projectId(url))"));
    }

    @Test public void namedProjectConversationIsStoredWithoutLosingOriginalAddress() throws Exception {
        SelfRun3Engine.State state = bind(initial(), RAW);
        assertEquals(RAW, state.resource("conversationUrl"));
        assertEquals(RAW, state.history().getJSONObject(0).getString("conversationUrl"));
    }

    @Test public void rawAddressSurvivesSnapshotRestore() throws Exception {
        SelfRun3Engine.State original = bind(initial(), RAW);
        SelfRun3Engine.State restored = new SelfRun3Engine.State(new JSONObject(original.json().toString()));
        assertEquals(RAW, restored.resource("conversationUrl"));
        assertEquals(RAW, restored.execution(original.turnId()).resource("conversationUrl"));
        assertEquals(RAW, restored.history().getJSONObject(0).getString("conversationUrl"));
    }

    @Test public void generalAndOtherValidProjectAddressesNeedNoProjectAllowlist() throws Exception {
        for (String url : new String[]{"https://chatgpt.com/c/owned-1", "https://www.chatgpt.com/c/owned_2", "https://chatgpt.com/g/g-p-another-project/c/another-3"})
            assertEquals(url, bind(initial(), url).resource("conversationUrl"));
    }

    @Test public void samePinnedAddressIsIdempotent() throws Exception {
        SelfRun3Engine.State state = bind(initial(), RAW);
        assertSame(state, bind(state, RAW));
    }

    @Test public void differentConversationCannotOverwritePinnedResource() throws Exception {
        SelfRun3Engine.State state = bind(initial(), RAW);
        try { bind(state, "https://chatgpt.com/c/different-conversation"); fail("pinned resource changed"); }
        catch (IllegalStateException expected) { assertEquals("pinned resource cannot change", expected.getMessage()); }
        assertEquals(RAW, state.resource("conversationUrl"));
    }

    @Test public void unsafeAndNonConversationUrlsRemainRejected() throws Exception {
        for (String url : new String[]{"http://chatgpt.com/c/owned-1", "https://evil.example/c/owned-1", "https://chatgpt.com@evil.example/c/owned-1", "https://user@chatgpt.com/c/owned-1", "https://chatgpt.com/", "https://chatgpt.com/g/g-p-any/project", "https://chatgpt.com/g/g-p-any/c/a%2Fb", "https://chatgpt.com/c/local-chatgpt"}) {
            try { bind(initial(), url); fail("accepted invalid URL: " + url); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("conversation URL")); }
        }
    }

    @Test public void foreignTaskAndUnknownTurnCannotBindResource() throws Exception {
        SelfRun3Engine.State state = initial();
        assertSame(state, SelfRun3Engine.reduce(state, event("other-task", state.turnId(), RAW)));
        assertSame(state, SelfRun3Engine.reduce(state, event(state.taskId(), "unknown-turn", RAW)));
        assertEquals("", state.resource("conversationUrl"));
    }

    @Test public void stoppedTaskIgnoresLateConversationBinding() throws Exception {
        SelfRun3Engine.State state = initial();
        state = SelfRun3Engine.reduce(state, new SelfRun3Engine.Event("stop", SelfRun3Engine.Kind.STOP, state.taskId(), state.turnId(), new JSONObject()));
        assertSame(state, bind(state, RAW));
        assertEquals(SelfRun3Engine.Stage.STOPPED, state.stage());
        assertEquals(SelfRun3Engine.Action.NONE, SelfRun3Engine.nextAction(state));
    }

    private static SelfRun3Engine.State initial() throws Exception {
        return SelfRun3Engine.create("task", "task:turn:1", new JSONObject().put("mode", "CHAT").put("projectUrl", "https://chatgpt.com/"));
    }
    private static SelfRun3Engine.State bind(SelfRun3Engine.State state, String url) throws Exception {
        return SelfRun3Engine.reduce(state, event(state.taskId(), state.turnId(), url));
    }
    private static SelfRun3Engine.Event event(String task, String turn, String url) throws Exception {
        return new SelfRun3Engine.Event("conversation", SelfRun3Engine.Kind.RESOURCE, task, turn, new JSONObject().put("key", "conversationUrl").put("value", url));
    }
    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
