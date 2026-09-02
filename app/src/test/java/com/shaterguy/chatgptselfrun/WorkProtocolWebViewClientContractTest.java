package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

/** Guards the existing automation WebViewClient callback surface while adding native observation. */
public final class WorkProtocolWebViewClientContractTest {
    @Test public void headlessClientIsWrappedInsteadOfDiscarded() throws Exception {
        String host = source("HeadlessWebViewHost.java");
        String wrapper = source("WorkProtocolObservingWebViewClient.java");
        assertTrue(host.contains("new WorkProtocolObservingWebViewClient(getContext(), client)"));
        assertTrue(wrapper.contains("WorkProtocolNativeObserver.observeWebViewRequest"));
        assertTrue(wrapper.contains("return delegate.shouldInterceptRequest(view, request)"));
        assertTrue(wrapper.contains("delegate.onPageStarted"));
        assertTrue(wrapper.contains("delegate.onPageFinished"));
        assertTrue(wrapper.contains("return delegate.shouldOverrideUrlLoading"));
        assertTrue(wrapper.contains("delegate.onReceivedHttpError"));
        assertTrue(wrapper.contains("delegate.onReceivedError"));
        assertTrue(wrapper.contains("delegate.onReceivedSslError"));
        assertTrue(wrapper.contains("return delegate.onRenderProcessGone"));
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
