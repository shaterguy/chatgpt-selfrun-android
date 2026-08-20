package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import static org.junit.Assert.*;

public class SelfRunDriveAssistantIsolationTest {
    @Test public void productionJavaContainsNoAssistantControlObservationLegacy() throws Exception {
        Path root = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun");
        if (!Files.exists(root)) root = Paths.get("src/main/java/com/shaterguy/chatgptselfrun");
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                all.append(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).append('\n');
            }
        }
        String source = all.toString();
        for (String banned : new String[]{
                "readLatestSelfRunControl", "observeAssistant", "assistantSnapshot", "assistantBaselineKey",
                "ASSISTANT_BASELINE_WAIT", "PHASE_READ_NEXT_CONTROL", "CONTROL_FOUND", "CONTROL_MISSING",
                "SELF_RUN_NEXT", "conversation 제어신호", "data-message-author-role=\\\"assistant\\\"",
                "article[data-turn=\\\"assistant\\\"]", "Command Recevied Record Required"}) {
            assertFalse("banned assistant/control legacy remains: " + banned, source.contains(banned));
        }
    }

    @Test public void assistantRoleIsNotRuntimeStateOrDisplay() throws Exception {
        String store = src("SelfRunStore.java");
        String main = src("MainActivity.java");
        String history = src("SelfRunHistoryStore.java");
        String log = src("SelfRunRunLog.java");
        String detail = src("SelfRunDetailActivity.java");
        assertFalse(store.contains("String role()"));
        assertFalse(store.contains("setRole("));
        assertFalse(store.contains("putString(\"role\""));
        assertFalse(main.contains("store.role()"));
        assertFalse(main.contains("현재/다음 역할"));
        assertFalse(history.contains("item.put(\"role\""));
        assertFalse(log.contains("store.role()"));
        assertFalse(log.contains("item.put(\"role\""));
        assertFalse(detail.contains("optString(\"role\""));
    }

    @Test public void completedDriveTurnRoutesDirectlyToUiExecutionAfterSendReadiness() throws Exception {
        String service = src("SelfRunService.java");
        String handler = between(service, "private void handleWebResult", "private String driveBootstrap");
        assertTrue(handler.contains("PHASE_WAIT_INTERNAL_SEND.equals(phase)"));
        assertTrue(handler.contains("SelfRunContinuationDom.SEND_ENABLED.equals(status)"));
        assertTrue(handler.contains("MODE_WORK"));
        assertTrue(handler.contains("PHASE_APPLY_PREFS"));
        assertTrue(handler.contains("PHASE_SEND_CONTINUE"));
        assertFalse(handler.contains("READ_NEXT_CONTROL"));
        assertFalse(handler.contains("SELF_RUN_NEXT"));
    }

    @Test public void workPreferencesStillComeFromPendingDriveCompletion() throws Exception {
        String store = src("SelfRunStore.java");
        assertTrue(store.contains("pendingDriveWorkProfile()"));
        assertTrue(store.contains("DriveSignalParser.workProfile(pendingDriveSignalRaw())"));
        assertTrue(store.contains("return p.valid?p.model"));
        assertTrue(store.contains("return p.valid?p.reasoning"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int a = source.indexOf(start), b = source.indexOf(end, Math.max(0, a));
        assertTrue(a >= 0 && b > a);
        return source.substring(a, b);
    }
}
