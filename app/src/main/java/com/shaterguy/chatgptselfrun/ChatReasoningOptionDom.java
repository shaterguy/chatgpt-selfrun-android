package com.shaterguy.chatgptselfrun;

/** SelfRun Chat reasoning bridge resolved from ProfileRegistry; no menu interaction occurs. */
final class ChatReasoningOptionDom {
    private ChatReasoningOptionDom() {}

    static String inline(String selection, String runId) {
        String wanted = ChatReasoningPreferenceStore.normalize(selection);
        if (ChatReasoningPreferenceStore.KEEP.equals(wanted)) {
            return "if(requestedMode==='work')return result('READY','WORK target profile initialization complete',{strategy:'request-profile',observed:'',action:'skip-chat-profile-work'});"
                    + "return result('CHAT_REASONING_OPTION_UNAVAILABLE','Chat target requires an explicit registered reasoning profile.',{strategy:'request-profile',requested:'keep'});";
        }
        if (ProfileRegistry.resolveChat(wanted) == null) {
            return "return result('CHAT_REASONING_OPTION_UNAVAILABLE','Unsupported or deleted Chat reasoning target.',{strategy:'request-profile',requested:"
                    + SelfRunScript.quote(wanted) + "});";
        }
        return """
                try{
                  __SET_CHAT_REASONING__
                  diagnostics={...diagnostics,observed:__WANTED__,verifiedValue:__WANTED__,action:'profile-ready',uiClicks:0};
                }catch(_){return result('CHAT_REASONING_OPTION_UNAVAILABLE','request profile Chat target rejected',{strategy:'request-profile',profileStage:'target',enginePresent:true,engineVersionMatch:true,operationOk:false});}
                """
                .replace("__SET_CHAT_REASONING__", RequestProfileScript.setChatReasoning(wanted))
                .replace("__WANTED__", SelfRunScript.quote(wanted));
    }
}
