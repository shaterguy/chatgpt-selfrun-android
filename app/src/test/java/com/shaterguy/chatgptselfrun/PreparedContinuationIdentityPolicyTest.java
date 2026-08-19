package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class PreparedContinuationIdentityPolicyTest {
    @Test public void preparedTokenIncludesProbeHeadComposerSignature() {
        String prepare = ContinuationGuardDom.prepareDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "m", "tok", "h", "c", "s");
        assertTrue(prepare.contains("head:"));
        assertTrue(prepare.contains("composerKey:"));
        assertTrue(prepare.contains("sig:"));
        String click = ContinuationGuardDom.clickPreparedDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "m", "tok", "h", "c", "s");
        assertTrue(click.contains("prepared.head!=="));
        assertTrue(click.contains("prepared.composerKey!=="));
        assertTrue(click.contains("prepared.sig!=="));
    }
}
