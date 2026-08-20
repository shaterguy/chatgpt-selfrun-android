package com.shaterguy.chatgptselfrun;

/**
 * Continuation-only DOM adapter for Drive V1.
 *
 * <p>The current in-process WebView is the sole source of SEND/STOP and submission state.
 * This adapter never assumes that another ChatGPT client is synchronized with this WebView.</p>
 */
final class SelfRunContinuationDom {
    static final String SEND_ENABLED = "SEND_ENABLED";
    static final String STOP = "STOP";
    static final String SEND_DISABLED = "SEND_DISABLED";
    static final String COMPOSER_IDLE = "COMPOSER_IDLE";
    static final String UNKNOWN = "UNKNOWN";

    private SelfRunContinuationDom() {}

    static String buttonState(String conversationUrl) {
        String conversation = q(SelfRunScript.conversationId(conversationUrl));
        String composerKey = composerKey(conversationUrl);
        String sendKey = sendKey(conversationUrl);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + conversationGuard(conversation) + authGuard() + calibration()
                + composer(composerKey) + "if(!composer)return result('" + UNKNOWN + "','continuation composer unavailable');" + controls(sendKey)
                + "const c=controlState();return result(c.state,'continuation button state');})()";
    }

    /** Uses the same verified SEND/STOP protocol for the very first Drive request. */
    static String prepareBootstrap(String projectUrl, String prompt, String markerId) {
        String project = q(SelfRunScript.projectId(projectUrl));
        String expected = q(prompt);
        String marker = q("selfrun-drive:verified-bootstrap:" + markerId);
        String composerKey = composerKey(projectUrl);
        String sendKey = sendKey(projectUrl);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + projectGuard(project) + authGuard() + calibration() + textHelpers(expected)
                + composer(composerKey) + "if(!composer)return result('" + UNKNOWN + "','bootstrap composer unavailable');"
                + composerOps() + controls(sendKey) + markerOps(marker)
                + "let m=readMarker();if(m.state==='clicked')return result('VERIFY_REQUIRED','prior bootstrap click requires submission verification');"
                + "if(m.state==='confirmed')return result('SUBMISSION_CONFIRMED','bootstrap submission was already confirmed');"
                + "const c0=controlState();if(c0.state!=='" + SEND_ENABLED + "'&&c0.state!=='" + SEND_DISABLED + "'&&c0.state!=='" + COMPOSER_IDLE + "')return result(c0.state,'bootstrap waits for an idle editable composer before mutation');"
                + "if(!m.state||m.state==='failed'){writeMarker({state:'clearing',at:Date.now()});clearComposer();return result('COMPOSER_CLEARING','bootstrap composer cleared');}"
                + "if(m.state==='clearing'){if(!empty()){clearComposer();return result('COMPOSER_CLEARING','waiting for empty bootstrap composer readback');}writeMarker({state:'inputting',at:Date.now()});inputComposer();return result('COMPOSER_INPUTTING','fresh bootstrap inserted');}"
                + "if(m.state==='inputting'){if(!same()){if(empty())inputComposer();else{writeMarker({state:'clearing',at:Date.now()});clearComposer();return result('COMPOSER_CLEARING','bootstrap composer diverged; clearing again');}return result('COMPOSER_INPUTTING','waiting for exact bootstrap readback');}const c=controlState();if(c.state!=='" + SEND_ENABLED + "')return result(c.state,'waiting for enabled SEND after bootstrap readback');writeMarker({state:'prepared',at:Date.now()});return result('READY_TO_SUBMIT','exact bootstrap prepared');}"
                + "if(m.state==='prepared'){if(!same()){writeMarker({state:'clearing',at:Date.now()});clearComposer();return result('COMPOSER_CLEARING','prepared bootstrap changed; restarting input');}const c=controlState();if(c.state!=='" + SEND_ENABLED + "')return result(c.state,'prepared bootstrap waiting for SEND');return result('READY_TO_SUBMIT','exact bootstrap prepared');}"
                + "writeMarker({state:'clearing',at:Date.now()});clearComposer();return result('COMPOSER_CLEARING','unknown bootstrap marker state reset');})()";
    }

