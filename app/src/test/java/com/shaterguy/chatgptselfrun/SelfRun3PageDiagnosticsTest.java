package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

/** Standalone diagnostics may remain, but the active 3.1 execution path must not poll them. */
public final class SelfRun3PageDiagnosticsTest {
    @Test public void classifiesWithoutPersistingRawSecrets() {
        String raw = "NotFoundError removeChild user@example.com secret_cookie=abc";
        String summary = SelfRun3PageDiagnostics.classifyError(raw);
        assertTrue(summary.contains("kind=NOTFOUNDERROR"));
        assertFalse(summary.contains("example.com"));
        assertFalse(summary.contains("secret_cookie"));
    }

    @Test public void snapshotProjectionDropsUntrustedFields() throws Exception {
        JSONObject data = new JSONObject();
        data.put("rawEditors", -9);
        data.put("bodyTextLength", Long.MAX_VALUE);
        data.put("titleClass", "secret user title");
        data.put("body", "secret private chat");
        String summary = SelfRun3PageDiagnostics.compactSnapshot(data);
        assertTrue(summary.contains(";ed=0;"));
        assertTrue(summary.contains(";txt=999999;"));
        assertFalse(summary.contains("secret"));
    }

    @Test public void collectionHelperStaysReadOnly() throws Exception {
        String script = SelfRun3PageDiagnostics.pageSnapshotScript();
        for (String forbidden : new String[]{"setInterval", "setTimeout", "MutationObserver", "fetch(",
                "XMLHttpRequest", "location.href=", "localStorage", "sessionStorage", "document.cookie",
                "innerHTML", "outerHTML", "replaceChildren", "removeChild", "appendChild"}) {
            assertFalse("diagnostic must stay read-only: " + forbidden, script.contains(forbidden));
        }
    }

    @Test public void active31AdapterHasNoPageOrCompletionPolling() throws Exception {
        String adapter = source("SelfRun3WebAdapter.java");
        assertFalse(adapter.contains("pageSnapshotRequest"));
        assertFalse(adapter.contains("pageSnapshotTimeoutRequest"));
        assertFalse(adapter.contains("capturePageDiagnostics"));
        assertFalse(adapter.contains("completion_dispatch"));
        assertFalse(adapter.contains("message_stream_complete"));
        assertTrue(adapter.contains("SelfRun3BootstrapTransport.prepare"));
        assertTrue(adapter.contains("SelfRun3DispatchScript.arm"));
    }

    private static String source(String name) throws Exception {
        Path p = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(p)) p = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
