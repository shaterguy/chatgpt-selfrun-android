package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public final class BootstrapSendFallbackPolicyTest {
    @Test public void bootstrapUsesBeforeInputAndVerifiedFormSubmitFallback() {
        String prepare = SelfRunContinuationDom.prepareBootstrap(
                "https://chatgpt.com/", "private bootstrap text", "marker-a");
        String click = SelfRunContinuationDom.clickPreparedBootstrap(
                "https://chatgpt.com/", "private bootstrap text", "marker-a",
                "run-a", "observer-a", 5_000L);

        assertTrue(prepare.contains("new InputEvent('beforeinput'"));
        assertTrue(prepare.contains("composer.replaceChildren(document.createTextNode(expected))"));
        assertTrue(prepare.contains("requestComposerSubmit"));
        assertTrue(prepare.contains("c.state!=='SEND_ENABLED'&&c.state!=='COMPOSER_IDLE'"));
        assertTrue(click.contains("form.requestSubmit()"));
        assertTrue(click.contains("submitPath='form_request_submit'"));
        assertTrue(click.contains("writeMarker({state:'prepared',at:Date.now()})"));
        assertTrue(click.contains("result('SEND_DISABLED','verified bootstrap text has no submit path')"));
    }

    @Test public void continuationUsesTheSameNonDuplicatingFallback() {
        String prepare = SelfRunContinuationDom.prepareDriveTurn(
                "https://chatgpt.com/c/conversation-a", "private continuation text", "marker-b");
        String click = SelfRunContinuationDom.clickPreparedDriveTurn(
                "https://chatgpt.com/c/conversation-a", "private continuation text", "marker-b",
                "run-b", "observer-b", 5_000L);

        assertTrue(prepare.contains("new InputEvent('beforeinput'"));
        assertTrue(prepare.contains("c.state!=='SEND_ENABLED'&&c.state!=='COMPOSER_IDLE'"));
        assertTrue(click.contains("form.requestSubmit()"));
        assertTrue(click.contains("submitPath='form_request_submit'"));
        assertTrue(click.contains("writeMarker({state:'clicked'"));
        assertTrue(click.contains("writeMarker({state:'prepared',at:Date.now()})"));
    }

    @Test public void stopDetectionIsScopedToTheActiveComposerAndIgnoresGlobalControls() {
        String prepare = SelfRunContinuationDom.prepareBootstrap(
                "https://chatgpt.com/", "private bootstrap text", "marker-scope");

        assertTrue(prepare.contains("const inComposer=e=>"));
        assertTrue(prepare.contains("const isStop=e=>!!e&&buttonLike(e)&&inComposer(e)&&stopSemantic(e)"));
        assertTrue(prepare.contains("const isAdjacentSend=e=>"));
        assertTrue(prepare.contains("inComposerScope(e)"));
        assertTrue(prepare.contains("const stop=controls.find(isStop)"));
    }

    @Test public void diagnosticsNeverIncludePromptMaterial() {
        String detail = SelfRunWebDiagnostics.waitDetail(
                SelfRunStore.PHASE_BOOTSTRAP_SEND, "COMPOSER_INPUTTING",
                "private bootstrap text https://chatgpt.com/");
        assertEquals("status=COMPOSER_INPUTTING;phase=bootstrap_send;reason=composer_inputting", detail);
        assertFalse(detail.contains("private bootstrap"));
        assertFalse(detail.contains("chatgpt.com"));
    }
}
