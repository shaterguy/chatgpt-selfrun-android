package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public final class ProfileRegistryTest {
    @Before public void reset() { ProfileRegistry.resetForTests(); }
    @After public void cleanup() { ProfileRegistry.resetForTests(); }

    @Test public void builtInsContainOnlyCapturedChatAndCanonicalWorkPairs() {
        assertEquals(4, ProfileRegistry.listChat().size());
        assertNotNull(ProfileRegistry.resolveChat("instant"));
        assertNotNull(ProfileRegistry.resolveChat("medium"));
        assertNotNull(ProfileRegistry.resolveChat("high"));
        assertNotNull(ProfileRegistry.resolveChat("xhigh"));
        assertNull(ProfileRegistry.resolveChat("pro"));
        assertNull(ProfileRegistry.resolveChat("pro_standard"));
        assertNull(ProfileRegistry.resolveChat("pro_extended"));

        assertEquals(8, ProfileRegistry.listWork().size());
        assertNotNull(ProfileRegistry.resolveWork("sol", "high"));
        assertNotNull(ProfileRegistry.resolveWork("sol", "ultra"));
        assertNotNull(ProfileRegistry.resolveWork("terra", "max"));
        assertNotNull(ProfileRegistry.resolveWork("luna", "max"));
        assertNull(ProfileRegistry.resolveWork("terra", "ultra"));
        assertNull(ProfileRegistry.resolveWork("luna", "high"));
    }

    @Test public void solMaxRegistryExpressionMatchesExistingNetworkProfile() {
        ProfileRegistry.Profile profile = ProfileRegistry.resolveWork("sol", "max");
        assertNotNull(profile);
        assertEquals("gpt-5.6-sol-wm", profile.requestValue("model"));
        assertEquals("max", profile.requestValue("thinking_effort"));
        assertEquals("tpp", profile.requestValue("conversation_origin"));
        assertEquals("standard", profile.requestValue("service_tier"));
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

    @Test public void newWorkProfileBecomesProtocolValidAndDeletionFailsClosed() {
        ProfileRegistry.CapturedProfile captured = capture("work", "gpt-5.7-nova-wm", "extreme", true);
        ProfileRegistry.RegisterResult result = ProfileRegistry.registerCaptured(captured, " Nova ", " EXTREME ");
        assertEquals(ProfileRegistry.RegisterResult.ADDED, result.status);
        assertTrue(SelfRunProtocol.validWorkProfile("nova", "extreme"));
        assertNotNull(ProfileRegistry.resolveWork("nova", "extreme"));
        assertTrue(ProfileRegistry.delete(result.profile.fingerprint));
        assertFalse(SelfRunProtocol.validWorkProfile("nova", "extreme"));
        assertNull(ProfileRegistry.resolveWork("nova", "extreme"));
        assertThrows(IllegalArgumentException.class, () -> RequestProfileEngine.plan(
                new RequestProfileEngine.TargetProfile(RequestProfileEngine.Mode.WORK, "nova", "extreme")));
    }

    @Test public void duplicateFingerprintWinsOverNewSignalNames() {
        ProfileRegistry.CapturedProfile captured = capture("work", "gpt-5.6-sol-wm", "max", true);
        ProfileRegistry.RegisterResult result = ProfileRegistry.registerCaptured(captured, "solar", "maximum");
        assertEquals(ProfileRegistry.RegisterResult.DUPLICATE_PROFILE, result.status);
        assertEquals("sol", result.profile.signalModel);
        assertEquals("max", result.profile.signalReasoning);
        assertNull(ProfileRegistry.resolveWork("solar", "maximum"));
    }

    @Test public void chatRegistrationUsesReasoningSignalOnly() {
        ProfileRegistry.CapturedProfile captured = capture("chat", "gpt-5.6-pro", "super", false);
        ProfileRegistry.RegisterResult result = ProfileRegistry.registerCaptured(captured, "ignored", "pro");
        assertEquals(ProfileRegistry.RegisterResult.ADDED, result.status);
        assertEquals("", result.profile.signalModel);
        assertEquals("pro", result.profile.signalReasoning);
        assertNotNull(ProfileRegistry.resolveChat("pro"));
    }

    @Test public void signalValidationIsLowercaseTrimmedAndRejectsUnsafeTokens() {
        assertEquals("nova-v2", ProfileRegistry.canonicalSignalToken("  Nova-V2  "));
        assertThrows(IllegalArgumentException.class, () -> ProfileRegistry.canonicalSignalToken(""));
        assertThrows(IllegalArgumentException.class, () -> ProfileRegistry.canonicalSignalToken("two words"));
        assertThrows(IllegalArgumentException.class, () -> ProfileRegistry.canonicalSignalToken("MODEL"));
        assertThrows(IllegalArgumentException.class, () -> ProfileRegistry.canonicalSignalToken("next_input_b64url"));
    }

    @Test public void exportRoundTripsAllWorkProfilesWithoutConversationData() throws Exception {
        ProfileRegistry.RegisterResult added = ProfileRegistry.registerCaptured(
                capture("work", "gpt-5.7-nova-wm", "extreme", true), "nova", "extreme");
        assertEquals(ProfileRegistry.RegisterResult.ADDED, added.status);
        String raw = ProfileRegistry.exportWorkJson("2.1.0-dev1");
        assertFalse(raw.contains("conversation_id"));
        assertFalse(raw.contains("parent_message_id"));
        assertFalse(raw.contains("messages"));
        assertFalse(raw.contains("cookie"));
        assertFalse(raw.contains("session"));
        JSONObject parsed = new JSONObject(raw);
        assertEquals(ProfileRegistry.WORK_EXPORT_SCHEMA, parsed.getString("schema"));
        JSONArray profiles = parsed.getJSONArray("profiles");
        assertEquals(ProfileRegistry.listWork().size(), profiles.length());
        boolean found = false;
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject item = profiles.getJSONObject(i);
            JSONObject signal = item.getJSONObject("signal");
            if ("nova".equals(signal.getString("model")) && "extreme".equals(signal.getString("reasoning"))) {
                found = true;
                assertEquals("gpt-5.7-nova-wm", item.getJSONObject("request").getString("model"));
                assertEquals("extreme", item.getJSONObject("request").getString("thinking_effort"));
                assertEquals(4, item.getJSONArray("operations").length());
                assertEquals(64, item.getString("fingerprint").length());
            }
        }
        assertTrue(found);
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
