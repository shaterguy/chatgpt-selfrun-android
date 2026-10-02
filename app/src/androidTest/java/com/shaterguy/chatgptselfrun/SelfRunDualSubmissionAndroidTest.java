package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.webkit.WebView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Exercises the real selected v3 adapter against a local HTTPS/WebView fixture. */
@RunWith(AndroidJUnit4.class)
public final class SelfRunDualSubmissionAndroidTest {
    @Test public void onDeviceSubmitsOnceCapturesUrlAndDestroysWebViewWithoutResultWait() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String task = "dual-submission-runtime";
        String turn = task + ":turn:2";
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "taskMode", "CHAT");
        SelfRun3Engine.put(config, "projectUrl", "https://chatgpt.com/");
        SelfRun3Engine.put(config, "model", "");
        SelfRun3Engine.put(config, "reasoning", "keep");
        JSONObject raw = SelfRun3Engine.create(task, turn, config).json();
        SelfRun3Engine.put(raw, "turn", 2);
        SelfRun3Engine.put(raw, "maxTurn", 2);
        SelfRun3Engine.put(raw, "stage", "READY");
        SelfRun3Engine.put(raw, "submissionMode", "ON_DEVICE");
        SelfRun3Engine.put(raw, "prompt", "TASK_ID=" + task + "\nTURN_ID=" + turn
                + "\nREQUEST_ID=" + turn + "-request\nlocal fixture");
        AtomicReference<SelfRun3Engine.State> state =
                new AtomicReference<>(new SelfRun3Engine.State(raw));
        AtomicReference<SelfRunSubmissionAdapter> selected = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicBoolean disposed = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        SelfRun3RuntimeTestBridge.installScenario(task, task + ":turn:1", "{}", false);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                SelfRun3WebAdapter.Listener listener = new SelfRun3WebAdapter.Listener() {
                    @Override public void onPrepared(String t, String tr, String request) {
                        JSONObject p = new JSONObject();
                        SelfRun3Engine.put(p, "at", System.currentTimeMillis());
                        state.set(apply(state.get(), SelfRun3Engine.Kind.CLAIM_SEND, p));
                        selected.get().submit(state.get());
                    }
                    @Override public void onConversation(String t, String tr, String url) {
                        JSONObject p = new JSONObject();
                        SelfRun3Engine.put(p, "key", "conversationUrl");
                        SelfRun3Engine.put(p, "value", url);
                        state.set(apply(state.get(), SelfRun3Engine.Kind.RESOURCE, p));
                    }
                    @Override public void onStarted(String t, String tr, String request) {
                        JSONObject p = new JSONObject();
                        SelfRun3Engine.put(p, "requestId", request);
                        SelfRun3Engine.put(p, "source", "canonical_post");
                        SelfRun3Engine.put(p, "protocolStage", "turn_request");
                        state.set(apply(state.get(), SelfRun3Engine.Kind.STARTED, p));
                        disposed.set(selected.get().disposeForConfirmedResultWait(state.get()));
                        started.countDown();
                    }
                    @Override public void onAccepted(String t, String tr, String request) { }
                    @Override public void onDispatched(String t, String tr, String request) { }
                    @Override public void onUnsent(String t, String tr, String request, String status) {
                        failure.set(status); started.countDown();
                    }
                    @Override public void onFailure(String t, String tr, String request, String code) {
                        failure.set(code); started.countDown();
                    }
                };
                SelfRunSubmissionAdapter adapter = new SelfRunSubmissionAdapter(context, listener);
                selected.set(adapter);
                // The test supplies the selected profile and bypasses only external registry I/O.
                try {
                    Field local = SelfRunSubmissionAdapter.class.getDeclaredField("onDevice");
                    local.setAccessible(true);
                    Field refresh = SelfRun3WebAdapter.class.getDeclaredField("registryRefreshRequest");
                    refresh.setAccessible(true);
                    refresh.set(local.get(adapter), state.get().requestId());
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                adapter.prepare(state.get());
            });
            assertTrue("submission timed out", started.await(25, TimeUnit.SECONDS));
            assertNull("submission failure", failure.get());
            assertEquals("https://chatgpt.com/c/runtime-successor", state.get().resource("conversationUrl"));
            assertEquals(SelfRun3Engine.Stage.WAITING, state.get().stage());
            assertTrue("WebView must be destroyed before any result wait", disposed.get());
            assertEquals(1, SelfRun3RuntimeTestBridge.canonicalPostConfirmationCount());
            assertEquals(0, SelfRun3RuntimeTestBridge.resultReadCount());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertNull(HeadlessWebViewHost.activeHost());
                assertNull(HeadlessWebViewHost.activeWebView());
            });
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (selected.get() != null) selected.get().close();
            });
            SelfRun3RuntimeTestBridge.clear();
        }
    }

    private static SelfRun3Engine.State apply(SelfRun3Engine.State s,
                                               SelfRun3Engine.Kind kind, JSONObject payload) {
        return SelfRun3Engine.reduce(s, new SelfRun3Engine.Event(
                kind.name() + s.turnId(), kind, s.taskId(), s.turnId(), payload));
    }
}
