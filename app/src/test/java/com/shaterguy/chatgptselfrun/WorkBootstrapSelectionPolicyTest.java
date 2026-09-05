package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class WorkBootstrapSelectionPolicyTest {
    @Test public void newWorkTaskUsesRegistryBackedBootstrapProfileAndRemembersIt() throws Exception {
        String activity = src("SelfRunNewActivity.java");
        String preferences = src("WorkBootstrapPreferenceStore.java");
        String store = src("SelfRunStore.java");

        assertTrue(activity.contains("ProfileRegistry.listWork()"));
        assertTrue(activity.contains("WorkBootstrapPreferenceStore.load(this)"));
        assertTrue(activity.contains("WorkBootstrapPreferenceStore.save("));
        assertTrue(activity.contains("store.startWork(runId, project, request, new ArrayList<>(selectedAttachments),"));
        assertFalse(activity.contains("store.setPendingWorkProfile("));
        assertFalse(activity.contains("store.setPendingModel(workProfile.signalModel)"));
        assertFalse(activity.contains("store.setPendingReasoning(workProfile.signalReasoning)"));
        assertTrue(activity.contains("STATE_WORK_BOOTSTRAP_REASONING"));
        assertTrue(activity.contains("STATE_WORK_BOOTSTRAP_MODEL"));
        assertTrue(activity.contains("STATE_WORK_BOOTSTRAP_REASONING"));
        assertFalse(activity.contains("Work 모드는 새 작업에서 수동 선택하지 않고"));

        assertTrue(preferences.contains("LEGACY_DEFAULT_MODEL = \"sol\""));
        assertTrue(preferences.contains("LEGACY_DEFAULT_REASONING = \"xhigh\""));
        assertTrue(preferences.contains("ProfileRegistry.resolveWork(model, reasoning)"));
        assertTrue(preferences.contains("putString(KEY_MODEL, selection.model)"));
        assertTrue(preferences.contains("putString(KEY_REASONING, selection.reasoning)"));

        assertTrue(store.contains("void startWork(String runId, String projectUrl, String requirement"));
        assertTrue(store.contains("SelfRunProtocol.validWorkProfile(model, reasoning)"));
        assertTrue(store.contains("startInternal(runId, MODE_WORK, projectUrl, requirement, attachments, model, reasoning)"));
        assertTrue(store.contains("putString(\"pendingModel\",safe(initialModel)).putString(\"pendingReasoning\",safe(initialReasoning))"));
    }

    @Test public void workSelectionDoesNotReplaceTurnCompletedDynamicProfileFlow() throws Exception {
        String store = src("SelfRunStore.java");
        String service = src("SelfRunService.java");
        assertTrue(store.contains("hasPendingDriveCompletion()"));
        assertTrue(store.contains("pendingDriveWorkProfile()"));
        assertTrue(service.contains("WorkPreferenceDom.modelForConversation(store.conversationUrl(),store.pendingModel())"));
        assertTrue(service.contains("WorkPreferenceDom.reasoningForConversation(store.conversationUrl(),store.pendingReasoning())"));
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
