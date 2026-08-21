package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Prevents a normal asynchronous new-chat transition from being treated as an immediate failure. */
public final class BootstrapNewChatPolicyTest {
    @Test public void newChatTransitionHasProtectedRetryAndFiniteFailureWindows() throws Exception {
        String dom = read("app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunDom.java",
                "src/main/java/com/shaterguy/chatgptselfrun/SelfRunDom.java");
        assertTrue(dom.contains("newChatRetryMs=1800"));
        assertTrue(dom.contains("newChatFailureMs=10000"));
        assertTrue(dom.contains("Number(newChatState.clicks)<2"));
        assertTrue(dom.contains("newChatNow-Number(newChatState.lastClickAt)>=2500"));
        assertTrue(dom.contains("새 대화 화면 전환 확인 대기"));
        assertTrue(dom.contains("CHAT_BOOTSTRAP_NEW_CHAT_FAILED"));
        assertFalse(dom.contains("새 대화 화면 대신 기존 conversation이 열렸습니다."));
    }

    private static String read(String first, String fallback) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(fallback);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
