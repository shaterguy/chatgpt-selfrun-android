package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ProjectCatalogControlDisambiguationInstrumentedTest {
    private static final long WAIT_SECONDS = 8L;

    @Test public void projectConversationNamedProjectsCannotMaskRealProjectsControl() throws Exception {
        String html = """
                <!doctype html><html><head>
                <style>button,a{display:block;margin:8px 0}</style>
                </head><body>
                <a href="/g/g-p-decoy/c/conversation-1">Projects Weekly</a>
                <button id="projects" onclick="openProjects()">Projects</button>
                <div id="list"></div>
                <script>
                function openProjects(){
                  document.getElementById('list').innerHTML=
                    '<a href="/g/g-p-invest/project">Invest</a>'+
                    '<a href="/g/g-p-vibe/project">Vibe Coding</a>';
                }
                </script>
                </body></html>
                """;
        HostPage page = open(html);
        try {
            ProjectCatalog.Probe first = eval(page.webView);
            assertEquals("OPENING", first.state);
            assertTrue(first.markerSeen);
            assertTrue(first.entries.isEmpty());

            ProjectCatalog.Probe second = eval(page.webView);
            assertEquals("FOUND", second.state);
            assertEquals(2, second.entries.size());
            assertEquals("Invest", second.entries.get(0).name);
            assertEquals("Vibe Coding", second.entries.get(1).name);
            assertFalse(second.entries.stream().anyMatch(e -> "Projects Weekly".equals(e.name)));
        } finally {
            close(page);
        }
    }

    private static HostPage open(String html) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch loaded = new CountDownLatch(1);
        HeadlessWebViewHost[] host = new HeadlessWebViewHost[1];
        WebView[] webView = new WebView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            host[0] = HeadlessWebViewHost.create(context);
            webView[0] = host[0].webView();
            WebViewConfig.applyAutomation(webView[0]);
            webView[0].setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) { loaded.countDown(); }
            });
            webView[0].loadDataWithBaseURL("https://chatgpt.com/", html, "text/html", "UTF-8", null);
        });
        assertTrue("fixture page did not finish loading", loaded.await(WAIT_SECONDS, TimeUnit.SECONDS));
        return new HostPage(host[0], webView[0]);
    }

    private static ProjectCatalog.Probe eval(WebView webView) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        String[] raw = new String[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                webView.evaluateJavascript(ProjectCatalogLoader.probeScriptForTesting(), value -> {
                    raw[0] = value;
                    complete.countDown();
                }));
        assertTrue("project probe did not return", complete.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertNotNull(raw[0]);
        return ProjectCatalog.parseProbe(raw[0]);
    }

    private static void close(HostPage page) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(page.host::destroy);
    }

    private static final class HostPage {
        final HeadlessWebViewHost host;
        final WebView webView;
        HostPage(HeadlessWebViewHost host, WebView webView) {
            this.host = host;
            this.webView = webView;
        }
    }
}
