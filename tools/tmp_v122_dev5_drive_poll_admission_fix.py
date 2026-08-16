from pathlib import Path

root=Path('.')
service=root/'app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java'
text=service.read_text(encoding='utf-8')
old='private void pollDrive(){if((SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(store.phase())||SelfRunStore.PHASE_RESUME_BASELINE.equals(store.phase()))&&canRun())authorizeAndRunDrive();}'
new='private void pollDrive(){boolean eligible=SelfRunStore.PHASE_WAIT_DRIVE_COMMIT.equals(store.phase())||SelfRunStore.PHASE_RESUME_BASELINE.equals(store.phase());long delay=DrivePollAdmission.delayMs(eligible,canRun(),driveInFlight,authorizationInFlight);if(delay<0L)return;if(delay>0L){handler.removeCallbacks(driveRunnable);handler.postDelayed(driveRunnable,delay);return;}authorizeAndRunDrive();}'
if old not in text:
    raise SystemExit('pollDrive source shape changed')
text=text.replace(old,new,1)
service.write_text(text,encoding='utf-8')

helper=root/'app/src/main/java/com/shaterguy/chatgptselfrun/DrivePollAdmission.java'
helper.write_text('''package com.shaterguy.chatgptselfrun;\n\n/** Pure admission policy for WAIT/RESUME Drive polling. */\nfinal class DrivePollAdmission {\n    static final long BUSY_RETRY_MS = 250L;\n    private DrivePollAdmission() {}\n\n    static long delayMs(boolean eligiblePhase, boolean canRun,\n                        boolean driveInFlight, boolean authorizationInFlight) {\n        if (!eligiblePhase || !canRun) return -1L;\n        return driveInFlight || authorizationInFlight ? BUSY_RETRY_MS : 0L;\n    }\n}\n''',encoding='utf-8')

test=root/'app/src/test/java/com/shaterguy/chatgptselfrun/DrivePollAdmissionTest.java'
test.write_text('''package com.shaterguy.chatgptselfrun;\n\nimport org.junit.Test;\nimport static org.junit.Assert.*;\n\npublic class DrivePollAdmissionTest {\n    @Test public void readyPollIsAdmittedImmediately() {\n        assertEquals(0L, DrivePollAdmission.delayMs(true, true, false, false));\n    }\n\n    @Test public void driveInFlightPollIsRequeuedInsteadOfDropped() {\n        assertEquals(DrivePollAdmission.BUSY_RETRY_MS,\n                DrivePollAdmission.delayMs(true, true, true, false));\n    }\n\n    @Test public void authorizationInFlightPollIsRequeuedInsteadOfDropped() {\n        assertEquals(DrivePollAdmission.BUSY_RETRY_MS,\n                DrivePollAdmission.delayMs(true, true, false, true));\n    }\n\n    @Test public void bothBusyConditionsStillUseSingleBoundedRetry() {\n        assertEquals(DrivePollAdmission.BUSY_RETRY_MS,\n                DrivePollAdmission.delayMs(true, true, true, true));\n    }\n\n    @Test public void inactiveOrIneligiblePollingDoesNotRequeue() {\n        assertEquals(-1L, DrivePollAdmission.delayMs(false, true, true, false));\n        assertEquals(-1L, DrivePollAdmission.delayMs(true, false, true, false));\n    }\n}\n''',encoding='utf-8')

guard=root/'app/src/test/java/com/shaterguy/chatgptselfrun/GuardRecoveryLivenessTest.java'
g=guard.read_text(encoding='utf-8')
marker='    private static String src(String f) throws Exception {'
extra='''    @Test public void busyPollAdmissionRequeuesWithoutReleasingInflightWakeLock() throws Exception {\n        String service = src("SelfRunService.java");\n        String poll = between(service, "private void pollDrive(){", "private void pollDriveNow");\n        assertTrue(poll.contains("DrivePollAdmission.delayMs(eligible,canRun(),driveInFlight,authorizationInFlight)"));\n        assertTrue(poll.contains("if(delay>0L){handler.removeCallbacks(driveRunnable);handler.postDelayed(driveRunnable,delay);return;}"));\n        assertFalse(poll.contains("scheduleDrivePoll"));\n        assertTrue(poll.indexOf("handler.postDelayed(driveRunnable,delay)") < poll.indexOf("authorizeAndRunDrive()"));\n    }\n\n'''
if extra.strip() not in g:
    if marker not in g:
        raise SystemExit('GuardRecoveryLivenessTest marker missing')
    g=g.replace(marker,extra+marker,1)
    guard.write_text(g,encoding='utf-8')

doc=root/'docs/SELF_RUN_DRIVE_RUNTIME.md'
d=doc.read_text(encoding='utf-8')
line='- WAIT/RESUME Drive poll admission is lossless: if a scheduled poll collides with an in-flight Drive request or authorization request, it requeues itself after 250 ms instead of dropping the poll; this busy requeue does not release the active Drive WakeLock.\n'
if line not in d:
    anchor='- Guard recovery that moves back to `WAIT_DRIVE_COMMIT` immediately schedules a Drive poll; it never leaves a one-shot rebaseline or previous-signal revalidation waiting on an inactive guard callback.\n'
    if anchor not in d:
        raise SystemExit('runtime doc anchor missing')
    d=d.replace(anchor,anchor+line,1)
    doc.write_text(d,encoding='utf-8')

print('busy poll admission patch applied')
