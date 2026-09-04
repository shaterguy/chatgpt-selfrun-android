package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class WebViewTransientNoStartPolicyWiringTest {
    @Test public void retryableResourceErrorsPauseNoStartWithoutCompleting() throws Exception {
        String service = src("SelfRunService.java");
        String policy = src("SelfRunRolloverPolicy.java");
        long started = 1_000L;
        long expired = started + SelfRunRolloverPolicy.CONTINUATION_NO_START_MAX_WAIT_MS + 1L;

        assertTrue(SelfRunRolloverPolicy.retryHttpStatus(429));
        assertTrue(service.contains("markPostDispatchTransient(\"HTTP_\"+status,r)"));
        assertTrue(service.contains("\"canonical_conversation\":\"other_service_resource\""));
        assertEquals(2, occurrences(service, "if(!postDispatchTransientLogKeys.add(key))return;"));
        assertEquals(2, occurrences(service, "postDispatchTransientLogKeys.clear();"));
        assertFalse(service.contains("postDispatchTransientLogKey=\\\"\\\""));
        assertFalse(service.contains("key.equals(postDispatchTransientLogKey)"));
        assertFalse(service.contains("rawUrl"));
        assertEquals(SelfRunRolloverPolicy.NO_START_WAIT,
                SelfRunRolloverPolicy.postDispatchNoStartAction(
                        started, started, expired, false, true));
        assertEquals(SelfRunRolloverPolicy.NO_START_PAUSE_TRANSIENT,
                SelfRunRolloverPolicy.postDispatchNoStartAction(
                        started, started, expired, true, false));
        assertFalse(policy.contains("sawStop"));
        assertFalse(policy.contains("turnObserverSawStop"));
        assertFalse(policy.contains("NO_START_COMPLETE"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) count++;
        return count;
    }
}