    static String clickPreparedBootstrap(String projectUrl, String prompt, String markerId) {
        String project = q(SelfRunScript.projectId(projectUrl));
        String expected = q(prompt);
        String marker = q("selfrun-drive:verified-bootstrap:" + markerId);
        String composerKey = composerKey(projectUrl);
        String sendKey = sendKey(projectUrl);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + projectGuard(project) + authGuard() + calibration() + textHelpers(expected)
                + composer(composerKey) + "if(!composer)return result('" + UNKNOWN + "','bootstrap composer unavailable before click');"
                + composerOps() + controls(sendKey) + markerOps(marker)
                + "const m=readMarker();if(m.state!=='prepared')return result('VERIFY_REQUIRED','bootstrap prepared marker changed before click');"
                + "if(!same())return result('COMPOSER_CLEARING','exact bootstrap readback lost before click');"
                + "const c=controlState();if(c.state!=='" + SEND_ENABLED + "')return result(c.state,'SEND no longer enabled for bootstrap');"
                + "const baselineUserCount=userMessageCount();writeMarker({state:'clicked',clickedAt:Date.now(),baselineUserCount});c.send.focus?.();c.send.click();return result('BOOTSTRAP_CLICKED','SEND clicked; bootstrap verification required');})()";
    }

    static String verifyBootstrapSubmission(String projectUrl, String prompt, String markerId, long failureMs) {
        String project = q(SelfRunScript.projectId(projectUrl));
        String expected = q(prompt);
        String marker = q("selfrun-drive:verified-bootstrap:" + markerId);
        String composerKey = composerKey(projectUrl);
        String sendKey = sendKey(projectUrl);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + projectGuard(project) + authGuard() + calibration() + textHelpers(expected)
                + composer(composerKey) + composerOpsNullable() + controls(sendKey) + markerOps(marker)
                + "const m=readMarker();if(m.state==='confirmed')return result('SUBMISSION_CONFIRMED','bootstrap submission already confirmed');if(m.state!=='clicked')return result('SUBMISSION_FAILED','bootstrap clicked marker unavailable');"
                + "const c=controlState(),users=userMessageCount(),baseline=Math.max(0,Number(m.baselineUserCount)||0),isEmpty=empty(),stillSame=same(),elapsed=Math.max(0,Date.now()-Math.max(0,Number(m.clickedAt)||0));"
                + "if(c.state==='" + STOP + "'){writeMarker({...m,state:'confirmed',confirmedAt:Date.now(),proof:'STOP'});return result('SUBMISSION_CONFIRMED','SEND changed to STOP');}"
                + "if(users>baseline&&isEmpty){writeMarker({...m,state:'confirmed',confirmedAt:Date.now(),proof:'USER_MESSAGE'});return result('SUBMISSION_CONFIRMED','new user message exists and composer is empty');}"
                + "if(elapsed>=" + failureMs + "&&c.state==='" + SEND_ENABLED + "'&&stillSame&&users<=baseline){writeMarker({state:'failed',failedAt:Date.now()});return result('SUBMISSION_FAILED','SEND remained enabled; bootstrap remained; no new user message');}"
                + "return result('SUBMISSION_PENDING','bootstrap submission verification pending; state='+c.state+';empty='+(isEmpty?1:0)+';users='+users+';baseline='+baseline+';elapsed='+elapsed);})()";
    }

