from pathlib import Path
import re


def read(path):
    return Path(path).read_text(encoding="utf-8")


def write(path, text):
    Path(path).write_text(text, encoding="utf-8")


def sub_once(text, pattern, replacement, label, flags=0):
    rx = re.compile(pattern, flags)
    new, count = rx.subn(lambda m: replacement, text, count=1)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return new


engine_path = "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRun3Engine.java"
engine = read(engine_path)
engine = sub_once(
    engine,
    r"    static JSONObject parseResult\(String raw,State s\) \{.*?^    \}\n(?=    private static String routingPhase)",
    """    static JSONObject parseResult(String raw,State s) {
        if(raw==null || raw.trim().isEmpty()) return null;
        require(raw.indexOf('\\0')<0,\"result NUL\");
        JSONObject r=SelfRun3StrictJson.parseObject(raw.trim());
        return Boolean.TRUE.equals(r.opt(\"committed\")) ? r : null;
    }
""",
    "parseResult",
    re.S | re.M,
)
write(engine_path, engine)


drive_path = "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRun3DriveAdapter.java"
drive = read(drive_path)
drive = sub_once(
    drive,
    r"    ResultObservation observeResult\(String token, SelfRun3Engine\.State s\) throws Exception \{.*?^    \}\n(?=    private ResultObservation readObservation)",
    """    ResultObservation observeResult(String token, SelfRun3Engine.State s) throws Exception {
        acquireResultReadWakeLock();
        try {
            ResultObservation observation = readObservation(token, s);
            if (SelfRun3Engine.parseResult(observation.candidateBody, s) != null) return observation;
            SelfRun3Engine.State current = recordResultBodyObservation(s, observation);
            if (SelfRun3ResultWatchdog.shouldRepair(current, SystemClock.elapsedRealtime(), currentBootCountForRepair(),
                    runtimeSettings.resultRepairMs())) {
                diagnosticLog.record(projection, \"V3_RESULT_READ\", \"stage=STALE_CONFIRM;turn=\" + current.turn());
                ResultObservation finalObservation = readObservation(token, current);
                if (SelfRun3Engine.parseResult(finalObservation.candidateBody, current) != null) return finalObservation;
                recordResultBodyObservation(current, finalObservation);
                return finalObservation;
            }
            return observation;
        } finally {
            releaseResultReadWakeLock();
        }
    }
""",
    "observeResult",
    re.S | re.M,
)
drive = sub_once(
    drive,
    r"                    \} else if \(isInvalidCommittedResult\(raw, s\)\) \{\n                        diagnosticLog\.record\(projection, \"V3_RESULT_READ\", \"stage=INVALID_COMMITTED;turn=\" \+ s\.turn\(\)\);\n                        throw new InvalidCommittedResultException\(\);\n                    \} else \{",
    "                    } else {",
    "invalid committed branch",
)
drive = sub_once(
    drive,
    r"    static boolean isInvalidCommittedResult\(String raw, SelfRun3Engine\.State s\) \{.*?^    static final class InvalidCommittedResultException extends Exception \{ \}\n\n",
    "",
    "invalid committed helper",
    re.S | re.M,
)
write(drive_path, drive)


coordinator_path = "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRun3Coordinator.java"
coordinator = read(coordinator_path)
coordinator = sub_once(
    coordinator,
    r"            \} catch \(SelfRun3DriveAdapter\.InvalidCommittedResultException invalid\) \{.*?^            \} catch \(ResultPendingException pending\) \{",
    "            } catch (ResultPendingException pending) {",
    "coordinator invalid committed catch",
    re.S | re.M,
)
write(coordinator_path, coordinator)


turn_path = "app/src/test/java/com/shaterguy/chatgptselfrun/TurnDocumentRetryWiringTest.java"
turn = read(turn_path)
turn = sub_once(
    turn,
    r"    @Test public void onlyMachineIntegrityFailureOfCurrentCommittedPayloadAllowsImmediateRepairClassification\(\) \{.*?^    \}\n\n",
    """    @Test public void resultContentCannotCreateImmediateRepairClassification() throws Exception {
        String drive = src(\"SelfRun3DriveAdapter.java\");
        String coordinator = src(\"SelfRun3Coordinator.java\");
        assertFalse(drive.contains(\"isInvalidCommittedResult\"));
        assertFalse(drive.contains(\"InvalidCommittedResultException\"));
        assertFalse(drive.contains(\"stage=INVALID_COMMITTED\"));
        assertFalse(coordinator.contains(\"INVALID_COMMITTED_RESULT\"));
    }

""",
    "immediate repair test",
    re.S | re.M,
)
turn, count = re.subn(
    r"assertTrue\(coordinator\.contains\(\"InvalidCommittedResultException\"\)\);",
    'assertFalse(coordinator.contains("InvalidCommittedResultException"));',
    turn,
    count=1,
)
if count != 1:
    raise SystemExit(f"coordinator exception assertion: expected one match, found {count}")
write(turn_path, turn)


disposable_path = "app/src/test/java/com/shaterguy/chatgptselfrun/SelfRun3DisposableExecutionTest.java"
disposable = read(disposable_path)
if disposable.count("appRejectsWrongResultIdentityButNotAiCheckpointShapeDrift") != 1:
    raise SystemExit("disposable test name mismatch")
disposable = disposable.replace(
    "appRejectsWrongResultIdentityButNotAiCheckpointShapeDrift",
    "committedResultIgnoresMachineIdentityAndAiCheckpointShapeDrift",
    1,
)
old = "assertThrows(IllegalStateException.class,()->SelfRun3Engine.parseResult(wrongIdentity.toString(),a));"
if disposable.count(old) != 1:
    raise SystemExit("disposable wrong identity assertion mismatch")
disposable = disposable.replace(old, "assertNotNull(SelfRun3Engine.parseResult(wrongIdentity.toString(),a));", 1)
write(disposable_path, disposable)


unbounded_path = "app/src/test/java/com/shaterguy/chatgptselfrun/SelfRun3UnboundedPayloadPolicyTest.java"
unbounded = read(unbounded_path)
if unbounded.count("committedResultBeyondFormer512KiBGateKeepsStrictIdentityChecks") != 1:
    raise SystemExit("unbounded test name mismatch")
unbounded = unbounded.replace(
    "committedResultBeyondFormer512KiBGateKeepsStrictIdentityChecks",
    "committedResultBeyondFormer512KiBGateIgnoresFormerIdentityGate",
    1,
)
old = "assertThrows(IllegalStateException.class, () -> SelfRun3Engine.parseResult(result.toString(), state));"
if unbounded.count(old) != 1:
    raise SystemExit("unbounded wrong identity assertion mismatch")
unbounded = unbounded.replace(old, "assertNotNull(SelfRun3Engine.parseResult(result.toString(), state));", 1)
write(unbounded_path, unbounded)


gradle_path = "app/build.gradle"
gradle = read(gradle_path)
gradle = sub_once(gradle, r"def selfRunDriveVersionCode = 3025000", "def selfRunDriveVersionCode = 3025001", "version code")
gradle = sub_once(gradle, r"def selfRunDriveVersionName = '3\.2\.4'", "def selfRunDriveVersionName = '3.2.5-dev1'", "version name")
gradle = gradle.replace("strict result identity contract are required", "strict JSON result parsing are required")
write(gradle_path, gradle)


verify_path = "tools/verify_drive_variant.sh"
verify_lines = read(verify_path).splitlines(True)
verify_lines = [line for line in verify_lines if "InvalidCommittedResultException" not in line]
write(verify_path, "".join(verify_lines))
