package com.shaterguy.chatgptselfrun;

/** SelfRun Chat profile bridge. Bootstrap and continuation reasoning can differ without menu interaction. */
final class ChatReasoningOptionDom {
    private ChatReasoningOptionDom() {}

    static String inline(String selection, String runId) {
        String bootstrap = ChatReasoningPreferenceStore.normalize(selection);
        String continuation = ChatReasoningPreferenceStore.continuationSelectionForRun(runId);
        if (ChatReasoningPreferenceStore.KEEP.equals(continuation)) continuation = bootstrap;
        if (ChatReasoningPreferenceStore.KEEP.equals(bootstrap)) {
            return "if(requestedMode==='work')return result('READY','WORK target profile initialization complete',{strategy:'request-profile',observed:'',action:'skip-chat-profile-work'});"
                    + "return result('CHAT_REASONING_OPTION_UNAVAILABLE','Chat target requires an explicit registered reasoning profile.',{strategy:'request-profile',requested:'keep'});";
        }
        if (ProfileRegistry.resolveChat(bootstrap) == null) {
            return "return result('CHAT_REASONING_OPTION_UNAVAILABLE','Unsupported or deleted Chat bootstrap reasoning target.',{strategy:'request-profile',requested:"
                    + SelfRunScript.quote(bootstrap) + "});";
        }
        if (!ChatReasoningPreferenceStore.shouldApply(continuation)) {
            return "return result('CHAT_REASONING_OPTION_UNAVAILABLE','Unsupported or deleted Chat continuation reasoning target.',{strategy:'request-profile',requested:"
                    + SelfRunScript.quote(continuation) + "});";
        }
        return """
                try{
                  __SET_CHAT_PROFILES__
                  diagnostics={...diagnostics,observed:__BOOTSTRAP__,continuation:__CONTINUATION__,verifiedValue:__BOOTSTRAP__,action:'profile-ready',uiClicks:0};
                }catch(_){return result('CHAT_REASONING_OPTION_UNAVAILABLE','request profile Chat targets rejected',{strategy:'request-profile',profileStage:'target',enginePresent:true,engineVersionMatch:true,operationOk:false});}
                """
                .replace("__SET_CHAT_PROFILES__", RequestProfileScript.setChatProfiles(bootstrap, continuation))
                .replace("__BOOTSTRAP__", SelfRunScript.quote(bootstrap))
                .replace("__CONTINUATION__", SelfRunScript.quote(continuation));
    }
}
