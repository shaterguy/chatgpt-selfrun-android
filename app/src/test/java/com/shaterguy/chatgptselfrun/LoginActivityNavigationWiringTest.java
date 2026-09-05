package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class LoginActivityNavigationWiringTest {
    @Test public void topBarUsesDedicatedCloseAction() throws Exception {
        String source = src("LoginActivity.java");
        assertTrue(source.contains("menu.getMenu().add(\"닫기\")"));
    }

    @Test public void browserToolbarUsesBackTitleAndOverflowActions() throws Exception {
        String source = src("LoginActivity.java");
        int toolbarStart = source.indexOf("LinearLayout toolbar = Ui.topBar(this, \"ChatGPT\", \"\"");
        int toolbarEnd = source.indexOf("heading.addView(toolbar);", toolbarStart);
        assertTrue(toolbarStart >= 0 && toolbarEnd > toolbarStart);
        String toolbar = source.substring(toolbarStart, toolbarEnd);
        assertTrue(toolbar.contains("R.drawable.ic_more_vert, \"메뉴\", v -> showBrowserMenu(v)"));
        assertTrue(toolbar.contains("toolbar.addView(Ui.iconButton(this, R.drawable.ic_arrow_back, \"이전 페이지\", v -> navigateBack()), 0,"));
        int menuStart = source.indexOf("private void showBrowserMenu(");
        int menuEnd = source.indexOf("private void navigateBack()", menuStart);
        assertTrue(menuStart >= 0 && menuEnd > menuStart);
        String menu = source.substring(menuStart, menuEnd);
        assertTrue(menu.contains("new android.widget.PopupMenu(this, anchor)"));
        assertTrue(menu.contains("menu.getMenu().add(\"ChatGPT 홈\").setOnMenuItemClickListener(item -> { webView.loadUrl(\"https://chatgpt.com/\"); return true; });"));
        assertTrue(menu.contains("menu.getMenu().add(\"새로고침\").setOnMenuItemClickListener(item -> { webView.reload(); return true; });"));
        assertTrue(menu.contains("menu.getMenu().add(\"닫기\").setOnMenuItemClickListener(item -> { finish(); return true; });"));
        assertTrue(menu.contains("menu.show();"));
    }

    @Test public void backKeepsWebHistorySemantics() throws Exception {
        String source = src("LoginActivity.java");
        assertTrue(source.contains(
                "if (webView != null && webView.canGoBack()) webView.goBack(); else finish();"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
