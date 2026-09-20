package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public final class SelfRunDeliverableLinksTest {
    @Test public void doneResultKeepsSafeHttpsLinksAndDeduplicatesByDirectAccess() throws Exception {
        JSONObject result = new JSONObject().put("committed", true).put("status", "DONE");
        JSONArray links = new JSONArray()
                .put(new JSONObject().put("name", "APK").put("direct_access", "https://example.com/app.apk"))
                .put(new JSONObject().put("name", "HTTPS mirror").put("direct_access", "https://downloads.example.com/app.apk"))
                .put(new JSONObject().put("name", "duplicate").put("direct_access", "https://example.com/app.apk"))
                .put(new JSONObject().put("name", "script").put("direct_access", "javascript:alert(1)"))
                .put(new JSONObject().put("name", "file").put("direct_access", "file:///sdcard/a.apk"))
                .put(new JSONObject().put("name", "content").put("direct_access", "content://provider/item"))
                .put(new JSONObject().put("name", "intent").put("direct_access", "intent://example/#Intent;scheme=https;end"))
                .put(new JSONObject().put("name", "userinfo").put("direct_access", "https://user@example.com/a.apk"))
                .put(new JSONObject().put("name", " ").put("direct_access", "https://example.com/blank.apk"))
                .put("not-an-object");
        result.put("deliverable_links", links);

        JSONArray normalized = SelfRunDeliverableLinks.fromResult(result);

        assertEquals(2, normalized.length());
        assertEquals("APK", normalized.getJSONObject(0).getString("name"));
        assertEquals("https://example.com/app.apk", normalized.getJSONObject(0).getString("direct_access"));
        assertEquals("HTTPS mirror", normalized.getJSONObject(1).getString("name"));
    }

    @Test public void missingMalformedOrNonDoneMetadataIsSilentlyIgnored() throws Exception {
        assertEquals(0, SelfRunDeliverableLinks.fromResult("").length());
        assertEquals(0, SelfRunDeliverableLinks.fromResult("{").length());
        assertEquals(0, SelfRunDeliverableLinks.fromResult(
                new JSONObject().put("committed", false).put("status", "DONE")).length());
        assertEquals(0, SelfRunDeliverableLinks.fromResult(
                new JSONObject().put("committed", true).put("status", "CONTINUE")
                        .put("deliverable_links", new JSONArray()
                                .put(new JSONObject().put("name", "x").put("direct_access", "https://example.com/x")))).length());
        assertEquals(0, SelfRunDeliverableLinks.fromResult(
                new JSONObject().put("committed", true).put("status", "DONE")
                        .put("deliverable_links", "not-an-array")).length());
    }

    @Test public void optionalMetadataDoesNotParticipateInResultIdentityHardGate() throws Exception {
        JSONObject config = new JSONObject().put("taskMode", "CHAT").put("mode", "CHAT");
        SelfRun3Engine.State state = SelfRun3Engine.create("deliverable-test", "deliverable-test:turn:1", config);
        JSONObject resource = new JSONObject().put("key", "resultDocumentId").put("value", "result-document");
        state = SelfRun3Engine.reduce(state, new SelfRun3Engine.Event(
                "deliverable-test:resource", SelfRun3Engine.Kind.RESOURCE,
                state.taskId(), state.turnId(), resource));

        JSONObject result = SelfRun3Engine.emptyResult(state);
        result.put("committed", true);
        result.put("status", "DONE");
        result.put("phase_completed", "VERIFY_DONE");
        result.put("next_phase", "DONE");
        result.put("deliverable_links", "malformed-convenience-metadata");

        assertNotNull(SelfRun3Engine.parseResult(result.toString(), state));
        assertEquals(0, SelfRunDeliverableLinks.fromResult(result).length());
    }

    @Test public void displayProjectionIsBoundedToTwentyLinks() throws Exception {
        JSONObject result = new JSONObject().put("committed", true).put("status", "DONE");
        JSONArray links = new JSONArray();
        for (int i = 0; i < 25; i++) {
            links.put(new JSONObject()
                    .put("name", "file-" + i)
                    .put("direct_access", "https://example.com/file-" + i));
        }
        result.put("deliverable_links", links);
        assertEquals(20, SelfRunDeliverableLinks.fromResult(result).length());
    }

    @Test public void unsafeSchemesAndCredentialBearingUrlsAreRejected() {
        assertTrue(SelfRunDeliverableLinks.safeDirectAccess("https://raw.githubusercontent.com/o/r/a/file.apk"));
        assertFalse(SelfRunDeliverableLinks.safeDirectAccess("http://example.com/file"));
        assertFalse(SelfRunDeliverableLinks.safeDirectAccess("javascript:alert(1)"));
        assertFalse(SelfRunDeliverableLinks.safeDirectAccess("intent://example/#Intent;scheme=https;end"));
        assertFalse(SelfRunDeliverableLinks.safeDirectAccess("file:///tmp/file"));
        assertFalse(SelfRunDeliverableLinks.safeDirectAccess("content://provider/file"));
        assertFalse(SelfRunDeliverableLinks.safeDirectAccess("https://user@example.com/file"));
    }
}
