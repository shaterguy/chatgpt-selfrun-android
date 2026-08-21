package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class SelfRunProtocolRefactorTest {
    @Test public void identifierBoundsRemainCanonical() {
        assertTrue(SelfRunProtocolRules.validRunId("SR-20260821:ABC_1.2-3"));
        assertTrue(SelfRunProtocolRules.validRunId("A".repeat(80)));
        assertFalse(SelfRunProtocolRules.validRunId("A".repeat(81)));
        assertFalse(SelfRunProtocolRules.validRunId(null));

        assertTrue(SelfRunProtocolRules.validRecoveryId("wd.1:retry"));
        assertTrue(SelfRunProtocolRules.validRecoveryId("R".repeat(128)));
        assertFalse(SelfRunProtocolRules.validRecoveryId("R".repeat(129)));
        assertFalse(SelfRunProtocolRules.validRecoveryId(null));
    }

    @Test public void workProfileMatrixRemainsCanonical() {
        assertTrue(SelfRunProtocolRules.validWorkProfile("sol", "high"));
        assertTrue(SelfRunProtocolRules.validWorkProfile("sol", "ultra"));
        assertTrue(SelfRunProtocolRules.validWorkProfile("terra", "xhigh"));
        assertTrue(SelfRunProtocolRules.validWorkProfile("terra", "max"));
        assertTrue(SelfRunProtocolRules.validWorkProfile("luna", "max"));
        assertFalse(SelfRunProtocolRules.validWorkProfile("terra", "ultra"));
        assertFalse(SelfRunProtocolRules.validWorkProfile("luna", "high"));
        assertFalse(SelfRunProtocolRules.validWorkProfile("Sol", "high"));
    }

    @Test public void turnInfoRewriteGateIsRunScopedAndOneShot() {
        SelfRunTurnInfoRewriteGate gate = new SelfRunTurnInfoRewriteGate();
        gate.request("RUN-A");
        assertFalse(gate.consume("RUN-B"));
        assertTrue(gate.consume("RUN-A"));
        assertFalse(gate.consume("RUN-A"));
    }

    @Test public void completionFieldsRejectMalformedAndDuplicateTokens() {
        DriveSignalFields.Parsed parsed = DriveSignalFields.parse("MODEL=Sol REASONING=ULTRA");
        assertTrue(parsed.valid);
        assertEquals("Sol", parsed.values.get("MODEL"));
        assertEquals("ULTRA", parsed.values.get("REASONING"));
        assertFalse(DriveSignalFields.hasUnknown(parsed.values, true));
        assertTrue(DriveSignalFields.hasUnknown(parsed.values, false));

        assertFalse(DriveSignalFields.parse("MODEL=sol MODEL=terra").valid);
        assertFalse(DriveSignalFields.parse("MODEL").valid);
    }
}
