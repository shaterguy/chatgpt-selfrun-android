package com.shaterguy.chatgptselfrun;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Regression coverage for the detached hidden-display collision mitigation. */
public final class HeadlessDisplayOutputWiringTest {
    @Test public void virtualDisplayUsesAContinuouslyDrainedImageConsumer() throws Exception {
        String host = src("HeadlessWebViewHost.java");

        assertTrue(host.contains("ImageReader.newInstance("));
        assertTrue(host.contains("reader.acquireLatestImage()"));
        assertTrue(host.contains("image.close()"));
        assertTrue(host.contains("new HandlerThread(\"SelfRunDisplayDrain\")"));
        assertFalse(host.contains("new SurfaceTexture(false)"));
    }

    @Test public void outputCanBeDetachedWithoutDestroyingTheWebViewSession() throws Exception {
        String host = src("HeadlessWebViewHost.java");
        String detach = section(host, "boolean detachOutput()", "boolean attachOutput()");
        String attach = section(host, "boolean attachOutput()", "String takeDisplayDrainFailure()");

        assertTrue(detach.contains("virtualDisplay.setSurface(null)"));
        assertTrue(attach.contains("virtualDisplay.setSurface(surface)"));
        assertTrue(detach.contains("requireMainThread()"));
        assertTrue(attach.contains("requireMainThread()"));
        assertFalse(detach.contains("virtualDisplay.release()"));
    }

    @Test public void observerWaitDetachesAndRecoversBeforeFallingBackForTheTurn() throws Exception {
        String service = src("SelfRunService.java");
        String observer = section(service,
                "if(SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)){",
                "if(\"TARGET_ERROR\".equals(status))");
        String callback = section(service,
                "private void scheduleContinuationCallbackDeadline",
                "private void recoverBootstrapSendCallback");

        assertTrue(observer.contains("detachDisplayOutput(\"observer_armed\")"));
        assertTrue(observer.contains("recoverDetachedObserverOutput(\"observer_unavailable\")"));
        assertTrue(callback.contains("recoverDetachedObserverOutput(\"callback_timeout\")"));
        assertTrue(service.contains("DETACHED_OBSERVER_MAX_RECOVERIES = 2"));
        assertTrue(service.contains("\"DETACHED_OBSERVER_FALLBACK\""));
        assertTrue(service.contains("\"DISPLAY_OUTPUT_DETACHED\""));
        assertTrue(service.contains("\"DISPLAY_OUTPUT_ATTACHED\""));
        assertTrue(service.contains("\"DISPLAY_DRAIN_FAILURE\""));
        assertFalse(observer.contains("pauseWebView()"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        assertTrue("missing section start: " + start, from >= 0);
        assertTrue("missing section end: " + end, to > from);
        return source.substring(from, to);
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) {
            path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
