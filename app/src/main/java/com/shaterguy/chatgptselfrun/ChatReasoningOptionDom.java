package com.shaterguy.chatgptselfrun;

/** SelfRun 2.0 Chat reasoning bridge. No ChatGPT reasoning-menu interaction is performed. */
final class ChatReasoningOptionDom {
    private ChatReasoningOptionDom() {}

    static String inline(String selection, String runId) {
        String wanted = ChatReasoningPreferenceStore.normalize(selection);
        if (ChatReasoningPreferenceStore.KEEP.equals(wanted)) {
            return "if(requestedMode==='work')return result('READY','WORK target profile initialization complete',{strategy:'request-profile',observed:'',action:'skip-chat-profile-work'});"
                    + "return result('CHAT_REASONING_OPTION_UNAVAILABLE','Chat target requires an explicit captured reasoning profile.',{strategy:'request-profile',requested:'keep'});";
        }
        if (ChatReasoningPreferenceStore.PRO.equals(wanted)
                || ChatReasoningPreferenceStore.PRO_STANDARD.equals(wanted)
                || ChatReasoningPreferenceStore.PRO_EXTENDED.equals(wanted)) {
            return "return result('CHAT_REASONING_OPTION_UNAVAILABLE','Chat Pro request profile is uncaptured in 2.0.0-dev1.',{strategy:'request-profile',requested:"
                    + SelfRunScript.quote(wanted) + ",proCaptured:false});";
        }
        if (ChatReasoningPreferenceStore.ordinal(wanted) < 0) {
            return "return result('CHAT_REASONING_OPTION_UNAVAILABLE','Unsupported Chat reasoning target.',{strategy:'request-profile'});";
        }
        return """
                try{
                  window.__selfRunRequestProfileEngine.setChatReasoning(__WANTED__);
                  diagnostics={...diagnostics,observed:__WANTED__,verifiedValue:__WANTED__,action:'profile-ready',uiClicks:0};
                }catch(error){return result('CHAT_REASONING_OPTION_UNAVAILABLE',String(error?.message||error),{strategy:'request-profile',requested:__WANTED__});}
                """.replace("__WANTED__", SelfRunScript.quote(wanted));
    }
}
