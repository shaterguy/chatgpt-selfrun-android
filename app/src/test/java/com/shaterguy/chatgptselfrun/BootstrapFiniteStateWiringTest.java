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
        String mode = src("BootstrapModeDom.java");
        String history = src("SelfRunHistoryStore.java");
        String detail = src("SelfRunDetailActivity.java");
        String list = src("SelfRunHistoryActivity.java");
        String application = src("SelfRunApplication.java");
        String manifest = read("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml");
        assertTrue(dom.contains("BootstrapModeDom.inline(requested, runId)"));
        assertTrue(mode.contains("modeTimeoutMs=20000"));
        assertTrue(mode.contains("modeMaxAttempts=18"));
        assertTrue(mode.contains("CHAT_BOOTSTRAP_MODE_CONTROL_NOT_FOUND"));
        assertTrue(mode.contains("CHAT_BOOTSTRAP_MODE_READBACK_FAILED"));
        assertTrue(mode.contains("CHAT_BOOTSTRAP_COMPOSER_NOT_FOUND"));
        assertTrue(dom.contains("newChatRetryMs=1800"));
        assertTrue(dom.contains("newChatFailureMs=10000"));
        assertTrue(history.contains("BootstrapRunStateStore.appendHistory"));
        assertTrue(detail.contains("BootstrapRunStateStore.summary(item)"));
        assertTrue(list.contains("BootstrapRunStateStore.summary(item)"));
        assertFalse(list.contains("모델 변경 없음"));
        assertFalse(detail.contains("ChatReasoningPreferenceStore.summary(this"));
        assertTrue(application.contains("ChatReasoningPreferenceStore.initialize(context)"));
        assertTrue(application.contains("UserNextInputStore.initialize(context)"));
        assertTrue(manifest.contains("android:name=\".SelfRunApplication\""));
    }

    @Test public void developmentIdentityAdvancesOnce() throws Exception {
        String gradle = read("app/build.gradle", "build.gradle");
        assertTrue(gradle.contains("selfRunDriveVersionCode = 1000105"));
        assertTrue(gradle.contains("selfRunDriveVersionName = '1.8.1-dev1'"));
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
