from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one replacement, found {count}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "app/src/main/java/com/shaterguy/chatgptselfrun/WorkPreferenceDom.java",
    "                const __wpCalibratedTriggerValid=!!__wpCalibrated&&__wpMenuTrigger(__wpCalibrated)&&__wpNear(__wpCalibrated)&&(!!__wpCalibratedMeaning||__wpRowLabel(__wpCalibratedLabel));\n",
    "                const __wpCalibratedTriggerValid=!!__wpCalibrated&&__wpMenuTrigger(__wpCalibrated)&&(!!__wpCalibratedMeaning||__wpRowLabel(__wpCalibratedLabel));\n",
)
replace_once(
    "app/src/main/java/com/shaterguy/chatgptselfrun/WorkPreferenceDom.java",
    "                const __wpHeuristicTrigger=[...document.querySelectorAll('button,[role=\"button\"],[aria-haspopup],[aria-expanded]')].filter(__wpVisible).filter(__wpNear).find(e=>__wpMenuTrigger(e)&&!!__wpParse(__wpLabel(e)))||null;\n",
    "                const __wpHeuristicTriggers=[...document.querySelectorAll('button,[role=\"button\"],[aria-haspopup],[aria-expanded]')].filter(__wpVisible).filter(e=>__wpMenuTrigger(e)&&!!__wpParse(__wpLabel(e)));\n"
    "                const __wpHeuristicTrigger=__wpHeuristicTriggers.find(__wpNear)||__wpHeuristicTriggers[0]||null;\n",
)

path = ROOT / "app/src/test/java/com/shaterguy/chatgptselfrun/WebUiCalibrationPolicyTest.java"
text = path.read_text(encoding="utf-8")
pattern = re.compile(
    r"    @Test public void workPreferencePrioritizesVisibleOptionsAndRecognizesComposerSiblingTriggers\(\) \{.*?\n    \}\n\n    @Test public void calibrationActivityExposesFourIndependentWorkContexts",
    re.S,
)
replacement = '''    @Test public void workPreferenceValidatesCalibrationAndKeepsHeaderTriggerFallback() {
        String model = WorkPreferenceDom.modelForConversation("https://chatgpt.com/c/conversation123", "sol");
        String reasoning = WorkPreferenceDom.reasoningForConversation("https://chatgpt.com/c/conversation123", "xhigh");
        assertTrue(model.contains("__wpCalibratedOptionValid"));
        assertTrue(reasoning.contains("__wpCalibratedOptionValid"));
        assertTrue(model.contains("__wpCalibratedTriggerValid"));
        assertTrue(reasoning.contains("__wpCalibratedTriggerValid"));
        assertTrue(model.contains("calibratedTargetValid"));
        assertTrue(reasoning.contains("calibratedTargetValid"));
        assertFalse(model.contains("__wpMenuTrigger(__wpCalibrated)&&__wpNear(__wpCalibrated)"));
        assertFalse(reasoning.contains("__wpMenuTrigger(__wpCalibrated)&&__wpNear(__wpCalibrated)"));
        assertTrue(model.contains("__wpHeuristicTriggers.find(__wpNear)||__wpHeuristicTriggers[0]"));
        assertTrue(reasoning.contains("__wpHeuristicTriggers.find(__wpNear)||__wpHeuristicTriggers[0]"));
        assertTrue(model.contains("__wpOption=__wpSemanticOption||__wpCalibratedOption"));
        assertTrue(reasoning.contains("__wpOption=__wpSemanticOption||__wpCalibratedOption"));
        assertTrue(model.contains(WebUiCalibrationStore.PURPOSE_MODE_WORK));
        assertTrue(reasoning.contains(WebUiCalibrationStore.PURPOSE_MODE_WORK));
        assertTrue(model.contains(WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_MODEL));
        assertTrue(reasoning.contains(WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_REASONING));
        assertTrue(model.contains("open-work-mode-fallback"));
        assertTrue(reasoning.contains("open-work-mode-fallback"));
        assertTrue(model.contains("[aria-haspopup],[aria-expanded]"));
        assertTrue(reasoning.contains("[aria-haspopup],[aria-expanded]"));
        assertTrue(model.contains("menu|listbox|dialog|true"));
        assertTrue(reasoning.contains("menu|listbox|dialog|true"));
    }

    @Test public void calibrationActivityExposesFourIndependentWorkContexts'''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit(f"WebUiCalibrationPolicyTest method replacement count={count}")
path.write_text(text, encoding="utf-8")

Path(__file__).unlink()
