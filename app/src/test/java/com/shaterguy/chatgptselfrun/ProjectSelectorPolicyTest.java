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

    @Test public void discoveryIsOneWayOriginValidatedAndDoesNotExposeNativeBridge() throws Exception {
        String loader=src("ProjectCatalogLoader.java"),catalog=src("ProjectCatalog.java"),web=src("WebViewConfig.java");
        assertTrue(loader.contains("evaluateJavascript(PROBE_JS"));
        assertTrue(loader.contains("ProjectCatalog.isTrustedChatgptPage"));
        assertFalse(loader.contains("addJavascriptInterface"));
        assertFalse(loader.contains("getCookie("));
        assertTrue(catalog.contains("canonicalProjectUrl"));
        assertTrue(catalog.contains("uri.getRawUserInfo() != null || uri.getPort() != -1"));
        assertTrue(web.contains("setAllowFileAccess(false)"));
        assertTrue(web.contains("setAllowContentAccess(false)"));
        assertTrue(web.contains("setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW)"));
    }

    @Test public void refreshFailureAndStaleCallbacksCannotDestroyValidSelection() throws Exception {
        String activity=src("SelfRunNewActivity.java");
        assertTrue(activity.contains("generation != refreshGeneration"));
        assertTrue(activity.contains("새로고침 실패 · 기존 목록을 유지합니다"));
        assertTrue(activity.contains("ProjectCatalog.indexOfUrl(entries,defaultUrl) < 0"));
        assertTrue(activity.contains("refreshGeneration++"));
    }

    static String src(String file)throws Exception{return read("app/src/main/java/com/shaterguy/chatgptselfrun/"+file,"src/main/java/com/shaterguy/chatgptselfrun/"+file);}
    static String read(String a,String b)throws Exception{Path p=Paths.get(a);if(!Files.exists(p))p=Paths.get(b);return new String(Files.readAllBytes(p),java.nio.charset.StandardCharsets.UTF_8);}
}
