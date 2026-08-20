package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;

/** Fixed rendering contract for the private automation WebView. */
final class AutomationViewportPolicy {
    static final int WIDTH_PX = 390;
    static final int HEIGHT_PX = 844;
    static final int DENSITY_DPI = 160;
    static final double DEVICE_PIXEL_RATIO = 1.0d;
    static final String MOBILE_LAYOUT_FAMILY = "mobile_portrait_v1";

    private AutomationViewportPolicy() {}

    static boolean isMobileCalibrationViewport(int cssWidth, int cssHeight) {
        return cssWidth >= 320 && cssWidth <= 600 && cssHeight > cssWidth;
    }

    static boolean isMobileDescriptor(JSONObject descriptor) {
        return descriptor != null
                && MOBILE_LAYOUT_FAMILY.equals(descriptor.optString("layoutFamily"));
    }

    static String purposeStatus(JSONObject... descriptors) {
        boolean any = false;
        for (JSONObject descriptor : descriptors) {
            if (descriptor == null) continue;
            any = true;
            if (!isMobileDescriptor(descriptor)) return "재보정 필요";
        }
        return any ? "모바일 보정 호환" : "미설정";
    }

    static String profileStatus(JSONObject profile) {
        if (profile == null) return "미설정";
        JSONObject targets = profile.optJSONObject("targets");
        if (targets == null || targets.length() == 0) return "미설정";
        boolean any = false;
        java.util.Iterator<String> keys = targets.keys();
        while (keys.hasNext()) {
            JSONObject descriptor = targets.optJSONObject(keys.next());
            if (descriptor == null) continue;
            any = true;
            if (!isMobileDescriptor(descriptor)) return "재보정 필요";
        }
        return any ? "모바일 보정 호환" : "미설정";
    }

    static String runtimeContract() {
        return WIDTH_PX + "×" + HEIGHT_PX + " · DPR " + DEVICE_PIXEL_RATIO
                + " · densityDpi " + DENSITY_DPI;
    }
}