    static String prepareDriveTurn(String conversationUrl, String prompt, String markerId) {
        String conversation = q(SelfRunScript.conversationId(conversationUrl));
        String expected = q(prompt);
        String marker = q("selfrun-drive:verified-continuation:" + markerId);
        String composerKey = composerKey(conversationUrl);
        String sendKey = sendKey(conversationUrl);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + conversationGuard(conversation) + authGuard() + calibration() + textHelpers(expected)
                + composer(composerKey) + "if(!composer)return result('" + UNKNOWN + "','continuation composer unavailable');"
                + composerOps() + controls(sendKey) + markerOps(marker)
                + "let m=readMarker();if(m.state==='clicked')return result('VERIFY_REQUIRED','prior click requires submission verification');"
                + "if(m.state==='confirmed')return result('SUBMISSION_CONFIRMED','submission was already confirmed');"
                + "const c0=controlState();if(c0.state!=='" + SEND_ENABLED + "'&&c0.state!=='" + SEND_DISABLED + "'&&c0.state!=='" + COMPOSER_IDLE + "')return result(c0.state,'continuation waits for an idle editable composer before mutation');"
                + "if(!m.state||m.state==='failed'){writeMarker({state:'clearing',at:Date.now()});clearComposer();return result('COMPOSER_CLEARING','existing composer content cleared');}"
                + "if(m.state==='clearing'){if(!empty()){clearComposer();return result('COMPOSER_CLEARING','waiting for empty composer readback');}writeMarker({state:'inputting',at:Date.now()});inputComposer();return result('COMPOSER_INPUTTING','fresh continuation inserted');}"
                + "if(m.state==='inputting'){if(!same()){if(empty())inputComposer();else{writeMarker({state:'clearing',at:Date.now()});clearComposer();return result('COMPOSER_CLEARING','composer diverged; clearing again');}return result('COMPOSER_INPUTTING','waiting for exact continuation readback');}const c=controlState();if(c.state!=='" + SEND_ENABLED + "')return result(c.state,'waiting for enabled SEND after exact readback');writeMarker({state:'prepared',at:Date.now()});return result('READY_TO_SUBMIT','exact continuation prepared');}"
                + "if(m.state==='prepared'){if(!same()){writeMarker({state:'clearing',at:Date.now()});clearComposer();return result('COMPOSER_CLEARING','prepared composer changed; restarting input');}const c=controlState();if(c.state!=='" + SEND_ENABLED + "')return result(c.state,'prepared continuation waiting for SEND');return result('READY_TO_SUBMIT','exact continuation prepared');}"
                + "writeMarker({state:'clearing',at:Date.now()});clearComposer();return result('COMPOSER_CLEARING','unknown marker state reset');})()";
    }

    static String clickPreparedDriveTurn(String conversationUrl, String prompt, String markerId) {
        String conversation = q(SelfRunScript.conversationId(conversationUrl));
        String expected = q(prompt);
        String marker = q("selfrun-drive:verified-continuation:" + markerId);
        String composerKey = composerKey(conversationUrl);
        String sendKey = sendKey(conversationUrl);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + conversationGuard(conversation) + authGuard() + calibration() + textHelpers(expected)
                + composer(composerKey) + "if(!composer)return result('" + UNKNOWN + "','composer unavailable before click');"
                + composerOps() + controls(sendKey) + markerOps(marker)
                + "const m=readMarker();if(m.state!=='prepared')return result('VERIFY_REQUIRED','prepared marker changed before click');"
                + "if(!same())return result('COMPOSER_CLEARING','exact continuation readback lost before click');"
                + "const c=controlState();if(c.state!=='" + SEND_ENABLED + "')return result(c.state,'SEND no longer enabled');"
                + "const baselineUserCount=userMessageCount();writeMarker({state:'clicked',clickedAt:Date.now(),baselineUserCount});c.send.focus?.();c.send.click();return result('CONTINUE_CLICKED','SEND clicked; verification required');})()";
    }

