package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class SelfRun3PageDiagnosticsTest {
    @Test public void classifiesDomRemovalWithoutRecordingMessageOrStack() {
        String raw = "NotFoundError: Failed to execute 'removeChild' on 'Node': user@example.com secret_cookie=abc";
        assertEquals("kind=NOTFOUNDERROR;operation=REMOVE_CHILD;reactCode=0",
                SelfRun3PageDiagnostics.classifyError(raw));
    }

    @Test public void reportsOnlyNumericReactCodeAndFixedErrorClasses() {
        assertEquals("kind=UNCLASSIFIED;operation=NONE;reactCode=418",
                SelfRun3PageDiagnostics.classifyError("Minified React error #418; https://example.com/private?token=secret"));
        assertEquals("kind=TYPEERROR;operation=NONE;reactCode=0",
                SelfRun3PageDiagnostics.classifyError("TypeError: private conversation text"));
        assertEquals("kind=UNCLASSIFIED;operation=NONE;reactCode=0",
                SelfRun3PageDiagnostics.classifyError(null));
    }

    @Test public void snapshotProjectionDropsUntrustedFieldsAndClampsCounts() throws Exception {
        JSONObject data = new JSONObject();
        data.put("rawEditors", -9);
        data.put("bodyTextLength", Long.MAX_VALUE);
        data.put("titleClass", "secret user title");
        data.put("body", "secret private chat");
        data.put("url", "https://example.com/private");
        String summary = SelfRun3PageDiagnostics.compactSnapshot(data);
        assertTrue(summary.contains(";ed=0;"));
        assertTrue(summary.contains(";txt=999999;"));
        assertTrue(summary.endsWith("title=OTHER"));
        assertFalse(summary.contains("secret"));
        assertFalse(summary.contains("http"));
        assertEquals("snapshot=UNAVAILABLE", SelfRun3PageDiagnostics.compactSnapshot(null));
    }

    @Test public void fullSnapshotFitsExistingLogLimitWithoutWeakeningRedaction() throws Exception {
        JSONObject data = new JSONObject();
        for (String key : new String[]{"bodyChildren", "mainCount", "forms", "rawEditors", "textInputs",
                "shadowEditors", "frameEditors", "shadowRoots", "frames", "unreadableFrames", "scannedElements",
                "scanCapped", "bodyTextLength", "width", "height", "visualWidth", "visualHeight"}) {
            data.put(key, Long.MAX_VALUE);
        }
        data.put("titleClass", "CHALLENGE");
        String detail = "stage=PREPARE_TIMEOUT;" + SelfRun3PageDiagnostics.compactSnapshot(data);
        assertTrue(detail.length() <= 240);
        for (String sensitive : new String[]{"cookie", "authorization", "password", "token", "prompt", "oauth"}) {
            assertFalse(detail.toLowerCase(java.util.Locale.ROOT).contains(sensitive));
        }
    }

    @Test public void collectionHasNoTimerNetworkNavigationOrDomMutation() throws Exception {
        String script = SelfRun3PageDiagnostics.pageSnapshotScript();
        for (String forbidden : new String[]{"setInterval", "setTimeout", "MutationObserver", "fetch(",
                "XMLHttpRequest", "location.href=", "localStorage", "sessionStorage", "document.cookie",
                "innerHTML", "outerHTML", "replaceChildren", "removeChild", "appendChild"}) {
            assertFalse("diagnostic must stay read-only: " + forbidden, script.contains(forbidden));
        }
        assertTrue(script.contains("i<24"));
        assertTrue(script.contains(">=5000"));
        assertTrue(script.contains("out.frames>8"));
        String adapter = source("SelfRun3WebAdapter.java");
        assertTrue(adapter.contains("!state.requestId().equals(pageSnapshotRequest)"));
        assertTrue(adapter.contains("!state.requestId().equals(pageSnapshotTimeoutRequest)"));
        assertTrue(adapter.contains("page != generation"));
        assertTrue(adapter.contains("request.equals(state.requestId())"));
    }

    private static String source(String name) throws Exception {
        Path p = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(p)) p = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
