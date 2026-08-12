from pathlib import Path


def replace_once(path, old, new):
    path = Path(path)
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1))


# Fix the generated lifecycle test string escaping.
replace_once(
    "app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunObserverPauseLifecycleTest.java",
    'assertTrue(text.contains("requestDomEvaluation(0L, "resume_observer_ready")"));',
    'assertTrue(text.contains("requestDomEvaluation(0L, \\"resume_observer_ready\\")"));',
)

# A preserved resume must not reacquire the WakeLock while observer readiness is gated.
service = "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java"
replace_once(
    service,
    '''    private void updateWakeLockForState(String reason) {
        if (wakeLockController == null) return;
        WakeLockController.State desired = wakeLockStateFor(store.active(), store.paused(), store.userStopped(),
                store.phase(), isRateLimited(), recoveryInProgress);
        setWakeLockState(desired, reason);
    }
''',
    '''    private void updateWakeLockForState(String reason) {
        if (wakeLockController == null) return;
        if (resumeObserverGate) {
            setWakeLockState(WakeLockController.State.PAUSED, reason + "_resume_gate");
            return;
        }
        WakeLockController.State desired = wakeLockStateFor(store.active(), store.paused(), store.userStopped(),
                store.phase(), isRateLimited(), recoveryInProgress);
        setWakeLockState(desired, reason);
    }
''',
)
replace_once(
    service,
    '''        resumeObserverGate = false;
        runLog.record(store, "RESUME_OBSERVER_READY",
                "source=" + source + ";generation=" + activeGeneration + ";epoch=" + activeEpoch);
        requestDomEvaluation(0L, "resume_observer_ready");
''',
    '''        resumeObserverGate = false;
        runLog.record(store, "RESUME_OBSERVER_READY",
                "source=" + source + ";generation=" + activeGeneration + ";epoch=" + activeEpoch);
        updateWakeLockForState("resume_observer_ready");
        requestDomEvaluation(0L, "resume_observer_ready");
''',
)

# Update close tests to the strengthened execution-epoch and WakeLock semantics.
battery = "app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunBatteryEfficiencyTest.java"
replace_once(
    battery,
    '        assertTrue(text.contains("active != webView || activeGeneration != generation || activeEpoch != observerEpoch"));',
    '        assertTrue(text.contains("active != webView || activeGeneration != generation || activeExecutionEpoch != executionEpoch"));\n'
    '        assertTrue(text.contains("activeEpoch != observerEpoch"));',
)
replace_once(
    battery,
    "    public void preservedPauseStopsObserverAndWatchdogAndResumeAcquiresBeforeReattach() throws Exception {",
    "    public void preservedPauseStopsObserverAndWatchdogAndResumeWaitsForReadyBeforeAcquire() throws Exception {",
)
replace_once(
    battery,
    '''        int wakePrepare = resumeBody.indexOf("updateWakeLockForState(\\"resume_prepare\\")");
        int preservedBlock = resumeBody.indexOf("if (preserved)");
        int observerAttach = resumeBody.indexOf("ensureDomObserver();", preservedBlock);
        assertTrue(wakePrepare >= 0);
        assertTrue(preservedBlock > wakePrepare);
        assertTrue(observerAttach > wakePrepare);
''',
    '''        int wakePrepare = resumeBody.indexOf("updateWakeLockForState(\\"resume_prepare\\")");
        int preservedBlock = resumeBody.indexOf("if (preserved)");
        int observerAttach = resumeBody.indexOf("ensureDomObserver();", preservedBlock);
        assertTrue(resumeBody.contains("resumeObserverGate = preserved && !rateLimited;"));
        assertTrue(wakePrepare >= 0);
        assertTrue(preservedBlock > wakePrepare);
        assertTrue(observerAttach > wakePrepare);

        int updateWake = text.indexOf("private void updateWakeLockForState");
        int setWake = text.indexOf("private void setWakeLockState", updateWake);
        String wakeBody = text.substring(updateWake, setWake);
        assertTrue(wakeBody.contains("if (resumeObserverGate)"));
        assertTrue(wakeBody.contains("WakeLockController.State.PAUSED"));
        assertTrue(wakeBody.contains("reason + \\"_resume_gate\\""));

        int openGate = text.indexOf("private boolean openResumeObserverGate");
        int origin = text.indexOf("private static Uri chatGptOrigin", openGate);
        String gateBody = text.substring(openGate, origin);
        assertTrue(gateBody.contains("resumeObserverGate = false;"));
        assertTrue(gateBody.contains("updateWakeLockForState(\\"resume_observer_ready\\")"));
        assertTrue(gateBody.indexOf("updateWakeLockForState") < gateBody.indexOf("requestDomEvaluation"));
''',
)

# Strengthen the focused lifecycle test too.
focused = "app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunObserverPauseLifecycleTest.java"
replace_once(
    focused,
    '        assertTrue(text.contains("requestDomEvaluation(0L, \\"resume_observer_ready\\")"));',
    '        assertTrue(text.contains("updateWakeLockForState(\\"resume_observer_ready\\")"));\n'
    '        assertTrue(text.contains("requestDomEvaluation(0L, \\"resume_observer_ready\\")"));\n'
    '        assertTrue(text.contains("if (resumeObserverGate)"));\n'
    '        assertTrue(text.contains("WakeLockController.State.PAUSED"));',
)
