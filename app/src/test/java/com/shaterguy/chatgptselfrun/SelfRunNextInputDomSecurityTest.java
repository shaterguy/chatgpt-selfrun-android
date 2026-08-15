package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class SelfRunNextInputDomSecurityTest {
    @Test public void javascriptLookingNextInputRemainsComposerText() {
        String input = "');alert(1);//\n\\\" ] = 😎";
        String prompt = SelfRunProtocol.driveContinuation("SR-TEST-123", input);
        String script = SelfRunDom.prepareDriveTurn(
                "https://chatgpt.com/g/g-p-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/project/c/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                prompt,
                "marker");
        assertTrue(prompt.endsWith(input));
        assertTrue(script.contains("const expected=String("));
        assertTrue(script.contains("\\n"));
        assertFalse(script.contains("const expected=String(\"" + input + "\""));
        assertTrue(SelfRunScript.quote(input).contains("\\\""));
    }
}
