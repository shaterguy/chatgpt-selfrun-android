package com.shaterguy.chatgptselfrun;

/** SelfRun 2.0 bootstrap mode bridge. No ChatGPT mode-menu interaction is performed. */
final class BootstrapModeDom {
    private BootstrapModeDom() {}

    static String inline(String requested, String runId) {
        String mode = SelfRunStore.MODE_WORK.equals(requested) ? "work" : "chat";
        return """
                const requestedMode=__REQUESTED__;
                const modeRunId=__RUN_ID__;
                const diagnostics={strategy:'request-profile',requested:requestedMode,currentMode:'request-profile',targetFound:true,targetSelected:true,targetSource:'native-request',modeAttempts:1,modeClickAttempts:0,modeElapsedMs:0};
                const modeDiag=()=>('strategy=request-profile;requested='+requestedMode+';uiClicks=0');
                if(!window.__selfRunRequestProfileEngine||window.__selfRunRequestProfileEngine.version!=='calibration-v1')return result('CHAT_BOOTSTRAP_PROFILE_ENGINE_UNAVAILABLE','Request Profile Engine document-start injection is unavailable.',diagnostics);
                try{window.__selfRunRequestProfileEngine.begin(requestedMode,modeRunId);}catch(error){return result('CHAT_BOOTSTRAP_PROFILE_ENGINE_UNAVAILABLE',String(error?.message||error),diagnostics);}
                """
                .replace("__REQUESTED__", SelfRunScript.quote(mode))
                .replace("__RUN_ID__", SelfRunScript.quote(runId));
    }
}
