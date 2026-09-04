package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** End-to-end regression for rich composer bootstrap plus the request-profile fetch wrapper. */
@RunWith(AndroidJUnit4.class)
public final class RichComposerBootstrapWebViewTest {
    private static final String PROJECT_ID = "g-p-6a582c824ba08191ac7e74e9bad721fc";
    private static final String SLUGGED_PROJECT_ID = PROJECT_ID + "-vibe-coding";
    private static final String PROJECT_URL = "https://chatgpt.com/g/" + PROJECT_ID + "/project";
    private static final String SLUGGED_PROJECT_URL = "https://chatgpt.com/g/" + SLUGGED_PROJECT_ID + "/project";
    private static final String SLUGGED_CONVERSATION_PATH =
            "/g/" + SLUGGED_PROJECT_ID + "/c/conversation123";
    private static final String OTHER_PROJECT_URL =
            "https://chatgpt.com/g/g-p-6a582c824ba08191ac7e74e9bad721fd/project";
    private static final String HOSTILE_EMPTY_SLUG_PATH = "/g/" + PROJECT_ID + "-/project";
    private static final String PROMPT = "SELF_RUN_RICH_COMPOSER_BOOTSTRAP";

    @Test public void emptyRichComposerScaffoldSurvivesPreparationAndCreatesConversation() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = loadFixture(scenario);
            installChatProfile(scenario, web, "SR-RICH");

            JSONObject first = evaluate(scenario, web,
                    SelfRunContinuationDom.prepareBootstrap(PROJECT_URL, PROMPT, "rich-bootstrap"));
            assertEquals("COMPOSER_CLEARING", first.getString("status"));
            assertEquals("false", read(scenario, web, "String(window.editorBroken)"));
            assertEquals("1", read(scenario, web,
                    "String(document.querySelectorAll('#prompt-textarea > p').length)"));

            JSONObject prepared = first;
            for (int attempt = 0; attempt < 8; attempt++) {
                prepared = evaluate(scenario, web,
                        SelfRunContinuationDom.prepareBootstrap(PROJECT_URL, PROMPT, "rich-bootstrap"));
                if ("READY_TO_SUBMIT".equals(prepared.getString("status"))) break;
            }
            assertEquals("READY_TO_SUBMIT", prepared.getString("status"));
            assertEquals("false", read(scenario, web, "String(window.editorBroken)"));
            assertEquals("1", read(scenario, web,
                    "String(document.querySelectorAll('#prompt-textarea > p').length)"));
            assertEquals(PROMPT, read(scenario, web,
                    "document.getElementById('prompt-textarea').innerText"));

            JSONObject dispatched = evaluate(scenario, web,
                    SelfRunContinuationDom.clickPreparedBootstrap(
                            PROJECT_URL, PROMPT, "rich-bootstrap"));
            assertEquals(SelfRunContinuationDom.SUBMISSION_PENDING, dispatched.getString("status"));
            assertTrue(dispatched.getString("detail").contains("dispatch=BOOTSTRAP_CLICKED"));
            assertEquals("1", read(scenario, web, "String(window.submitCount)"));

