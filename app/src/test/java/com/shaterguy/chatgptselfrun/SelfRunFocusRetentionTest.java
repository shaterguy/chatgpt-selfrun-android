package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SelfRunFocusRetentionTest {
    @Test public void preservedWebViewRefocusesAfterResumeAndWindowReturn() throws Exception {
        String host = src("HeadlessWebViewHost.java");
        String focusWebView = between(host,
                "private static final class FocusPreservingWebView",
                "WebView webView()");
        assertTrue(host.contains("new FocusPreservingWebView(presentation.getContext())"));
        assertFalse(host.contains("new FocusPreservingWebView(context)"));
        assertTrue(host.contains("fixed mobile virtual display unavailable"));
        assertTrue(focusWebView.contains("@Override public void onResume()"));
        assertTrue(focusWebView.contains("super.onResume();"));
        assertTrue(focusWebView.contains("requestFocus();"));
        assertTrue(focusWebView.contains("@Override public void onWindowFocusChanged(boolean hasWindowFocus)"));
        assertTrue(focusWebView.contains("if (hasWindowFocus) requestFocus();"));
    }

    @Test public void normalContinuationReusesExistingWebViewWithoutReloadOrRecreation() throws Exception {
        String service = src("SelfRunService.java");
        String ensure = between(service, "private void ensureWebView()", "private void launchWebView");
        assertTrue(ensure.contains("if(webView!=null)"));
        assertTrue(ensure.contains("scheduleWeb(250L);return;"));
        assertFalse(ensure.contains("loadUrl("));
        assertFalse(ensure.contains("cleanupWebView()"));
        assertFalse(ensure.contains("HeadlessWebViewHost.create"));
    }

    @Test public void composerRefocusRunsBeforeEquivalentContentFastPaths() {
        String prompt = "[2026.08.17 | 21:13:30] [SELF_RUN_CONTINUE SR-FOCUS-TEST]";
        String prepare = SelfRunDom.prepareDriveTurn(
                "https://chatgpt.com/c/conversation123", prompt, "focus-prepare");
        String click = SelfRunDom.clickPreparedDriveTurn(
                "https://chatgpt.com/c/conversation123", prompt, "focus-click");

        int prepareFocus = prepare.indexOf("composer.focus();");
        int prepareFastPath = prepare.indexOf("if(!acceptable())");
        int clickFocus = click.indexOf("composer.focus();");
        int clickFastPath = click.indexOf("if(!acceptable())");
        assertTrue(prepareFocus >= 0 && prepareFastPath >= 0 && prepareFocus < prepareFastPath);
        assertTrue(clickFocus >= 0 && clickFastPath >= 0 && clickFocus < clickFastPath);
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        return source.substring(source.indexOf(start), source.indexOf(end));
    }
}
