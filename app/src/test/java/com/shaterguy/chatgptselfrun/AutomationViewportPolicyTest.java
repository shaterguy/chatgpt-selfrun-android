package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class AutomationViewportPolicyTest {
    @Test public void automationContractIsExactly390By844AtMdpi() {
        assertEquals(390, AutomationViewportPolicy.WIDTH_PX);
        assertEquals(844, AutomationViewportPolicy.HEIGHT_PX);
        assertEquals(160, AutomationViewportPolicy.DENSITY_DPI);
        assertEquals(1.0d, AutomationViewportPolicy.DEVICE_PIXEL_RATIO, 0.0d);
        assertTrue(AutomationViewportPolicy.isMobileCalibrationViewport(390, 844));
        assertTrue(AutomationViewportPolicy.isMobileCalibrationViewport(412, 915));
        assertFalse(AutomationViewportPolicy.isMobileCalibrationViewport(844, 390));
        assertFalse(AutomationViewportPolicy.isMobileCalibrationViewport(1200, 800));
    }

    @Test public void legacyOrDesktopDescriptorsFailClosedForRecapture() throws Exception {
        String policy = src("AutomationViewportPolicy.java");
        String runtime = WebUiCalibrationDom.runtimePrelude();
        assertTrue(policy.contains("MOBILE_LAYOUT_FAMILY.equals(descriptor.optString(\"layoutFamily\"))"));
        assertTrue(policy.contains("return \"재보정 필요\""));
        assertTrue(policy.contains("return any ? \"모바일 보정 호환\" : \"미설정\""));
        assertTrue(runtime.contains("const __srMobileDescriptor=d=>!!d&&d.layoutFamily===__srLayoutFamily"));
        assertTrue(runtime.contains("if(!__srMobileDescriptor(d)){__srTrace(k,'RECALIBRATE'"));
        assertTrue(runtime.contains("return null"));
    }

    @Test public void recorderTagsLayoutButMatcherStillUsesStructuralDescriptors() {
        String capture = WebUiCalibrationDom.install(WebUiCalibrationStore.PURPOSE_MODE_CHAT);
        String runtime = WebUiCalibrationDom.runtimePrelude();
        assertTrue(capture.contains("layoutFamily:layoutFamily()"));
        assertTrue(capture.contains("w>=320&&w<=600&&h>w"));
        assertTrue(runtime.contains("RECALIBRATE"));
        assertTrue(runtime.contains("d.id"));
        assertTrue(runtime.contains("d.testid"));
        assertTrue(runtime.contains("d.aria"));
        assertTrue(runtime.contains("d.role"));
        assertTrue(runtime.contains("d.tag"));
        assertFalse(capture.contains("getBoundingClientRect"));
        assertFalse(capture.contains("offsetLeft"));
        assertFalse(capture.contains("offsetTop"));
    }

    @Test public void headlessHostNeverMirrorsPhysicalOrCalibrationDensity() throws Exception {
        String host = src("HeadlessWebViewHost.java");
        assertTrue(host.contains("texture.setDefaultBufferSize(AutomationViewportPolicy.WIDTH_PX"));
        assertTrue(host.contains("AutomationViewportPolicy.DENSITY_DPI"));
        assertFalse(host.contains("DisplayMetrics"));
        assertFalse(host.contains(".viewport()"));
        assertFalse(host.contains("metrics.densityDpi"));
        assertFalse(host.contains("pixelWidth()"));
        assertFalse(host.contains("pixelHeight()"));
    }

    private static String src(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
