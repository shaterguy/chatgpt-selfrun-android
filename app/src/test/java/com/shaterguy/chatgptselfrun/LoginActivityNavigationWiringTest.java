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

    @Test public void browserActionsPlaceBackImmediatelyBeforeRefresh() throws Exception {
        String source = src("LoginActivity.java");
        int stripStart = source.indexOf("heading.addView(Ui.actionStrip(this,");
        int stripEnd = source.indexOf("LinearLayout.LayoutParams headingParams", stripStart);
        assertTrue(stripStart >= 0 && stripEnd > stripStart);
        String strip = source.substring(stripStart, stripEnd);
        int back = strip.indexOf("Ui.textButton(this, \"뒤로\", v -> navigateBack())");
        int refresh = strip.indexOf("Ui.textButton(this, \"새로고침\", v -> webView.reload())");
        int home = strip.indexOf("Ui.outlinedButton(this, \"ChatGPT 홈\"");
        assertTrue(back >= 0 && refresh > back && home > refresh);
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
