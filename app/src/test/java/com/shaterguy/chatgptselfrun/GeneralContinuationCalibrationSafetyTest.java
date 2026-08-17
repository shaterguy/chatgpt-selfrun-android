package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class GeneralContinuationCalibrationSafetyTest {
    @Test public void calibratedComposerAndSendMustMatchRuntimeSemanticRole() {
        String runtime = WebUiCalibrationDom.runtimePrelude();

        assertTrue(runtime.contains("const __srComposerKey=k=>k==='GENERAL_COMPOSER'||k==='PROJECT_COMPOSER'"));
        assertTrue(runtime.contains("const __srSendKey=k=>k==='GENERAL_SEND'||k==='PROJECT_SEND'"));
        assertTrue(runtime.contains("e.id==='prompt-textarea'||e.matches?.('textarea,[contenteditable=\\\"true\\\"]')"));
        assertTrue(runtime.contains("const __srSendCandidate=e=>"));
        assertTrue(runtime.contains("form.contains(e)"));
        assertTrue(runtime.contains("__srCompatible(k,e)"));
    }

    @Test public void rejectedHighScoreCalibrationIsObservableAndFallsBack() {
        String runtime = WebUiCalibrationDom.runtimePrelude();
        assertTrue(runtime.contains("rawScore>=6?'REJECT':'MISS'"));
        assertTrue(runtime.contains("'score='+score+';raw='+rawScore+';kind='+kind"));

        String general = SelfRunDom.prepareDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "marker-general");
        assertTrue(general.contains(WebUiCalibrationStore.TARGET_GENERAL_COMPOSER));
        assertTrue(general.contains(WebUiCalibrationStore.TARGET_GENERAL_SEND));
        assertFalse(general.contains(WebUiCalibrationStore.TARGET_PROJECT_COMPOSER));
        assertFalse(general.contains(WebUiCalibrationStore.TARGET_PROJECT_SEND));
        assertTrue(general.contains("if(!composer){for(const s of selectors)"));
        assertTrue(general.contains("const calibrated=__srFind"));
    }

    @Test public void projectContinuationStillUsesProjectTargets() {
        String project = SelfRunDom.prepareDriveTurn(
                "https://chatgpt.com/g/g-p-test/c/conversation123", "continue", "marker-project");
        assertTrue(project.contains(WebUiCalibrationStore.TARGET_PROJECT_COMPOSER));
        assertTrue(project.contains(WebUiCalibrationStore.TARGET_PROJECT_SEND));
        assertFalse(project.contains(WebUiCalibrationStore.TARGET_GENERAL_COMPOSER));
        assertFalse(project.contains(WebUiCalibrationStore.TARGET_GENERAL_SEND));
    }
}
