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
    private static final String REMOVED_BOOTSTRAP_SENTENCE = "SelfRun의 역할 전환, HANDOFF, continuation, SelfRun 제어신호, Drive 실행턴 signal, pause/resume의 AI 측 의미, SelfRun 완료 판정은 위 canonical SelfRun 운영문서를 따른다.";

    @Test public void bootstrapContainsGlobalSkillMetadataExactlyOnce() {
        String bootstrap = SelfRunProtocol.bootstrapDrive(RUN, SelfRunStore.MODE_CHAT, "work", DOC);
        assertEquals(1, occurrences(bootstrap, "SELF_RUN_SKILL_DOCUMENT_ID"));
        assertTrue(bootstrap.contains("SELF_RUN_SKILL_DOCUMENT_ID=" + SKILL_ID));
        assertTrue(bootstrap.contains("SELF_RUN_CLIENT=DRIVE_V1"));
        assertTrue(bootstrap.contains("DRIVE_TURN_DOCUMENT_ID=" + DOC));
        assertFalse(bootstrap.contains("앱은 현재 대화가 Project인지 직접 판정하지 않는다."));
        assertFalse(bootstrap.contains("위 메타데이터 및 설명 뒤에 사용자가 앱에 입력한 원본 요구사항을 내용 손실이나 요약 없이 그대로 붙인다."));
        assertFalse(bootstrap.contains(REMOVED_BOOTSTRAP_SENTENCE));
        assertTrue(bootstrap.contains("\n\n[요구사항]\nwork"));
    }

    @Test public void originalRequirementIsPreservedWithoutTrimOrSummary() {
        String requirement = "  첫 줄\n둘째 줄\n\n끝 공백  ";
        String bootstrap = SelfRunProtocol.bootstrapDrive(RUN, SelfRunStore.MODE_CHAT, requirement, DOC);
        assertTrue(bootstrap.endsWith("[요구사항]\n" + requirement));
        assertTrue(bootstrap.endsWith(requirement));
    }

    @Test public void chatAndWorkUseSameGlobalSkillIdWithoutProjectSpecificMetadata() {
        String chat = SelfRunProtocol.bootstrapDrive(RUN, SelfRunStore.MODE_CHAT, "chat", DOC);
        String work = SelfRunProtocol.bootstrapDrive(RUN, SelfRunStore.MODE_WORK, "work", DOC);
        assertTrue(chat.contains("SELF_RUN_SKILL_DOCUMENT_ID=" + SKILL_ID));
        assertTrue(work.contains("SELF_RUN_SKILL_DOCUMENT_ID=" + SKILL_ID));
        for (String bootstrap : new String[]{chat, work}) {
            assertFalse(bootstrap.contains("Vibe Coding")); assertFalse(bootstrap.contains("PROJECT_ID=")); assertFalse(bootstrap.contains("PROJECT_NAME=")); assertFalse(bootstrap.contains("PROJECT_SKILL"));
            assertFalse(bootstrap.contains(REMOVED_BOOTSTRAP_SENTENCE));
            assertTrue(bootstrap.contains("[요구사항]\n"));
        }
    }

    @Test public void driveContinueAddsCommandReceivedReminderWithoutChangingBareControlSignal() {
        assertEquals("1970.01.01 | 09:00:00", SelfRunProtocol.kstTimestamp(new Date(0)));
        String bootstrap = SelfRunProtocol.bootstrapDrive(RUN, SelfRunStore.MODE_CHAT, "work", DOC);
        assertTrue(bootstrap.split("\\n", 2)[0].matches("^\\[\\d{4}\\.\\d{2}\\.\\d{2} \\| \\d{2}:\\d{2}:\\d{2}] \\[SELF_RUN_BOOTSTRAP 0\\.1\\.0 .*"));
        String driveContinue = SelfRunProtocol.driveContinuation(RUN);
        assertTrue(driveContinue.matches("^\\[\\d{4}\\.\\d{2}\\.\\d{2} \\| \\d{2}:\\d{2}:\\d{2}] \\[SELF_RUN_CONTINUE " + RUN + "]\\nCommand Recevied Record Required$"));
        assertEquals("[SELF_RUN_CONTINUE " + RUN + "]", SelfRunProtocol.continuation(RUN));
        assertFalse(driveContinue.contains("SELF_RUN_SKILL_DOCUMENT_ID"));
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
