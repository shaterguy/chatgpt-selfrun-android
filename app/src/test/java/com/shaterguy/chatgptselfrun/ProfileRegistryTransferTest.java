package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public final class ProfileRegistryTransferTest {
    @Before public void reset() { ProfileRegistry.resetForTests(); }
    @After public void cleanup() { ProfileRegistry.resetForTests(); }

    @Test public void chatExportImportsCustomProfileAndSkipsExistingBuiltIns() throws Exception {
        ProfileRegistry.RegisterResult registered = ProfileRegistry.registerCaptured(
                captured("chat", "gpt-5.7-pro", "pro"), "", "pro");
        assertEquals(ProfileRegistry.RegisterResult.ADDED, registered.status);
        String exported = ProfileRegistry.exportChatJson("2.1.0-dev4");
        assertFalse(exported.contains("messages"));
        assertFalse(exported.contains("cookie"));
        assertFalse(exported.contains("session"));

        ProfileRegistry.resetForTests();
        ProfileRegistry.ImportResult imported = ProfileRegistry.importJson(ProfileRegistry.Mode.CHAT, exported);
        assertEquals(1, imported.added);
        assertEquals(4, imported.skipped);
        assertNotNull(ProfileRegistry.resolveChat("pro"));
        assertEquals("gpt-5.7-pro", ProfileRegistry.resolveChat("pro").requestValue("model"));
    }

    @Test public void workExportImportsCustomProfileAndSkipsExistingBuiltIns() throws Exception {
        ProfileRegistry.RegisterResult registered = ProfileRegistry.registerCaptured(
                captured("work", "gpt-5.7-nova-wm", "extreme"), "nova", "extreme");
        assertEquals(ProfileRegistry.RegisterResult.ADDED, registered.status);
        String exported = ProfileRegistry.exportWorkJson("2.1.0-dev4");

        ProfileRegistry.resetForTests();
        ProfileRegistry.ImportResult imported = ProfileRegistry.importJson(ProfileRegistry.Mode.WORK, exported);
        assertEquals(1, imported.added);
        assertEquals(8, imported.skipped);
        assertNotNull(ProfileRegistry.resolveWork("nova", "extreme"));
    }

    @Test public void importedFingerprintIsIgnoredAndRecomputed() throws Exception {
        JSONObject item = profileItem(ProfileRegistry.Mode.CHAT, "", "pro",
                "gpt-5.7-pro", "pro");
        item.put("fingerprint", "not-authoritative");
        ProfileRegistry.ImportResult result = ProfileRegistry.importJson(
                ProfileRegistry.Mode.CHAT, root(ProfileRegistry.Mode.CHAT, item).toString());
        assertEquals(1, result.added);
        ProfileRegistry.Profile restored = ProfileRegistry.resolveChat("pro");
        assertNotNull(restored);
        assertEquals(64, restored.fingerprint.length());
        assertNotEquals("not-authoritative", restored.fingerprint);
    }

    @Test public void conflictingSecondProfileRejectsWholeFileWithoutPartialRegistration() throws Exception {
        int before = ProfileRegistry.listChat().size();
        JSONObject first = profileItem(ProfileRegistry.Mode.CHAT, "", "pro",
                "gpt-5.7-pro", "pro");
        JSONObject conflicting = profileItem(ProfileRegistry.Mode.CHAT, "", "instant",
                "gpt-5.7-conflict", "conflict");
        JSONObject root = root(ProfileRegistry.Mode.CHAT, first, conflicting);

        assertThrows(IllegalArgumentException.class,
                () -> ProfileRegistry.importJson(ProfileRegistry.Mode.CHAT, root.toString()));
        assertEquals(before, ProfileRegistry.listChat().size());
        assertNull(ProfileRegistry.resolveChat("pro"));
        assertEquals("gpt-5-6", ProfileRegistry.resolveChat("instant").requestValue("model"));
    }

    @Test public void modeMismatchAndUnknownOperationAreRejected() throws Exception {
        JSONObject work = profileItem(ProfileRegistry.Mode.WORK, "nova", "extreme",
                "gpt-5.7-nova-wm", "extreme");
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRegistry.importJson(ProfileRegistry.Mode.CHAT,
                        root(ProfileRegistry.Mode.WORK, work).toString()));

        JSONObject bad = profileItem(ProfileRegistry.Mode.CHAT, "", "pro",
                "gpt-5.7-pro", "pro");
        bad.getJSONArray("operations").getJSONObject(0).put("path", "messages");
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRegistry.importJson(ProfileRegistry.Mode.CHAT,
                        root(ProfileRegistry.Mode.CHAT, bad).toString()));
        assertNull(ProfileRegistry.resolveChat("pro"));
    }

    @Test public void excessiveProfilesAreRejectedBeforeMutation() throws Exception {
        JSONArray profiles = new JSONArray();
        for (int i = 0; i <= ProfileRegistry.MAX_IMPORT_PROFILES; i++) {
            profiles.put(profileItem(ProfileRegistry.Mode.CHAT, "", "p" + i,
                    "gpt-5.7-p" + i, "e" + i));
        }
        JSONObject root = new JSONObject();
        root.put("schema", ProfileRegistry.CHAT_EXPORT_SCHEMA);
        root.put("registrySchemaVersion", ProfileRegistry.SCHEMA_VERSION);
        root.put("profiles", profiles);
        int before = ProfileRegistry.listChat().size();
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRegistry.importJson(ProfileRegistry.Mode.CHAT, root.toString()));
        assertEquals(before, ProfileRegistry.listChat().size());
    }

    private static ProfileRegistry.CapturedProfile captured(String mode, String model, String effort) {
        String origin = "work".equals(mode)
                ? "{\"op\":\"SET\",\"path\":\"conversation_origin\",\"value\":\"tpp\"}"
                : "{\"op\":\"REMOVE\",\"path\":\"conversation_origin\"}";
        String tier = "work".equals(mode)
                ? "{\"op\":\"SET\",\"path\":\"service_tier\",\"value\":\"standard\"}"
                : "{\"op\":\"REMOVE\",\"path\":\"service_tier\"}";
        return ProfileRegistry.parseCaptured("{\"mode\":\"" + mode + "\",\"operations\":["
                + "{\"op\":\"SET\",\"path\":\"model\",\"value\":\"" + model + "\"},"
                + "{\"op\":\"SET\",\"path\":\"thinking_effort\",\"value\":\"" + effort + "\"},"
                + origin + "," + tier + "]}");
    }

    private static JSONObject root(ProfileRegistry.Mode mode, JSONObject... items) throws Exception {
        JSONObject root = new JSONObject();
        root.put("schema", mode == ProfileRegistry.Mode.CHAT
                ? ProfileRegistry.CHAT_EXPORT_SCHEMA : ProfileRegistry.WORK_EXPORT_SCHEMA);
        root.put("registrySchemaVersion", ProfileRegistry.SCHEMA_VERSION);
        root.put("appVersion", "2.1.0-dev4");
        JSONArray profiles = new JSONArray();
        for (JSONObject item : items) profiles.put(item);
        root.put("profiles", profiles);
        return root;
    }

    private static JSONObject profileItem(ProfileRegistry.Mode mode, String signalModel,
                                          String signalReasoning, String requestModel,
                                          String effort) throws Exception {
        JSONObject item = new JSONObject();
        JSONObject signal = new JSONObject();
        if (mode == ProfileRegistry.Mode.WORK) signal.put("model", signalModel);
        signal.put("reasoning", signalReasoning);
        item.put("signal", signal);
        JSONArray operations = new JSONArray();
        operations.put(operation("SET", "model", requestModel));
        operations.put(operation("SET", "thinking_effort", effort));
        if (mode == ProfileRegistry.Mode.WORK) {
            operations.put(operation("SET", "conversation_origin", "tpp"));
            operations.put(operation("SET", "service_tier", "standard"));
        } else {
            operations.put(operation("REMOVE", "conversation_origin", null));
            operations.put(operation("REMOVE", "service_tier", null));
        }
        item.put("operations", operations);
        JSONObject request = new JSONObject();
        request.put("model", requestModel);
        request.put("thinking_effort", effort);
        if (mode == ProfileRegistry.Mode.WORK) {
            request.put("conversation_origin", "tpp");
            request.put("service_tier", "standard");
        }
        item.put("request", request);
        item.put("builtIn", false);
        return item;
    }

    private static JSONObject operation(String kind, String path, String value) throws Exception {
        JSONObject operation = new JSONObject();
        operation.put("op", kind);
        operation.put("path", path);
        if (value != null) operation.put("value", value);
        return operation;
    }
}
