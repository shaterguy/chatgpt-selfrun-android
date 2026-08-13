#!/usr/bin/env python3
from pathlib import Path
R=Path(__file__).resolve().parents[1]
p=R/'tools/verify_coinstall_emulator.sh'
s=p.read_text()
if s.count('DRIVE_EXPECTED_VERSION="1.0.0-dev2"')!=1: raise SystemExit('coinstall version')
p.write_text(s.replace('DRIVE_EXPECTED_VERSION="1.0.0-dev2"','DRIVE_EXPECTED_VERSION="1.0.0-dev3"',1))

p=R/'app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunDriveDev3PolicyTest.java'
s=p.read_text()
insert='''\n    @Test public void recoverableDriveFailuresNeverAutoPause() throws Exception {\n        String src = source("SelfRunService.java");\n        String part = src.substring(src.indexOf("private void handleDriveFailure"), src.indexOf("private static void verifyMetadata"));\n        assertTrue(part.contains("scheduleDriveRecovery"));\n        assertFalse(part.contains("pauseError("));\n        assertTrue(src.contains("return error instanceof IOException;"));\n    }\n\n    @Test public void transientAuthorizationAndDriveParserFailuresRetry() throws Exception {\n        String src = source("SelfRunService.java");\n        assertTrue(src.contains("scheduleAuthorizationRetry(\"DRIVE_ACCOUNT_CHECK_RETRY\""));\n        assertTrue(src.contains("scheduleAuthorizationRetry(\"DRIVE_ACCESS_TOKEN_EMPTY\""));\n        String poll = src.substring(src.indexOf("private void pollDriveNow"), src.indexOf("private void acceptCommit"));\n        assertTrue(poll.contains("DRIVE_PROTOCOL_TURN_RECHECK"));\n        assertTrue(poll.contains("DRIVE_COMMIT_RECHECK"));\n        assertFalse(poll.contains("pauseError("));\n    }\n\n    @Test public void invalidGuardReplaysCommitAndOutcomeUnknownReconciles() throws Exception {\n        String src = source("SelfRunService.java");\n        String st = source("SelfRunStore.java");\n        String client = source("DriveApiClient.java");\n        String guard = src.substring(src.indexOf("private void scheduleGuard"), src.indexOf("private void guardElapsed"));\n        assertTrue(guard.contains("resetPendingForDriveReplay"));\n        assertFalse(guard.contains("pauseError("));\n        assertTrue(st.contains("putString(\"lastSeenDriveVersion\", \"\")"));\n        assertTrue(src.contains("drive.findSingleTurnDocument"));\n        assertTrue(client.contains("Metadata findSingleTurnDocument"));\n    }\n\n    @Test public void coinstallVerifierTargetsDev3() throws Exception {\n        Path p = Paths.get("tools/verify_coinstall_emulator.sh");\n        if (!Files.exists(p)) p = Paths.get("../tools/verify_coinstall_emulator.sh");\n        String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);\n        assertTrue(text.contains("DRIVE_EXPECTED_VERSION=\\\"1.0.0-dev3\\\""));\n        assertFalse(text.contains("DRIVE_EXPECTED_VERSION=\\\"1.0.0-dev2\\\""));\n    }\n'''
if 'recoverableDriveFailuresNeverAutoPause' not in s:
 idx=s.rfind('\n}')
 if idx<0: raise SystemExit('test insertion')
 s=s[:idx]+insert+s[idx:]
p.write_text(s)
