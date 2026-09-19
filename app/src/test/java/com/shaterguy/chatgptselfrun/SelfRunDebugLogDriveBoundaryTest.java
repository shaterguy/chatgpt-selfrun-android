package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;

import static org.junit.Assert.*;

/** Production HTTP and archive paths must reject shared-drive and foreign discovery candidates. */
public final class SelfRunDebugLogDriveBoundaryTest {
    private static final ArrayDeque<Response> RESPONSES = new ArrayDeque<>();
    private static final ArrayList<FakeConnection> REQUESTS = new ArrayList<>();
    private final SelfRunDebugLogArchive.Binding binding =
            new SelfRunDebugLogArchive.Binding("SR-test", "account123", "base12345", "folder123");
    private final SelfRunDebugLogDrive remote =
            new SelfRunDebugLogDrive("synthetic-test-credential", () -> false);

    @BeforeClass public static void installTransport() {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                if (RESPONSES.isEmpty()) throw new AssertionError("unexpected request " + url);
                FakeConnection connection = new FakeConnection(url, RESPONSES.removeFirst());
                REQUESTS.add(connection);
                return connection;
            }
        } : null);
    }

    @Before public void resetTransport() {
        RESPONSES.clear();
        REQUESTS.clear();
    }

    @Test public void sharedDriveFolderIsRejectedWhenSharedFieldIsAbsent() throws Exception {
        account();
        ok(sharedDrive(folder()));

        try {
            remote.validate(binding);
            fail("shared-drive folder accepted");
        } catch (IOException expected) { }

        assertEquals(2, REQUESTS.size());
        assertTrue("metadata request must ask for driveId", REQUESTS.get(1).getURL().getQuery().contains("driveId"));
    }

    @Test public void sharedDriveDocumentIsRejectedBeforeDocsWrite() throws Exception {
        ok(sharedDrive(document("document123")));
        ok(documentBody("\n"));
        ok(new JSONObject());
        ok(documentBody("new\n"));

        try {
            remote.write(binding, "document123", 1, "new");
            fail("shared-drive document accepted");
        } catch (IOException expected) { }

        assertEquals(1, REQUESTS.size());
        assertEquals(0, REQUESTS.stream().filter(request -> "POST".equals(request.getRequestMethod())).count());
        assertTrue("metadata request must ask for driveId", REQUESTS.get(0).getURL().getQuery().contains("driveId"));
    }

    @Test public void rejectedDiscoveryIsNotPinnedAndNextTriggerRecovers() throws Exception {
        SelfRunDebugLogArchive archive = new SelfRunDebugLogArchive(
                Files.createTempDirectory("debug-discovery-boundary").toFile());
        archive.append(binding.taskId, "event");
        archive.request(binding, "WAIT", "turn1");

        account();
        ok(folder());
        ok(searchResult("document123"));
        ok(document("document123").put("appProperties",
                new JSONObject().put("job_id", "other-task").put("selfrun_kind", "debug_log")));
        try {
            archive.upload(binding.taskId, remote);
            fail("foreign discovery accepted");
        } catch (IOException expected) { }
        assertEquals("foreign discovery must not become durable identity", "",
                archive.state(binding.taskId).documentId);

        archive.request(binding, "DONE", "turn2");
        account();
        ok(folder());
        ok(searchResult("document456"));
        ok(document("document456"));
        ok(document("document456"));
        ok(documentBody("\n"));
        ok(new JSONObject());
        String expected = "SelfRun debug log\nTask: SR-test\nSequence: 2\nTrigger: DONE\n\nevent\n";
        ok(documentBody(expected + "\n"));

        assertTrue(archive.upload(binding.taskId, remote));
        assertEquals("document456", archive.state(binding.taskId).documentId);
        assertEquals(2, archive.state(binding.taskId).uploadedSequence);
    }

    private static void account() throws Exception {
        ok(new JSONObject().put("user", new JSONObject().put("permissionId", "account123")));
    }

    private static JSONObject folder() throws Exception {
        return new JSONObject().put("id", "folder123").put("name", "SR-test")
                .put("mimeType", DriveApiClient.MIME_FOLDER)
                .put("parents", new JSONArray().put("base12345"))
                .put("isAppAuthorized", true).put("shared", false).put("trashed", false)
                .put("capabilities", new JSONObject().put("canAddChildren", true))
                .put("appProperties", new JSONObject().put("job_id", "SR-test"));
    }

    private static JSONObject document(String id) throws Exception {
        return new JSONObject().put("id", id).put("name", "SR-test-debug-log")
                .put("mimeType", DriveApiClient.MIME_DOCUMENT)
                .put("parents", new JSONArray().put("folder123"))
                .put("isAppAuthorized", true).put("shared", false).put("trashed", false)
                .put("appProperties", new JSONObject()
                        .put("job_id", "SR-test").put("selfrun_kind", "debug_log"));
    }

    private static JSONObject sharedDrive(JSONObject value) throws Exception {
        value.remove("shared");
        return value.put("driveId", "sharedDrive123");
    }

    private static JSONObject searchResult(String id) throws Exception {
        return new JSONObject().put("files", new JSONArray().put(new JSONObject().put("id", id)));
    }

    private static JSONObject documentBody(String text) throws Exception {
        JSONObject paragraph = new JSONObject().put("startIndex", 1).put("endIndex", text.length() + 1)
                .put("paragraph", new JSONObject().put("elements", new JSONArray().put(
                        new JSONObject().put("textRun", new JSONObject().put("content", text)))));
        return new JSONObject().put("revisionId", "revision1").put("tabs", new JSONArray().put(
                new JSONObject().put("tabProperties", new JSONObject().put("tabId", "t.0"))
                        .put("documentTab", new JSONObject().put("body",
                                new JSONObject().put("content", new JSONArray().put(paragraph))))));
    }

    private static void ok(JSONObject value) {
        RESPONSES.add(new Response(200, value.toString()));
    }

    private static final class Response {
        final int status;
        final String body;
        Response(int status, String body) { this.status = status; this.body = body; }
    }

    private static final class FakeConnection extends HttpURLConnection {
        final Response response;
        final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
        FakeConnection(URL url, Response response) { super(url); this.response = response; }
        @Override public void connect() { }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return response.status; }
        @Override public OutputStream getOutputStream() { return requestBody; }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream(response.body.getBytes(StandardCharsets.UTF_8));
        }
        @Override public InputStream getErrorStream() { return getInputStream(); }
    }
}
