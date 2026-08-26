package com.shaterguy.chatgptselfrun;

import android.os.Bundle;

import androidx.test.runner.AndroidJUnitRunner;

/** Keeps branch-critical regressions in the existing filtered emulator invocation. */
public final class SelfRunAndroidTestRunner extends AndroidJUnitRunner {
    private static final String PROCESS_RECREATION_TEST =
            "com.shaterguy.chatgptselfrun.ChatReasoningProcessRecreationAndroidTest";
    private static final String BOOTSTRAP_STAGE_TEST =
            "com.shaterguy.chatgptselfrun.BootstrapStageAndDirectPickerAndroidTest";
    private static final String HIERARCHICAL_REASONING_TEST =
            "com.shaterguy.chatgptselfrun.ChatReasoningHierarchicalMenuAndroidTest";
    private static final String WORK_HEADER_CONTINUATION_TEST =
            "com.shaterguy.chatgptselfrun.WorkPreferenceHeaderContinuationAndroidTest";
    private static final String TURN_DOCUMENT_RETRY_TEST =
            "com.shaterguy.chatgptselfrun.TurnDocumentRetryAndroidTest";

    @Override public void onCreate(Bundle arguments) {
        Bundle effective = arguments == null ? new Bundle() : new Bundle(arguments);
        appendRequiredClass(effective, PROCESS_RECREATION_TEST);
        appendRequiredClass(effective, BOOTSTRAP_STAGE_TEST);
        appendRequiredClass(effective, HIERARCHICAL_REASONING_TEST);
        appendRequiredClass(effective, WORK_HEADER_CONTINUATION_TEST);
        appendRequiredClass(effective, TURN_DOCUMENT_RETRY_TEST);
        super.onCreate(effective);
    }

    private static void appendRequiredClass(Bundle arguments, String required) {
        String selected = arguments.getString("class", "").trim();
        if (!containsClass(selected, required)) {
            arguments.putString("class", selected.isEmpty() ? required : selected + "," + required);
        }
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
