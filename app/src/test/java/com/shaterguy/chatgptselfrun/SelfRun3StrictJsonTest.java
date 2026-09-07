package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public final class SelfRun3StrictJsonTest {
    @Test public void acceptsStrictObjectSyntax() {
        assertTrue(SelfRun3StrictJson.isSyntacticallyValidObject("{\"a\":1,\"b\":[true,false,null,1.25e-3],\"s\":\"x\\n\\u0041\"}"));
    }

    @Test public void rejectsAndroidJsonTokenerExtensionsAndAmbiguities() {
        assertFalse(SelfRun3StrictJson.isSyntacticallyValidObject("{'a':1}"));
        assertFalse(SelfRun3StrictJson.isSyntacticallyValidObject("{/*x*/\"a\":1}"));
        assertFalse(SelfRun3StrictJson.isSyntacticallyValidObject("{\"a\":1,}"));
        assertFalse(SelfRun3StrictJson.isSyntacticallyValidObject("{\"a\":1,\"a\":2}"));
        assertFalse(SelfRun3StrictJson.isSyntacticallyValidObject("{\"a\":01}"));
        assertFalse(SelfRun3StrictJson.isSyntacticallyValidObject("{\"a\":1} trailing"));
        assertFalse(SelfRun3StrictJson.isSyntacticallyValidObject("[1,2,3]"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateKeysAreRejectedBeforeJSONObjectConstruction() {
        SelfRun3StrictJson.parseObject("{\"x\":1,\"x\":2}");
    }
}
