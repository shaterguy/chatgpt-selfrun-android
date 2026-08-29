package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Locks portable registry transfer and split Chat profile wiring into production sources. */
public final class ProfileRegistryTransferWiringTest {
    @Test public void registryActivityExposesModeSpecificImportAndExportThroughSaf() throws Exception {
        String activity = source("ProfileRegistryActivity.java");
        assertTrue(occurrences(activity, "등록 조합 내보내기") >= 2);
        assertTrue(occurrences(activity, "등록 조합 가져오기") >= 2);
        assertTrue(activity.contains("Intent.ACTION_CREATE_DOCUMENT"));
        assertTrue(activity.contains("Intent.ACTION_OPEN_DOCUMENT"));
        assertTrue(activity.contains("CodingErrorAction.REPORT"));
        assertTrue(activity.contains("MAX_IMPORT_BYTES"));
        assertFalse(activity.contains("MANAGE_EXTERNAL_STORAGE"));
    }

    @Test public void registryCodecIsStrictBoundedAndAtomic() throws Exception {
        String registry = source("ProfileRegistry.java");
        assertTrue(registry.contains("CHAT_EXPORT_SCHEMA"));
        assertTrue(registry.contains("WORK_EXPORT_SCHEMA"));
        assertTrue(registry.contains("MAX_IMPORT_PROFILES"));
        assertTrue(registry.contains("requireOnlyKeys"));
        assertTrue(registry.contains("validateExportRequest"));
        assertTrue(registry.contains("new Profile(mode, model, reasoning, operations, false"));
        assertTrue(registry.contains("if (!persistLocked(next.userProfiles, next.tombstones))"));
    }

    @Test public void newRunSeparatesPlannerAndTaskProfilesAndEngineRoutesByLatestMessage() throws Exception {
        String activity = source("SelfRunNewActivity.java");
        String preference = source("ChatReasoningPreferenceStore.java");
        String engine = source("RequestProfileScript.java");
        assertTrue(activity.contains("작업 추론 정도 · 두 번째 턴부터"));
        assertTrue(activity.contains("부트스트랩 전용 추론 정도"));
        assertTrue(activity.contains("ChatReasoningPreferenceStore.save(this, runId, bootstrapReasoning, continuationReasoning)"));
        assertTrue(preference.contains("KEY_BOOTSTRAP_SELECTION"));
        assertTrue(preference.contains("KEY_CONTINUATION_SELECTION"));
        assertTrue(engine.contains("setChatProfiles"));
        assertTrue(engine.contains("latestMessageText"));
        assertTrue(engine.contains("SELF_RUN_BOOTSTRAP"));
        assertTrue(engine.contains("continuationReasoning"));
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int at = value.indexOf(needle); at >= 0;
             at = value.indexOf(needle, at + needle.length())) count++;
        return count;
    }
}
