package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class RateLimitAndStopPolicyTest {
    @Test public void requiredMetadataLogEventsArePresent() throws Exception {
        String service = Source.read("SelfRunService.java");
        for (String event : new String[]{
                "CONVERSATION_REMOTE_UPDATE", "CONVERSATION_SYNC_DIRTY",
                "CONVERSATION_SYNC_PROVEN", "CONVERSATION_SYNC_UNPROVEN",
                "CONVERSATION_CHANNEL_OPEN", "CONVERSATION_CHANNEL_CLOSED",
                "RESPONSE_ACTIVE_DETECTED", "RESPONSE_ACTIVE_WAIT_10S",
                "RESPONSE_IDLE_CONFIRMED", "SUBMIT_BLOCKED_STOP",
                "SUBMIT_BLOCKED_FRESHNESS", "SUBMIT_GUARD_PASS",
                "RATE_LIMIT_BACKOFF", "RATE_LIMIT_RECOVERED"}) {
            assertTrue("missing log event: " + event, service.contains("\"" + event + "\""));
        }
    }

    @Test public void rateLimitRetryBudgetPreservesWebViewAndDoesNotNavigate() throws Exception {
        String service = Source.read("SelfRunService.java");
        String rate = Source.between(service, "private void handleWebRateLimit", "private void onConversationProbeEvent");
        assertTrue(rate.contains("RATE_LIMIT_BACKOFF_MS"));
        assertTrue(rate.contains("CHATGPT_RATE_LIMIT_RETRY_LIMIT"));
        assertTrue(rate.contains("webview=preserved"));
        assertTrue(rate.contains("invalidateConversationFreshness"));
        assertFalse(rate.contains("reload()"));
        assertFalse(rate.contains("loadUrl("));
    }

    @Test public void stopWaitDoesNotHaveElapsedTimeForceFallback() throws Exception {
        String service = Source.read("SelfRunService.java");
        assertTrue(service.contains("RESPONSE_ACTIVE_WAIT_MS = 10_000L"));
        assertFalse(service.contains("MAX_STOP_WAIT"));
        assertFalse(service.contains("forceSubmit"));
        assertFalse(service.contains("forceSend"));
    }

    static final class Source {
        static String read(String file) throws Exception {
            java.nio.file.Path p = java.nio.file.Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
            if (!java.nio.file.Files.exists(p)) p = java.nio.file.Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
            return new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
        }
        static String between(String value, String start, String end) {
            int a=value.indexOf(start), b=value.indexOf(end,a);
            assertTrue(a>=0 && b>a);
            return value.substring(a,b);
        }
    }
}
