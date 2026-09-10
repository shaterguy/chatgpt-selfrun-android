package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class SelfRun3UnboundedPayloadPolicyTest {
    @Test public void turnReadyAcceptsPromptBeyondFormerOneMiBGate() {
        SelfRun3Engine.State state = preparingState();
        String prompt = "가나다".repeat(150_000);
        assertTrue(prompt.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024);
        JSONObject ready = new JSONObject();
        SelfRun3Engine.put(ready, "prompt", prompt);
        SelfRun3Engine.put(ready, "inputRevision", 1L);
        SelfRun3Engine.State after = SelfRun3Engine.reduce(state,
                new SelfRun3Engine.Event("ready-large", SelfRun3Engine.Kind.TURN_READY,
                        state.taskId(), state.turnId(), ready));
        assertEquals(SelfRun3Engine.Stage.READY, after.stage());
        assertEquals(prompt.length(), after.text("prompt").length());
    }

    @Test public void committedResultBeyondFormer512KiBGateKeepsStrictIdentityChecks() {
        SelfRun3Engine.State state = preparingState();
        JSONObject result = SelfRun3Engine.emptyResult(state);
        SelfRun3Engine.put(result, "committed", true);
        SelfRun3Engine.put(result, "status", "CONTINUE");
        SelfRun3Engine.put(result, "large_payload", "결과데이터".repeat(80_000));
        String raw = result.toString();
        assertTrue(raw.getBytes(StandardCharsets.UTF_8).length > 512 * 1024);
        JSONObject parsed = SelfRun3Engine.parseResult(raw, state);
        assertNotNull(parsed);
        assertEquals(state.taskId(), parsed.optString("task_id"));

        SelfRun3Engine.put(result, "document_id", "other-result");
        assertThrows(IllegalStateException.class, () -> SelfRun3Engine.parseResult(result.toString(), state));
        assertThrows(IllegalStateException.class, () -> SelfRun3Engine.parseResult(raw + "\0", state));
    }

    @Test public void trailingBraceRecoveryWorksForLargeCommittedResult() {
        SelfRun3Engine.State state = preparingState();
        JSONObject result = SelfRun3Engine.emptyResult(state);
        SelfRun3Engine.put(result, "committed", true);
        SelfRun3Engine.put(result, "status", "CONTINUE");
        SelfRun3Engine.put(result, "large_payload", "복구".repeat(120_000));
        String raw = result.toString();
        assertTrue(raw.getBytes(StandardCharsets.UTF_8).length > 512 * 1024);
        assertEquals(raw, SelfRun3DriveAdapter.recoverSingleRedundantTrailingBrace(raw + "}", state));
    }

    @Test public void additionalInputMergeExceedsFormer64KiBWithoutTruncation() throws Exception {
        String user = "추가지시".repeat(30_000);
        assertTrue(user.getBytes(StandardCharsets.UTF_8).length > 64 * 1024);
        String merged = UserNextInputStore.mergeText("prior", user);
        String prompt = UserNextInputStore.composePrompt("original", merged);
        assertEquals("prior\n\n" + user, merged);
        assertEquals("original\nprior\n\n" + user, prompt);

        String store = source("UserNextInputStore.java");
        String immediate = source("UserImmediateInputCoordinator.java");
        String engine = source("SelfRun3Engine.java");
        assertFalse(store.contains("MAX_USER_UTF8_BYTES"));
        assertFalse(store.contains("MAX_COMBINED_UTF8_BYTES"));
        assertFalse(store.contains("withinUtf8Limit"));
        assertFalse(immediate.contains("MAX_USER_UTF8_BYTES"));
        assertFalse(engine.contains("MAX_RESULT_BYTES"));
        assertFalse(engine.contains("utf8(p.optString(\"prompt\"))"));
    }

    @Test public void DriveAndPickerContainNoFormerAttachmentOrResultCeilings() throws Exception {
        String drive = source("SelfRun3DriveAdapter.java");
        String store = source("SelfRunStore.java");
        String activity = source("SelfRunNewActivity.java");
        assertFalse(drive.contains("MAX_RESULT_BYTES"));
        assertFalse(drive.contains("MAX_ATTACHMENT_BYTES"));
        assertFalse(drive.contains("ATTACHMENT_TOO_LARGE"));
        assertFalse(store.contains("MAX_ATTACHMENTS_PER_RUN"));
        assertFalse(store.contains("MAX_ATTACHMENT_BYTES"));
        assertFalse(activity.contains("MAX_ATTACHMENTS_PER_RUN"));
        assertFalse(activity.contains("attachment too large"));
    }

    private static SelfRun3Engine.State preparingState() {
        JSONObject config = new JSONObject();
        SelfRun3Engine.put(config, "mode", "CHAT");
        SelfRun3Engine.put(config, "reasoning", "medium");
        SelfRun3Engine.State state = SelfRun3Engine.create("large-task", "large-task:turn:1", config);
        state = resource(state, "folderId", "folder");
        state = resource(state, "requirementDocumentId", "requirements");
        state = SelfRun3Engine.reduce(state, new SelfRun3Engine.Event("setup", SelfRun3Engine.Kind.SETUP_DONE,
                state.taskId(), state.turnId(), new JSONObject()));
        return resource(state, "resultDocumentId", "result-large");
    }

    private static SelfRun3Engine.State resource(SelfRun3Engine.State state, String key, String value) {
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "key", key);
        SelfRun3Engine.put(payload, "value", value);
        return SelfRun3Engine.reduce(state, new SelfRun3Engine.Event("resource:" + key,
                SelfRun3Engine.Kind.RESOURCE, state.taskId(), state.turnId(), payload));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
