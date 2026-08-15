package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelfRunParentGuardPolicyTest {
    @Test public void rawOrSensitiveFailureTextNeverEscapesAllowlist() {
        assertEquals("GUARD_INTERNAL_FAILURE",
                SelfRunParentGuardPolicy.safeFailureCode("Bearer secret conversation body"));
        assertEquals("PARENT_GUARD_INTERNAL_FAILURE",
                SelfRunParentGuardPolicy.errorCode("Bearer secret conversation body"));
        assertFalse(SelfRunParentGuardPolicy.message("Bearer secret conversation body")
                .contains("Bearer"));
    }

    @Test public void canonicalHttpCodesAreBoundedAndMapped() {
        assertEquals("CANONICAL_HTTP_403", SelfRunParentGuardPolicy.safeFailureCode("CANONICAL_HTTP_403"));
        assertEquals("PARENT_GUARD_CANONICAL_LOOKUP_FAILED",
                SelfRunParentGuardPolicy.errorCode("CANONICAL_HTTP_403"));
        assertEquals("GUARD_INTERNAL_FAILURE",
                SelfRunParentGuardPolicy.safeFailureCode("CANONICAL_HTTP_999"));
    }

    @Test public void waitStagesAreAllowlisted() {
        assertEquals("COMPOSER_WAIT", SelfRunParentGuardPolicy.safeStage("COMPOSER_WAIT"));
        assertEquals("POST_INTERCEPTED", SelfRunParentGuardPolicy.safeStage("POST_INTERCEPTED"));
        assertEquals("SUBMISSION_CONFIRMED", SelfRunParentGuardPolicy.safeStage("SUBMISSION_CONFIRMED"));
        assertEquals("HANDSHAKE_WAIT", SelfRunParentGuardPolicy.safeStage("prompt=secret"));
    }

    @Test public void domFailureAndExpiryAreTerminalNotRetryWait() throws Exception {
        String dom = source("SelfRunDom.java");
        assertTrue(dom.contains("return result('PARENT_GUARD_FAILED','canonical parent guard 실패'"));
        assertTrue(dom.contains("guardCode:observed?'HANDSHAKE_TIMEOUT':'NO_POST_AFTER_CLICK'"));
        assertTrue(dom.contains("window.\" + livenessFn + \"()===true"));
        assertFalse(dom.contains("return result('UI_WAIT','canonical parent guard 재시도 준비')"));
    }

    @Test public void serviceBoundsComposerWaitAndSurfacesGuardFailure() throws Exception {
        String service = source("SelfRunService.java");
        assertTrue(service.contains("CONTINUE_UI_TIMEOUT_MS = 2 * 60_000L"));
        assertTrue(service.contains("PARENT_GUARD_FAILED"));
        assertTrue(service.contains("pauseParentGuardFailure"));
        assertTrue(service.contains("recordParentGuardStage"));
        assertTrue(service.contains("COMPOSER_TIMEOUT"));
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
