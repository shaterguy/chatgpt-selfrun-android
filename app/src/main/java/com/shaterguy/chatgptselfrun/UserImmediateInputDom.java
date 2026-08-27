package com.shaterguy.chatgptselfrun;

/** DOM adapter used only for an explicit user-triggered immediate steering input. */
final class UserImmediateInputDom {
    static final String PREPARED = "IMMEDIATE_INPUT_PREPARED";
    static final String SEND_READY = "IMMEDIATE_INPUT_SEND_READY";
    static final String SENT = "IMMEDIATE_INPUT_SENT";
    static final String DEFERRED = "IMMEDIATE_INPUT_DEFERRED";
    static final String CLEANUP_PENDING = "IMMEDIATE_INPUT_CLEANUP_PENDING";
    static final String CLICK_UNCERTAIN = "IMMEDIATE_INPUT_CLICK_UNCERTAIN";

    private UserImmediateInputDom() {}

    static String prepare(String conversationUrl, String text, String requestId) {
        String conversation = q(SelfRunScript.conversationId(conversationUrl));
        String expected = q(text);
        String composerKey = composerKey(conversationUrl);
        String sendKey = sendKey(conversationUrl);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + conversationGuard(conversation) + authGuard() + calibration() + textHelpers(expected)
                + markerOps(requestId) + composer(composerKey)
                + "if(!composer){writeMarker('deferred','composer_unavailable');return result('" + DEFERRED + "','composer unavailable');}"
                + composerOps() + controls(sendKey)
                + "const m=readMarker();if(m.state==='sent')return result('" + SENT + "','already sent');if(m.state==='deferred')return result('" + DEFERRED + "','already deferred');if(m.state==='clicking'||m.state==='clicking_cleaned')return result('" + CLICK_UNCERTAIN + "','prior click outcome is uncertain');"
                + "if(!runningStop()){writeMarker('deferred','assistant_not_running');return result('" + DEFERRED + "','assistant response is no longer running');}"
                + "if(!composerEditable()){writeMarker('deferred','composer_not_editable');return result('" + DEFERRED + "','composer not editable');}"
                + "if(!empty()&&!same()){writeMarker('deferred','composer_busy');return result('" + DEFERRED + "','composer already contains different text');}"
                + "if(empty())inputComposer();"
                + "if(same()){writeMarker('prepared','exact_readback');return result('" + PREPARED + "','exact immediate input prepared');}"
                + "if(!empty())clearComposer();if(empty()){writeMarker('deferred','input_readback_failed');return result('" + DEFERRED + "','input readback failed; composer restored empty');}"
                + "return result('" + CLEANUP_PENDING + "','input readback failed; cleanup pending');})()";
    }

    static String resolve(String conversationUrl, String text, String requestId) {
        String conversation = q(SelfRunScript.conversationId(conversationUrl));
        String expected = q(text);
        String composerKey = composerKey(conversationUrl);
        String sendKey = sendKey(conversationUrl);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + conversationGuard(conversation) + authGuard() + calibration() + textHelpers(expected)
                + markerOps(requestId) + composer(composerKey)
                + "if(!composer){writeMarker('deferred','composer_unavailable');return result('" + DEFERRED + "','composer unavailable');}"
                + composerOps() + controls(sendKey)
                + "const m=readMarker();if(m.state==='sent')return result('" + SENT + "','already sent');if(m.state==='deferred')return result('" + DEFERRED + "','already deferred');if(m.state==='clicking'||m.state==='clicking_cleaned')return result('" + CLICK_UNCERTAIN + "','prior click outcome is uncertain');"
                + "if(!same()){writeMarker('deferred','composer_changed');return result('" + DEFERRED + "','composer changed before immediate send');}"
                + "if(!runningStop()){clearComposer();if(empty()){writeMarker('deferred','assistant_finished_before_send');return result('" + DEFERRED + "','assistant finished before SEND decision');}return result('" + CLEANUP_PENDING + "','assistant finished; cleanup pending');}"
                + "const send=forceSend();if(send){writeMarker('send_ready','enabled_send');return result('" + SEND_READY + "','running response has enabled SEND ready');}"
                + "clearComposer();if(empty()){writeMarker('deferred','send_unavailable');return result('" + DEFERRED + "','running response has no enabled SEND; defer next turn');}"
                + "return result('" + CLEANUP_PENDING + "','enabled SEND unavailable; cleanup pending');})()";
    }

