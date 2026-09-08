package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Device-level persistence and headless-runtime checks for the V3 execution lineage. */
@RunWith(AndroidJUnit4.class)
public final class SelfRun3RuntimeAndroidTest {
    private Context context;
    private File ledgerFile;

    @Before public void before() {
        context = ApplicationProvider.getApplicationContext();
        ledgerFile = new File(context.getNoBackupFilesDir(), "selfrun3-ledger.db");
        deleteLedgerFiles();
        context.getSharedPreferences("selfrun3_run_marker", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("selfrun_drive_user_next_input", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void after() {
        deleteLedgerFiles();
        context.getSharedPreferences("selfrun3_run_marker", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("selfrun_drive_user_next_input", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void ledgerPersistsClaimAcrossCloseAndIgnoresDuplicateEvent() {
        SelfRun3Engine.State initial = initial();
        SelfRun3Ledger first = new SelfRun3Ledger(context);
        SelfRun3Engine.State state = first.ensure(initial);
        state = resource(first, state, "folderId", "folder");
        state = resource(first, state, "requirementDocumentId", "requirements");
        state = first.apply(event(state, "setup", SelfRun3Engine.Kind.SETUP_DONE, new JSONObject()));
        state = resource(first, state, "resultDocumentId", "result");
        JSONObject ready = new JSONObject(); SelfRun3Engine.put(ready, "prompt", "hello");
        state = first.apply(event(state, "ready", SelfRun3Engine.Kind.TURN_READY, ready));
        JSONObject claim = new JSONObject(); SelfRun3Engine.put(claim, "at", 123L);
        state = first.apply(event(state, "claim", SelfRun3Engine.Kind.CLAIM_SEND, claim));
        int count = first.eventCount(state.taskId());
        SelfRun3Engine.State duplicate = first.apply(event(state, "claim", SelfRun3Engine.Kind.CLAIM_SEND, claim));
        assertEquals(count, first.eventCount(state.taskId()));
        assertTrue(duplicate.flag("sendClaimed"));
        first.close();

        SelfRun3Ledger reopened = new SelfRun3Ledger(context);
        SelfRun3Engine.State recovered = reopened.load(initial.taskId());
        assertNotNull(recovered);
        assertTrue(recovered.flag("sendClaimed"));
        assertEquals(SelfRun3Engine.Stage.DISPATCHING, recovered.stage());
        assertEquals(initial.requestId(), recovered.requestId());
        reopened.close();
    }

    @Test public void runMarkerSeparatesV3LineageAndSurvivesNewStoreObjects() {
        String run = "SR-V3-MARKER";
        assertFalse(SelfRun3RunMarker.current(context, run));
        assertTrue(SelfRun3RunMarker.mark(context, run));
        assertTrue(SelfRun3RunMarker.current(context, run));
        SelfRun3RunMarker.clearIfCurrent(context, run);
        assertFalse(SelfRun3RunMarker.current(context, run));
    }

    @Test public void userInputConsumeIsRevisionConditionalSoLateEditSurvives() {
        String run = "SR-V3-INPUT";
        assertTrue(context.getSharedPreferences("selfrun_drive_user_next_input", Context.MODE_PRIVATE).edit()
                .putString("runId", run).putString("text", "first").putLong("revision", 7L).commit());
        SelfRun3UserInput.Snapshot first = SelfRun3UserInput.snapshot(context, run);
        assertEquals("first", first.text);
        assertEquals(7L, first.revision);
        assertTrue(context.getSharedPreferences("selfrun_drive_user_next_input", Context.MODE_PRIVATE).edit()
                .putString("text", "late").putLong("revision", 8L).commit());
        assertFalse(SelfRun3UserInput.consumeIfRevision(context, run, 7L));
        SelfRun3UserInput.Snapshot late = SelfRun3UserInput.snapshot(context, run);
        assertEquals("late", late.text);
        assertEquals(8L, late.revision);
    }

    @Test public void ledgerLivesInNoBackupStorage() {
        SelfRun3Ledger ledger = new SelfRun3Ledger(context);
        ledger.ensure(initial());
        String databasePath = ledger.getReadableDatabase().getPath();
        ledger.close();
        assertTrue(databasePath.startsWith(context.getNoBackupFilesDir().getAbsolutePath()));
    }

    @Test public void generationDetachWaitsUntilThinkingComposerReappears() throws Exception {
        AtomicReference<HeadlessWebViewHost> hostRef = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            HeadlessWebViewHost host = HeadlessWebViewHost.create(context);
            hostRef.set(host);
            WebView web = host.webView();
            web.getSettings().setJavaScriptEnabled(true);
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) { loaded.countDown(); }
            });
            web.loadDataWithBaseURL(
                    "https://chatgpt.com/c/selfrun-v3-detach-gate",
                    "<!doctype html><html><body><main></main><script>"
                            + "window.__selfRunTurnProtocol={snapshot:()=>({phase:'THINKING'})};"
                            + "</script></body></html>",
                    "text/html", "UTF-8", null);
        });
        assertTrue("detach gate fixture did not load", loaded.await(15, TimeUnit.SECONDS));
        HeadlessWebViewHost host = hostRef.get();
        assertNotNull(host);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertTrue(host.hasDetachableOutput());
                assertTrue(host.isOutputAttached());
                assertTrue(host.detachOutputWhenComposerReady());
            });
            Thread.sleep(5_500L);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> assertTrue("surface detached before delayed composer returned", host.isOutputAttached()));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    host.webView().evaluateJavascript(
                            "(()=>{const e=document.createElement('textarea');e.id='prompt-textarea';"
                                    + "e.setAttribute('aria-label','Message');document.querySelector('main').appendChild(e);return true;})()",
                            null));
            Thread.sleep(2_300L);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> assertFalse("surface remained attached after delayed composer returned", host.isOutputAttached()));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(host::destroy);
        }
    }

    @Test public void pageDiagnosticsDistinguishMissingMainComposerFromShadowAndFrameWithoutMutation() throws Exception {
        AtomicReference<HeadlessWebViewHost> hostRef = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            HeadlessWebViewHost host = HeadlessWebViewHost.create(context);
            hostRef.set(host);
            WebView view = host.webView();
            view.getSettings().setJavaScriptEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView web, String url) { loaded.countDown(); }
            });
            view.loadDataWithBaseURL("https://chatgpt.com/c/diagnostic-fixture", """
                    <!doctype html><html><head><title>PRIVATE_FIXTURE_TITLE</title></head><body>
                    <main><form><textarea id="prompt-textarea">PRIVATE_FIXTURE_TEXT</textarea></form></main>
                    <div id="shadow-host"></div><iframe srcdoc="<textarea>PRIVATE_FRAME_TEXT</textarea>"></iframe>
                    <script>
                    document.getElementById('shadow-host').attachShadow({mode:'open'}).innerHTML=
                      '<div contenteditable="true">PRIVATE_SHADOW_TEXT</div>';
                    window.__selfRunTurnProtocol={snapshot:()=>({phase:'COMPLETE'})};
                    </script></body></html>
                    """, "text/html", "UTF-8", null);
        });
        HeadlessWebViewHost host = hostRef.get();
        assertNotNull(host);
        try {
            assertTrue("diagnostic fixture did not load", loaded.await(15, TimeUnit.SECONDS));
            String before = readPage(host.webView(), "document.documentElement.outerHTML");
            String raw = readPage(host.webView(), SelfRun3PageDiagnostics.pageSnapshotScript());
            JSONObject snapshot = new JSONObject(raw);
            assertEquals(1, snapshot.getInt("rawEditors"));
            assertEquals(1, snapshot.getInt("shadowEditors"));
            assertEquals(1, snapshot.getInt("frameEditors"));
            assertEquals("OTHER", snapshot.getString("titleClass"));
            assertFalse(raw.contains("PRIVATE_"));
            assertEquals(before, readPage(host.webView(), "document.documentElement.outerHTML"));
            assertEquals("COMPLETE", readPage(host.webView(), "window.__selfRunTurnProtocol.snapshot().phase"));
            readPage(host.webView(), "document.querySelector('main').replaceChildren();'removed'");
            snapshot = new JSONObject(readPage(host.webView(), SelfRun3PageDiagnostics.pageSnapshotScript()));
            assertEquals(0, snapshot.getInt("rawEditors"));
            assertEquals(1, snapshot.getInt("shadowEditors"));
            assertEquals(1, snapshot.getInt("frameEditors"));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(host::detachOutput);
            readPage(host.webView(), SelfRun3PageDiagnostics.pageSnapshotScript());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> assertFalse("diagnostics must not reattach the surface", host.isOutputAttached()));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(host::destroy);
        }
    }

    @Test public void consoleDiagnosticsDeduplicateCapAndDoNotLeakErrorBodies() {
        java.util.List<String> records = new java.util.ArrayList<>();
        SelfRun3PageDiagnostics client = new SelfRun3PageDiagnostics(records::add);
        android.webkit.ConsoleMessage first = new android.webkit.ConsoleMessage(
                "TypeError PRIVATE_ERROR_BODY", "https://example.com/private.js", 5,
                android.webkit.ConsoleMessage.MessageLevel.ERROR);
        assertFalse(client.onConsoleMessage(first));
        assertFalse(client.onConsoleMessage(first));
        assertEquals(1, records.size());
        for (int i = 0; i < 20; i++) {
            client.onConsoleMessage(new android.webkit.ConsoleMessage(
                    "Minified React error #" + i + "; PRIVATE_ERROR_BODY", "private.js", 1,
                    android.webkit.ConsoleMessage.MessageLevel.ERROR));
        }
        assertEquals(12, records.size());
        assertFalse(records.toString().contains("PRIVATE_"));
        assertFalse(records.toString().contains("http"));
    }

    private static String readPage(WebView view, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> raw = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                view.evaluateJavascript(script, value -> { raw.set(value); done.countDown(); }));
        assertTrue("diagnostic evaluation did not return", done.await(15, TimeUnit.SECONDS));
        assertNotNull(raw.get());
        return String.valueOf(new JSONTokener(raw.get()).nextValue());
    }

    private SelfRun3Engine.State initial() {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "projectUrl", "https://chatgpt.com/");
        return SelfRun3Engine.create("task", "task:turn:1", config);
    }

    private static SelfRun3Engine.State resource(SelfRun3Ledger ledger, SelfRun3Engine.State state,
                                                  String key, String value) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", key);
        SelfRun3Engine.put(payload, "value", value);
        return ledger.apply(event(state, "resource:" + key, SelfRun3Engine.Kind.RESOURCE, payload));
    }

    private static SelfRun3Engine.Event event(SelfRun3Engine.State state, String id,
                                               SelfRun3Engine.Kind kind, JSONObject payload) {
        return new SelfRun3Engine.Event(id, kind, state.taskId(), state.turnId(), payload);
    }

    private void deleteLedgerFiles() {
        for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
            File file = new File(ledgerFile.getAbsolutePath() + suffix);
            if (file.exists()) assertTrue("failed to delete " + file, file.delete());
        }
    }
}
