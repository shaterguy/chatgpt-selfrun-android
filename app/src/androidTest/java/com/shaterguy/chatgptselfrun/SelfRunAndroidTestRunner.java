package com.shaterguy.chatgptselfrun;

import android.os.Bundle;

import androidx.test.runner.AndroidJUnitRunner;

/** Keeps branch-critical regressions in the existing filtered emulator invocation. */
public final class SelfRunAndroidTestRunner extends AndroidJUnitRunner {
    private static final String PROCESS_RECREATION_TEST =
            "com.shaterguy.chatgptselfrun.ChatReasoningProcessRecreationAndroidTest";
    private static final String BOOTSTRAP_RECONNECT_TEST =
            "com.shaterguy.chatgptselfrun.BootstrapCanonicalReconnectAndroidTest";
    private static final String TURN_DOCUMENT_RETRY_TEST =
            "com.shaterguy.chatgptselfrun.TurnDocumentRetryAndroidTest";
    private static final String REQUEST_PROFILE_RECREATION_TEST =
            "com.shaterguy.chatgptselfrun.RequestProfileRecreationAndroidTest";
    private static final String PROFILE_REGISTRY_CAPTURE_TEST =
            "com.shaterguy.chatgptselfrun.ProfileRegistryCaptureAndroidTest";
    private static final String PROFILE_REGISTRY_PERSISTENCE_TEST =
            "com.shaterguy.chatgptselfrun.ProfileRegistryPersistenceAndroidTest";
    private static final String IMMEDIATE_INPUT_DOM_TEST =
            "com.shaterguy.chatgptselfrun.UserImmediateInputDomWebViewTest";
    private static final String SUBMISSION_WEBVIEW_TEST =
            "com.shaterguy.chatgptselfrun.WorkPreferenceDomWebViewTest";
    private static final String RICH_COMPOSER_BOOTSTRAP_TEST =
            "com.shaterguy.chatgptselfrun.RichComposerBootstrapWebViewTest";
    private static final String TURN_PROTOCOL_STATE_TEST =
            "com.shaterguy.chatgptselfrun.TurnProtocolStateWebViewTest";
    private static final String WORK_TURN_PROTOCOL_INGRESS_TEST =
            "com.shaterguy.chatgptselfrun.WorkTurnProtocolIngressWebViewTest";
    private static final String PRO_EARLY_COMPLETE_TEST =
            "com.shaterguy.chatgptselfrun.ProEarlyCompleteFallbackWebViewTest";
    private static final String DOM_COMPLETION_FALLBACK_TEST =
            "com.shaterguy.chatgptselfrun.TurnCompletionDomFallbackWebViewTest";
    private static final String DRIVE_SIGNAL_IDENTITY_TEST =
            "com.shaterguy.chatgptselfrun.DriveSignalDocumentIdentityAndroidTest";
    private static final String SUBMISSION_WEBVIEW_METHODS = String.join(",",
            SUBMISSION_WEBVIEW_TEST + "#continuationClassifierIgnoresAStopOutsideTheComposerForm",
            SUBMISSION_WEBVIEW_TEST + "#continuationClassifierStillBlocksAStopInsideTheComposerForm",
            SUBMISSION_WEBVIEW_TEST + "#continuationClassifierSeparatesSendDisabledAndEditableIdle",
            SUBMISSION_WEBVIEW_TEST + "#voiceIdleComposerBecomesSendAfterInputWithoutClickingVoice",
            SUBMISSION_WEBVIEW_TEST + "#bootstrapVoiceIdleComposerAlsoClicksOnlySend");

    @Override public void onCreate(Bundle arguments) {
        Bundle effective = arguments == null ? new Bundle() : new Bundle(arguments);
        String selected = effective.getString("class", "").trim();
        if (SUBMISSION_WEBVIEW_TEST.equals(selected)) {
            effective.putString("class", SUBMISSION_WEBVIEW_METHODS);
            super.onCreate(effective);
            return;
        }
        if (RICH_COMPOSER_BOOTSTRAP_TEST.equals(selected)) {
            appendRequiredClass(effective, REQUEST_PROFILE_RECREATION_TEST);
            appendRequiredClass(effective, PROFILE_REGISTRY_CAPTURE_TEST);
            appendRequiredClass(effective, PROFILE_REGISTRY_PERSISTENCE_TEST);
            appendRequiredClass(effective, TURN_PROTOCOL_STATE_TEST);
            appendRequiredClass(effective, WORK_TURN_PROTOCOL_INGRESS_TEST);
            appendRequiredClass(effective, PRO_EARLY_COMPLETE_TEST);
            appendRequiredClass(effective, DOM_COMPLETION_FALLBACK_TEST);
            appendRequiredClass(effective, DRIVE_SIGNAL_IDENTITY_TEST);
            super.onCreate(effective);
            return;
        }
        appendRequiredClass(effective, PROCESS_RECREATION_TEST);
        appendRequiredClass(effective, BOOTSTRAP_RECONNECT_TEST);
        appendRequiredClass(effective, TURN_DOCUMENT_RETRY_TEST);
        appendRequiredClass(effective, REQUEST_PROFILE_RECREATION_TEST);
        appendRequiredClass(effective, PROFILE_REGISTRY_CAPTURE_TEST);
        appendRequiredClass(effective, PROFILE_REGISTRY_PERSISTENCE_TEST);
        appendRequiredClass(effective, IMMEDIATE_INPUT_DOM_TEST);
        appendRequiredClass(effective, TURN_PROTOCOL_STATE_TEST);
        appendRequiredClass(effective, WORK_TURN_PROTOCOL_INGRESS_TEST);
        appendRequiredClass(effective, PRO_EARLY_COMPLETE_TEST);
        appendRequiredClass(effective, DOM_COMPLETION_FALLBACK_TEST);
        appendRequiredClass(effective, DRIVE_SIGNAL_IDENTITY_TEST);
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
