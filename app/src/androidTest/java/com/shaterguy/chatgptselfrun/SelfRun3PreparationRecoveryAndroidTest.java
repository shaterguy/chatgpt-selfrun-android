package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.os.SystemClock;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** API 36 behavior coverage for preparation timeout recovery and stale WebView callback fencing. */
@RunWith(AndroidJUnit4.class)
public final class SelfRun3PreparationRecoveryAndroidTest {
    private static final String ROOT = "https://chatgpt.com/";
    private Context context;

    @Before public void before() {
        context = ApplicationProvider.getApplicationContext();
        clearRuntimeSettings();
    }

    @After public void after() {
        clearRuntimeSettings();
    }

    @Test public void timeoutRetryRecreatesWebViewFencesStaleCallbacksAndRecoversSameRequest()
            throws Exception {
        SelfRun3Engine.State state = readyState();
        SelfRun3RuntimeSettings settings = new SelfRun3RuntimeSettings(context);
        assertTrue(settings.saveWebPreparationSeconds("1"));
        RecordingListener listener = new RecordingListener();
        AtomicReference<SelfRun3WebAdapter> adapterRef = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> adapterRef.set(new SelfRun3WebAdapter(context, listener)));
        SelfRun3WebAdapter adapter = adapterRef.get();

        try {
            Attempt first = startStalledAttempt(adapter, state);
            awaitCount(listener.failures, 1, 4_000L, "first preparation timeout");
            assertIdentity(listener.lastFailureTask.get(), listener.lastFailureTurn.get(),
                    listener.lastFailureRequest.get(), state);
            assertEquals("WEB_PREPARATION_TIMEOUT", listener.lastFailureCode.get());
            assertEquals(0, listener.prepared.get());
            assertEquals(0, listener.started.get());
            assertEquals(0, listener.dispatched.get());

            // Keep the second attempt live long enough to exercise stale-callback fencing even on a
            // heavily scheduled hosted emulator; it still expires through the real watchdog below.
            assertTrue(settings.saveWebPreparationSeconds("5"));
            Attempt second = startStalledAttempt(adapter, state);
            assertNotSame(first.web, second.web);
            assertSame(second.web, HeadlessWebViewHost.activeWebView());
            assertSameRequest(adapter, state);

            int failuresBeforeStaleFirst = listener.failures.get();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> first.client.onPageFinished(first.web, ROOT));
            SystemClock.sleep(200L);
            assertEquals(failuresBeforeStaleFirst, listener.failures.get());
            assertEquals(0, listener.prepared.get());
            assertSame(second.web, HeadlessWebViewHost.activeWebView());
            assertTrue(booleanField(adapter, "loading"));

            awaitCount(listener.failures, 2, 8_000L, "second preparation timeout");
            assertIdentity(listener.lastFailureTask.get(), listener.lastFailureTurn.get(),
                    listener.lastFailureRequest.get(), state);
            assertEquals("WEB_PREPARATION_TIMEOUT", listener.lastFailureCode.get());
            assertEquals(0, listener.started.get());
            assertEquals(0, listener.dispatched.get());