    static String click(String conversationUrl, String text, String requestId) {
        String conversation = q(SelfRunScript.conversationId(conversationUrl));
        String expected = q(text);
        String composerKey = composerKey(conversationUrl);
        String sendKey = sendKey(conversationUrl);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + conversationGuard(conversation) + authGuard() + calibration() + textHelpers(expected)
                + markerOps(requestId) + composer(composerKey)
                + "if(!composer){writeMarker('deferred','composer_unavailable_before_click');return result('" + DEFERRED + "','composer unavailable before click');}"
                + composerOps() + controls(sendKey)
                + "const m=readMarker();if(m.state==='sent')return result('" + SENT + "','already sent');if(m.state==='deferred')return result('" + DEFERRED + "','already deferred');if(m.state==='clicking'||m.state==='clicking_cleaned')return result('" + CLICK_UNCERTAIN + "','prior click outcome is uncertain');"
                + "if(!same()){writeMarker('deferred','composer_changed_before_click');return result('" + DEFERRED + "','composer changed before click');}"
                + "if(!runningStop()){clearComposer();if(empty()){writeMarker('deferred','assistant_finished_before_click');return result('" + DEFERRED + "','assistant finished before click');}return result('" + CLEANUP_PENDING + "','assistant finished; cleanup pending');}"
                + "const send=forceSend();if(send){writeMarker('clicking','enabled_send');try{send.focus?.();send.click();writeMarker('sent','button_click');return result('" + SENT + "','enabled SEND clicked once');}catch(_){return result('" + CLICK_UNCERTAIN + "','SEND click threw after dispatch attempt');}}"
                + "clearComposer();if(empty()){writeMarker('deferred','send_lost_before_click');return result('" + DEFERRED + "','enabled SEND lost before click; defer next turn');}"
                + "return result('" + CLEANUP_PENDING + "','SEND lost; cleanup pending');})()";
    }

    static String cleanup(String conversationUrl, String text, String requestId) {
        String conversation = q(SelfRunScript.conversationId(conversationUrl));
        String expected = q(text);
        String composerKey = composerKey(conversationUrl);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + conversationGuard(conversation) + calibration() + textHelpers(expected)
                + markerOps(requestId) + composer(composerKey)
                + "const m=readMarker();if(m.state==='sent')return result('" + SENT + "','already sent');if(m.state==='deferred')return result('" + DEFERRED + "','already deferred');"
                + "const ambiguous=m.state==='clicking'||m.state==='clicking_cleaned';"
                + "if(!composer){if(ambiguous){writeMarker('clicking_cleaned','composer_gone_after_click');return result('" + CLICK_UNCERTAIN + "','composer gone after click attempt');}writeMarker('deferred','composer_gone');return result('" + DEFERRED + "','composer gone');}"
                + composerOps()
                + "if(ambiguous){if(same())clearComposer();if(empty()||!same()){writeMarker('clicking_cleaned','cleanup_after_ambiguous_click');return result('" + CLICK_UNCERTAIN + "','post-click cleanup completed without fallback');}return result('" + CLEANUP_PENDING + "','post-click cleanup pending');}"
                + "if(!same()){writeMarker('deferred','composer_changed');return result('" + DEFERRED + "','different composer content preserved');}"
                + "clearComposer();if(empty()){writeMarker('deferred','cleanup_complete');return result('" + DEFERRED + "','immediate text removed before next-turn fallback');}"
                + "return result('" + CLEANUP_PENDING + "','cleanup pending');})()";
    }

    private static String conversationGuard(String conversation) {
        return "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','host mismatch');const p=location.pathname.split('/').filter(Boolean);const after=k=>{const i=p.indexOf(k);return i>=0&&i+1<p.length?p[i+1]:''};if(after('c')!==" + conversation + ")return result('TARGET_ERROR','canonical conversation mismatch');";
    }

    private static String authGuard() {
        return "const authVisible=e=>!!e&&e.isConnected&&e.offsetParent!==null;const auth=[...document.querySelectorAll('[data-testid*=login],a[href*=\"/auth/login\"],button')].filter(authVisible).some(e=>/^(log in|sign up|로그인|가입)$/i.test(String(e.innerText||e.getAttribute('aria-label')||'').trim()));if(auth)return result('AUTH_REQUIRED','ChatGPT login required');";
    }

