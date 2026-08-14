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
public final class ProjectCatalogProbeInstrumentedTest {
    private static final long WAIT_SECONDS = 8L;

    @Test public void collapsedSidebarIsOpenedBeforeProjectsAreCollected() throws Exception {
        HostPage page = open("""
                <!doctype html><html><body>
                <button id="nav" aria-label="Open sidebar" onclick="openSidebar()">☰</button>
                <div id="sidebar"></div>
                <script>
                function openSidebar() {
                  const sidebar=document.getElementById('sidebar');
                  sidebar.innerHTML='<button id="projects">Projects</button>';
                  document.getElementById('projects').onclick=openProjects;
                }
                function openProjects() {
                  const sidebar=document.getElementById('sidebar');
                  if (document.getElementById('alpha')) return;
                  sidebar.insertAdjacentHTML('beforeend',
                    '<a id="alpha" href="/g/g-p-alpha/project">Alpha</a>'+
                    '<a id="beta" href="/g/g-p-beta">Beta</a>');
                }
                </script>
                </body></html>
                """);
        try {
            ProjectCatalog.Probe first = eval(page.webView);
            assertEquals("OPENING", first.state);
            assertFalse(first.markerSeen);
            assertTrue(first.entries.isEmpty());

            ProjectCatalog.Probe second = eval(page.webView);
            assertEquals("OPENING", second.state);
            assertTrue(second.markerSeen);
            assertTrue(second.entries.isEmpty());

            ProjectCatalog.Probe third = eval(page.webView);
            assertEquals("FOUND", third.state);
            assertTrue(third.markerSeen);
            assertEquals(2, third.entries.size());
            assertEquals("Alpha", third.entries.get(0).name);
            assertEquals("https://chatgpt.com/g/g-p-alpha/project", third.entries.get(0).url);
            assertEquals("Beta", third.entries.get(1).name);
            assertEquals("https://chatgpt.com/g/g-p-beta/project", third.entries.get(1).url);
        } finally {
            close(page);
        }
    }

    @Test public void expandedSidebarProjectsAreCollectedWithoutNavigationClick() throws Exception {
        HostPage page = open("""
                <!doctype html><html><body>
                <button id="projects">Projects</button>
                <a href="/g/g-p-one/project">One</a>
                <a href="/g/g-p-two">Two</a>
                </body></html>
                """);
        try {
            ProjectCatalog.Probe result = eval(page.webView);
            assertEquals("FOUND", result.state);
            assertTrue(result.markerSeen);
            assertEquals(2, result.entries.size());
        } finally {
            close(page);
        }
    }

    @Test public void visibleProjectsControlWithNoEntriesIsOnlyAnAmbiguousProbeState() throws Exception {
        HostPage page = open("""
                <!doctype html><html><body>
                <button id="projects">Projects</button>
                </body></html>
                """);
        try {
            ProjectCatalog.Probe first = eval(page.webView);
            assertEquals("OPENING", first.state);
            assertTrue(first.markerSeen);

            ProjectCatalog.Probe second = eval(page.webView);
            assertEquals("EMPTY", second.state);
            assertTrue(second.markerSeen);
            assertTrue(second.entries.isEmpty());
        } finally {
            close(page);
        }
    }

    @Test public void projectConversationLinksAreCanonicalizedToProjectHome() throws Exception {
        HostPage page = open("""
                <!doctype html><html><body>
                <button>Projects</button>
                <a href="/g/g-p-one/c/conversation-123">One current conversation</a>
                </body></html>
                """);
        try {
            ProjectCatalog.Probe result = eval(page.webView);
            assertEquals("FOUND", result.state);
            assertEquals(1, result.entries.size());
            assertEquals("https://chatgpt.com/g/g-p-one/project", result.entries.get(0).url);
        } finally {
            close(page);
        }
    }

    @Test public void routerStyleDataUrlProjectEntriesAreCollectedWithoutAnchorHref() throws Exception {
        HostPage page = open("""
                <!doctype html><html><body>
                <button>Projects</button>
                <div role="link" data-url="/g/g-p-one/project">One</div>
                <div role="link" data-to="/g/g-p-two/c/conversation-456">Two</div>
                </body></html>
                """);
        try {
            ProjectCatalog.Probe result = eval(page.webView);
            assertEquals("FOUND", result.state);
            assertEquals(2, result.entries.size());
            assertEquals("One", result.entries.get(0).name);
            assertEquals("https://chatgpt.com/g/g-p-one/project", result.entries.get(0).url);
            assertEquals("Two", result.entries.get(1).name);
            assertEquals("https://chatgpt.com/g/g-p-two/project", result.entries.get(1).url);
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
