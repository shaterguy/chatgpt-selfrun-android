package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.Assert.*;

public class DriveCommitParserTest {
    private static final String JOB = "SR-20260813-ABC123";

    @Test public void sessionBoundIsConnectionOnlyNotACommit() {
        String body = bound(1) + "\nSTATE=ANALYZING\n";
        assertTrue(DriveCommitParser.hasSessionBound(body, JOB, 1));
        assertEquals(DriveCommitParser.Status.NONE,
                DriveCommitParser.latest(body, JOB, 1, 0, SelfRunStore.MODE_CHAT).status);
    }

    @Test public void progressDoesNotProduceContinuation() {
        assertEquals(DriveCommitParser.Status.NONE,
                DriveCommitParser.latest("STATE=IMPLEMENTING\n", JOB, 1, 0, SelfRunStore.MODE_CHAT).status);
    }

    @Test public void acceptsCompleteContinueCommit() {
        DriveCommitParser.Result result = DriveCommitParser.latest(
                commit(1, 7, "CONTINUE", "TURN_COMMITTED", "[SELF_RUN_NEXT " + JOB + " ROLE=BUILDER]"),
                JOB, 1, 0, SelfRunStore.MODE_CHAT);
        assertEquals(DriveCommitParser.Status.ACCEPTED, result.status);
        assertEquals(JOB + ":1:7", result.commit.id());
        assertEquals(SelfRunProtocol.Type.NEXT, result.commit.signal.type);
    }

    @Test public void partialCommitIsIgnored() {
        String partial = commit(1, 1, "CONTINUE", "TURN_COMMITTED",
                "[SELF_RUN_NEXT " + JOB + " ROLE=BUILDER]").replace("[/SELF_RUN_DRIVE_COMMIT_V1]", "");
        assertEquals(DriveCommitParser.Status.NONE,
                DriveCommitParser.latest(partial, JOB, 1, 0, SelfRunStore.MODE_CHAT).status);
    }

    @Test public void partialOpenBeforeCompleteCommitIsIgnored() {
        String partial = "[SELF_RUN_DRIVE_COMMIT_V1]\nPROTOCOL_VERSION=1\n";
        DriveCommitParser.Result result = DriveCommitParser.latest(partial + commit(1, 1,
                        "CONTINUE", "TURN_COMMITTED", "[SELF_RUN_NEXT " + JOB + " ROLE=BUILDER]"),
                JOB, 1, 0, SelfRunStore.MODE_CHAT);
        assertEquals(DriveCommitParser.Status.ACCEPTED, result.status);
        assertEquals(1L, result.commit.eventSeq);
    }

    @Test public void partialBoundBeforeCompleteBoundIsIgnored() {
        String partial = "[SELF_RUN_DRIVE_BOUND_V1]\nPROTOCOL_VERSION=1\n";
        assertTrue(DriveCommitParser.hasSessionBound(partial + bound(1), JOB, 1));
    }

    @Test public void repeatedUnclosedMarkersAreBoundedAndRejected() {
        String commits = repeat("[SELF_RUN_DRIVE_COMMIT_V1]\n", 129);
        assertEquals(DriveCommitParser.Status.MALFORMED,
                DriveCommitParser.latest(commits, JOB, 1, 0, SelfRunStore.MODE_CHAT).status);
        String bounds = repeat("[SELF_RUN_DRIVE_BOUND_V1]\n", 129);
        assertFalse(DriveCommitParser.hasSessionBound(bounds, JOB, 1));
    }

    @Test public void rejectsWrongClientJobTurnAndSequence() {
        String good = commit(1, 1, "DONE", "RUN_DONE", "[SELF_RUN_DONE " + JOB + "]");
        assertEquals(DriveCommitParser.Status.MALFORMED,
                DriveCommitParser.latest(good.replace("CLIENT_ID=SELFRUN_DRIVE_ANDROID", "CLIENT_ID=OTHER"),
                        JOB, 1, 0, SelfRunStore.MODE_CHAT).status);
        assertEquals(DriveCommitParser.Status.MALFORMED,
                DriveCommitParser.latest(good.replace("JOB_ID=" + JOB, "JOB_ID=OTHER"),
                        JOB, 1, 0, SelfRunStore.MODE_CHAT).status);
        assertEquals(DriveCommitParser.Status.FUTURE_TURN,
                DriveCommitParser.latest(good.replace("TURN=1", "TURN=2"),
                        JOB, 1, 0, SelfRunStore.MODE_CHAT).status);
        assertEquals(DriveCommitParser.Status.NONE,
                DriveCommitParser.latest(good, JOB, 1, 1, SelfRunStore.MODE_CHAT).status);
    }

