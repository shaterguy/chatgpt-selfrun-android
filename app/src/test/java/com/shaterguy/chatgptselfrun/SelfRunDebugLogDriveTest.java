package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Rejects cross-task, shared, trashed and foreign documents before any outgoing write. */
public final class SelfRunDebugLogDriveTest {
    private final SelfRunDebugLogArchive.Binding binding =
            new SelfRunDebugLogArchive.Binding("SR-test", "12345", "base12345", "folder123");

    @Test public void exactPrivateAppDocumentAccepted() throws Exception {
        SelfRunDebugLogDrive.requireDocument(new DriveApiClient.Metadata(document()), binding, "document123");
    }
    @Test public void incorrectDocumentBoundariesRejected() throws Exception {
        for (String field : new String[]{"id", "name", "mimeType", "parents", "trashed", "shared", "driveId", "isAppAuthorized", "appProperties"}) {
            JSONObject bad = document();
            switch (field) {
                case "id", "name", "mimeType" -> bad.put(field, "wrong");
                case "parents" -> bad.put(field, new JSONArray().put("otherFolder"));
                case "trashed", "shared" -> bad.put(field, true);
                case "driveId" -> bad.put(field, "sharedDrive123");
                case "isAppAuthorized" -> bad.put(field, false);
                case "appProperties" -> bad.put(field, new JSONObject().put("job_id", "otherTask").put("selfrun_kind", "debug_log"));
            }
            try { SelfRunDebugLogDrive.requireDocument(new DriveApiClient.Metadata(bad), binding, "document123"); fail(field); }
            catch (java.io.IOException expected) { }
        }
    }
    @Test public void staleSequenceAndForeignBodyRejected() throws Exception {
        SelfRunDebugLogDrive.requireExistingBody("\n", "SR-test", 1);
        SelfRunDebugLogDrive.requireExistingBody("SelfRun debug log\nTask: SR-test\nSequence: 2\nTrigger: DONE\n\n", "SR-test", 2);
        for (String bad : new String[]{"private notes\n", "SelfRun debug log\nTask: other\nSequence: 1\n",
                "SelfRun debug log\nTask: SR-test\nSequence: 3\nTrigger: DONE\n\n"}) {
            try { SelfRunDebugLogDrive.requireExistingBody(bad, "SR-test", 2); fail(); }
            catch (java.io.IOException expected) { }
        }
    }
    private JSONObject document() throws Exception {
        return new JSONObject().put("id", "document123").put("name", "SR-test-debug-log")
                .put("mimeType", DriveApiClient.MIME_DOCUMENT).put("parents", new JSONArray().put("folder123"))
                .put("trashed", false).put("shared", false).put("isAppAuthorized", true)
                .put("appProperties", new JSONObject().put("job_id", "SR-test").put("selfrun_kind", "debug_log"));
    }
}
