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
    private static final String BLOCK_STYLE = "<style>button,a,[role=link]{display:block;margin:8px 0}</style>";

    @Test public void collapsedSidebarIsOpenedBeforeProjectsAreCollected() throws Exception {
        HostPage page = open("""
                <!doctype html><html><head>%s</head><body>
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
                """.formatted(BLOCK_STYLE));
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

    @Test public void expandedSidebarUsesExplicitControlledProjectList() throws Exception {
        HostPage page = open("""
                <!doctype html><html><head>%s</head><body>
                <div id="sidebar">
                  <button id="projects" aria-expanded="true" aria-controls="project-list">Projects</button>
                  <div id="project-list" role="list">
                    <a href="/g/g-p-one/project">One</a>
                    <a href="/g/g-p-two">Two</a>
                  </div>
                </div>
                </body></html>
                """.formatted(BLOCK_STYLE));
        try {
            ProjectCatalog.Probe result = eval(page.webView);
            assertEquals("FOUND", result.state);
            assertTrue(result.markerSeen);
            assertEquals(2, result.entries.size());
            assertEquals("One", result.entries.get(0).name);
            assertEquals("Two", result.entries.get(1).name);
        } finally {
            close(page);
        }
    }

    @Test public void ambientCurrentProjectBeforeProjectsControlDoesNotShortCircuitCatalog() throws Exception {
        HostPage page = open("""
                <!doctype html><html><head>%s</head><body>
                <a id="ambient" href="/g/g-p-current/project">Current project header</a>
                <div id="sidebar">
                  <button id="projects" onclick="openProjects()">Projects</button>
                  <div id="list"></div>
                </div>
                <script>
                function openProjects(){
                  document.getElementById('list').innerHTML=
                    '<a href="/g/g-p-alpha/project">Alpha</a>'+
                    '<a href="/g/g-p-beta/project">Beta</a>';
                }
                </script>
                </body></html>
                """.formatted(BLOCK_STYLE));
        try {
            ProjectCatalog.Probe first = eval(page.webView);
            assertEquals("OPENING", first.state);
            assertTrue(first.markerSeen);
            assertTrue(first.entries.isEmpty());

            ProjectCatalog.Probe second = eval(page.webView);
            assertEquals("FOUND", second.state);
            assertEquals(2, second.entries.size());
            assertEquals("Alpha", second.entries.get(0).name);
            assertEquals("Beta", second.entries.get(1).name);
            assertFalse(second.entries.stream().anyMatch(e -> e.url.contains("g-p-current")));
        } finally {
            close(page);
        }
    }

    @Test public void ambientCurrentProjectAfterProjectsControlDoesNotShortCircuitCatalog() throws Exception {
        HostPage page = open("""
                <!doctype html><html><head>%s</head><body>
                <div id="sidebar">
                  <button id="projects" onclick="openProjects()">Projects</button>
                  <a id="ambient" href="/g/g-p-current/project">Wrong Current Wrapper</a>
                  <div id="list"></div>
                </div>
                <script>
                function openProjects(){
                  document.getElementById('list').innerHTML=
                    '<a href="/g/g-p-alpha/project">Alpha</a>'+
                    '<a href="/g/g-p-beta/project">Beta</a>';
                }
                </script>
                </body></html>
                """.formatted(BLOCK_STYLE));
        try {
            ProjectCatalog.Probe first = eval(page.webView);
            assertEquals("OPENING", first.state);
            assertTrue(first.entries.isEmpty());

            ProjectCatalog.Probe second = eval(page.webView);
            assertEquals("FOUND", second.state);
            assertEquals(2, second.entries.size());
            assertEquals("Alpha", second.entries.get(0).name);
            assertEquals("Beta", second.entries.get(1).name);
            assertFalse(second.entries.stream().anyMatch(e -> e.url.contains("g-p-current")));
        } finally {
            close(page);
        }
    }

    @Test public void routerStateAfterProjectsControlDoesNotShortCircuitCatalog() throws Exception {
        HostPage page = open("""
                <!doctype html><html><head>%s</head><body>
                <div id="sidebar">
                  <button id="projects" onclick="openProjects()">Projects</button>
                  <div id="router" role="link" data-url="/g/g-p-current/project">Wrong router label</div>
                  <div id="list"></div>
                </div>
                <script>
                function openProjects(){
                  document.getElementById('list').innerHTML='<div role="link" data-url="/g/g-p-real/project"><span>Real Project</span></div>';
                }
                </script>
                </body></html>
                """.formatted(BLOCK_STYLE));
        try {
            ProjectCatalog.Probe first = eval(page.webView);
            assertEquals("OPENING", first.state);
            ProjectCatalog.Probe second = eval(page.webView);
            assertEquals("FOUND", second.state);
            assertEquals(1, second.entries.size());
            assertEquals("Real Project", second.entries.get(0).name);
            assertEquals("https://chatgpt.com/g/g-p-real/project", second.entries.get(0).url);
        } finally {
            close(page);
        }
    }

    @Test public void existingAmbientUrlDoesNotHideNewListElementWithSameProjectUrl() throws Exception {
        HostPage page = open("""
                <!doctype html><html><head>%s</head><body>
                <a id="ambient" href="/g/g-p-one/project">Ambient One</a>
                <button id="projects" onclick="openProjects()">Projects</button>
                <div id="list"></div>
                <script>
                function openProjects(){
                  document.getElementById('list').innerHTML=
                    '<a href="/g/g-p-one/project">One Actual</a>'+
                    '<a href="/g/g-p-two/project">Two Actual</a>';
                }
                </script>
                </body></html>
                """.formatted(BLOCK_STYLE));
        try {
            ProjectCatalog.Probe first = eval(page.webView);
            assertEquals("OPENING", first.state);
            ProjectCatalog.Probe second = eval(page.webView);
            assertEquals("FOUND", second.state);
            assertEquals(2, second.entries.size());
            assertEquals("One Actual", second.entries.get(0).name);
            assertEquals("https://chatgpt.com/g/g-p-one/project", second.entries.get(0).url);
            assertEquals("Two Actual", second.entries.get(1).name);
        } finally {
            close(page);
        }
    }

    @Test public void portalProjectMenuCollectsOnlyEntriesRevealedByProjectsControl() throws Exception {
        HostPage page = open("""
                <!doctype html><html><head>%s</head><body>
                <a href="/g/g-p-current/project">Ambient Current Project</a>
                <button id="projects" onclick="openProjects()">Projects</button>
                <script>
                function openProjects(){
                  const menu=document.createElement('div');menu.setAttribute('role','menu');
                  menu.innerHTML='<a href="/g/g-p-one/project">One</a><a href="/g/g-p-two/project">Two</a>';
                  document.body.appendChild(menu);
                }
                </script>
                </body></html>
                """.formatted(BLOCK_STYLE));
        try {
            ProjectCatalog.Probe first = eval(page.webView);
            assertEquals("OPENING", first.state);
            ProjectCatalog.Probe second = eval(page.webView);
            assertEquals("FOUND", second.state);
            assertEquals(2, second.entries.size());
            assertEquals("One", second.entries.get(0).name);
            assertEquals("Two", second.entries.get(1).name);
        } finally {
            close(page);
        }
    }

    @Test public void visibleProjectsControlWithNoEntriesIsOnlyAnAmbiguousProbeState() throws Exception {
        HostPage page = open("""
                <!doctype html><html><head>%s</head><body>
                <button id="projects">Projects</button>
                </body></html>
                """.formatted(BLOCK_STYLE));
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

    @Test public void projectConversationLinksAreNotCatalogEntries() throws Exception {
        HostPage page = open("""
                <!doctype html><html><head>%s</head><body>
                <div id="sidebar">
                  <button aria-expanded="true" aria-controls="project-list">Projects</button>
                  <div id="project-list" role="list">
                    <a href="/g/g-p-one/c/conversation-123">One current conversation</a>
                  </div>
                </div>
                </body></html>
                """.formatted(BLOCK_STYLE));
        try {
            ProjectCatalog.Probe result = eval(page.webView);
            assertEquals("OPENING", result.state);
            assertTrue(result.markerSeen);
            assertTrue(result.entries.isEmpty());
            ProjectCatalog.Probe settled = eval(page.webView);
            assertEquals("EMPTY", settled.state);
            assertTrue(settled.markerSeen);
            assertTrue(settled.entries.isEmpty());
        } finally {
            close(page);
        }
    }

    @Test public void routerStyleProjectRootsWinOverConversationDecoys() throws Exception {
        HostPage page = open("""
                <!doctype html><html><head>%s</head><body>
                <div id="sidebar">
                  <button aria-expanded="true" aria-controls="project-list">Projects</button>
                  <div id="project-list" role="list">
                    <div role="link" data-url="/g/g-p-one/project">One</div>
                    <div role="link" data-to="/g/g-p-two/c/conversation-456">Daily Briefing</div>
                    <div role="link" data-to="/g/g-p-two/project">Two</div>
                  </div>
                </div>
                </body></html>
                """.formatted(BLOCK_STYLE));
        try {
            ProjectCatalog.Probe result = eval(page.webView);
            assertEquals("FOUND", result.state);
            assertEquals(2, result.entries.size());
            assertEquals("One", result.entries.get(0).name);
            assertEquals("https://chatgpt.com/g/g-p-one/project", result.entries.get(0).url);
            assertEquals("Two", result.entries.get(1).name);
            assertEquals("https://chatgpt.com/g/g-p-two/project", result.entries.get(1).url);
            assertFalse(result.entries.stream().anyMatch(e -> "Daily Briefing".equals(e.name)));
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
