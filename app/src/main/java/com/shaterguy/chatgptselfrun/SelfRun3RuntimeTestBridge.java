package com.shaterguy.chatgptselfrun;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * QA-only deterministic substitutes for the two external dependencies used by the installed
 * successor-transition regression: Drive transport and the remote ChatGPT page/network.
 *
 * Production/release variants can never activate this bridge because their applicationId does
 * not end in ".test".
 */
final class SelfRun3RuntimeTestBridge {
    private static final Set<String> CHATGPT_ORIGINS =
            Set.of("https://chatgpt.com", "https://www.chatgpt.com");

    private static String taskId = "";
    private static String predecessorTurnId = "";
    private static String committedResult = "";
    private static boolean suppressAcceptedProgression;
    private static int resultReads;
    private static int resultDocumentCreations;
    private static int fixtureLoads;
    private static int canonicalPostConfirmations;

    private SelfRun3RuntimeTestBridge() { }

    static synchronized void installScenario(String task, String predecessorTurn,
                                             String result, boolean loseAcceptedProgression) {
        requireTestVariant();
        if (!SelfRun3Engine.validId(task) || !SelfRun3Engine.validId(predecessorTurn)
                || result == null || result.isEmpty()) {
            throw new IllegalArgumentException("valid runtime fixture identity required");
        }
        taskId = task;
        predecessorTurnId = predecessorTurn;
        committedResult = result;
        suppressAcceptedProgression = loseAcceptedProgression;
        resultReads = 0;
        resultDocumentCreations = 0;
        fixtureLoads = 0;
        canonicalPostConfirmations = 0;
    }

    static synchronized void clear() {
        taskId = "";
        predecessorTurnId = "";
        committedResult = "";
        suppressAcceptedProgression = false;
        resultReads = 0;
        resultDocumentCreations = 0;
        fixtureLoads = 0;
        canonicalPostConfirmations = 0;
    }

    static synchronized boolean activeFor(SelfRun3Engine.State state) {
        return testVariant() && state != null && !taskId.isEmpty() && taskId.equals(state.taskId());
    }

    static synchronized SelfRun3DriveAdapter.ResultObservation observeResult(
            SelfRun3Engine.State state) {
        if (!activeFor(state)) {
            throw new IllegalStateException("fixture result requested outside scenario");
        }
        if (predecessorTurnId.equals(state.turnId())) {
            resultReads++;
            return new SelfRun3DriveAdapter.ResultObservation(
                    "runtime-fixture:predecessor:" + resultReads,
                    committedResult, committedResult);
        }
        if (state.turn() > 1) {
            String pending = SelfRun3Engine.emptyResult(state).toString();
            return new SelfRun3DriveAdapter.ResultObservation(
                    "runtime-fixture:successor-pending:" + state.turn(),
                    pending, pending);
        }
        throw new IllegalStateException("fixture result requested for unexpected turn");
    }

    static synchronized SelfRun3Engine.State prepareTurn(SelfRun3Ledger ledger,
                                                          SelfRun3Engine.State original) {
        if (!activeFor(original)) throw new IllegalStateException("fixture turn outside scenario");
        SelfRun3Engine.State current = ledger.loadExecution(original.taskId(), original.turnId());
        if (current == null) throw new IllegalStateException("fixture turn missing");
        if (current.resource("resultDocumentId").isEmpty()) {
            String documentId = "fixture_result_" + current.turn();
            org.json.JSONObject payload = new org.json.JSONObject();
            SelfRun3Engine.put(payload, "key", "resultDocumentId");
            SelfRun3Engine.put(payload, "value", documentId);
            current = ledger.apply(new SelfRun3Engine.Event(
                    current.requestId() + ":fixture-result-document",
                    SelfRun3Engine.Kind.RESOURCE, current.taskId(), current.turnId(), payload))
                    .execution(current.turnId());
            resultDocumentCreations++;
        }
        return current;
    }