    @Test public void rejectsUnknownDuplicateConfusableOverflowAndMultilineSignal() {
        String good = commit(1, 9, "DONE", "RUN_DONE", "[SELF_RUN_DONE " + JOB + "]");
        assertMalformed(good.replace("TURN=1", "UNKNOWN=1\nTURN=1"));
        assertMalformed(good.replace("TURN=1", "TURN=1\nTURN=1"));
        assertMalformed(good.replace("TURN=1", "T\u0423RN=1"));
        assertMalformed(good.replace("EVENT_SEQ=9", "EVENT_SEQ=99999999999999999999"));
        assertMalformed(good.replace("[SELF_RUN_DONE " + JOB + "]",
                "[SELF_RUN_DONE " + JOB + "]\nextra"));
        assertMalformed(good.replace("SIGNAL_END", "SIGNAL_END\nUNKNOWN=1"));
    }

    @Test public void multipleUnseenCommitsAreAmbiguous() {
        String body = commit(1, 1, "CONTINUE", "TURN_COMMITTED",
                "[SELF_RUN_NEXT " + JOB + " ROLE=BUILDER]")
                + commit(1, 2, "DONE", "RUN_DONE", "[SELF_RUN_DONE " + JOB + "]");
        DriveCommitParser.Result result = DriveCommitParser.latest(body, JOB, 1, 0, SelfRunStore.MODE_CHAT);
        assertEquals(DriveCommitParser.Status.MALFORMED, result.status);
        assertEquals("multiple or conflicting unseen commits", result.reason);
    }

    @Test public void byteEquivalentDuplicateCommitIsIdempotent() {
        String once = commit(1, 5, "DONE", "RUN_DONE", "[SELF_RUN_DONE " + JOB + "]");
        DriveCommitParser.Result result = DriveCommitParser.latest(once + "\n" + once,
                JOB, 1, 0, SelfRunStore.MODE_CHAT);
        assertEquals(DriveCommitParser.Status.ACCEPTED, result.status);
        assertEquals(5L, result.commit.eventSeq);
    }

    @Test public void validatesEveryTerminalMeaning() {
        assertTerminal("DONE", "RUN_DONE", "[SELF_RUN_DONE " + JOB + "]", SelfRunProtocol.Type.DONE);
        assertTerminal("PAUSE", "RUN_PAUSED", "[SELF_RUN_PAUSE " + JOB + "]", SelfRunProtocol.Type.PAUSE);
        assertTerminal("USER_ACTION_REQUIRED", "USER_ACTION_REQUIRED",
                "[SELF_RUN_USER_ACTION_REQUIRED " + JOB + " LOGIN]", SelfRunProtocol.Type.USER_ACTION);
        assertTerminal("ERROR", "RUN_ERROR", "[SELF_RUN_ERROR " + JOB + " REASON=DRIVE_WRITE]",
                SelfRunProtocol.Type.ERROR);
    }

    @Test public void commitKindAndStateMustMatchSignal() {
        assertMalformed(commit(1, 1, "DONE", "RUN_DONE",
                "[SELF_RUN_NEXT " + JOB + " ROLE=BUILDER]"));
    }

    @Test public void rejectsTimestampBeyondAllowedFutureSkew() {
        String future = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).toString();
        assertMalformed(commit(1, 1, "DONE", "RUN_DONE", "[SELF_RUN_DONE " + JOB + "]")
                .replace("2026-08-13T14:01:02+09:00", future));
    }

    private static void assertTerminal(String kind, String state, String signal, SelfRunProtocol.Type type) {
        DriveCommitParser.Result result = DriveCommitParser.latest(commit(1, 1, kind, state, signal),
                JOB, 1, 0, SelfRunStore.MODE_CHAT);
        assertEquals(DriveCommitParser.Status.ACCEPTED, result.status);
        assertEquals(type, result.commit.signal.type);
    }

    private static void assertMalformed(String value) {
        assertEquals(DriveCommitParser.Status.MALFORMED,
                DriveCommitParser.latest(value, JOB, 1, 0, SelfRunStore.MODE_CHAT).status);
    }

    private static String repeat(String value, int count) {
        StringBuilder output = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) output.append(value);
        return output.toString();
    }

    private static String bound(int turn) {
        return "[SELF_RUN_DRIVE_BOUND_V1]\n"
                + "PROTOCOL_VERSION=1\nCLIENT_ID=SELFRUN_DRIVE_ANDROID\nJOB_ID=" + JOB + "\n"
                + "TURN=" + turn + "\nSTATE=SESSION_BOUND\nBOUND_AT=2026-08-13T14:00:00+09:00\n"
                + "[/SELF_RUN_DRIVE_BOUND_V1]";
    }

    private static String commit(int turn, long seq, String kind, String state, String signal) {
        return "[SELF_RUN_DRIVE_COMMIT_V1]\n"
                + "PROTOCOL_VERSION=1\nCLIENT_ID=SELFRUN_DRIVE_ANDROID\nJOB_ID=" + JOB + "\n"
                + "TURN=" + turn + "\nEVENT_SEQ=" + seq + "\nCOMMIT_KIND=" + kind + "\nSTATE=" + state + "\n"
                + "COMMITTED_AT=2026-08-13T14:01:02+09:00\nSIGNAL_BEGIN\n" + signal + "\nSIGNAL_END\n"
                + "[/SELF_RUN_DRIVE_COMMIT_V1]";
    }
}
