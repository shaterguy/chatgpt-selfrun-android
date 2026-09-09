package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.webkit.WebSettings;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class SelfRun3ProjectDirectoryNavigationWebViewTest {
    private static final String TARGET_PROJECT = "g-p-6a507cce80cc81919eeb9ba553b6ad9e";
    private static final String OTHER_PROJECT = "g-p-7a507cce80cc81919eeb9ba553b6ad9e";

    @Test public void selectsCapturedProjectRowAndInvokesItsClickMethod() throws Exception {
        Scenario result = runScenario("💾 Vibe Coding", 0);
        assertEquals("PROJECT_ROW_CLICKED", result.result.getString("status"));
        assertEquals("target", result.clicked);
        assertEquals("/projects", result.pathname);
    }

    @Test public void missingProjectWaitsWithoutClickingAnotherRow() throws Exception {
        Scenario result = runScenario("없는 프로젝트", 0);
        assertEquals("RETRY", result.result.getString("status"));
        assertEquals("", result.clicked);
        assertEquals("/projects", result.pathname);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private static Scenario runScenario(String projectName, int candidateIndex) throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> rawResult = new AtomicReference<>();
        AtomicReference<String> pathname = new AtomicReference<>();
        AtomicReference<String> clicked = new AtomicReference<>();
        AtomicReference<HeadlessWebViewHost> hostRef = new AtomicReference<>();
        String script = SelfRun3ProjectDirectoryNavigation.build(projectName, candidateIndex);
        String installClickSpies = "(()=>{" +
                "history.replaceState({},'', '/projects');" +
                "for(const row of document.querySelectorAll('[data-marker]')){" +
                "row.click=function(){window.__clicked=this.dataset.marker||'';};" +
                "}" +
                "return location.pathname;" +
                "})()";

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            HeadlessWebViewHost host = HeadlessWebViewHost.create(context);
            hostRef.set(host);
            host.attachOutput();
            WebView webView = host.webView();
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) {
                    view.evaluateJavascript(installClickSpies, ignored ->
                            view.evaluateJavascript(script, raw -> {
                                rawResult.set(raw);
                                view.evaluateJavascript("location.pathname", pathRaw -> {
                                    pathname.set(decodeJsString(pathRaw));
                                    view.evaluateJavascript("window.__clicked||''", clickedRaw -> {
                                        clicked.set(decodeJsString(clickedRaw));
                                        done.countDown();
                                    });
                                });
                            }));
                }
            });
            String html = "<!doctype html><html><body>"
                    + row("🎥 Other Project", OTHER_PROJECT + "-other-project", "other")
                    + row("💾 Vibe Coding", TARGET_PROJECT + "-vibe-coding", "target")
                    + "</body></html>";
            webView.loadDataWithBaseURL("https://chatgpt.com/",
                    html, "text/html", "UTF-8", "https://chatgpt.com/projects");
        });

        assertTrue("SelfRun project directory row-selection scenario timed out",
                done.await(12, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            HeadlessWebViewHost host = hostRef.get();
            if (host != null) host.destroy();
        });

        assertNotNull(rawResult.get());
        Object decoded = new JSONTokener(rawResult.get()).nextValue();
        String json = decoded instanceof String ? (String) decoded : String.valueOf(decoded);
        return new Scenario(new JSONObject(json), pathname.get(), clicked.get());
    }

    private static String row(String name, String projectSegment, String marker) {
        return "<div role='row' tabindex='0' data-page-table-selectable-row='true' style='display:block' "
                + "data-marker='" + marker + "' data-route='/g/" + projectSegment + "/project'>"
                + "<div role='gridcell'><div data-testid='project-folder-icon'></div><div>" + name + "</div></div>"
                + "<button aria-label='" + name + " project options'>options</button></div>";
    }

    private static String decodeJsString(String raw) {
        try {
            Object decoded = new JSONTokener(raw == null ? "\"\"" : raw).nextValue();
            return decoded instanceof String ? (String) decoded : String.valueOf(decoded);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static final class Scenario {
        final JSONObject result;
        final String pathname;
        final String clicked;

        Scenario(JSONObject result, String pathname, String clicked) {
            this.result = result;
            this.pathname = pathname;
            this.clicked = clicked;
        }
    }
}
