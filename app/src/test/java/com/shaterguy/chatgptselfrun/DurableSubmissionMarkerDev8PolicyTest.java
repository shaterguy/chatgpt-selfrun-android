package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class DurableSubmissionMarkerDev8PolicyTest {
    @Test public void finalClickStillUsesDurableMarkerAndClickedGuard() {
        String prepare = ContinuationGuardDom.prepareDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "marker", "tok", "h", "c", "s");
        String click = ContinuationGuardDom.clickPreparedDriveTurn(
                "https://chatgpt.com/c/conversation123", "continue", "marker", "tok", "h", "c", "s");
        assertTrue(prepare.contains("localStorage.setItem"));
        assertTrue(prepare.contains("sessionStorage.setItem"));
        assertTrue(click.contains("prepared.clicked"));
        assertTrue(click.contains("state='clicked'"));
    }
}