    static String verifyDriveTurnSubmission(String conversationUrl, String prompt, String markerId, long failureMs) {
        String conversation = q(SelfRunScript.conversationId(conversationUrl));
        String expected = q(prompt);
        String marker = q("selfrun-drive:verified-continuation:" + markerId);
        String composerKey = composerKey(conversationUrl);
        String sendKey = sendKey(conversationUrl);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + conversationGuard(conversation) + authGuard() + calibration() + textHelpers(expected)
                + composer(composerKey) + composerOpsNullable() + controls(sendKey) + markerOps(marker)
                + "const m=readMarker();if(m.state==='confirmed')return result('SUBMISSION_CONFIRMED','submission already confirmed');if(m.state!=='clicked')return result('SUBMISSION_FAILED','clicked marker unavailable');"
                + "const c=controlState(),users=userMessageCount(),baseline=Math.max(0,Number(m.baselineUserCount)||0),isEmpty=empty(),stillSame=same(),elapsed=Math.max(0,Date.now()-Math.max(0,Number(m.clickedAt)||0));"
                + "if(c.state==='" + STOP + "'){writeMarker({...m,state:'confirmed',confirmedAt:Date.now(),proof:'STOP'});return result('SUBMISSION_CONFIRMED','SEND changed to STOP');}"
                + "if(users>baseline&&isEmpty){writeMarker({...m,state:'confirmed',confirmedAt:Date.now(),proof:'USER_MESSAGE'});return result('SUBMISSION_CONFIRMED','new user message exists and composer is empty');}"
                + "if(elapsed>=" + failureMs + "&&c.state==='" + SEND_ENABLED + "'&&stillSame&&users<=baseline){writeMarker({state:'failed',failedAt:Date.now()});return result('SUBMISSION_FAILED','SEND remained enabled; continuation remained; no new user message');}"
                + "return result('SUBMISSION_PENDING','submission verification pending; state='+c.state+';empty='+(isEmpty?1:0)+';users='+users+';baseline='+baseline+';elapsed='+elapsed);})()";
    }

    private static String conversationGuard(String conversation) {
        return "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','host mismatch');const p=location.pathname.split('/').filter(Boolean);const after=k=>{const i=p.indexOf(k);return i>=0&&i+1<p.length?p[i+1]:''};if(after('c')!==" + conversation + ")return result('TARGET_ERROR','canonical conversation mismatch');";
    }

    private static String projectGuard(String project) {
        String general = q(SelfRunScript.GENERAL_CHAT_SCOPE);
        return "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','host mismatch');const p=location.pathname.split('/').filter(Boolean);const after=k=>{const i=p.indexOf(k);return i>=0&&i+1<p.length?p[i+1]:''};const expectedProject=" + project + ";const actualProject=after('g');if(expectedProject===" + general + "){const generalNew=p.length===0;const generalConversation=p.length===2&&p[0]==='c'&&!!p[1];if(!generalNew&&!generalConversation)return result('TARGET_ERROR','general chat target mismatch');}else if(actualProject!==expectedProject)return result('TARGET_ERROR','project mismatch');";
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
        return "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;const label=e=>String((e?.getAttribute?.('aria-label')||'')+' '+(e?.title||'')+' '+(e?.innerText||e?.textContent||'')).replace(/\\s+/g,' ').trim().toLowerCase();const testid=e=>String(e?.dataset?.testid||'').replace(/\\s+/g,' ').trim().toLowerCase();const buttonLike=e=>!!e&&e.matches?.('button,[role=\"button\"]');const composerRoot=composer?.closest?.('form')||composer?.closest?.('[data-type=\"unified-composer\"]')||composer?.closest?.('[class*=\"composer\"]')||composer?.parentElement;const inComposer=e=>!!e&&!!composerRoot&&composerRoot.contains(e);const composerEditable=()=>visible(composer)&&composer.getAttribute?.('aria-disabled')!=='true'&&!composer.disabled&&!composer.readOnly&&(('value'in composer)||composer.isContentEditable);const isStop=e=>{if(!buttonLike(e))return false;const id=testid(e),text=label(e);return /(^|[-_:])(?:composer-)?stop(?:[-_:]|$)/.test(id)||/\\bstop(?:\\s+(?:generating|streaming|responding))?\\b/.test(text)||/(?:생성|응답)?\\s*(?:중지|정지)/.test(text);};const isSend=e=>{if(!buttonLike(e)||isStop(e)||!inComposer(e))return false;const id=testid(e),text=label(e);return /(^|[-_:])(?:send-button|composer-submit-button)(?:[-_:]|$)/.test(id)||/\\b(?:send|submit)\\b|보내기/.test(text)||e.matches?.('button[type=\"submit\"]');};const isVoice=e=>{if(!buttonLike(e)||!inComposer(e))return false;const id=testid(e),text=label(e);return /(^|[-_:])(?:composer-)?(?:speech|voice|mic|microphone|dictation)(?:-mode|-button)?(?:[-_:]|$)/.test(id)||/\\b(?:start\\s+)?(?:voice(?:\\s+(?:mode|input))?|dictat(?:e|ion)|microphone|mic)\\b/.test(text)||/(?:음성\\s*(?:모드|입력)?|받아쓰기|마이크)/.test(text);};const userMessageCount=()=>document.querySelectorAll('[data-message-author-role=\"user\"]').length;const controlState=()=>{const calibrated=__srFind(" + q(sendKey) + ");const controls=[...document.querySelectorAll('button,[role=\"button\"]')].filter(visible);if(calibrated&&visible(calibrated)&&!controls.includes(calibrated))controls.unshift(calibrated);const stop=controls.find(isStop);if(stop)return{state:'" + STOP + "',send:null};const send=calibrated&&visible(calibrated)&&isSend(calibrated)?calibrated:controls.find(isSend);if(send){if(send.disabled||send.getAttribute('aria-disabled')==='true')return{state:'" + SEND_DISABLED + "',send};return{state:'" + SEND_ENABLED + "',send};}const voice=controls.find(isVoice);if(voice&&composerEditable())return{state:'" + COMPOSER_IDLE + "',send:null};return{state:'" + UNKNOWN + "',send:null};};";
    }

