package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 3.1 user input is durable input for the next disposable execution, never an in-chat continuation. */
public final class UserImmediateInputPolicyTest {
    @Test public void activeUiOnlySavesDurableNextExecutionInput() throws Exception {
        String activity = src("MainActivity.java");
        assertFalse(activity.contains("\"즉시 보내기\""));
        assertFalse(activity.contains("UserImmediateInputCoordinator.submit"));
        assertFalse(activity.contains("forceImmediateInput"));
        assertTrue(activity.contains("v -> saveNextInput()"));
        assertTrue(activity.contains("UserNextInputStore.save(runId, nextInputEditor.getText().toString())"));
        assertTrue(activity.contains("다음 새 대화에 반영됩니다."));
    }

    @Test public void engineConsumesRevisionedInputOnlyAtExecutionBoundary() throws Exception {
        String coordinator = src("SelfRun3Coordinator.java");
        String input = src("SelfRun3UserInput.java");
        assertTrue(coordinator.contains("SelfRun3UserInput.snapshot"));
        assertTrue(coordinator.contains("input.revision > consumed ? input.text : \"\""));
        assertTrue(coordinator.contains("SelfRun3UserInput.consumeIfRevision"));
        assertTrue(input.contains("Snapshot latest"));
        assertTrue(input.contains("lateInput"));
        assertFalse(coordinator.contains("UserImmediateInputCoordinator"));
    }

    private static String src(String file) throws Exception {
        Path path = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
