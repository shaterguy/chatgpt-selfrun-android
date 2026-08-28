package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class RequestProfileEngineTest {
    private static Map<String,Object> nativeRequest() {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("action", "next");
        body.put("messages", new ArrayList<>(List.of(Map.of("author", "user", "content", "opaque"))));
        body.put("conversation_id", "opaque-conversation");
        body.put("parent_message_id", "opaque-parent");
        body.put("client_prepare_state", "sent");
        body.put("local_function_names", List.of("local.continue_in_work"));
        body.put("supports_buffering", true);
        return body;
    }

    private static Map<String,Object> apply(RequestProfileEngine.TargetProfile target) {
        return RequestProfileEngine.apply(nativeRequest(), target);
    }

    @Test public void chatInstantRemovesThinkingAndWorkFields() {
        Map<String,Object> body = nativeRequest();
        body.put("thinking_effort", "max");
        body.put("conversation_origin", "tpp");
        body.put("service_tier", "standard");
        Map<String,Object> out = RequestProfileEngine.apply(body,
                new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.CHAT, "", "instant"));
        assertEquals("gpt-5-6", out.get("model"));
        assertFalse(out.containsKey("thinking_effort"));
        assertFalse(out.containsKey("conversation_origin"));
        assertFalse(out.containsKey("service_tier"));
    }

    @Test public void chatMediumMatchesCapture() {
        Map<String,Object> out = apply(new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.CHAT, "", "medium"));
        assertEquals("gpt-5-6-thinking", out.get("model"));
        assertEquals("standard", out.get("thinking_effort"));
    }

    @Test public void chatHighMatchesCapture() {
        Map<String,Object> out = apply(new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.CHAT, "", "high"));
        assertEquals("extended", out.get("thinking_effort"));
    }

    @Test public void chatExtraHighMatchesCapture() {
        Map<String,Object> out = apply(new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.CHAT, "", "xhigh"));
        assertEquals("max", out.get("thinking_effort"));
    }

    @Test public void chatProIsExplicitlyUnsupported() {
        assertThrows(IllegalArgumentException.class, () -> apply(
                new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.CHAT, "", "pro")));
    }

    @Test public void workSolUltraIsAbsolute() {
        Map<String,Object> out = apply(new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.WORK, "sol", "ultra"));
        assertEquals("gpt-5.6-sol-wm", out.get("model"));
        assertEquals("ultra", out.get("thinking_effort"));
        assertEquals("tpp", out.get("conversation_origin"));
        assertEquals("standard", out.get("service_tier"));
    }

    @Test public void workTerraHighIsAbsolute() {
        Map<String,Object> out = apply(new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.WORK, "terra", "high"));
        assertEquals("gpt-5.6-terra-wm", out.get("model"));
        assertEquals("extended", out.get("thinking_effort"));
    }

    @Test public void workLunaMaxIsAbsolute() {
        Map<String,Object> out = apply(new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.WORK, "luna", "max"));
        assertEquals("gpt-5.6-luna-wm", out.get("model"));
        assertEquals("max", out.get("thinking_effort"));
    }

    @Test public void workLunaUltraIsRejectedRatherThanDowngraded() {
        assertThrows(IllegalArgumentException.class, () -> apply(
                new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.WORK, "luna", "ultra")));
    }

    @Test public void workFactorizationSupportsTerraMaxCrossCheck() {
        Map<String,Object> sol = apply(new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.WORK, "sol", "max"));
        Map<String,Object> terra = apply(new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.WORK, "terra", "max"));
        assertEquals(sol.get("thinking_effort"), terra.get("thinking_effort"));
        assertNotEquals(sol.get("model"), terra.get("model"));
        assertEquals(sol.get("conversation_origin"), terra.get("conversation_origin"));
        assertEquals(sol.get("service_tier"), terra.get("service_tier"));
    }

    @Test public void priorTurnStateDoesNotInfluenceNextTarget() {
        Map<String,Object> first = apply(new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.WORK, "sol", "ultra"));
        Map<String,Object> middle = RequestProfileEngine.apply(first,
                new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.WORK, "terra", "high"));
        Map<String,Object> last = RequestProfileEngine.apply(middle,
                new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.WORK, "sol", "ultra"));
        assertEquals("gpt-5.6-sol-wm", last.get("model"));
        assertEquals("ultra", last.get("thinking_effort"));
    }

    @Test public void workToChatRemovesWorkOnlyControlFields() {
        Map<String,Object> work = apply(new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.WORK, "terra", "max"));
        Map<String,Object> chat = RequestProfileEngine.apply(work,
                new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.CHAT, "", "high"));
        assertFalse(chat.containsKey("conversation_origin"));
        assertFalse(chat.containsKey("service_tier"));
        assertEquals("gpt-5-6-thinking", chat.get("model"));
    }

    @Test public void nonControlDataPlaneIsSemanticallyPreserved() {
        Map<String,Object> before = nativeRequest();
        Map<String,Object> after = RequestProfileEngine.apply(before,
                new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.WORK, "sol", "max"));
        assertTrue(RequestProfileEngine.nonControlEquivalent(before, after));
        assertSame(before.get("messages"), after.get("messages"));
        assertEquals(before.get("conversation_id"), after.get("conversation_id"));
        assertEquals(before.get("parent_message_id"), after.get("parent_message_id"));
        assertEquals(before.get("client_prepare_state"), after.get("client_prepare_state"));
    }

    @Test public void firstAndFollowupUseSameAbsoluteProfile() {
        Map<String,Object> first = nativeRequest();
        first.remove("conversation_id");
        Map<String,Object> followup = nativeRequest();
        RequestProfileEngine.TargetProfile target = new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.WORK, "terra", "high");
        Map<String,Object> a = RequestProfileEngine.apply(first, target);
        Map<String,Object> b = RequestProfileEngine.apply(followup, target);
        assertEquals(a.get("model"), b.get("model"));
        assertEquals(a.get("thinking_effort"), b.get("thinking_effort"));
        assertEquals(a.get("conversation_origin"), b.get("conversation_origin"));
        assertEquals(a.get("service_tier"), b.get("service_tier"));
    }

    @Test public void unknownSchemaFailsClosed() {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("action", "next");
        assertThrows(IllegalArgumentException.class, () -> RequestProfileEngine.apply(body,
                new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.WORK, "sol", "max")));
    }

    @Test public void unsupportedProfileVersionFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> RequestProfileEngine.plan(
                new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.WORK, "sol", "max", "future-unknown")));
    }

    @Test public void controlAllowlistIsExactlyCalibrationProvenSet() {
        assertEquals(Set.of("model", "thinking_effort", "conversation_origin", "service_tier"),
                RequestProfileEngine.CONTROL_PATHS);
    }

    @Test public void browserInterceptorPreflightsBeforeRebuildingOnlyExactConversationRoutes() {
        String script = RequestProfileScript.documentStartScript();
        assertTrue(script.contains("p==='/backend-api/conversation'||p==='/backend-api/f/conversation'"));
        assertTrue(script.contains("p=p.replace(/\\/+$/"));
        assertTrue(script.contains("if(!probe.eligible)return nativeFetch(input,init)"));
        assertTrue(script.indexOf("if(!probe.eligible)return nativeFetch(input,init)")
                < script.indexOf("input instanceof Request?input.clone():input"));
        assertTrue(script.contains("input instanceof Request?input.clone():input"));
        assertFalse(script.contains("p.includes('/backend-api/')"));
        assertFalse(script.contains("p.includes('conversation')"));
    }

    @Test public void legacyUiSelectorsAreNotRequiredByV2ProfileBridges() {
        String work = WorkPreferenceDom.modelForConversation("https://chatgpt.com/c/abc", "sol");
        String chat = ChatReasoningOptionDom.inline(ChatReasoningPreferenceStore.HIGH, "run");
        assertFalse(work.contains("querySelectorAll('button"));
        assertFalse(work.contains("click()"));
        assertFalse(chat.contains("querySelectorAll"));
        assertFalse(chat.contains("click()"));
        assertTrue(work.contains("__selfRunRequestProfileEngine"));
        assertTrue(chat.contains("__selfRunRequestProfileEngine"));
    }
}
