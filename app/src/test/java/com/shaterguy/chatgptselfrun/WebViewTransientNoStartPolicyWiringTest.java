package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class WebViewTransientNoStartPolicyWiringTest {
    @Test public void retryableResourceErrorsAreDiagnosticAndCannotPauseNoStartFlow() throws Exception {
        String service = src("SelfRunService.java");
        String policy = src("SelfRunRolloverPolicy.java");

        assertTrue(service.contains("if(status>=400&&SelfRunRolloverPolicy.retryHttpStatus(status))"));
        assertTrue(service.contains("markPostDispatchTransient(\"HTTP_\"+status)"));
        assertTrue(policy.contains("if (sawStop || transientSeen) return NO_START_WAIT;"));
        assertFalse(policy.contains("if (transientSeen) return NO_START_PAUSE_TRANSIENT;"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