            for (int attempt = 0; attempt < 40; attempt++) {
                if (SLUGGED_CONVERSATION_PATH.equals(read(scenario, web, "location.pathname"))) break;
                Thread.sleep(100L);
            }
            assertEquals(SLUGGED_CONVERSATION_PATH, read(scenario, web, "location.pathname"));
            assertEquals("removed", read(scenario, web,
                    "(()=>{document.querySelector('form')?.remove();return 'removed';})()"));
            JSONObject confirmed = evaluate(scenario, web,
                    SelfRunContinuationDom.prepareBootstrap(PROJECT_URL, PROMPT, "rich-bootstrap"));
            assertEquals("SUBMISSION_CONFIRMED", confirmed.getString("status"));
            assertTrue(confirmed.getString("detail").contains("control=UNAVAILABLE"));
            assertEquals("true", read(scenario, web, "String(window.submitFetchOk)"));
            assertEquals("/backend-api/conversation/", read(scenario, web,
                    "window.fetchRecords.find(r=>r.path==='/backend-api/conversation/').path"));
            assertEquals("gpt-5-6-thinking", read(scenario, web,
                    "JSON.parse(window.fetchRecords.find(r=>r.path==='/backend-api/conversation/').body).model"));
            assertEquals("standard", read(scenario, web,
                    "JSON.parse(window.fetchRecords.find(r=>r.path==='/backend-api/conversation/').body).thinking_effort"));
            assertEquals("preserved", read(scenario, web,
                    "window.fetchRecords.find(r=>r.path==='/backend-api/conversation/').meta"));
            assertEquals("include", read(scenario, web,
                    "window.fetchRecords.find(r=>r.path==='/backend-api/conversation/').credentials"));
            assertEquals("false", read(scenario, web,
                    "String(window.fetchRecords.find(r=>r.path==='/backend-api/conversation/').signalAborted)"));
        }
    }

    @Test public void bootstrapAndWorkGuardsAcceptSluggedPageButRejectAnotherProject() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = loadFixture(scenario);
            installChatProfile(scenario, web, "SR-SLUG-GUARDS");

            JSONObject work = evaluate(scenario, web,
                    WorkPreferenceDom.modelForProject(PROJECT_URL, "sol"));
            assertEquals("READY", work.getString("status"));

            JSONObject otherBootstrap = evaluate(scenario, web,
                    SelfRunDom.prepareInitialContext(
                            OTHER_PROJECT_URL, SelfRunStore.MODE_CHAT, "SR-OTHER-PROJECT"));
            assertEquals("TARGET_ERROR", otherBootstrap.getString("status"));
            JSONObject otherWork = evaluate(scenario, web,
                    WorkPreferenceDom.modelForProject(OTHER_PROJECT_URL, "sol"));
            assertEquals("TARGET_ERROR", otherWork.getString("status"));

            assertEquals("changed", read(scenario, web,
                    "(()=>{history.replaceState({},'',"
                            + SelfRunScript.quote(HOSTILE_EMPTY_SLUG_PATH)
                            + ");return 'changed';})()"));
            JSONObject hostileBootstrap = evaluate(scenario, web,
                    SelfRunDom.prepareInitialContext(
                            PROJECT_URL, SelfRunStore.MODE_CHAT, "SR-HOSTILE-SUFFIX"));
            assertEquals("TARGET_ERROR", hostileBootstrap.getString("status"));
            JSONObject hostileWork = evaluate(scenario, web,
                    WorkPreferenceDom.modelForProject(PROJECT_URL, "sol"));
            assertEquals("TARGET_ERROR", hostileWork.getString("status"));
            JSONObject hostileSubmission = evaluate(scenario, web,
                    SelfRunContinuationDom.prepareBootstrap(
                            PROJECT_URL, PROMPT, "hostile-suffix-marker"));
            assertEquals("TARGET_ERROR", hostileSubmission.getString("status"));
        }
    }

    @Test public void requestInputAndNonSubmissionPostsKeepBodyOwnershipAndIdentity() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = loadFixture(scenario);
            installChatProfile(scenario, web, "SR-OWNERSHIP");
            assertEquals("started", read(scenario, web, requestOwnershipProbe()));

            for (int attempt = 0; attempt < 40; attempt++) {
                if ("true".equals(read(scenario, web, "String(window.profileProbeDone)"))) break;
                Thread.sleep(100L);
            }
            assertEquals("true", read(scenario, web, "String(window.profileProbeDone)"));
            assertEquals("", read(scenario, web, "String(window.profileProbeError||'')"));
            assertEquals("false", read(scenario, web,
                    "String(window.exactOriginalBodyUsedAfterWrapper)"));
            assertEquals("gpt-5-6-thinking", read(scenario, web,
                    "JSON.parse(window.recordFor('/backend-api/f/conversation').body).model"));
            assertEquals("standard", read(scenario, web,
                    "JSON.parse(window.recordFor('/backend-api/f/conversation').body).thinking_effort"));
            assertEquals("opaque-message", read(scenario, web,
                    "JSON.parse(window.recordFor('/backend-api/f/conversation').body).messages[0].opaque"));
            assertEquals("true", read(scenario, web,
                    "String(window.recordFor('/backend-api/accounts/check').sameExpected)"));
            assertEquals("opaque=1", read(scenario, web,
                    "window.recordFor('/backend-api/accounts/check').body"));
            assertEquals("opaque=1", read(scenario, web, "window.nonSubmissionBodyStillUsable"));
            assertEquals("true", read(scenario, web,
                    "String(window.recordFor('/telemetry').sameCrossOrigin)"));
            assertEquals("cross=1", read(scenario, web, "window.crossOriginBodyStillUsable"));
            assertFalse(read(scenario, web, "window.exactBodyStillUsable").isEmpty());
        }
    }

    @Test public void rejectedConversationSchemaBecomesFixedSubmissionFailure() throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = loadFixture(scenario);
            installChatProfile(scenario, web, "SR-REJECT");
            assertEquals("true", read(scenario, web, "String(window.forceInvalidBody=true)"));

            JSONObject state = evaluate(scenario, web,
                    SelfRunContinuationDom.prepareBootstrap(PROJECT_URL, PROMPT, "rejected-bootstrap"));
            for (int attempt = 0; attempt < 8; attempt++) {
                state = evaluate(scenario, web,
                        SelfRunContinuationDom.prepareBootstrap(PROJECT_URL, PROMPT, "rejected-bootstrap"));
                if ("READY_TO_SUBMIT".equals(state.getString("status"))) break;
            }
            assertEquals("READY_TO_SUBMIT", state.getString("status"));
            JSONObject dispatched = evaluate(scenario, web,
                    SelfRunContinuationDom.clickPreparedBootstrap(
                            PROJECT_URL, PROMPT, "rejected-bootstrap"));
            assertEquals(SelfRunContinuationDom.SUBMISSION_PENDING, dispatched.getString("status"));

            for (int attempt = 0; attempt < 40; attempt++) {
                state = evaluate(scenario, web,
                        SelfRunContinuationDom.prepareBootstrap(PROJECT_URL, PROMPT, "rejected-bootstrap"));
                if ("SUBMISSION_FAILED".equals(state.getString("status"))) break;
                Thread.sleep(100L);
            }
            assertEquals("SUBMISSION_FAILED", state.getString("status"));
            assertEquals("request_profile_rejected", state.getString("detail"));
            JSONObject persisted = evaluate(scenario, web,
                    SelfRunContinuationDom.prepareBootstrap(PROJECT_URL, PROMPT, "rejected-bootstrap"));
            assertEquals("SUBMISSION_FAILED", persisted.getString("status"));
            assertEquals("request_profile_rejected", persisted.getString("detail"));
            assertFalse(persisted.toString().contains(PROMPT));
            assertFalse(persisted.toString().contains("/backend-api/"));
        }
    }

    @Test public void proBootstrapStaleStopContinuationUsesOnlyARealSendException() throws Exception {
        ProBootstrapStaleStopContinuationWebViewRegression.run();
    }

    private static AtomicReference<WebView> loadFixture(
            ActivityScenario<SelfRunNewActivity> scenario) throws Exception {
        AtomicReference<WebView> web = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity);
            view.getSettings().setJavaScriptEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView ignored, String url) {
                    loaded.countDown();
                }
            });
            activity.setContentView(view);
            web.set(view);
            view.loadDataWithBaseURL(SLUGGED_PROJECT_URL, fixture(), "text/html", "UTF-8", null);
        });
        assertTrue("Rich composer fixture did not load", loaded.await(15, TimeUnit.SECONDS));
        return web;
    }

    private static void installChatProfile(ActivityScenario<SelfRunNewActivity> scenario,
                                           AtomicReference<WebView> web, String runId) throws Exception {
        AtomicReference<Boolean> persisted = new AtomicReference<>(false);
        scenario.onActivity(activity -> persisted.set(ChatReasoningPreferenceStore.save(
                activity, runId, ChatReasoningPreferenceStore.MEDIUM)));
        assertTrue("MEDIUM Chat profile was not persisted for the run", persisted.get());

        read(scenario, web, RequestProfileScript.documentStartScript());
        JSONObject initial = evaluate(scenario, web,
                SelfRunDom.prepareInitialContext(PROJECT_URL, SelfRunStore.MODE_CHAT, runId));
        assertEquals("READY", initial.getString("status"));
        JSONObject diagnostics = initial.getJSONObject("diagnostics");
        assertEquals("request-profile", diagnostics.getString("strategy"));
        assertTrue(diagnostics.getBoolean("enginePresent"));
        assertTrue(diagnostics.getBoolean("engineVersionMatch"));
        assertEquals(ChatReasoningPreferenceStore.MEDIUM,
                diagnostics.getString("observed"));
    }

    private static JSONObject evaluate(ActivityScenario<SelfRunNewActivity> scenario,
                                       AtomicReference<WebView> web, String script) throws Exception {
        String decoded = read(scenario, web, script);
        return new JSONObject(decoded);
    }

    private static String read(ActivityScenario<SelfRunNewActivity> scenario,
                               AtomicReference<WebView> web, String expression) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<String> raw = new AtomicReference<>();
        scenario.onActivity(activity -> web.get().evaluateJavascript(expression, value -> {
            raw.set(value);
            complete.countDown();
        }));
        assertTrue("WebView read timed out", complete.await(15, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(raw.get()).nextValue();
        return String.valueOf(decoded);
    }

    private static String requestOwnershipProbe() {
        return """
                (()=>{window.profileProbeDone=false;window.profileProbeError='';
                const exactBody=JSON.stringify({action:'next',messages:[{opaque:'opaque-message'}],opaqueTop:'keep'});
                const exact=new Request('/backend-api/f/conversation',{method:'POST',headers:{'Content-Type':'application/json','X-SelfRun-Meta':'request-input'},credentials:'include',body:exactBody});
                const nonSubmission=new Request('/backend-api/accounts/check',{method:'POST',body:'opaque=1'});
                const crossOrigin=new Request('https://example.com/telemetry',{method:'POST',body:'cross=1'});
                window.expectedPassthrough=nonSubmission;window.expectedCrossOrigin=crossOrigin;
                Promise.all([fetch(exact),fetch(nonSubmission),fetch(crossOrigin)]).then(async()=>{
                  window.exactOriginalBodyUsedAfterWrapper=exact.bodyUsed;
                  window.exactBodyStillUsable=await exact.clone().text();
                  window.nonSubmissionBodyStillUsable=await nonSubmission.clone().text();
                  window.crossOriginBodyStillUsable=await crossOrigin.clone().text();
                  window.profileProbeDone=true;
                }).catch(error=>{window.profileProbeError=String(error?.message||error);window.profileProbeDone=true;});
                return 'started';})()
                """;
    }

    private static String fixture() {
        return """
                <!doctype html><html><head><style>
                body{margin:20px}#prompt-textarea{min-height:48px;border:1px solid #999}button{display:block;margin:8px}
                </style></head><body><main><form>
                <div id="prompt-textarea" contenteditable="true" data-lexical-editor="true"><p><br></p></div>
                <button type="submit" data-testid="send-button" aria-label="Send">Send</button>
                </form></main><script>
                window.editorBroken=false;window.submitCount=0;window.submitFetchOk=false;window.fetchRecords=[];
                const editor=document.getElementById('prompt-textarea'),form=document.querySelector('form');
                const nativeFocus=editor.focus.bind(editor);
                editor.focus=()=>{nativeFocus();const selection=window.getSelection();selection.removeAllRanges();};
                const nativeExec=document.execCommand.bind(document);
                document.execCommand=function(command,show,value){
                  if(command==='delete'&&!editor.innerText.trim())return false;
                  if(command==='insertText'){
                    if(window.editorBroken)return false;
                    const p=editor.querySelector('p'),selection=window.getSelection();
                    const range=selection.rangeCount===1?selection.getRangeAt(0):null;
                    const node=range?.commonAncestorContainer;
                    if(!p||!range||!(node===p||p.contains(node)))return false;
                    p.textContent=String(value||'');return true;
                  }
                  return nativeExec(command,show,value);
                };
                editor.addEventListener('input',()=>{
                  if(!editor.querySelector('p'))window.editorBroken=true;
                  if(window.editorBroken)editor.innerHTML='<p><br></p>';
                });
                window.recordFor=path=>window.fetchRecords.find(record=>record.path===path);
                window.fetch=function(input,init){
                  const request=input instanceof Request?input:new Request(input,init);
                  const path=new URL(request.url,location.href).pathname;
                  const sameExpected=input===window.expectedPassthrough&&init===undefined;
                  const sameCrossOrigin=input===window.expectedCrossOrigin&&init===undefined;
                  return request.clone().text().then(body=>{
                    window.fetchRecords.push({path,method:request.method,credentials:request.credentials,
                      signalAborted:request.signal.aborted,meta:request.headers.get('X-SelfRun-Meta')||'',
                      body,sameExpected,sameCrossOrigin});
                    return new Response('{}',{status:200,headers:{'Content-Type':'application/json'}});
                  });
                };
                form.addEventListener('submit',event=>{
                  event.preventDefault();window.submitCount++;
                  const controller=new AbortController();
                  const body=window.forceInvalidBody?{action:'next'}:{action:'next',messages:[{opaque:'bootstrap-message'}],model:'old-model',
                    thinking_effort:'old-effort',opaqueTop:{keep:true}};
                  fetch('/backend-api/conversation/',{method:'POST',
                    headers:{'Content-Type':'application/json','X-SelfRun-Meta':'preserved'},
                    credentials:'include',signal:controller.signal,body:JSON.stringify(body)})
                    .then(()=>{const record=window.recordFor('/backend-api/conversation/');
                      const parsed=record?JSON.parse(record.body):{};
                      window.submitFetchOk=!!record&&record.method==='POST'&&record.meta==='preserved'
                        &&record.credentials==='include'&&!record.signalAborted
                        &&parsed.model==='gpt-5-6-thinking'&&parsed.thinking_effort==='standard'
                        &&parsed.messages?.[0]?.opaque==='bootstrap-message'
                        &&parsed.opaqueTop?.keep===true;
                      if(window.submitFetchOk)history.replaceState({},'', __SLUGGED_CONVERSATION_PATH__);
                    }).catch(error=>{window.submitError=String(error?.message||error);});
                });
                </script></body></html>
                """.replace("__SLUGGED_CONVERSATION_PATH__",
                        SelfRunScript.quote(SLUGGED_CONVERSATION_PATH));
    }
}