    static synchronized boolean consumeLostAcceptedProgression(SelfRun3Engine.State completed,
                                                                String turnId) {
        if (!suppressAcceptedProgression || completed == null
                || !activeFor(completed) || !predecessorTurnId.equals(turnId)) return false;
        SelfRun3Engine.State predecessor = completed.execution(turnId);
        if (predecessor == null || !predecessor.hasResult()
                || predecessor.stage() != SelfRun3Engine.Stage.RECONCILING
                || !SelfRun3SuccessorTransitionPolicy.armed(predecessor)) return false;
        suppressAcceptedProgression = false;
        return true;
    }

    static void installNetworkSink(WebView web) {
        if (!testVariant() || web == null) return;
        synchronized (SelfRun3RuntimeTestBridge.class) {
            if (taskId.isEmpty()) return;
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            throw new IllegalStateException("fixture requires DOCUMENT_START_SCRIPT");
        }
        String script = """
                (()=>{
                  const nativeFetch=window.fetch.bind(window);
                  window.__selfRunFixturePosts=[];
                  window.fetch=function(input,init){
                    try{
                      const isRequest=typeof Request!=='undefined'&&input instanceof Request;
                      const url=isRequest?input.url:String(input??'');
                      const method=init?.method??(isRequest?input.method:'GET');
                      const u=new URL(url,location.href);
                      if(String(method).toUpperCase()==='POST'&&u.origin===location.origin
                         &&u.pathname.replace(/\\/+$/,'')==='/backend-api/f/conversation'){
                        window.__selfRunFixturePosts.push({path:u.pathname,at:Date.now()});
                        return Promise.resolve(new Response('{}',{status:200,headers:{'Content-Type':'application/json'}}));
                      }
                    }catch(_){}
                    return nativeFetch(input,init);
                  };
                })();
                """;
        WebViewCompat.addDocumentStartJavaScript(web, script, CHATGPT_ORIGINS);
    }

    static boolean loadWebFixture(WebView web, SelfRun3Engine.State state) {
        if (web == null || !activeFor(state) || state.turn() <= 1) return false;
        synchronized (SelfRun3RuntimeTestBridge.class) { fixtureLoads++; }
        // Use a real HTTPS navigation so document-start scripts run under the same origin and
        // lifecycle as the installed product. The main-frame response is intercepted below.
        web.loadUrl(SelfRunScript.GENERAL_CHAT_URL);
        return true;
    }

    static WebResourceResponse interceptWebFixture(WebResourceRequest request) {
        if (!testVariant() || request == null || request.getUrl() == null
                || !request.isForMainFrame()
                || !"GET".equalsIgnoreCase(request.getMethod())
                || !"chatgpt.com".equalsIgnoreCase(request.getUrl().getHost())) return null;
        synchronized (SelfRun3RuntimeTestBridge.class) {
            if (taskId.isEmpty()) return null;
        }
        String html = """
                <!doctype html><html><body>
                  <form id='composer'>
                    <textarea id='prompt-textarea' data-testid='prompt-textarea'></textarea>
                    <button type='submit' data-testid='send-button' aria-label='Send'>Send</button>
                  </form>
                  <script>
                    document.querySelector('#composer').addEventListener('submit',e=>{
                      e.preventDefault();
                      const prompt=document.querySelector('#prompt-textarea').value;
                      fetch('/backend-api/f/conversation',{method:'POST',headers:{'Content-Type':'application/json'},
                        body:JSON.stringify({messages:[{author:{role:'user'},content:{parts:[prompt]}}]})});
                      history.replaceState({},'', '/c/runtime-successor');
                    });
                  </script>
                </body></html>
                """;
        return new WebResourceResponse(
                "text/html", "UTF-8",
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }

    static synchronized int resultReadCount() { return resultReads; }
    static synchronized int resultDocumentCreationCount() { return resultDocumentCreations; }
    static synchronized int fixtureLoadCount() { return fixtureLoads; }
    static synchronized void recordCanonicalPostConfirmation(SelfRun3Engine.State state) {
        if (activeFor(state)) canonicalPostConfirmations++;
    }
    static synchronized int canonicalPostConfirmationCount() { return canonicalPostConfirmations; }

    private static boolean testVariant() {
        return BuildConfig.APPLICATION_ID.endsWith(".test");
    }

    private static void requireTestVariant() {
        if (!testVariant()) throw new IllegalStateException("runtime fixture is QA-only");
    }
}
