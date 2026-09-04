package com.shaterguy.chatgptselfrun;

/** SelfRun 2.0 Work target bridge. Model/reasoning menus are not opened or clicked. */
final class WorkPreferenceDom {
    static final String TURN_INFO_REWRITE_SENTINEL = "__SELF_RUN_TURN_INFO_REWRITE__";
    private WorkPreferenceDom() {}

    static String modelForProject(String projectUrl, String model) {
        return preference(projectGuard(SelfRunScript.projectId(projectUrl)), "model", model,
                "project:" + SelfRunScript.projectId(projectUrl));
    }

    static String reasoningForProject(String projectUrl, String reasoning) {
        return preference(projectGuard(SelfRunScript.projectId(projectUrl)), "reasoning", reasoning, "");
    }

    static String modelForConversation(String conversationUrl, String model) {
        String guard = conversationGuard(SelfRunScript.conversationId(conversationUrl));
        if (TURN_INFO_REWRITE_SENTINEL.equals(model)) return preferenceBypass(guard, "차기 WORK 모델 정보 재작성 요청 준비");
        return preference(guard, "model", model, "conversation:" + SelfRunScript.conversationId(conversationUrl));
    }

    static String reasoningForConversation(String conversationUrl, String reasoning) {
        String guard = conversationGuard(SelfRunScript.conversationId(conversationUrl));
        if (TURN_INFO_REWRITE_SENTINEL.equals(reasoning)) return preferenceBypass(guard, "차기 WORK 추론 정보 재작성 요청 준비");
        return preference(guard, "reasoning", reasoning, "");
    }

    private static String preferenceBypass(String guard, String detail) {
        return "(() =>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,diagnostics,url:location.href});"
                + guard + "return result('READY'," + q(detail) + ",{bypassed:true,strategy:'request-profile'});})()";
    }

    private static String preference(String guard, String kind, String wanted, String resetKey) {
        String action = "model".equals(kind)
                ? RequestProfileScript.setWorkModel(wanted)
                : RequestProfileScript.setWorkReasoning(wanted);
        String begin = "model".equals(kind)
                ? "const previousTarget=profileEngine.target();"
                    + "const previousRunId=String(previousTarget?.runId||'');"
                    + "const workRunId=previousRunId&&!previousRunId.startsWith('project:')&&!previousRunId.startsWith('conversation:')?previousRunId:" + q(resetKey) + ";"
                    + "profileEngine.begin('work',workRunId);"
                : "";
        String failure = "model".equals(kind) ? "WORK_MODEL_READBACK_MISMATCH" : "WORK_REASONING_READBACK_MISMATCH";
        String engineAvailable = RequestProfileScript.engineAvailableExpression();
        return "(() =>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,diagnostics,url:location.href});"
                + guard
                + "const profileEngine=window.__selfRunRequestProfileEngine;const enginePresent=!!profileEngine;const engineVersionMatch=enginePresent&&(" + engineAvailable + ");"
                + "const profileAvailability={strategy:'request-profile',profileStage:'availability',enginePresent,engineVersionMatch};"
                + "if(!enginePresent)return result('" + failure + "','request profile engine absent',profileAvailability);"
                + "if(!engineVersionMatch)return result('" + failure + "','request profile engine version mismatch',profileAvailability);"
                + "try{" + begin + action + "const t=profileEngine.target();return result('READY','absolute Work target profile staged',{strategy:'request-profile',kind:"
                + q(kind) + ",requested:" + q(wanted) + ",targetMode:t?.mode||'',targetModel:t?.model||'',targetReasoning:t?.reasoning||'',targetRunId:t?.runId||'',targetReady:!!t?.ready,uiClicks:0});}catch(_){return result('"
                + failure + "','request profile target rejected',{strategy:'request-profile',profileStage:'target',enginePresent:true,engineVersionMatch:true,operationOk:false});}})()";
    }

    private static String projectGuard(String projectId) {
        if (SelfRunScript.GENERAL_CHAT_SCOPE.equals(projectId)) {
            return "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','호스트 불일치');";
        }
        return "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','호스트 불일치');"
                + ProjectUrlPolicy.webProjectIdentityPrelude()
                + "const __wpParts=location.pathname.split('/').filter(Boolean),__wpI=__wpParts.indexOf('g'),__wpRaw=__wpI>=0&&__wpI+1<__wpParts.length?__wpParts[__wpI+1]:'',__wpActual=__srCanonicalProjectId(__wpRaw);if(!__wpActual||__wpActual!=="
                + q(projectId) + ")return result('TARGET_ERROR','프로젝트 불일치');";
    }

    private static String conversationGuard(String conversationId) {
        return "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','호스트 불일치');const __wpParts=location.pathname.split('/').filter(Boolean),__wpI=__wpParts.indexOf('c'),__wpActual=__wpI>=0&&__wpI+1<__wpParts.length?__wpParts[__wpI+1]:'';if(__wpActual!=="
                + q(conversationId) + ")return result('TARGET_ERROR','canonical conversation 이탈');";
    }

    private static String q(String value) { return SelfRunScript.quote(value == null ? "" : value); }
}
