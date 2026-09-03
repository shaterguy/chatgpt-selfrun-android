package com.shaterguy.chatgptselfrun;

/** Bootstrap bridge for MODE=HYBRID using the durable phase-specific request profile. */
final class HybridBootstrapDom {
    private HybridBootstrapDom() {}

    static String inline(String runId) {
        HybridRunProfileStore.Selection selection = HybridRunProfileStore.selectionForRun(runId);
        if (!selection.valid()) {
            return "return result('CHAT_BOOTSTRAP_PROFILE_ENGINE_UNAVAILABLE','HYBRID run profile missing',{strategy:'hybrid-request-profile',profileStage:'selection'});";
        }
        HybridRunProfileStore.Endpoint endpoint = selection.effectiveBootstrap();
        String mode = endpoint.isWork() ? "work" : "chat";
        String configure = RequestProfileScript.beginTarget(mode, runId)
                + (endpoint.isWork()
                ? RequestProfileScript.setWorkModel(endpoint.model)
                + RequestProfileScript.setWorkReasoning(endpoint.reasoning)
                : RequestProfileScript.setChatProfiles(endpoint.reasoning, endpoint.reasoning));
        String targetCheck = endpoint.isWork()
                ? "t&&t.mode==='work'&&t.model===" + q(endpoint.model)
                + "&&t.reasoning===" + q(endpoint.reasoning) + "&&t.ready===true"
                : "t&&t.mode==='chat'&&t.reasoning===" + q(endpoint.reasoning)
                + "&&t.ready===true";
        return "const requestedMode=" + q(mode) + ";"
                + "const modeRunId=" + q(runId) + ";"
                + "let diagnostics={strategy:'hybrid-request-profile',requested:requestedMode,hybridStage:"
                + q(selection.stage) + ",currentMode:'request-profile',targetFound:true,targetSelected:true,targetSource:'native-request',modeAttempts:1,modeClickAttempts:0,modeElapsedMs:0};"
                + "const modeDiag=()=>('strategy=hybrid-request-profile;requested='+requestedMode+';stage=' + "
                + q(selection.stage) + "+';uiClicks=0');"
                + "const profileEngine=window.__selfRunRequestProfileEngine;const enginePresent=!!profileEngine;"
                + "const engineVersionMatch=enginePresent&&(" + RequestProfileScript.engineAvailableExpression() + ");"
                + "const profileAvailability={strategy:'hybrid-request-profile',profileStage:'availability',enginePresent,engineVersionMatch};"
                + "diagnostics={...diagnostics,...profileAvailability};"
                + "if(!enginePresent)return result('CHAT_BOOTSTRAP_PROFILE_ENGINE_UNAVAILABLE','request profile engine absent',profileAvailability);"
                + "if(!engineVersionMatch)return result('CHAT_BOOTSTRAP_PROFILE_ENGINE_UNAVAILABLE','request profile engine version mismatch',profileAvailability);"
                + "try{" + configure
                + "const t=profileEngine.target();if(!(" + targetCheck + "))return result('CHAT_BOOTSTRAP_PROFILE_ENGINE_UNAVAILABLE','HYBRID bootstrap target readback mismatch',{...profileAvailability,profileStage:'target',target:t});"
                + "diagnostics={...diagnostics,hybridBootstrapMode:" + q(selection.bootstrap.mode)
                + ",hybridContinuationMode:" + q(selection.continuation.mode)
                + ",targetMode:t.mode,targetModel:t.model||'',targetReasoning:t.reasoning||'',targetReady:!!t.ready};"
                + "}catch(_){return result('CHAT_BOOTSTRAP_PROFILE_ENGINE_UNAVAILABLE','HYBRID bootstrap target rejected',{...profileAvailability,profileStage:'target',operationOk:false});}";
    }

    private static String q(String value) { return SelfRunScript.quote(value == null ? "" : value); }
}
