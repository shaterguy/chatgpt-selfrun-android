package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.os.SystemClock;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Actual native bridge + browser adapter + SQLite ledger; only the remote service is a fixture. */
@RunWith(AndroidJUnit4.class)
public final class SelfRun3ObservationWebViewTest {
    private static final String RUN = "SR-V3-NATIVE-OBSERVATION";
    private static final String URL = "https://chatgpt.com/c/v3-native-observation";

    @Test public void unboundNoiseIsIgnoredThenVerifiedBootstrapAndTwoContinuationsAdvanceExactlyOnce()
            throws Exception {
        try (Harness h = new Harness()) {
            h.open();
            SelfRun3Engine.State first = h.current.get();

            // Reproduce the dev5 failure class: canonical traffic exists while first-turn preparation
            // is not yet at READY_TO_SUBMIT. It must not acquire this SelfRun request identity.
            h.read("window.unrelatedPost();'noise-started'");
            h.awaitPosts(1);
            Thread.sleep(150L);
            assertFalse(h.current.get().flag("sendClaimed"));
            assertFalse(h.current.get().flag("dispatchObserved"));
            assertEquals(SelfRun3Engine.Stage.READY, h.current.get().stage());

            // The first request is owned only after exact preparation -> native claim -> verified submit.
            h.beginPreparation(first);
            h.awaitFlag("dispatchObserved");
            assertEquals(1, h.prepared.get());
            assertEquals(SelfRun3Engine.Stage.WAITING, h.current.get().stage());
            assertEquals("2", h.read("String(window.posts.length)"));
            assertEquals("noise", h.read("window.posts[0]"));
            assertEquals("first-prompt", h.read("window.posts[1]"));

            // A late duplicate submit call cannot create a second physical bootstrap request.
            h.ui(() -> h.adapter.submit(h.claimSnapshot(first)));
            assertEquals("2", h.read("String(window.posts.length)"));

            h.read("window.finishResponse();'released'");
            h.awaitFlag("ended");
            h.awaitResource("conversationUrl", URL);
            assertEquals(1, h.ended.get());
            assertEquals("message_stream_complete", h.current.get().text("endSource"));

            // Observation and the conversation route survive a real SQLite reopen.
            h.ui(() -> {
                h.ledger.close();
                h.ledger = new SelfRun3Ledger(h.context);
                h.current.set(h.ledger.load(RUN));
            });
            assertTrue(h.current.get().flag("sendClaimed"));
            assertTrue(h.current.get().flag("ended"));
            assertEquals(URL, h.current.get().resource("conversationUrl"));
            h.ui(h::commitAndPrepareNext);

            for (int turn = 2; turn <= 3; turn++) {
                SelfRun3Engine.State next = h.current.get();
                h.beginPreparation(next);
                h.awaitFlag("dispatchObserved");
                assertEquals(String.valueOf(turn + 1), h.read("String(window.posts.length)"));
                h.read("window.finishResponse();'released'");
                h.awaitFlag("ended");
                assertEquals(turn, h.ended.get());
                if (turn == 2) h.ui(h::commitAndPrepareNext);
            }
            assertEquals("first-prompt|second-prompt|third-prompt",
                    h.read("window.posts.slice(1).join('|')"));
            assertEquals(3, h.prepared.get());
            assertEquals("", h.failure.get());
            assertEquals(SelfRun3Engine.Stage.RECONCILING, h.current.get().stage());
        }
    }

