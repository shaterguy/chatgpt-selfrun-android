package com.shaterguy.chatgptselfrun;

/** SelfRun 2.0 bootstrap mode bridge. No ChatGPT mode-menu interaction is performed. */
final class BootstrapModeDom {
    private BootstrapModeDom() {}

    static String inline(String requested, String runId) {
        String mode = SelfRunStore.MODE_WORK.equalsIgnoreCase(requested) ? "work" : "chat";
        return """
                const requestedMode=__REQUESTED__;
                const modeRunId=__RUN_ID__;
                let diagnostics={strategy:'request-profile',requested:requestedMode,currentMode:'request-profile',targetFound:true,targetSelected:true,targetSource:'native-request',modeAttempts:1,modeClickAttempts:0,modeElapsedMs:0};
                const modeDiag=()=>('strategy=request-profile;requested='+requestedMode+';uiClicks=0');
                const profileEngine=window.__selfRunRequestProfileEngine;
                const enginePresent=!!profileEngine;
                const engineVersionMatch=enginePresent&&(__ENGINE_AVAILABLE__);
                const profileAvailability={strategy:'request-profile',profileStage:'availability',enginePresent,engineVersionMatch};
                diagnostics={...diagnostics,...profileAvailability};
                if(!enginePresent)return result('CHAT_BOOTSTRAP_PROFILE_ENGINE_UNAVAILABLE','request profile engine absent',profileAvailability);
                if(!engineVersionMatch)return result('CHAT_BOOTSTRAP_PROFILE_ENGINE_UNAVAILABLE','request profile engine version mismatch',profileAvailability);
                try{profileEngine.begin(requestedMode,modeRunId);}catch(_){return result('CHAT_BOOTSTRAP_PROFILE_ENGINE_UNAVAILABLE','request profile initialization rejected',{...profileAvailability,profileStage:'begin',operationOk:false});}
                """
                .replace("__REQUESTED__", SelfRunScript.quote(mode))
                .replace("__RUN_ID__", SelfRunScript.quote(runId))
                .replace("__ENGINE_AVAILABLE__", RequestProfileScript.engineAvailableExpression());
    }

}
