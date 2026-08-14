package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.stream.Stream;
import static org.junit.Assert.*;

public class SelfRunProtocolTest {
    private static final String RUN = "SR-20260813-220315-A1B2C3";
    private static final String DOC = "document_12345678";
    private static final String SKILL_ID = "1qPTSmJG8GpXMSyIGm6SIpgx6-LtWCBGVW3WUpoKj9fs";

    @Test public void bootstrapContainsGlobalSkillMetadataExactlyOnce() {
        String bootstrap = SelfRunProtocol.bootstrapDrive(RUN, SelfRunStore.MODE_CHAT, "work", DOC);
        assertEquals(1, occurrences(bootstrap, "SELF_RUN_SKILL_DOCUMENT_ID"));
        assertTrue(bootstrap.contains("SELF_RUN_SKILL_DOCUMENT_ID=" + SKILL_ID));
        assertTrue(bootstrap.contains("SELF_RUN_CLIENT=DRIVE_V1"));
        assertTrue(bootstrap.contains("DRIVE_TURN_DOCUMENT_ID=" + DOC));
    }

    @Test public void originalRequirementIsPreservedWithoutTrimOrSummary() {
        String requirement = "  첫 줄\n둘째 줄\n\n끝 공백  ";
        String bootstrap = SelfRunProtocol.bootstrapDrive(RUN, SelfRunStore.MODE_CHAT, requirement, DOC);
        assertTrue(bootstrap.endsWith(requirement));
    }

    @Test public void chatAndWorkUseSameGlobalSkillIdWithoutProjectSpecificMetadata() {
        String chat = SelfRunProtocol.bootstrapDrive(RUN, SelfRunStore.MODE_CHAT, "chat", DOC);
        String work = SelfRunProtocol.bootstrapDrive(RUN, SelfRunStore.MODE_WORK, "work", DOC);
        assertTrue(chat.contains("SELF_RUN_SKILL_DOCUMENT_ID=" + SKILL_ID));
        assertTrue(work.contains("SELF_RUN_SKILL_DOCUMENT_ID=" + SKILL_ID));
        for (String bootstrap : new String[]{chat, work}) {
            assertFalse(bootstrap.contains("Vibe Coding")); assertFalse(bootstrap.contains("PROJECT_ID=")); assertFalse(bootstrap.contains("PROJECT_NAME=")); assertFalse(bootstrap.contains("PROJECT_SKILL"));
        }
    }

    @Test public void kstPrefixOnlyWrapsAppDriveCommandsAndContinueContractIsUnchanged() {
        assertEquals("1970.01.01 | 09:00:00", SelfRunProtocol.kstTimestamp(new Date(0)));
        String bootstrap = SelfRunProtocol.bootstrapDrive(RUN, SelfRunStore.MODE_CHAT, "work", DOC);
        assertTrue(bootstrap.split("\\n", 2)[0].matches("^\\[\\d{4}\\.\\d{2}\\.\\d{2} \\| \\d{2}:\\d{2}:\\d{2}] \\[SELF_RUN_BOOTSTRAP 0\\.1\\.0 .*"));
        assertTrue(SelfRunProtocol.driveContinuation(RUN).matches("^\\[\\d{4}\\.\\d{2}\\.\\d{2} \\| \\d{2}:\\d{2}:\\d{2}] \\[SELF_RUN_CONTINUE " + RUN + "]$"));
        assertEquals("[SELF_RUN_CONTINUE " + RUN + "]", SelfRunProtocol.continuation(RUN));
        assertFalse(SelfRunProtocol.driveContinuation(RUN).contains("SELF_RUN_SKILL_DOCUMENT_ID"));
    }

    @Test public void canonicalSkillIdHasOneJavaSourceOfTruth() throws Exception {
        Path root = Paths.get("app/src/main/java"); if (!Files.exists(root)) root = Paths.get("src/main/java");
        long filesWithId; try (Stream<Path> stream = Files.walk(root)) { filesWithId = stream.filter(path -> path.toString().endsWith(".java")).filter(path -> read(path).contains(SKILL_ID)).count(); }
        assertEquals(1L, filesWithId); assertEquals(SKILL_ID, SelfRunProtocol.SELF_RUN_SKILL_DOCUMENT_ID);
    }

    @Test public void assistantControlSignalRemainsUntimestamped() {
        SelfRunProtocol.Signal signal = SelfRunProtocol.parseLatest("x\n[SELF_RUN_NEXT " + RUN + " ROLE=VERIFIER]", RUN, SelfRunStore.MODE_CHAT);
        assertEquals(SelfRunProtocol.Type.NEXT, signal.type); assertTrue(signal.raw.startsWith("[SELF_RUN_NEXT "));
    }

    private static int occurrences(String text, String token) { int count = 0; for (int at = 0; (at = text.indexOf(token, at)) >= 0; at += token.length()) count++; return count; }
    private static String read(Path path) { try { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); } catch (Exception error) { throw new AssertionError(error); } }
}
