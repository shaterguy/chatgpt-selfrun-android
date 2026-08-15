package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ProjectCatalogNavigatorInstrumentedTest {
    private static final long WAIT_SECONDS = 8L;
    private static final String STYLE =
            "<style>button,a,[role=menuitem]{display:block;margin:8px 0}</style>";

    @Test public void clickOnlyProjectRowIsDiscoveredAndNavigationProvidesCanonicalUrl() throws Exception {
        HostPage page = open("""
                <!doctype html><html><head>%s</head><body>
                <button id="projects" onclick="openProjects()">Projects</button>
                <div id="list"></div>
                <script>
                function openProjects(){
                  document.getElementById('list').innerHTML=
                    '<button role="menuitem" id="alpha" '+
                    'onclick="history.pushState({},\\'\\',\\'/g/g-p-alpha/project\\')">Alpha</button>';
                }
                </script>
                </body></html>
                """.formatted(STYLE));
        try {
            JSONObject opening = scan(page.webView, new LinkedHashSet<>());
            assertEquals("OPENING", opening.getString("state"));
            assertTrue(opening.getBoolean("marker"));

            JSONObject ready = scan(page.webView, new LinkedHashSet<>());
            assertEquals("READY", ready.getString("state"));
            JSONObject candidate = ready.getJSONObject("candidate");
            assertEquals("Alpha", candidate.getString("name"));
            assertEquals("", candidate.getString("directUrl"));

            JSONObject clicked = evalObject(page.webView,
                    ProjectCatalogNavigator.clickScriptForTesting(candidate.getString("key")));
            assertTrue(clicked.getBoolean("clicked"));

            String canonical = waitForCanonical(page.webView);
            assertEquals("https://chatgpt.com/g/g-p-alpha/project", canonical);
        } finally {
            close(page);
        }
    }

    @Test public void duplicateProjectNamesKeepDistinctTraversalKeys() throws Exception {
        HostPage page = open("""
                <!doctype html><html><head>%s</head><body>
                <button id="projects" aria-expanded="true" aria-controls="project-list">Projects</button>
                <div id="project-list" role="list">
                  <button role="menuitem" onclick="history.pushState({},'', '/g/g-p-one/project')">Same</button>
                  <button role="menuitem" onclick="history.pushState({},'', '/g/g-p-two/project')">Same</button>
                </div>
                </body></html>
                """.formatted(STYLE));
        try {
            Set<String> visited = new LinkedHashSet<>();
            JSONObject first = scan(page.webView, visited);
            assertEquals("READY", first.getString("state"));
            String firstKey = first.getJSONObject("candidate").getString("key");
            visited.add(firstKey);

            JSONObject second = scan(page.webView, visited);
            assertEquals("READY", second.getString("state"));
            String secondKey = second.getJSONObject("candidate").getString("key");
            assertNotEquals(firstKey, secondKey);
            assertEquals("Same", second.getJSONObject("candidate").getString("name"));
        } finally {
            close(page);
        }
    }

    @Test public void projectConversationHrefIsSkippedWhileClickOnlyProjectRemains() throws Exception {
        HostPage page = open("""
                <!doctype html><html><head>%s</head><body>
                <button id="projects" aria-expanded="true" aria-controls="project-list">Projects</button>
                <div id="project-list" role="list">
                  <a href="/g/g-p-decoy/c/conversation-1">Conversation inside a project</a>
                  <button role="menuitem" onclick="history.pushState({},'', '/g/g-p-real/project')">Real Project</button>
                </div>
                </body></html>
                """.formatted(STYLE));
        try {
            JSONObject result = scan(page.webView, new LinkedHashSet<>());
            assertEquals("READY", result.getString("state"));
            assertEquals("Real Project", result.getJSONObject("candidate").getString("name"));
            assertEquals("", result.getJSONObject("candidate").getString("directUrl"));
        } finally {
            close(page);
        }
    }

    @Test public void exhaustedVisibleRowsScrollAndDiscoverVirtualizedNextProject() throws Exception {
        HostPage page = open("""
                <!doctype html><html><head>
                %s
                <style>
                  #project-list{height:80px;overflow-y:auto;position:relative}
                  #content{height:420px;position:relative}
                  #first,#second{position:absolute;left:0}
                  #first{top:0} #second{top:260px}
                </style>
                </head><body>
                <button id="projects" aria-expanded="true" aria-controls="project-list">Projects</button>
                <div id="project-list" role="list" onscroll="swapRow()">
                  <div id="content">
                    <button id="first" role="menuitem">First</button>
                  </div>
                </div>
                <script>
                function swapRow(){
                  if(document.getElementById('project-list').scrollTop<50)return;
                  document.getElementById('content').innerHTML=
                    '<button id="second" role="menuitem">Second</button>';
                }
                </script>
                </body></html>
                """.formatted(STYLE));
        try {
            Set<String> visited = new LinkedHashSet<>();
            JSONObject first = scan(page.webView, visited);
            assertEquals("READY", first.getString("state"));
            visited.add(first.getJSONObject("candidate").getString("key"));

            JSONObject scrolled = scan(page.webView, visited);
            assertEquals("SCROLLED", scrolled.getString("state"));
            Thread.sleep(200L);

            JSONObject second = scan(page.webView, visited);
            assertEquals("READY", second.getString("state"));
            assertEquals("Second", second.getJSONObject("candidate").getString("name"));
        } finally {
            close(page);
        }
    }

    private static JSONObject scan(WebView webView, Set<String> visited) throws Exception {
        return evalObject(webView, ProjectCatalogNavigator.scanScriptForTesting(visited));
    }

    private static JSONObject evalObject(WebView webView, String script) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        String[] raw = new String[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                webView.evaluateJavascript(script, value -> {
                    raw[0] = value;
                    complete.countDown();
                }));
        assertTrue("javascript did not return", complete.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertNotNull(raw[0]);
        Object decoded = new JSONTokener(raw[0]).nextValue();
        if (decoded instanceof String) decoded = new JSONTokener((String) decoded).nextValue();
        assertTrue(decoded instanceof JSONObject);
        return (JSONObject) decoded;
    }

    private static String waitForCanonical(WebView webView) throws Exception {
        long deadline = System.currentTimeMillis() + 2_500L;
        while (System.currentTimeMillis() < deadline) {
            String[] current = new String[1];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> current[0] = webView.getUrl());
            String canonical = ProjectCatalog.canonicalProjectUrl(current[0]);
            if (!canonical.isEmpty()) return canonical;
            Thread.sleep(50L);
        }
        return "";
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
                @Override public void onPageFinished(WebView view, String url) {
                    loaded.countDown();
                }
            });
            webView[0].loadDataWithBaseURL(
                    "https://chatgpt.com/", html, "text/html", "UTF-8", null);
        });
        assertTrue("fixture page did not finish loading",
                loaded.await(WAIT_SECONDS, TimeUnit.SECONDS));
        return new HostPage(host[0], webView[0]);
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
