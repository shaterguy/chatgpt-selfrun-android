package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public final class SelfRun3DispatchScriptTest {
    @Test public void observerIsOneShotNonBlockingAndDoesNotInspectResponses() {
        String js = SelfRun3DispatchScript.documentStartScript();
        assertTrue(js.contains("stage:'turn_request'"));
        assertTrue(js.contains("source:'canonical_post'"));
        assertTrue(js.contains("b?.conversation_id"));
        assertTrue(js.contains("return nativeFetch(input,init)"));
        assertTrue(js.contains("return send.call(this,body)"));
        assertFalse(js.contains("throw new Error"));
        assertFalse(js.contains("reject()"));
        assertFalse(js.contains("response.clone"));
        assertFalse(js.contains("captureIdentity"));
        assertFalse(js.contains("message_stream_complete"));
    }

    @Test public void adapterUsesDev21FirstMessageTransportForEveryExecution() throws Exception {
        String web = source("SelfRun3WebAdapter.java");
        assertTrue(web.contains("SelfRun3BootstrapTransport.prepare"));
        assertTrue(web.contains("SelfRun3BootstrapTransport.submit"));
        assertFalse(web.contains("SelfRunContinuationDom.prepareBootstrap"));
        assertFalse(web.contains("SelfRun3ComposerTransport.prepareContinuation"));
        assertTrue(web.contains("SelfRun3DispatchScript.arm"));
        String config = source("WebViewConfig.java");
        assertTrue(config.contains("SelfRun3DispatchScript.install"));
        assertTrue(config.contains("RequestProfileScript.installDocumentStart"));
    }

    private static String source(String name) throws Exception {
        Path p = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(p)) p = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
