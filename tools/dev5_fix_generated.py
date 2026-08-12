from pathlib import Path

path = Path("app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunObserverPauseLifecycleTest.java")
text = path.read_text()
bad = 'assertTrue(text.contains("requestDomEvaluation(0L, "resume_observer_ready")"));'
good = 'assertTrue(text.contains("requestDomEvaluation(0L, \\"resume_observer_ready\\")"));'
if text.count(bad) != 1:
    raise SystemExit(f"expected one generated-test escape target, found {text.count(bad)}")
path.write_text(text.replace(bad, good, 1))
