package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class GeneralContinuationCalibrationSafetyTest {
    @Test public void calibratedComposerAndSendMustMatchRuntimeSemanticRole() {
        String runtime = WebUiCalibrationDom.runtimePrelude();

        assertTrue(runtime.contains("const __srComposerKey=k=>k==='GENERAL_COMPOSER'||k==='PROJECT_COMPOSER'"));
        assertTrue(runtime.contains("const __srSendKey=k=>k==='GENERAL_SEND'||k==='PROJECT_SEND'"));
        assertTrue(runtime.contains("const __srComposerCandidate=e=>"));
        assertTrue(runtime.contains("textarea,[contenteditable="));
        assertTrue(runtime.contains("const __srSendCandidate=e=>"));
        assertTrue(runtime.contains("form.contains(e)"));
        assertTrue(runtime.contains("__srCompatible(k,e)"));
    }

    @Test public void rejectedHighScoreCalibrationIsObservableAndFallsBack() {
        String runtime = WebUiCalibrationDom.runtimePrelude();
        assertTrue(runtime.contains("rawScore>=6?'REJECT':'MISS'"));
        assertTrue(runtime.contains("rawScore"));
        assertTrue(runtime.contains("kind=__srComposerKey(k)?'composer'"));

        String general = SelfRunDom.prepareDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "marker-general");
        assertTrue(general.contains("__srFind(\"GENERAL_COMPOSER\")"));
        assertTrue(general.contains("__srFind(\"GENERAL_SEND\")"));
        assertTrue(general.contains("if(!composer){for(const s of selectors)"));
        assertTrue(general.contains("const calibrated=__srFind"));
    }

    @Test public void projectContinuationStillUsesProjectTargets() {
        String project = SelfRunDom.prepareDriveTurn(
                "https://chatgpt.com/g/g-p-test/c/conversation123", "continue", "marker-project");
        assertTrue(project.contains("__srFind(\"PROJECT_COMPOSER\")"));
        assertTrue(project.contains("__srFind(\"PROJECT_SEND\")"));
    }
}
