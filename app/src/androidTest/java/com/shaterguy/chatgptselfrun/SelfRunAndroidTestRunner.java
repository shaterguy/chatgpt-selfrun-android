package com.shaterguy.chatgptselfrun;

import android.os.Bundle;

import androidx.test.runner.AndroidJUnitRunner;

/** Keeps branch-critical regressions in the existing filtered emulator invocation. */
public final class SelfRunAndroidTestRunner extends AndroidJUnitRunner {
    private static final String PROCESS_RECREATION_TEST =
            "com.shaterguy.chatgptselfrun.ChatReasoningProcessRecreationAndroidTest";

    @Override public void onCreate(Bundle arguments) {
        Bundle effective = arguments == null ? new Bundle() : new Bundle(arguments);
        String selected = effective.getString("class", "").trim();
        if (!containsClass(selected, PROCESS_RECREATION_TEST)) {
            effective.putString("class", selected.isEmpty()
                    ? PROCESS_RECREATION_TEST : selected + "," + PROCESS_RECREATION_TEST);
        }
        super.onCreate(effective);
    }

    static boolean containsClass(String selected, String required) {
        if (selected == null || selected.isBlank()) return false;
        for (String entry : selected.split(",")) {
            String value = entry.trim();
            int methodSeparator = value.indexOf('#');
            if (methodSeparator >= 0) value = value.substring(0, methodSeparator);
            if (required.equals(value)) return true;
        }
        return false;
    }
}