    private static String composerOps() {
        return "const raw=()=>('value'in composer?composer.value:(composer.innerText||composer.textContent||''));const same=()=>canonical(raw())===canonical(expected);const empty=()=>canonical(raw())==='';const setValue=v=>{const p=Object.getPrototypeOf(composer),own=Object.getOwnPropertyDescriptor(p,'value'),base=typeof HTMLTextAreaElement!=='undefined'?Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value'):null,setter=own?.set||base?.set;if(setter)setter.call(composer,v);else composer.value=v;};const clearComposer=()=>{composer.focus();if('value'in composer){setValue('');composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'deleteContentBackward',data:null}));composer.dispatchEvent(new Event('change',{bubbles:true}));}else{const sel=window.getSelection(),range=document.createRange();range.selectNodeContents(composer);sel.removeAllRanges();sel.addRange(range);let deleted=false;try{deleted=document.execCommand('delete',false,null);}catch(_){}if(!deleted)composer.replaceChildren();composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'deleteContentBackward',data:null}));}};const inputComposer=()=>{composer.focus();if('value'in composer){setValue(expected);composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));composer.dispatchEvent(new Event('change',{bubbles:true}));}else{let inserted=false;try{inserted=document.execCommand('insertText',false,expected);}catch(_){}if(!inserted)composer.replaceChildren(document.createTextNode(expected));composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));}};";
    }

    private static String composerOpsNullable() {
        return "const raw=()=>!composer?'':('value'in composer?composer.value:(composer.innerText||composer.textContent||''));const same=()=>!!composer&&canonical(raw())===canonical(expected);const empty=()=>!composer||canonical(raw())==='';";
    }

    private static String markerOps(String marker) {
        return "const markerKey=" + marker + ";const readMarker=()=>{let raw='';try{raw=localStorage.getItem(markerKey)||sessionStorage.getItem(markerKey)||'';}catch(_){}try{return raw?JSON.parse(raw):{};}catch(_){return{};}};const writeMarker=data=>{const raw=JSON.stringify(data);let ok=false;try{localStorage.setItem(markerKey,raw);ok=localStorage.getItem(markerKey)===raw;}catch(_){}if(!ok){try{sessionStorage.setItem(markerKey,raw);}catch(_){}}};";
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