    @Test public void staleTokenAndInvalidPhaseCannotDriveNativeAdapter() throws Exception {
        try (Harness h = new Harness()) {
            h.open();
            SelfRun3Engine.State before = h.current.get();
            h.bindAdapter(before, false);
            h.read("selfRunTurnLog.postMessage(JSON.stringify({runId:'" + RUN
                    + "',turnToken:'old-request',stage:'turn_request',source:'canonical_post',phase:'THINKING'}));'sent'");
            h.read("selfRunTurnLog.postMessage(JSON.stringify({runId:'" + RUN
                    + "',turnToken:'" + before.requestId()
                    + "',stage:'complete',source:'message_stream_complete',phase:'IDLE'}));'sent'");
            h.read("selfRunTurnLog.postMessage(JSON.stringify({runId:'" + RUN
                    + "',turnToken:'" + before.requestId()
                    + "',stage:'complete',source:'outer_done',phase:'COMPLETE'}));'sent'");
            Thread.sleep(200L);
            assertFalse(h.current.get().flag("dispatchObserved"));
            assertFalse(h.current.get().flag("sendClaimed"));
            assertEquals(0, h.ended.get());
            assertEquals("0", h.read("String(window.posts.length)"));
        }
    }

    private static final class Harness implements AutoCloseable, SelfRun3WebAdapter.Listener {
        final Context context = ApplicationProvider.getApplicationContext();
        final AtomicReference<SelfRun3Engine.State> current = new AtomicReference<>();
        final AtomicReference<String> failure = new AtomicReference<>("");
        final AtomicInteger prepared = new AtomicInteger(), ended = new AtomicInteger(), events = new AtomicInteger();
        final ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class);
        SelfRun3Ledger ledger;
        SelfRun3WebAdapter adapter;
        WebView view;

