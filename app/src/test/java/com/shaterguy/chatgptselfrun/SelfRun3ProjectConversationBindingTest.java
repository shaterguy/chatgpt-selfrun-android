package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class SelfRun3ProjectConversationBindingTest {
    @Test public void projectConversationResourceUsesConfiguredProjectScope() {
        String project = "g-p-0123456789abcdef0123456789abcdef";
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "reasoning", "medium");
        SelfRun3Engine.put(config, "projectUrl", "https://chatgpt.com/g/" + project + "/project");
        SelfRun3Engine.State state = SelfRun3Engine.create("task", "task:turn:1", config);

        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", "conversationUrl");
        SelfRun3Engine.put(payload, "value", "https://chatgpt.com/g/" + project + "/c/conversation-1");
        SelfRun3Engine.State bound = SelfRun3Engine.reduce(state,
                new SelfRun3Engine.Event("bind-project", SelfRun3Engine.Kind.RESOURCE,
                        state.taskId(), state.turnId(), payload));

        assertEquals("https://chatgpt.com/g/" + project + "/c/conversation-1",
                bound.resource("conversationUrl"));
    }

    @Test(expected = IllegalStateException.class)
    public void conversationResourceCannotCrossConfiguredProjectScope() {
        String project = "g-p-0123456789abcdef0123456789abcdef";
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "reasoning", "medium");
        SelfRun3Engine.put(config, "projectUrl", "https://chatgpt.com/g/" + project + "/project");
        SelfRun3Engine.State state = SelfRun3Engine.create("task", "task:turn:1", config);

        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", "conversationUrl");
        SelfRun3Engine.put(payload, "value",
                "https://chatgpt.com/g/g-p-fedcba9876543210fedcba9876543210/c/conversation-2");
        SelfRun3Engine.reduce(state,
                new SelfRun3Engine.Event("bind-cross-project", SelfRun3Engine.Kind.RESOURCE,
                        state.taskId(), state.turnId(), payload));
    }
}
