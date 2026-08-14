package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class ProjectSelectorPolicyTest {
    @Test public void newTaskUsesProjectSelectorAndPreservesGeneralChatFallback() throws Exception {
        String activity=src("SelfRunNewActivity.java");
        assertTrue(activity.contains("GENERAL_CHAT_LABEL = \"일반채팅\""));
        assertTrue(activity.contains("private Spinner project;"));
        assertTrue(activity.contains("refreshProjects()"));
        assertTrue(activity.contains("catalogStore.save(entries,System.currentTimeMillis())"));
        assertTrue(activity.contains("store.setDefaultProjectUrl(projectCanonical)"));
        assertTrue(activity.contains("projectCanonical.isEmpty()?SelfRunScript.GENERAL_CHAT_URL:projectCanonical"));
        assertFalse(activity.contains("private EditText projectUrl;"));
        assertFalse(activity.contains("TYPE_TEXT_VARIATION_URI"));
    }

    @Test public void discoveryIsTransitionScopedFailClosedNavigationAwareAndOriginValidated() throws Exception {
        String loader=src("ProjectCatalogLoader.java"),catalog=src("ProjectCatalog.java"),web=src("WebViewConfig.java");
        assertTrue(loader.contains("evaluateJavascript(PROBE_JS"));
        assertTrue(loader.contains("ProjectCatalog.isTrustedChatgptPage"));
        assertTrue(loader.contains("EMPTY_SETTLE_MS"));
        assertTrue(loader.contains("CONTROL_DISCOVERY_MS"));
        assertTrue(loader.contains("getClientRects"));
        assertTrue(loader.contains("open-sidebar-button"));
        assertTrue(loader.contains("__selfrunSidebarOpenAttempted"));
        assertTrue(loader.contains("PROJECTS_CONTROL_NOT_FOUND"));
        assertTrue(loader.contains("PROJECT_LIST_UNRESOLVED"));
        assertTrue(loader.contains("DESKTOP_USER_AGENT"));
        assertTrue(loader.contains("desktopFallbackAttempted"));
        assertTrue(loader.contains("data-href"));
        assertTrue(loader.contains("data-url"));
        assertTrue(loader.contains("const projectContextUrl=raw=>"));
        assertTrue(loader.contains("const valid=tail.length===0||(tail.length===1&&tail[0]==='project');"));
        assertTrue(loader.contains("const isProjectLink=e=>candidateValues(e).some(v=>!!projectContextUrl(v));"));
        assertTrue(loader.contains("const url=projectUrl(raw);if(!url)continue;"));
        assertTrue(loader.contains("tail[0]==='c'"));
        assertTrue(loader.contains("aria-controls"));
        assertTrue(loader.contains("aria-owns"));
        assertTrue(loader.contains("collectControlled"));
        assertTrue(loader.contains("collectOpenedPortal"));
        assertTrue(loader.contains("collectNewlyVisible"));
        assertTrue(loader.contains("__selfrunProjectsBeforeElements=new WeakSet"));
        assertTrue(loader.contains("__selfrunProjectsBeforeCounts"));
        assertTrue(loader.contains("collectNewItems"));
        assertFalse(loader.contains("collectNearControl"));
        assertFalse(loader.contains("compareDocumentPosition"));
        assertFalse(loader.contains("value.includes('g-p-')"));
        assertFalse(loader.contains("for(const attr of Array.from(e.attributes||[]))"));
        assertTrue(loader.contains("fallbackOrFail(\"PROJECT_LIST_UNRESOLVED\")"));
        assertFalse(loader.contains("result.entries.isEmpty() && result.markerSeen\n                    && nowElapsed >= EMPTY_SETTLE_MS && stableProbes >= REQUIRED_STABLE_PROBES) {\n                succeed(result.entries)"));
        assertTrue(loader.contains("probeScriptForTesting()"));
        assertFalse(loader.contains("addJavascriptInterface"));
        assertFalse(loader.contains("getCookie("));
        assertTrue(catalog.contains("canonicalProjectUrl"));
        assertTrue(catalog.contains("uri.getRawUserInfo() != null || uri.getPort() != -1"));
        assertTrue(web.contains("setAllowFileAccess(false)"));
        assertTrue(web.contains("setAllowContentAccess(false)"));
        assertTrue(web.contains("setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW)"));
    }

    @Test public void staleConversationPollutedCatalogCacheIsInvalidatedBySchemaVersion() throws Exception {
        String store=src("ProjectCatalogStore.java");
        assertTrue(store.contains("SCHEMA_VERSION = 3"));
        assertTrue(store.contains("prefs.getInt(KEY_SCHEMA, 0) != SCHEMA_VERSION"));
        assertTrue(store.contains("putInt(KEY_SCHEMA, SCHEMA_VERSION)"));
    }

    @Test public void refreshFailureAndStaleCallbacksCannotDestroyValidSelection() throws Exception {
        String activity=src("SelfRunNewActivity.java");
        assertTrue(activity.contains("generation != refreshGeneration"));
        assertTrue(activity.contains("safeCode"));
        assertTrue(activity.contains("새로고침 실패 · \" + safeCode + \" · 기존 목록을 유지합니다"));
        assertTrue(activity.contains("ProjectCatalog.indexOfUrl(entries,defaultUrl) < 0"));
        assertTrue(activity.contains("refreshGeneration++"));
    }

    static String src(String file)throws Exception{return read("app/src/main/java/com/shaterguy/chatgptselfrun/"+file,"src/main/java/com/shaterguy/chatgptselfrun/"+file);}
    static String read(String a,String b)throws Exception{Path p=Paths.get(a);if(!Files.exists(p))p=Paths.get(b);return new String(Files.readAllBytes(p),java.nio.charset.StandardCharsets.UTF_8);}
}
