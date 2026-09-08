package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

/** Active 3.1 transport contract: every automatic execution uses the proven first-message bootstrap. */
public final class SelfRun3ComposerTransportArchitectureTest {
    @Test public void adapterAlwaysUsesFirstMessageBootstrapAndNeverContinuation() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("SelfRun3BootstrapTransport.prepare"));
        assertTrue(web.contains("SelfRun3BootstrapTransport.submit"));
        assertTrue(web.contains("SelfRun3DispatchScript.arm"));
        assertFalse(web.contains("SelfRun3ComposerTransport.prepareContinuation"));
        assertFalse(web.contains("SelfRun3ComposerTransport.submitContinuation"));
        assertFalse(web.contains("SelfRunContinuationDom.prepareBootstrap"));
        assertFalse(web.contains("message_stream_complete"));
        assertFalse(web.contains("completion_dispatch"));
    }

    @Test public void dev21BootstrapPreparedSubmitBoundaryIsPreserved() throws Exception {
        String bootstrap = source("SelfRun3BootstrapTransport.java");
        assertTrue(bootstrap.contains("state:'prepared'"));
        assertTrue(bootstrap.contains("READY_TO_SUBMIT"));
        assertTrue(bootstrap.contains("baselineUserCount"));
        assertTrue(bootstrap.contains("c.send.click()"));
        assertTrue(bootstrap.contains("requestComposerSubmit()"));
        assertTrue(bootstrap.contains("exact bootstrap readback"));
    }

    @Test public void dispatchObserverCannotBlockOrReadAssistantResponse() throws Exception {
        String dispatch = source("SelfRun3DispatchScript.java");
        assertTrue(dispatch.contains("const result=nativeFetch(input,init)"));
        assertTrue(dispatch.contains("const result=send.call(this,body)"));
        assertTrue(dispatch.contains("if(matched)emit();"));
        assertTrue(dispatch.contains("return result;"));
        assertFalse(dispatch.contains("throw new Error"));
        assertFalse(dispatch.contains("reject()"));
        assertFalse(dispatch.contains("response.clone"));
        assertFalse(dispatch.contains("captureIdentity"));
        assertFalse(dispatch.contains("message_stream_complete"));
    }

    @Test public void firstConversationRegressionUsesActualBootstrapPrepareAndSubmit() throws Exception {
        String test = androidTestSource("SelfRun31FirstConversationAndroidTest.java");
        assertTrue(test.contains("SelfRun3BootstrapTransport.prepare"));
        assertTrue(test.contains("SelfRun3BootstrapTransport.submit"));
        assertTrue(test.contains("/backend-api/f/conversation"));
        assertTrue(test.contains("fixturePosts.length"));
        assertTrue(test.contains("host.get().detachOutput()"));
        assertTrue(test.contains("assertSame(original, host.get().webView())"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String androidTestSource(String name) throws Exception {
        Path path = Paths.get("app/src/androidTest/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/androidTest/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
