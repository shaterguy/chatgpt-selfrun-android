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

            awaitCount(listener.failures, 2, 4_000L, "second preparation timeout");
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
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "projectUrl", ROOT);
        SelfRun3Engine.put(config, "model", "");
        SelfRun3Engine.put(config, "reasoning", "xhigh");

        JSONObject raw = new JSONObject();
        SelfRun3Engine.put(raw, "schema", SelfRun3Engine.STATE_SCHEMA);
        SelfRun3Engine.put(raw, "taskId", "SR-PREPARATION-RECOVERY-ANDROID");
        SelfRun3Engine.put(raw, "taskMode", "CHAT");
        SelfRun3Engine.put(raw, "turnId", "SR-PREPARATION-RECOVERY-ANDROID:turn:1");
        SelfRun3Engine.put(raw, "requestId", "SR-PREPARATION-RECOVERY-ANDROID:turn:1-request");
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
        @Override public void onConversation(String task, String turn, String url) {}
        @Override public void onUnsent(String task, String turn, String request, String status) { unsent.incrementAndGet(); }
        @Override public void onFailure(String task, String turn, String request, String code) {
            lastFailureTask.set(task); lastFailureTurn.set(turn); lastFailureRequest.set(request);
            lastFailureCode.set(code); failures.incrementAndGet();
        }
        @Override public void onDispatched(String task, String turn, String request) { dispatched.incrementAndGet(); }
    }
}