    private static String calibration() { return WebUiCalibrationDom.runtimePrelude(); }

    private static String textHelpers(String expected) {
        return "const norm=s=>String(s??'').replace(/[\\u200B-\\u200D\\uFEFF]/g,'').replace(/\\u00a0/g,' ').replace(/\\r\\n?/g,'\\n').trim();const canonical=s=>norm(s).replace(/[ \\t]+/g,' ').replace(/ *\\n+ */g,'\\n');const expected=norm(" + expected + ");";
    }

    private static String composer(String targetKey) {
        return "let composer=__srFind(" + q(targetKey) + ");const composerSelectors=['textarea#prompt-textarea','textarea[data-testid=\"prompt-textarea\"]','div#prompt-textarea[contenteditable=\"true\"]','main form [contenteditable=\"true\"][data-lexical-editor=\"true\"]','main form [contenteditable=\"true\"]'];if(!composer){for(const s of composerSelectors){composer=[...document.querySelectorAll(s)].find(e=>e&&e.isConnected&&e.offsetParent!==null);if(composer)break;}}";
    }

    private static String controls(String sendKey) {
        return "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;"
                + "const label=e=>String((e?.getAttribute?.('aria-label')||'')+' '+(e?.title||'')+' '+(e?.innerText||e?.textContent||'')).replace(/\\s+/g,' ').trim().toLowerCase();"
                + "const testid=e=>String(e?.dataset?.testid||'').replace(/\\s+/g,' ').trim().toLowerCase();"
                + "const buttonLike=e=>!!e&&e.matches?.('button,[role=\"button\"]');"
                + "const composerRoot=composer?.closest?.('form')||composer?.closest?.('[data-type=\"unified-composer\"]')||composer?.closest?.('[class*=\"composer\"]')||composer?.parentElement;"
                + "const composerScope=composerRoot?.parentElement||composerRoot;"
                + "const inComposer=e=>!!e&&!!composerRoot&&composerRoot.contains(e);"
                + "const inComposerScope=e=>!!e&&!!composerScope&&composerScope.contains(e);"
                + "const composerEditable=()=>visible(composer)&&composer.getAttribute?.('aria-disabled')!=='true'&&!composer.disabled&&!composer.readOnly&&(('value'in composer)||composer.isContentEditable);"
                + "const stopSemantic=e=>{const id=testid(e),text=label(e);return /(^|[-_:])(?:composer-)?stop(?:[-_:]|$)/.test(id)||/\\bstop(?:\\s+(?:generating|streaming|responding))?\\b/.test(text)||/(?:생성|응답)?\\s*(?:중지|정지)/.test(text);};"
                + "const voiceSemantic=e=>{const id=testid(e),text=label(e);return /(^|[-_:])(?:composer-)?(?:speech|voice|mic|microphone|dictation)(?:-mode|-button)?(?:[-_:]|$)/.test(id)||/\\b(?:start\\s+)?(?:voice(?:\\s+(?:mode|input))?|dictat(?:e|ion)|microphone|mic)\\b/.test(text)||/(?:음성\\s*(?:모드|입력)?|받아쓰기|마이크)/.test(text);};"
                + "const sendSemantic=e=>{const id=testid(e),text=label(e);return /(^|[-_:])(?:send-button|composer-submit-button)(?:[-_:]|$)/.test(id)||/\\b(?:send|submit)(?:\\s+(?:message|prompt))?\\b|보내기/.test(text);};"
                + "const isSend=e=>!!e&&buttonLike(e)&&inComposer(e)&&!stopSemantic(e)&&!voiceSemantic(e)&&(sendSemantic(e)||e.matches?.('button[type=\"submit\"]'));"
                + "const isAdjacentSend=e=>!!e&&buttonLike(e)&&!inComposer(e)&&inComposerScope(e)&&!voiceSemantic(e)&&!stopSemantic(e)&&sendSemantic(e);"
                + "const runningStop=()=>[...document.querySelectorAll('button,[role=\"button\"]')].some(e=>visible(e)&&buttonLike(e)&&inComposer(e)&&stopSemantic(e));"
                + "const forceSend=()=>{const calibrated=__srFind(" + q(sendKey) + ");const buttons=composerRoot?[...composerRoot.querySelectorAll('button,[role=\"button\"]')].filter(visible):[];const adjacent=composerScope&&composerScope!==composerRoot?[...composerScope.querySelectorAll('button,[role=\"button\"]')].filter(visible).filter(e=>!inComposer(e)):[];if(calibrated&&visible(calibrated)&&!buttons.includes(calibrated)&&!adjacent.includes(calibrated))adjacent.unshift(calibrated);const send=calibrated&&visible(calibrated)&&(isSend(calibrated)||isAdjacentSend(calibrated))?calibrated:(buttons.find(isSend)||adjacent.find(isAdjacentSend));if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return null;return send;};";
    }

