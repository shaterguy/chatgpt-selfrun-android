package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class BootstrapFiniteStateWiringTest {
    @Test public void serviceOwnsPersistentDeadlineAndMalformedCallbackFailure() throws Exception {
        String service = src("SelfRunService.java");
        assertTrue(service.contains("BootstrapRunStateStore.touchBootstrap"));
        assertTrue(service.contains("BootstrapRunStateStore.recordBootstrapResult"));
        assertTrue(service.contains("BootstrapResultPolicy.parse(raw)"));
        assertTrue(service.contains("BootstrapResultPolicy.fatalStatus"));
        assertTrue(service.contains("scheduleBootstrapCallbackDeadline"));
        assertTrue(service.contains("BootstrapResultPolicy.TIMEOUT"));
        assertTrue(service.contains("webEvaluationId++"));
        assertFalse(service.contains("JSONObject result=parse(raw);String status=result.optString(\"status\",\"SCRIPT_ERROR\")"));
    }

    @Test public void domModeGateAndRunHistoryAreFiniteAndRunScoped() throws Exception {
        String dom = src("SelfRunDom.java");
        String history = src("SelfRunHistoryStore.java");
        String detail = src("SelfRunDetailActivity.java");
        assertTrue(dom.contains("modeTimeoutMs=20000"));
        assertTrue(dom.contains("modeMaxAttempts=18"));
        assertTrue(dom.contains("CHAT_BOOTSTRAP_MODE_CONTROL_NOT_FOUND"));
        assertTrue(dom.contains("CHAT_BOOTSTRAP_MODE_READBACK_FAILED"));
        assertTrue(dom.contains("CHAT_BOOTSTRAP_COMPOSER_NOT_FOUND"));
        assertTrue(history.contains("BootstrapRunStateStore.appendHistory"));
        assertTrue(detail.contains("BootstrapRunStateStore.summary(item)"));
        assertFalse(detail.contains("ChatReasoningPreferenceStore.summary(this"));
    }

    @Test public void developmentIdentityAdvancesOnce() throws Exception {
        String gradle = read("app/build.gradle", "build.gradle");
        assertTrue(gradle.contains("selfRunDriveVersionCode = 1000071"));
        assertTrue(gradle.contains("selfRunDriveVersionName = '1.4.2-dev7'"));
    }

    private static String src(String file) throws Exception {
        return read("app/src/main/java/com/shaterguy/chatgptselfrun/" + file,
      "src/main/java/com/shaterguy/chatgptselfrun/" + file);
    }

    private static String read(String first, String fallback) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(fallback);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
