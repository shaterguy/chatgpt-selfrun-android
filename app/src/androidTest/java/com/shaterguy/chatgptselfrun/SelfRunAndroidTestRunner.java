package com.shaterguy.chatgptselfrun;

import android.os.Bundle;
import androidx.test.runner.AndroidJUnitRunner;

/** Single owner of the branch-critical Android instrumentation profile. */
public final class SelfRunAndroidTestRunner extends AndroidJUnitRunner {
    private static final String[] V3_REQUIRED = {
            "com.shaterguy.chatgptselfrun.SelfRun3RuntimeAndroidTest",
            "com.shaterguy.chatgptselfrun.SelfRun3ComposerTransportWebViewTest",
            "com.shaterguy.chatgptselfrun.ProtocolDetachedSurfaceWebViewTest",
            "com.shaterguy.chatgptselfrun.TurnProtocolStateWebViewTest",
            "com.shaterguy.chatgptselfrun.RichComposerBootstrapWebViewTest",
            "com.shaterguy.chatgptselfrun.RequestProfileRecreationAndroidTest",
            "com.shaterguy.chatgptselfrun.WorkTurnProtocolIngressWebViewTest"
    };
    private static final String[] V2_REQUIRED = {
            "com.shaterguy.chatgptselfrun.ProtocolDetachedSurfaceWebViewTest",
            "com.shaterguy.chatgptselfrun.TurnProtocolStateWebViewTest",
            "com.shaterguy.chatgptselfrun.WorkTurnProtocolIngressWebViewTest",
            "com.shaterguy.chatgptselfrun.RichComposerBootstrapWebViewTest",
            "com.shaterguy.chatgptselfrun.ChatReasoningProcessRecreationAndroidTest",
            "com.shaterguy.chatgptselfrun.RequestProfileRecreationAndroidTest",
            "com.shaterguy.chatgptselfrun.TurnDocumentRetryAndroidTest",
            "com.shaterguy.chatgptselfrun.DriveSignalDocumentIdentityAndroidTest"
    };

    @Override public void onCreate(Bundle arguments) {
        Bundle effective = arguments == null ? new Bundle() : new Bundle(arguments);
        String[] required = BuildConfig.VERSION_NAME != null && BuildConfig.VERSION_NAME.startsWith("3.")
                ? V3_REQUIRED : V2_REQUIRED;
        for (String item : required) appendRequiredClass(effective, item);
        super.onCreate(effective);
    }

    private static void appendRequiredClass(Bundle arguments,String required) {
        String selected=arguments.getString("class","").trim();
        if(!containsClass(selected,required)){
            arguments.putString("class",selected.isEmpty()?required:selected+","+required);
        }
    }

    static boolean containsClass(String selected,String required) {
        if(selected==null||selected.isBlank())return false;
        for(String entry:selected.split(",")){
            String value=entry.trim();int method=value.indexOf('#');
            if(method>=0)value=value.substring(0,method);
            if(required.equals(value))return true;
        }
        return false;
    }
}