    private static String composerOps() {
        return "const raw=()=>('value'in composer?composer.value:(composer.innerText||composer.textContent||''));const same=()=>canonical(raw())===canonical(expected);const empty=()=>canonical(raw())==='';"
                + "const setValue=v=>{const p=Object.getPrototypeOf(composer),own=Object.getOwnPropertyDescriptor(p,'value'),base=typeof HTMLTextAreaElement!=='undefined'?Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value'):null,setter=own?.set||base?.set;if(setter)setter.call(composer,v);else composer.value=v;};"
                + "const beforeInput=(inputType,data)=>{try{return composer.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,cancelable:true,inputType,data}));}catch(_){return true;}};"
                + "const clearComposer=()=>{composer.focus();let deleted=!beforeInput('deleteContentBackward',null)&&empty();if('value'in composer){if(!deleted)setValue('');composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'deleteContentBackward',data:null}));composer.dispatchEvent(new Event('change',{bubbles:true}));}else{if(!deleted){const sel=window.getSelection(),range=document.createRange();range.selectNodeContents(composer);sel.removeAllRanges();sel.addRange(range);try{deleted=document.execCommand('delete',false,null);}catch(_){}if(!deleted||!empty())composer.replaceChildren();}composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'deleteContentBackward',data:null}));composer.dispatchEvent(new Event('change',{bubbles:true}));}};"
                + "const inputComposer=()=>{composer.focus();let inserted=!beforeInput('insertText',expected)&&same();if('value'in composer){if(!inserted)setValue(expected);composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));composer.dispatchEvent(new Event('change',{bubbles:true}));}else{if(!inserted){try{inserted=document.execCommand('insertText',false,expected);}catch(_){}if(!same())composer.replaceChildren(document.createTextNode(expected));}composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));composer.dispatchEvent(new Event('change',{bubbles:true}));}};";
    }

    private static String markerOps(String requestId) {
        return "const requestId=" + q(requestId) + ",markerKey='selfrun-drive:immediate-input',markerCache=window.__selfRunDriveImmediateInput||(window.__selfRunDriveImmediateInput={});"
                + "const readMarker=()=>{let raw='';try{raw=localStorage.getItem(markerKey)||'';}catch(_){}if(!raw){try{raw=sessionStorage.getItem(markerKey)||'';}catch(_){}}if(!raw)raw=markerCache.raw||'';try{const value=raw?JSON.parse(raw):{};return value.requestId===requestId?value:{};}catch(_){return{};}};"
                + "const writeMarker=(state,detail='')=>{const raw=JSON.stringify({requestId,state,detail,at:Date.now()});markerCache.raw=raw;let ok=false;try{localStorage.setItem(markerKey,raw);ok=localStorage.getItem(markerKey)===raw;}catch(_){}if(!ok){try{sessionStorage.setItem(markerKey,raw);}catch(_){}}};";
    }

    private static String composerKey(String url) {
        return SelfRunScript.GENERAL_CHAT_SCOPE.equals(SelfRunScript.projectId(url))
                ? WebUiCalibrationStore.TARGET_GENERAL_COMPOSER : WebUiCalibrationStore.TARGET_PROJECT_COMPOSER;
    }

    private static String sendKey(String url) {
        return SelfRunScript.GENERAL_CHAT_SCOPE.equals(SelfRunScript.projectId(url))
                ? WebUiCalibrationStore.TARGET_GENERAL_SEND : WebUiCalibrationStore.TARGET_PROJECT_SEND;
    }

    private static String q(String value) { return SelfRunScript.quote(value); }
}
