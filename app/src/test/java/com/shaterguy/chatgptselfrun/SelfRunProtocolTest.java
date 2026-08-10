package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class SelfRunProtocolTest {
    @Test
    public void bootstrapCarriesSelectedModeBeforeRequirement() {
        String text = SelfRunProtocol.bootstrap("SR-1", SelfRunStore.MODE_CHAT, "do work");
        assertTrue(text.startsWith("[SELF_RUN_BOOTSTRAP 0.1.0 SR-1 MODE=CHAT]"));
        assertTrue(text.endsWith("do work"));
    }

    @Test
    public void workNextParsesDynamicProfile() {
        SelfRunProtocol.Signal signal = SelfRunProtocol.parseLatest(
                "done\n[SELF_RUN_NEXT SR-1 ROLE=VERIFIER MODEL=terra REASONING=high]",
                "SR-1", SelfRunStore.MODE_WORK);
        assertEquals(SelfRunProtocol.Type.NEXT, signal.type);
        assertEquals("VERIFIER", signal.role);
        assertEquals("terra", signal.model);
        assertEquals("high", signal.reasoning);
    }

    @Test
    public void lunaCannotGoBelowMax() {
        assertTrue(SelfRunProtocol.validWorkProfile("luna", "max"));
        assertTrue(SelfRunProtocol.validWorkProfile("luna", "ultra"));
        assertFalse(SelfRunProtocol.validWorkProfile("luna", "xhigh"));
        assertFalse(SelfRunProtocol.validWorkProfile("luna", "high"));
    }

    @Test
    public void invalidLunaSignalIsIgnored() {
        SelfRunProtocol.Signal signal = SelfRunProtocol.parseLatest(
                "[SELF_RUN_NEXT SR-1 ROLE=BUILDER MODEL=luna REASONING=xhigh]",
                "SR-1", SelfRunStore.MODE_WORK);
        assertEquals(SelfRunProtocol.Type.NONE, signal.type);
    }

    @Test
    public void chatModeIgnoresModelFieldsAndKeepsRole() {
        SelfRunProtocol.Signal signal = SelfRunProtocol.parseLatest(
                "[SELF_RUN_NEXT SR-1 ROLE=VERIFIER MODEL=sol REASONING=ultra]",
                "SR-1", SelfRunStore.MODE_CHAT);
        assertEquals(SelfRunProtocol.Type.NEXT, signal.type);
        assertEquals("VERIFIER", signal.role);
        assertEquals("", signal.model);
        assertEquals("", signal.reasoning);
    }

    @Test
    public void parserUsesLatestValidSignalForSameRun() {
        String text = "[SELF_RUN_NEXT SR-1 ROLE=BUILDER MODEL=luna REASONING=max]\n"
                + "[SELF_RUN_NEXT OTHER ROLE=PLANNER MODEL=sol REASONING=xhigh]\n"
                + "[SELF_RUN_NEXT SR-1 ROLE=VERIFIER MODEL=terra REASONING=high]";
        SelfRunProtocol.Signal signal = SelfRunProtocol.parseLatest(text, "SR-1", SelfRunStore.MODE_WORK);
        assertEquals("VERIFIER", signal.role);
        assertEquals("terra", signal.model);
    }

    @Test
    public void terminalSignalsAreRecognized() {
        assertEquals(SelfRunProtocol.Type.DONE,
                SelfRunProtocol.parseLatest("[SELF_RUN_DONE SR-1]", "SR-1", SelfRunStore.MODE_CHAT).type);
        SelfRunProtocol.Signal action = SelfRunProtocol.parseLatest(
                "[SELF_RUN_USER_ACTION_REQUIRED SR-1 LOGIN]", "SR-1", SelfRunStore.MODE_WORK);
        assertEquals(SelfRunProtocol.Type.USER_ACTION, action.type);
        assertEquals("LOGIN", action.actionId);
    }
}
