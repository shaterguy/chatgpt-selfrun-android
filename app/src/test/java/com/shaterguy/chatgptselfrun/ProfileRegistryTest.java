package com.shaterguy.chatgptselfrun;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public final class ProfileRegistryTest {
    @Before public void reset() { ProfileRegistry.resetForTests(); }
    @After public void cleanup() { ProfileRegistry.resetForTests(); }

    @Test public void canonicalFixtureDefinesAllActiveCandidates() {
        assertEquals(5, ProfileRegistry.listChat().size());
        assertEquals(24, ProfileRegistry.listWork().size());
        assertNotNull(ProfileRegistry.resolveChat("instant"));
        assertNotNull(ProfileRegistry.resolveChat("medium"));
        assertNotNull(ProfileRegistry.resolveChat("high"));
        assertNotNull(ProfileRegistry.resolveChat("xhigh"));
        assertNotNull(ProfileRegistry.resolveChat("6pro"));
        assertNotNull(ProfileRegistry.resolveWork("5.6sol", "max"));
        assertNotNull(ProfileRegistry.resolveWork("6astra", "ultra"));
        assertNotNull(ProfileRegistry.resolveWork("6sol", "medium"));
        assertNotNull(ProfileRegistry.resolveWork("6luna", "xhigh"));
        assertNull(ProfileRegistry.resolveWork("sol", "max"));
    }

    @Test public void chatInstantKeepsExplicitRemoveOperations() {
        ProfileRegistry.Profile profile = ProfileRegistry.resolveChat("instant");
        assertNotNull(profile);
        assertEquals("gpt-5-6", profile.requestValue("model"));
        assertFalse(profile.requestHas("thinking_effort"));
        assertFalse(profile.requestHas("conversation_origin"));
        assertFalse(profile.requestHas("service_tier"));
        assertEquals(4, profile.operations.size());
    }

    @Test public void canonicalWorkExpressionIsAbsolute() {
        ProfileRegistry.Profile profile = ProfileRegistry.resolveWork("5.6sol", "max");
        assertNotNull(profile);
        assertEquals("gpt-5.6-sol-wm", profile.requestValue("model"));
        assertEquals("max", profile.requestValue("thinking_effort"));
        assertEquals("tpp", profile.requestValue("conversation_origin"));
        assertEquals("standard", profile.requestValue("service_tier"));
    }

    @Test public void manualMutationApisFailClosed() {
        ProfileRegistry.CapturedProfile captured = capture("work", "gpt-5.7-nova-wm", "extreme", true);
        assertThrows(UnsupportedOperationException.class,
                () -> ProfileRegistry.registerCaptured(captured, "nova", "extreme"));
        assertFalse(ProfileRegistry.delete(ProfileRegistry.listWork().get(0).fingerprint));
        assertThrows(UnsupportedOperationException.class,
                () -> ProfileRegistry.importJson(ProfileRegistry.Mode.WORK, "{}"));
    }

    @Test public void exportIsReadOnlyDiagnosticOfCurrentCanonicalState() throws Exception {
        String raw = ProfileRegistry.exportWorkJson("4.0.4-dev1");
        assertFalse(raw.contains("conversation_id"));
        assertFalse(raw.contains("parent_message_id"));
        assertFalse(raw.contains("messages"));
        assertTrue(raw.contains("gpt-6-astra-wm"));
        assertEquals(ProfileRegistry.listWork().size(), new org.json.JSONObject(raw).getJSONArray("profiles").length());
    }

    @Test public void malformedCanonicalReplacementKeepsLastKnownGoodInMemory() {
        int before = ProfileRegistry.listChat().size();
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRegistry.replaceCanonicalSnapshot(ProfileRegistry.Mode.CHAT,
                        "{\"schema\":\"selfrun-chat-profile-registry-v1\",\"registrySchemaVersion\":1,\"appVersion\":\"x\",\"profiles\":[]}",
                        "bad"));
        assertEquals(before, ProfileRegistry.listChat().size());
        assertNotNull(ProfileRegistry.resolveChat("instant"));
    }

    @Test public void signalValidationIsLowercaseTrimmedAndRejectsUnsafeTokens() {
        assertEquals("nova-v2", ProfileRegistry.canonicalSignalToken("  Nova-V2  "));
        assertThrows(IllegalArgumentException.class, () -> ProfileRegistry.canonicalSignalToken(""));
        assertThrows(IllegalArgumentException.class, () -> ProfileRegistry.canonicalSignalToken("two words"));
        assertThrows(IllegalArgumentException.class, () -> ProfileRegistry.canonicalSignalToken("MODEL"));
        assertThrows(IllegalArgumentException.class, () -> ProfileRegistry.canonicalSignalToken("next_input_b64url"));
    }

    private static ProfileRegistry.CapturedProfile capture(String mode, String model, String effort,
                                                            boolean work) {
        String origin = work ? "{\"op\":\"SET\",\"path\":\"conversation_origin\",\"value\":\"tpp\"}" : "{\"op\":\"REMOVE\",\"path\":\"conversation_origin\"}";
        String tier = work ? "{\"op\":\"SET\",\"path\":\"service_tier\",\"value\":\"standard\"}" : "{\"op\":\"REMOVE\",\"path\":\"service_tier\"}";
        String thinking = effort == null
                ? "{\"op\":\"REMOVE\",\"path\":\"thinking_effort\"}"
                : "{\"op\":\"SET\",\"path\":\"thinking_effort\",\"value\":\"" + effort + "\"}";
        return ProfileRegistry.parseCaptured("{\"mode\":\"" + mode + "\",\"operations\":["
                + "{\"op\":\"SET\",\"path\":\"model\",\"value\":\"" + model + "\"},"
                + thinking + "," + origin + "," + tier + "]}");
    }
}