            assertTrue(settings.saveWebPreparationSeconds("10"));
            AtomicReference<WebView> thirdRef = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                adapter.prepare(state);
                WebView third = HeadlessWebViewHost.activeWebView();
                assertNotNull(third);
                thirdRef.set(third);
                setIntField(adapter, "step", 2);
                third.loadDataWithBaseURL(ROOT, fixture(), "text/html", "UTF-8", null);
            });
            WebView third = thirdRef.get();
            assertNotSame(second.web, third);
            assertNotSame(first.web, third);
            assertSameRequest(adapter, state);

            awaitCount(listener.prepared, 1, 8_000L, "recovered bootstrap preparation");
            assertIdentity(listener.lastPreparedTask.get(), listener.lastPreparedTurn.get(),
                    listener.lastPreparedRequest.get(), state);
            assertEquals(2, listener.failures.get());
            assertEquals(0, listener.started.get());
            assertEquals(0, listener.dispatched.get());
            assertEquals(0, listener.unsent.get());
            assertSame(third, HeadlessWebViewHost.activeWebView());

            int preparedBeforeStaleSecond = listener.prepared.get();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> second.client.onPageFinished(second.web, ROOT));
            SystemClock.sleep(200L);
            assertEquals(preparedBeforeStaleSecond, listener.prepared.get());
            assertEquals(2, listener.failures.get());
            assertSame(third, HeadlessWebViewHost.activeWebView());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(adapter::close);
        }
    }

    @Test public void postSubmissionTimeoutStartsFreshBootstrapAndPostsAgainUntilConversationAppears()
            throws Exception {
        String task = "SR-WATCHDOG-POST-" + SystemClock.elapsedRealtimeNanos();
        SelfRun3Engine.State ready = readyState(task);
        SelfRun3Engine.State claimed = claimedState(ready);
        SelfRun3RuntimeSettings settings = new SelfRun3RuntimeSettings(context);
        assertTrue(settings.saveWebPreparationSeconds("30"));
        RecordingListener listener = new RecordingListener();
        AtomicReference<SelfRun3WebAdapter> adapterRef = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> adapterRef.set(new SelfRun3WebAdapter(context, listener)));
        SelfRun3WebAdapter adapter = adapterRef.get();

        try {
            Attempt first = startSubmissionAttempt(adapter, ready, false);
            awaitCount(listener.prepared, 1, 8_000L, "initial bootstrap prepared");
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> adapter.submit(claimed));
            awaitCount(listener.dispatched, 1, 8_000L, "first canonical POST");
            assertTrue(booleanField(adapter, "dispatchConfirmed"));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> adapter.prepare(claimed));
            SystemClock.sleep(250L);
            assertEquals("same attempt must not submit twice", 1, listener.dispatched.get());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> expireCurrentPreparation(adapter));
            awaitCount(listener.failures, 1, 2_000L, "first post-submit timeout");
            assertIdentity(listener.lastFailureTask.get(), listener.lastFailureTurn.get(),
                    listener.lastFailureRequest.get(), claimed);
            assertEquals("WEB_PREPARATION_TIMEOUT", listener.lastFailureCode.get());

            Attempt second = startSubmissionAttempt(adapter, claimed, false);
            assertNotSame(first.web, second.web);
            awaitCount(listener.dispatched, 2, 8_000L, "second canonical POST");
            assertTrue(booleanField(adapter, "dispatchConfirmed"));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> expireCurrentPreparation(adapter));
            awaitCount(listener.failures, 2, 2_000L, "second post-submit timeout");

            Attempt third = startSubmissionAttempt(adapter, claimed, true);
            assertNotSame(second.web, third.web);
            awaitCount(listener.dispatched, 3, 8_000L, "third canonical POST");
            awaitCount(listener.started, 1, 8_000L, "conversation start after retry");
            assertTrue(booleanField(adapter, "dispatchConfirmed"));
            assertTrue(booleanField(adapter, "conversationCaptured"));
            assertEquals(1, listener.prepared.get());
            assertEquals(3, listener.dispatched.get());
            assertEquals(2, listener.failures.get());
            assertEquals(1, listener.started.get());
            assertEquals(1, listener.conversations.get());
            assertSameRequest(adapter, claimed);
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(adapter::close);
        }
    }

    @Test public void preparationTimeoutErrorKeepsReadyRequestIdentityRetryable() {
        SelfRun3Engine.State state = readyState();
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "code", "WEB_PREPARATION_TIMEOUT");
        SelfRun3Engine.State errored = SelfRun3Engine.reduce(state,
                new SelfRun3Engine.Event("preparation-timeout", SelfRun3Engine.Kind.ERROR,
                        state.taskId(), state.turnId(), payload));

        assertEquals(state.taskId(), errored.taskId());
        assertEquals(state.turnId(), errored.turnId());
        assertEquals(state.requestId(), errored.requestId());
        assertEquals(SelfRun3Engine.Stage.READY, errored.stage());
        assertEquals(SelfRun3Engine.Action.PREPARE_WEB, SelfRun3Engine.nextAction(errored));
        assertFalse(errored.flag("sendClaimed"));
        assertFalse(errored.flag("dispatchObserved"));
        assertEquals("WEB_PREPARATION_TIMEOUT", errored.text("error"));
    }

    private Attempt startSubmissionAttempt(SelfRun3WebAdapter adapter, SelfRun3Engine.State state,
                                             boolean completeConversation) {
        AtomicReference<Attempt> out = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            adapter.prepare(state);
            WebView web = HeadlessWebViewHost.activeWebView();
            assertNotNull(web);
            WebViewClient client = web.getWebViewClient();
            assertNotNull(client);
            web.stopLoading();
            setIntField(adapter, "step", 2);
            web.loadDataWithBaseURL(ROOT, submissionFixture(completeConversation),
                    "text/html", "UTF-8", null);
            out.set(new Attempt(web, client));
        });
        return out.get();
    }

    private static void expireCurrentPreparation(SelfRun3WebAdapter adapter) {
        try {
            Field attempt = SelfRun3WebAdapter.class.getDeclaredField("preparationAttempt");
            attempt.setAccessible(true);
            long value = attempt.getLong(adapter);
            java.lang.reflect.Method expire = SelfRun3WebAdapter.class
                    .getDeclaredMethod("expireConversationCreation", long.class);
            expire.setAccessible(true);
            expire.invoke(adapter, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private Attempt startStalledAttempt(SelfRun3WebAdapter adapter, SelfRun3Engine.State state) {
        AtomicReference<Attempt> out = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            adapter.prepare(state);
            WebView web = HeadlessWebViewHost.activeWebView();
            assertNotNull(web);
            WebViewClient client = web.getWebViewClient();
            assertNotNull(client);
            web.setWebViewClient(new WebViewClient());
            web.stopLoading();
            out.set(new Attempt(web, client));
        });
        return out.get();
    }

    private static SelfRun3Engine.State readyState() {
        return readyState("SR-PREPARATION-RECOVERY-ANDROID");
    }

    private static SelfRun3Engine.State claimedState(SelfRun3Engine.State ready) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "at", System.currentTimeMillis());
        return SelfRun3Engine.reduce(ready, new SelfRun3Engine.Event(
                ready.requestId() + ":claim", SelfRun3Engine.Kind.CLAIM_SEND,
                ready.taskId(), ready.turnId(), payload));
    }

    private static SelfRun3Engine.State readyState(String taskId) {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "projectUrl", ROOT);
        SelfRun3Engine.put(config, "model", "");
        SelfRun3Engine.put(config, "reasoning", "xhigh");

        JSONObject raw = new JSONObject();
        SelfRun3Engine.put(raw, "schema", SelfRun3Engine.STATE_SCHEMA);
        SelfRun3Engine.put(raw, "taskId", taskId);
        SelfRun3Engine.put(raw, "taskMode", "CHAT");
        SelfRun3Engine.put(raw, "turnId", taskId + ":turn:1");
        SelfRun3Engine.put(raw, "requestId", taskId + ":turn:1-request");
        SelfRun3Engine.put(raw, "turn", 1);
        SelfRun3Engine.put(raw, "maxTurn", 1);
        SelfRun3Engine.put(raw, "stage", "READY");
        SelfRun3Engine.put(raw, "phase", "WORK");
        SelfRun3Engine.put(raw, "config", config);
        SelfRun3Engine.put(raw, "resources", new JSONObject());
        SelfRun3Engine.put(raw, "executionKind", "NORMAL");
        SelfRun3Engine.put(raw, "signalType", "AUTO_NEXT_TURN");
        SelfRun3Engine.put(raw, "prompt", "preparation recovery fixture");
        SelfRun3Engine.put(raw, "sendClaimed", false);
        SelfRun3Engine.put(raw, "dispatchObserved", false);
        SelfRun3Engine.put(raw, "accepted", false);
        SelfRun3Engine.put(raw, "committed", false);
        return new SelfRun3Engine.State(raw);
    }

    private static String submissionFixture(boolean completeConversation) {
        String complete = completeConversation
                ? "history.replaceState({},'', '/c/watchdog-recovered');"
                : "";
        return "<!doctype html><html><body><main>"
                + "<form id='composer'><textarea id='prompt-textarea' data-testid='prompt-textarea' aria-label='Message'></textarea>"
                + "<button type='submit' data-testid='send-button' aria-label='Send'>Send</button></form>"
                + "<script>" + SelfRun3DispatchScript.documentStartScript()
                + "document.querySelector('#composer').addEventListener('submit',e=>{e.preventDefault();"
                + "const prompt=document.querySelector('#prompt-textarea').value;"
                + "const abort=new AbortController();abort.abort();"
                + "fetch('/backend-api/f/conversation',{method:'POST',signal:abort.signal,body:JSON.stringify({messages:[{author:{role:'user'},content:{parts:[prompt]}}]})}).catch(()=>{});"
                + complete
                + "});</script></main></body></html>";
    }

    private static String fixture() {
        return """
                <!doctype html><html><body><main>
                <form id='composer'>
                  <textarea id='prompt-textarea' data-testid='prompt-textarea' aria-label='Message'></textarea>
                  <button type='submit' data-testid='send-button' aria-label='Send'>Send</button>
                </form>
                </main></body></html>
                """;
    }

    private static void awaitCount(AtomicInteger counter, int expected, long timeoutMs, String label) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (counter.get() >= expected) return;
            SystemClock.sleep(50L);
        }
        assertEquals(label, expected, counter.get());
    }

    private static void assertIdentity(String task, String turn, String request,
                                       SelfRun3Engine.State state) {
        assertEquals(state.taskId(), task);
        assertEquals(state.turnId(), turn);
        assertEquals(state.requestId(), request);
    }

    private static void assertSameRequest(SelfRun3WebAdapter adapter, SelfRun3Engine.State expected)
            throws Exception {
        Field field = SelfRun3WebAdapter.class.getDeclaredField("state");
        field.setAccessible(true);
        SelfRun3Engine.State actual = (SelfRun3Engine.State) field.get(adapter);
        assertNotNull(actual);
        assertEquals(expected.taskId(), actual.taskId());
        assertEquals(expected.turnId(), actual.turnId());
        assertEquals(expected.requestId(), actual.requestId());
        assertEquals(expected.config().optString("mode"), actual.config().optString("mode"));
        assertEquals(expected.config().optString("model"), actual.config().optString("model"));
        assertEquals(expected.config().optString("reasoning"), actual.config().optString("reasoning"));
    }

    private static boolean booleanField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static void setIntField(Object target, String name, int value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.setInt(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private void clearRuntimeSettings() {
        context.getSharedPreferences(SelfRun3RuntimeSettings.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    private record Attempt(WebView web, WebViewClient client) {}

    private static final class RecordingListener implements SelfRun3WebAdapter.Listener {
        final AtomicInteger prepared = new AtomicInteger();
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger dispatched = new AtomicInteger();
        final AtomicInteger conversations = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger unsent = new AtomicInteger();
        final AtomicReference<String> lastPreparedTask = new AtomicReference<>("");
        final AtomicReference<String> lastPreparedTurn = new AtomicReference<>("");
        final AtomicReference<String> lastPreparedRequest = new AtomicReference<>("");
        final AtomicReference<String> lastFailureTask = new AtomicReference<>("");
        final AtomicReference<String> lastFailureTurn = new AtomicReference<>("");
        final AtomicReference<String> lastFailureRequest = new AtomicReference<>("");
        final AtomicReference<String> lastFailureCode = new AtomicReference<>("");

        @Override public void onPrepared(String task, String turn, String request) {
            lastPreparedTask.set(task); lastPreparedTurn.set(turn); lastPreparedRequest.set(request);
            prepared.incrementAndGet();
        }
        @Override public void onStarted(String task, String turn, String request) { started.incrementAndGet(); }
        @Override public void onAccepted(String task, String turn, String url) {}
        @Override public void onConversation(String task, String turn, String url) { conversations.incrementAndGet(); }
        @Override public void onUnsent(String task, String turn, String request, String status) { unsent.incrementAndGet(); }
        @Override public void onFailure(String task, String turn, String request, String code) {
            lastFailureTask.set(task); lastFailureTurn.set(turn); lastFailureRequest.set(request);
            lastFailureCode.set(code); failures.incrementAndGet();
        }
        @Override public void onDispatched(String task, String turn, String request) { dispatched.incrementAndGet(); }
    }
}
