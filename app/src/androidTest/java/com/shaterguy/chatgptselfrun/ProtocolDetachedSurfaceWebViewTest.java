package com.shaterguy.chatgptselfrun;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ProtocolDetachedSurfaceWebViewTest {
    private static final String RUN_ID="SR-DETACHED-PROTOCOL";
    private static final String TOKEN="protocol-token-current";
    private static final String ORIGIN="https://chatgpt.com/";

    @Test public void detachedSurfaceCompletesFromTerminalProtocolEventAlone() throws Exception {
        try(ActivityScenario<SelfRunNewActivity> scenario=ActivityScenario.launch(SelfRunNewActivity.class)){
            AtomicReference<HeadlessWebViewHost> hostRef=new AtomicReference<>();
            AtomicReference<WebView> webRef=new AtomicReference<>();
            AtomicReference<SelfRunStore> storeRef=new AtomicReference<>();
            AtomicReference<String> callbackRef=new AtomicReference<>("");
            CountDownLatch loaded=new CountDownLatch(1),completed=new CountDownLatch(1);

            scenario.onActivity(activity->{
                activity.getSharedPreferences("selfrun_drive",0).edit()
                        .putString("runId",RUN_ID)
                        .putString("mode",SelfRunStore.MODE_CHAT)
                        .putString("phase",SelfRunStore.PHASE_BOOTSTRAP_SEND)
                        .putBoolean("active",true).putBoolean("paused",false).commit();
                SelfRunStore store=new SelfRunStore(activity);
                store.prepareTurnProtocolToken(TOKEN);
                HeadlessWebViewHost host=HeadlessWebViewHost.create(activity);
                assertTrue(host.hasDetachableOutput());
                WebView web=host.webView();
                assertTrue(WebViewConfig.applyAutomation(web));
                web.setWebViewClient(new WebViewClient(){
                    @Override public void onPageFinished(WebView view,String url){
                        if(url!=null&&url.startsWith(ORIGIN))loaded.countDown();
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                        Uri uri=request.getUrl();
                        if(!ChatGptTurnProtocolScript.COMPLETION_SCHEME.equals(uri.getScheme()))return false;
                        String run=uri.getQueryParameter("run"),token=uri.getQueryParameter("token");
                        String source=uri.getQueryParameter("source");
                        callbackRef.set(uri.toString());
                        if(RUN_ID.equals(run)&&TOKEN.equals(token)
                                &&SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(store.phase())
                                &&TurnProtocolLogBridge.isAllowedCompletionSource(source)){
                            store.beginPostProtocolDriveSync(token,source);
                        }
                        completed.countDown();return true;
                    }
                });
                hostRef.set(host);webRef.set(web);storeRef.set(store);
                web.loadDataWithBaseURL(ORIGIN,"<!doctype html><html><body>protocol fixture</body></html>",
                        "text/html","UTF-8",null);
            });
            assertTrue("fixture load timed out",loaded.await(15,TimeUnit.SECONDS));
            assertEquals("true",evaluate(scenario,webRef,
                    "String(window.__selfRunTurnProtocol.bindTurn('"+RUN_ID+"','"+TOKEN+"'))"));
            scenario.onActivity(activity->{
                SelfRunStore store=storeRef.get();
                store.beginTurnCompletionWait(TOKEN,"protocol wait");
                assertTrue(hostRef.get().detachOutput());
                assertFalse(hostRef.get().isOutputAttached());
                webRef.get().evaluateJavascript(
                        ChatGptTurnProtocolScript.armCompletion(RUN_ID,TOKEN),null);
                assertFalse(store.beginPostProtocolDriveSync(
                        "stale-token","message_stream_complete"));
                assertEquals(SelfRunStore.PHASE_WAIT_TURN_COMPLETION,store.phase());
                assertFalse(hostRef.get().isOutputAttached());
            });

            JSONObject thinking=state(scenario,webRef,
                    "window.__selfRunTurnProtocol.observeRequest('POST','/backend-api/f/conversation')");
            assertEquals("THINKING",thinking.getString("phase"));
            assertFalse(thinking.getBoolean("sawVisibleAnswer"));
            assertEquals("",thinking.getString("currentFinalMessageId"));
            assertEquals("",callbackRef.get());
            scenario.onActivity(activity->assertFalse(hostRef.get().isOutputAttached()));

            JSONObject complete=state(scenario,webRef,
                    "window.__selfRunTurnProtocol.observeSseText("
                            +"'data: {\\\"type\\\":\\\"message_stream_complete\\\"}\\n\\n',"
                            +"'fixture',{requestIdentity:window.__selfRunTurnProtocol.snapshot().requestIdentity})");
            assertEquals("COMPLETE",complete.getString("phase"));
            assertTrue(complete.getBoolean("sawStreamComplete"));
            assertFalse(complete.getBoolean("sawVisibleAnswer"));
            assertEquals("",complete.getString("currentFinalMessageId"));
            assertEquals("message_stream_complete",complete.getString("completionSource"));
            assertTrue("protocol completion callback timed out",completed.await(15,TimeUnit.SECONDS));
            scenario.onActivity(activity->{
                assertEquals(SelfRunStore.PHASE_POST_PROTOCOL_DRIVE_SYNC,storeRef.get().phase());
                assertFalse(hostRef.get().isOutputAttached());
            });
            String callback=callbackRef.get();
            assertTrue(callback.contains("run="+RUN_ID));
            assertTrue(callback.contains("token="+TOKEN));
            assertTrue(callback.contains("source=message_stream_complete"));

            scenario.onActivity(activity->hostRef.get().destroy());
        }
    }

    private static JSONObject state(ActivityScenario<SelfRunNewActivity> scenario,
                                    AtomicReference<WebView> web,String expression) throws Exception {
        return new JSONObject(evaluate(scenario,web,"JSON.stringify("+expression+")"));
    }

    private static String evaluate(ActivityScenario<SelfRunNewActivity> scenario,
                                   AtomicReference<WebView> web,String script) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>("");
        scenario.onActivity(activity->web.get().evaluateJavascript(script,value->{
            try{
                Object parsed=new org.json.JSONTokener(value).nextValue();
                result.set(parsed instanceof String?(String)parsed:String.valueOf(parsed));
            }catch(Throwable ignored){result.set(value);}
            done.countDown();
        }));
        assertTrue("JavaScript timed out",done.await(15,TimeUnit.SECONDS));
        return result.get();
    }
}
