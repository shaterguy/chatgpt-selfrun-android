package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Prevents producer/consumer registry engine drift. */
public final class RequestProfileVersionContractTest {
    @Test public void producerOwnsOneCanonicalRegistryEngineVersion() throws Exception {
        assertEquals("profile-registry-v1", RequestProfileScript.ENGINE_VERSION);
        String producer = source("RequestProfileScript.java");
        assertEquals(1, occurrences(producer, "profile-registry-v1"));
        assertTrue(producer.contains("static final String ENGINE_VERSION"));
        assertTrue(producer.contains("__ENGINE_VERSION__"));
        assertTrue(producer.contains("ProfileRegistry.runtimeJson()"));

        String script = RequestProfileScript.documentStartScript();
        assertEquals(2, occurrences(script, RequestProfileScript.ENGINE_VERSION));
        assertTrue(script.contains("selfrun-drive:profile-registry-runtime:v1"));
        assertTrue(script.contains("selfrun-drive:request-profile-target:v2"));
        assertTrue(script.contains("p==='/backend-api/conversation'||p==='/backend-api/f/conversation'"));
        assertTrue(script.contains("if(!probe.eligible)return nativeFetch(input,init)"));
    }

    @Test public void scriptContainsNoStaticModelOrReasoningAliasTable() throws Exception {
        String producer = source("RequestProfileScript.java");
        String engine = source("RequestProfileEngine.java");
        String rules = source("SelfRunProtocolRules.java");
        assertFalse(producer.contains("gpt-5.6-sol-wm"));
        assertFalse(producer.contains("gpt-5.6-terra-wm"));
        assertFalse(producer.contains("gpt-5.6-luna-wm"));
        assertFalse(producer.contains("{sol:"));
        assertFalse(engine.contains("case \"sol\""));
        assertFalse(engine.contains("case \"terra\""));
        assertFalse(engine.contains("case \"luna\""));
        assertTrue(rules.contains("ProfileRegistry.resolveWork(model, reasoning)"));
    }

    @Test public void captureIsOneShotAndDoesNotMutateOriginalSubmission() {
        String script = RequestProfileScript.documentStartScript();
        assertTrue(script.contains("state.capture.armed=false"));
        assertTrue(script.contains("if(state.capture.armed){try{captureBody(body);"));
        assertTrue(script.contains("return nativeFetch(input,init)"));
        assertTrue(script.contains("return nativeSend.call(this,body)"));
        assertTrue(script.contains("Array.isArray(body.messages)"));
        assertTrue(script.contains("captureOperations=body=>CONTROL.map"));
        assertFalse(script.contains("messages:body.messages"));
        assertFalse(script.contains("conversation_id"));
        assertFalse(script.contains("parent_message_id"));
    }

    @Test public void restoredTargetMustStillResolveAgainstCurrentRegistry() {
        String script = RequestProfileScript.documentStartScript();
        assertTrue(script.contains("state.target=restoreTarget();"));
        assertTrue(script.contains("if(state.target&&!targetValid(state.target))"));
        assertTrue(script.contains("target_deleted_or_unsupported"));
        assertTrue(script.contains("profile_deleted_or_unsupported"));
        assertTrue(script.contains("resolveProfile(t.mode,t.model,t.reasoning)"));
    }

    @Test public void consumersUseSharedEngineExpressionAndRegistryInjection() throws Exception {
        String expression = RequestProfileScript.engineAvailableExpression();
        String bootstrapScript = BootstrapModeDom.inline(SelfRunStore.MODE_CHAT, "SR-VERSION");
        String workScript = WorkPreferenceDom.modelForProject("https://chatgpt.com/g/g-p-test", "sol");
        assertTrue(bootstrapScript.contains(expression));
        assertTrue(workScript.contains(expression));
        assertTrue(workScript.contains("installRegistry"));
        assertFalse(source("BootstrapModeDom.java").contains("gpt-5.6-sol-wm"));
        assertFalse(source("WorkPreferenceDom.java").contains("gpt-5.6-sol-wm"));
        assertFalse(source("ChatReasoningOptionDom.java").contains("gpt-5-6-thinking"));
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int at = value.indexOf(needle); at >= 0;
             at = value.indexOf(needle, at + needle.length())) count++;
        return count;
    }
}
