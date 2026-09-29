package com.shaterguy.chatgptselfrun;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProfileRegistryTransferTest {
    @Before public void reset() { ProfileRegistry.resetForTests(); }

    @Test public void manualImportIsDisabledAndDoesNotMutateCanonicalState() {
        int chat = ProfileRegistry.listChat().size();
        int work = ProfileRegistry.listWork().size();
        assertThrows(UnsupportedOperationException.class,
                () -> ProfileRegistry.importJson(ProfileRegistry.Mode.CHAT, "{}"));
        assertThrows(UnsupportedOperationException.class,
                () -> ProfileRegistry.importJson(ProfileRegistry.Mode.WORK, "{}"));
        assertEquals(chat, ProfileRegistry.listChat().size());
        assertEquals(work, ProfileRegistry.listWork().size());
    }

    @Test public void diagnosticExportPreservesCanonicalSchemaAndFingerprint() throws Exception {
        String raw = ProfileRegistry.exportChatJson("4.0.4-dev1");
        org.json.JSONObject root = new org.json.JSONObject(raw);
        assertEquals(ProfileRegistry.CHAT_EXPORT_SCHEMA, root.getString("schema"));
        assertEquals(ProfileRegistry.SCHEMA_VERSION, root.getInt("registrySchemaVersion"));
        assertEquals(ProfileRegistry.listChat().size(), root.getJSONArray("profiles").length());
        for (int i = 0; i < root.getJSONArray("profiles").length(); i++) {
            assertEquals(64, root.getJSONArray("profiles").getJSONObject(i).getString("fingerprint").length());
        }
    }
}