        void open() throws Exception {
            clearDatabase();
            assertTrue(context.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE).edit()
                    .putString("runId", RUN).putString("mode", "CHAT")
                    .putString("turnProtocolToken", "legacy-stale-token").commit());
            ledger = new SelfRun3Ledger(context);
            JSONObject c = new JSONObject(); SelfRun3Engine.put(c, "mode", "CHAT");
            SelfRun3Engine.put(c, "projectUrl", "https://chatgpt.com/");
            current.set(ledger.ensure(SelfRun3Engine.create(RUN, RUN + ":turn:1", c)));
            resource("folderId", "fixture-folder"); resource("requirementDocumentId", "fixture-requirement");
            apply(SelfRun3Engine.Kind.SETUP_DONE, new JSONObject());
            ready();
            CountDownLatch loaded = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                view = new WebView(activity); view.getSettings().setJavaScriptEnabled(true);
                view.getSettings().setDomStorageEnabled(true);
                assertTrue(TurnProtocolLogBridge.install(view));
                view.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView v, String url) { loaded.countDown(); }
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        return "selfrun-drive".equals(request.getUrl().getScheme());
                    }
                });
                activity.setContentView(view);
                adapter = new SelfRun3WebAdapter(context, this);
                set(adapter, "web", view); set(null, "active", adapter);
                view.loadDataWithBaseURL("https://chatgpt.com/", fixture(), "text/html", "UTF-8", null);
            });
            assertTrue(loaded.await(15, TimeUnit.SECONDS));
            read(ChatGptTurnProtocolScript.documentStartScript());
            assertEquals(RUN, new SelfRunStore(context).runId());
        }

        void beginPreparation(SelfRun3Engine.State s) throws Exception {
            bindAdapter(s, true);
            ui(() -> {
                try {
                    Method method = SelfRun3WebAdapter.class.getDeclaredMethod("advance");
                    method.setAccessible(true); method.invoke(adapter);
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
        }

        void bindAdapter(SelfRun3Engine.State s, boolean preparing) throws Exception {
            ui(() -> {
                set(adapter, "state", s); set(adapter, "step", 2);
                set(adapter, "prepareStarted", SystemClock.elapsedRealtime());
                set(adapter, "preparing", preparing); set(adapter, "loading", false);
                set(adapter, "observedRequest", ""); set(adapter, "acceptedRequest", "");
                set(adapter, "endedRequest", "");
            });
        }

        SelfRun3Engine.State claimSnapshot(SelfRun3Engine.State s) {
            return SelfRun3Engine.reduce(s, new SelfRun3Engine.Event("late-claim", SelfRun3Engine.Kind.CLAIM_SEND,
                    s.taskId(), s.turnId(), new JSONObject()));
        }
        void ready() {
            resource("resultDocumentId", "fixture-result-" + current.get().turn());
            JSONObject p = new JSONObject();
            String prompt = switch (current.get().turn()) {
                case 1 -> "first-prompt"; case 2 -> "second-prompt"; default -> "third-prompt";
            };
            SelfRun3Engine.put(p, "prompt", prompt); SelfRun3Engine.put(p, "inputRevision", 0);
            apply(SelfRun3Engine.Kind.TURN_READY, p);
        }
        void commitAndPrepareNext() {
            SelfRun3Engine.State s = current.get();
            JSONObject r = SelfRun3Engine.emptyResult(s);
            SelfRun3Engine.put(r, "committed", true); SelfRun3Engine.put(r, "status", "CONTINUE");
            SelfRun3Engine.put(r, "phase_completed", s.text("phase"));
            SelfRun3Engine.put(r, "next_phase", s.turn() == 1 ? "WORK" : "VERIFY");
            JSONObject handoff = new JSONObject();
            for (String k : new String[]{"objective","completed","remaining","evidence","constraints","next_action"})
                SelfRun3Engine.put(handoff, k, "fixture");
            SelfRun3Engine.put(r, "handoff", handoff);
            JSONObject result = new JSONObject(); SelfRun3Engine.put(result, "text", r.toString());
            apply(SelfRun3Engine.Kind.RESULT, result);
            assertEquals(SelfRun3Engine.Action.COMMIT, SelfRun3Engine.nextAction(current.get()));
            JSONObject p = new JSONObject(); SelfRun3Engine.put(p, "nextTurnId", RUN + ":turn:" + (s.turn() + 1));
            apply(SelfRun3Engine.Kind.COMMIT, p); ready();
        }
        void resource(String key, String value) {
            JSONObject p = new JSONObject(); SelfRun3Engine.put(p, "key", key); SelfRun3Engine.put(p, "value", value);
            apply(SelfRun3Engine.Kind.RESOURCE, p);
        }
        void apply(SelfRun3Engine.Kind kind, JSONObject payload) {
            SelfRun3Engine.State s = current.get();
            current.set(ledger.apply(new SelfRun3Engine.Event("fixture-" + events.incrementAndGet(), kind,
                    s.taskId(), s.turnId(), payload)));
        }
        JSONObject request(String request) {
            JSONObject p = new JSONObject(); SelfRun3Engine.put(p, "requestId", request); return p;
        }
        @Override public void onStarted(String t, String turn, String request) { apply(SelfRun3Engine.Kind.STARTED, request(request)); }
        @Override public void onAccepted(String t, String turn, String request) { apply(SelfRun3Engine.Kind.ACCEPTED, request(request)); }
        @Override public void onEnded(String t, String turn, String request, String source) {
            JSONObject p = request(request); SelfRun3Engine.put(p, "source", source);
            apply(SelfRun3Engine.Kind.ENDED, p); ended.incrementAndGet();
        }
        @Override public void onConversation(String t, String turn, String url) { resource("conversationUrl", url); }
        @Override public void onPrepared(String t, String turn, String request) {
            prepared.incrementAndGet(); apply(SelfRun3Engine.Kind.CLAIM_SEND, new JSONObject());
            adapter.submit(current.get());
        }
        @Override public void onUnsent(String t, String turn, String request, String status) { failure.set("UNSENT:" + status); }
        @Override public void onFailure(String t, String turn, String request, String code) { failure.set(code); }
        @Override public void onDispatched(String t, String turn, String request) {}

        void awaitFlag(String name) throws Exception {
            for (int i = 0; i < 120; i++) {
                if (current.get().flag(name)) return;
                assertEquals("", failure.get()); Thread.sleep(50L);
            }
            fail("missing " + name + "; state=" + current.get().stage() + "; failure=" + failure.get());
        }
        void awaitResource(String name, String expected) throws Exception {
            for (int i = 0; i < 120; i++) {
                if (expected.equals(current.get().resource(name))) return;
                assertEquals("", failure.get()); Thread.sleep(50L);
            }
            assertEquals("resource was not recorded: " + name, expected, current.get().resource(name));
        }
        void awaitPosts(int expected) throws Exception {
            for (int i = 0; i < 120; i++) {
                if (String.valueOf(expected).equals(read("String(window.posts.length)"))) return;
                Thread.sleep(25L);
            }
            assertEquals(String.valueOf(expected), read("String(window.posts.length)"));
        }
        void ui(Runnable action) throws Exception {
            AtomicReference<Throwable> error = new AtomicReference<>();
            scenario.onActivity(a -> { try { action.run(); } catch (Throwable e) { error.set(e); } });
            if (error.get() != null) throw new AssertionError(error.get());
        }
        String read(String script) throws Exception {
            CountDownLatch done = new CountDownLatch(1); AtomicReference<String> raw = new AtomicReference<>();
            ui(() -> view.evaluateJavascript(script, value -> { raw.set(value); done.countDown(); }));
            assertTrue(done.await(15, TimeUnit.SECONDS));
            return String.valueOf(new JSONTokener(raw.get()).nextValue());
        }
        @Override public void close() throws Exception {
            ui(() -> { if (adapter != null) adapter.close(); if (view != null) view.destroy(); });
            if (ledger != null) ledger.close(); scenario.close(); clearDatabase();
            context.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE).edit().clear().commit();
        }
        void clearDatabase() {
            for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
                File f = new File(context.getNoBackupFilesDir(), "selfrun3-ledger.db" + suffix);
                if (f.exists()) assertTrue(f.delete());
            }
        }
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field field = SelfRun3WebAdapter.class.getDeclaredField(name);
            field.setAccessible(true); field.set(target, value);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static String fixture() {
        return """
                <!doctype html><html><body><main></main><script>
                window.posts=[];window.editCalls=0;window.pendingResolve=null;
                const sse=()=>new Response('data: '+JSON.stringify({type:'message_stream_complete'})+'\\n\\n',
                  {status:200,headers:{'Content-Type':'text/event-stream'}});
                window.fetch=(url,init={})=>{
                  const body=JSON.parse(String(init.body||'{}'));
                  const text=String(body.messages?.[0]?.content?.parts?.[0]||'');
                  window.posts.push(text);
                  if(text==='noise')return Promise.resolve(sse());
                  return new Promise(resolve=>{window.pendingResolve=resolve;});
                };
                window.finishResponse=()=>{
                  if(window.pendingResolve){const resolve=window.pendingResolve;window.pendingResolve=null;resolve(sse());rebuild();}
                };
                window.unrelatedPost=()=>fetch('/backend-api/f/conversation',{method:'POST',
                  body:JSON.stringify({action:'next',messages:[{content:{parts:['noise']}}]})});
                function rebuild(){
                  const main=document.querySelector('main');main.replaceChildren();
                  const form=document.createElement('form'),editor=document.createElement('div');
                  editor.contentEditable='true';editor.setAttribute('role','textbox');
                  editor.setAttribute('aria-label','Message');editor.setAttribute('aria-multiline','true');
                  form.append(editor);main.append(form);let sent=false;
                  const send=()=>{
                    if(sent)return;sent=true;const text=editor.textContent;
                    history.replaceState({},'','/c/v3-native-observation');
                    fetch('/backend-api/f/conversation',{method:'POST',body:JSON.stringify({action:'next',
                      messages:[{content:{parts:[text]}}]})});
                    form.remove();
                  };
                  editor.addEventListener('beforeinput',event=>{
                    if(event.inputType!=='insertText')return;
                    event.preventDefault();window.editCalls++;editor.textContent=event.data;
                  });
                  form.addEventListener('submit',event=>{event.preventDefault();send();});
                }
                rebuild();
                </script></body></html>
                """;
    }
}
