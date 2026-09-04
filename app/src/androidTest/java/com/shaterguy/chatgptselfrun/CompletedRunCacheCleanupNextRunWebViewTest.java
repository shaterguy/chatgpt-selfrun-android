package com.shaterguy.chatgptselfrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class CompletedRunCacheCleanupNextRunWebViewTest {
    private static final String PROJECT_ID = "g-p-6a582c824ba08191ac7e74e9bad721fc";
    private static final String PROJECT_URL = "https://chatgpt.com/g/" + PROJECT_ID + "/project";
    private static final String CONVERSATION_PATH = "/g/" + PROJECT_ID + "/c/cleanup-next-run";
    private static final String PROMPT = "SELF_RUN_COMPLETED_CACHE_NEXT_RUN";

    @Test public void completedCleanupPreservesSessionAndAllowsNextRunSubmission() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences drive = context.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE);
        String priorAccount = drive.getString("driveAccountId", null);
        boolean priorContains = drive.contains("driveAccountId");
        assertTrue(drive.edit().putString("driveAccountId", "cleanup-next-run-drive-account").commit());

        AtomicReference<HeadlessWebViewHost> currentHost = new AtomicReference<>();
        AtomicReference<String> cookieAfterCleanup = new AtomicReference<>();
        CountDownLatch pageLoaded = new CountDownLatch(1);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                CookieManager cookies = CookieManager.getInstance();
                cookies.setAcceptCookie(true);
                cookies.setCookie("https://chatgpt.com", "SelfRunCleanupNextRun=keep; Path=/; SameSite=Lax");
                cookies.flush();

                HeadlessWebViewHost completed = HeadlessWebViewHost.create(context);
                assertTrue(completed.clearResourceCacheAfterCompletedRun());
                completed.destroy();

                HeadlessWebViewHost nextRun = HeadlessWebViewHost.create(context);
                currentHost.set(nextRun);
                WebView web = nextRun.webView();
                web.getSettings().setJavaScriptEnabled(true);
                web.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String url) {
                        pageLoaded.countDown();
                    }
                });
                cookieAfterCleanup.set(cookies.getCookie("https://chatgpt.com"));
                web.loadDataWithBaseURL(PROJECT_URL, fixture(), "text/html", "UTF-8", null);
            });

            assertTrue("next-run fixture did not load", pageLoaded.await(15, TimeUnit.SECONDS));
            assertNotNull(cookieAfterCleanup.get());
            assertTrue(cookieAfterCleanup.get().contains("SelfRunCleanupNextRun=keep"));
            assertEquals("cleanup-next-run-drive-account", drive.getString("driveAccountId", ""));

            WebView nextRunWebView = currentHost.get().webView();
            JSONObject prepared = evaluate(nextRunWebView,
                    SelfRunContinuationDom.prepareBootstrap(PROJECT_URL, PROMPT, "cleanup-next-run-marker"));
            for (int attempt = 0; attempt < 8; attempt++) {
                if ("READY_TO_SUBMIT".equals(prepared.getString("status"))) break;
                prepared = evaluate(nextRunWebView,
                        SelfRunContinuationDom.prepareBootstrap(PROJECT_URL, PROMPT, "cleanup-next-run-marker"));
            }
            assertEquals("READY_TO_SUBMIT", prepared.getString("status"));

            JSONObject dispatched = evaluate(nextRunWebView,
                    SelfRunContinuationDom.clickPreparedBootstrap(PROJECT_URL, PROMPT,
                            "cleanup-next-run-marker"));
            assertEquals(SelfRunContinuationDom.SUBMISSION_PENDING, dispatched.getString("status"));

            JSONObject confirmed = dispatched;
            for (int attempt = 0; attempt < 40; attempt++) {
                confirmed = evaluate(nextRunWebView,
                        SelfRunContinuationDom.prepareBootstrap(PROJECT_URL, PROMPT, "cleanup-next-run-marker"));
                if ("SUBMISSION_CONFIRMED".equals(confirmed.getString("status"))) break;
                Thread.sleep(100L);
            }
            assertEquals("SUBMISSION_CONFIRMED", confirmed.getString("status"));
            assertEquals("1", read(nextRunWebView, "String(window.submitCount)"));
            assertEquals(CONVERSATION_PATH, read(nextRunWebView, "location.pathname"));
            assertEquals("cleanup-next-run-drive-account", drive.getString("driveAccountId", ""));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                HeadlessWebViewHost host = currentHost.getAndSet(null);
                if (host != null) host.destroy();
                try {
                    CookieManager cookies = CookieManager.getInstance();
                    cookies.setCookie("https://chatgpt.com", "SelfRunCleanupNextRun=; Max-Age=0; Path=/");
                    cookies.flush();
                } catch (Throwable ignored) {}
            });
            if (priorContains) assertTrue(drive.edit().putString("driveAccountId", priorAccount == null ? "" : priorAccount).commit());
            else assertTrue(drive.edit().remove("driveAccountId").commit());
        }
    }

    private static JSONObject evaluate(WebView web, String script) throws Exception {
        return new JSONObject(read(web, script));
    }

    private static String read(WebView web, String expression) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<String> raw = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                web.evaluateJavascript(expression, value -> {
                    raw.set(value);
                    complete.countDown();
                }));
        assertTrue("next-run WebView read timed out", complete.await(15, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(raw.get()).nextValue();
        return String.valueOf(decoded);
    }

    private static String fixture() {
        return """
                <!doctype html><html><body><main><form>
                <div id="prompt-textarea" contenteditable="true" data-lexical-editor="true"><p><br></p></div>
                <button type="submit" data-testid="send-button" aria-label="Send">Send</button>
                </form></main><script>
                window.submitCount=0;
                const editor=document.getElementById('prompt-textarea');
                const form=document.querySelector('form');
                const nativeExec=document.execCommand.bind(document);
                document.execCommand=function(command,show,value){
                  if(command==='selectAll'){
                    const range=document.createRange();range.selectNodeContents(editor);
                    const selection=window.getSelection();selection.removeAllRanges();selection.addRange(range);return true;
                  }
                  if(command==='delete'){
                    editor.innerHTML='<p><br></p>';editor.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'deleteContent'}));return true;
                  }
                  if(command==='insertText'){
                    editor.textContent=String(value||'');editor.dispatchEvent(new InputEvent('input',{bubbles:true,data:String(value||''),inputType:'insertText'}));return true;
                  }
                  return nativeExec(command,show,value);
                };
                form.addEventListener('submit',event=>{
                  event.preventDefault();window.submitCount++;
                  setTimeout(()=>history.replaceState({},'', '__CONVERSATION_PATH__'),50);
                });
                </script></body></html>
                """.replace("__CONVERSATION_PATH__", CONVERSATION_PATH);
    }
}
