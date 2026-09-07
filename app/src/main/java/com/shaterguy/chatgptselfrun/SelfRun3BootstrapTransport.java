package com.shaterguy.chatgptselfrun;

/**
 * V3 first-turn transport restored from the last known-good V2 bootstrap semantics.
 *
 * <p>This class owns only the first request. Preparation verifies an exact composer readback and
 * an enabled submit path without binding the response protocol. The caller binds task/request
 * identity only after READY_TO_SUBMIT and immediately before submit(). Continuations remain owned
 * by SelfRun3ComposerTransport.</p>
 */
final class SelfRun3BootstrapTransport {
    static final String SEND_ENABLED = "SEND_ENABLED";
    static final String STOP = "STOP";
    static final String SEND_DISABLED = "SEND_DISABLED";
    static final String COMPOSER_IDLE = "COMPOSER_IDLE";
    static final String COMPOSER_WAITING = "COMPOSER_WAITING";
    static final String COMPOSER_CLEARING = "COMPOSER_CLEARING";
    static final String COMPOSER_INPUTTING = "COMPOSER_INPUTTING";
    static final String READY_TO_SUBMIT = "READY_TO_SUBMIT";
    static final String SUBMISSION_PENDING = "SUBMISSION_PENDING";
    static final String AUTH_REQUIRED = "AUTH_REQUIRED";
    static final String TARGET_ERROR = "TARGET_ERROR";

    private SelfRun3BootstrapTransport() {}

    static String prepare(String projectUrl, String prompt, String markerId) {
        String project = q(SelfRunScript.projectId(projectUrl));
        String expected = q(prompt);
        String marker = q("selfrun-drive:v3-bootstrap:" + markerId);
        String composerKey = composerKey(projectUrl);
        String sendKey = sendKey(projectUrl);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + projectGuard(project) + authGuard() + calibration() + textHelpers(expected) + markerOps(marker)
                + "let m=readMarker();" + bootstrapRouteVerification()
                + "if(m.state==='confirmed')return result('SUBMISSION_PENDING','bootstrap submission was already confirmed');"
                + composer(composerKey) + "if(!composer)return result('" + COMPOSER_WAITING + "','bootstrap composer unavailable');"
                + composerOps() + controls(sendKey) + bootstrapClickedVerification()
                + "if(m.state==='failed'&&m.failure==='request_profile_rejected')return result('SUBMISSION_FAILED','request_profile_rejected');"
                + "const c0=controlState();if(c0.state!=='" + SEND_ENABLED + "'&&c0.state!=='" + SEND_DISABLED + "'&&c0.state!=='" + COMPOSER_IDLE + "')return result(c0.state,'bootstrap waits for an idle editable composer before mutation');"
                + "if(!m.state||m.state==='failed'){writeMarker({state:'clearing',at:Date.now()});clearComposer();return result('" + COMPOSER_CLEARING + "','bootstrap composer cleared');}"
                + "if(m.state==='clearing'){if(!empty()){clearComposer();return result('" + COMPOSER_CLEARING + "','waiting for empty bootstrap composer readback');}writeMarker({state:'inputting',at:Date.now()});inputComposer();return result('" + COMPOSER_INPUTTING + "','fresh bootstrap inserted');}"
                + "if(m.state==='inputting'){if(!same()){if(empty())inputComposer();else{writeMarker({state:'clearing',at:Date.now()});clearComposer();return result('" + COMPOSER_CLEARING + "','bootstrap composer diverged; clearing again');}return result('" + COMPOSER_INPUTTING + "','waiting for exact bootstrap readback');}const c=controlState();if(c.state!=='" + SEND_ENABLED + "'&&c.state!=='" + COMPOSER_IDLE + "')return result(c.state,'waiting for enabled SEND after bootstrap readback');writeMarker({state:'prepared',at:Date.now()});return result('" + READY_TO_SUBMIT + "','exact bootstrap prepared');}"
                + "if(m.state==='prepared'){if(!same()){writeMarker({state:'clearing',at:Date.now()});clearComposer();return result('" + COMPOSER_CLEARING + "','prepared bootstrap changed; restarting input');}const c=controlState();if(c.state!=='" + SEND_ENABLED + "'&&c.state!=='" + COMPOSER_IDLE + "')return result(c.state,'prepared bootstrap waiting for SEND');return result('" + READY_TO_SUBMIT + "','exact bootstrap prepared');}"
                + "writeMarker({state:'clearing',at:Date.now()});clearComposer();return result('" + COMPOSER_CLEARING + "','unknown bootstrap marker state reset');})()";
    }

    static String submit(String projectUrl, String prompt, String markerId) {
        String project = q(SelfRunScript.projectId(projectUrl));
        String expected = q(prompt);
        String marker = q("selfrun-drive:v3-bootstrap:" + markerId);
        String composerKey = composerKey(projectUrl);
        String sendKey = sendKey(projectUrl);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + projectGuard(project) + authGuard() + calibration() + textHelpers(expected)
                + composer(composerKey) + "if(!composer)return result('" + COMPOSER_WAITING + "','bootstrap composer unavailable before click');"
                + composerOps() + controls(sendKey) + markerOps(marker)
                + "const m=readMarker();if(m.state==='clicked'||m.state==='confirmed')return result('" + SUBMISSION_PENDING + "','bootstrap submission verification already pending');"
                + "if(m.state!=='prepared')return result('" + COMPOSER_INPUTTING + "','bootstrap prepared marker unavailable before dispatch');"
                + "if(!same())return result('" + COMPOSER_CLEARING + "','exact bootstrap readback lost before click');"
                + "const c=controlState();if(c.state!=='" + SEND_ENABLED + "'&&c.state!=='" + COMPOSER_IDLE + "')return result(c.state,'SEND no longer enabled for bootstrap');"
                + "const baselineUserCount=userMessageCount(),clickedAt=Date.now();writeMarker({state:'clicked',clickedAt,baselineUserCount,submitPath:'pending'});let submitPath='';if(c.send){c.send.focus?.();c.send.click();submitPath='button';}else if(requestComposerSubmit()){submitPath='form_request_submit';}else{writeMarker({state:'prepared',at:Date.now()});return result('" + SEND_DISABLED + "','verified bootstrap text has no submit path');}writeMarker({state:'clicked',clickedAt,baselineUserCount,submitPath});return result('" + SUBMISSION_PENDING + "','dispatch=BOOTSTRAP_CLICKED;submit='+submitPath+';verification=pending');})()";
    }

    private static String projectGuard(String project) {
        String general = q(SelfRunScript.GENERAL_CHAT_SCOPE);
        return "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('" + TARGET_ERROR + "','host mismatch');"
                + ProjectUrlPolicy.webProjectIdentityPrelude()
                + "const p=location.pathname.split('/').filter(Boolean);const after=k=>{const i=p.indexOf(k);return i>=0&&i+1<p.length?p[i+1]:''};const expectedProject=" + project + ";const actualProject=__srCanonicalProjectId(after('g'));if(expectedProject===" + general + "){const generalNew=p.length===0;const generalConversation=p.length===2&&p[0]==='c'&&!!p[1];if(!generalNew&&!generalConversation)return result('" + TARGET_ERROR + "','general chat target mismatch');}else if(!actualProject||actualProject!==expectedProject)return result('" + TARGET_ERROR + "','project mismatch');";
    }

    private static String authGuard() {
        return "const authVisible=e=>!!e&&e.isConnected&&e.offsetParent!==null;const auth=[...document.querySelectorAll('[data-testid*=login],a[href*=\"/auth/login\"],button')].filter(authVisible).some(e=>/^(log in|sign up|로그인|가입)$/i.test(String(e.innerText||e.getAttribute('aria-label')||'').trim()));if(auth)return result('" + AUTH_REQUIRED + "','ChatGPT login required');";
    }

    private static String calibration() {
        return WebUiCalibrationDom.runtimePrelude()
                + "const userMessageCount=()=>document.querySelectorAll('[data-message-author-role=\\\"user\\\"]').length;";
    }

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
                + "const isStop=e=>!!e&&buttonLike(e)&&inComposer(e)&&stopSemantic(e);"
                + "const isSend=e=>!!e&&buttonLike(e)&&inComposer(e)&&!stopSemantic(e)&&!voiceSemantic(e)&&(sendSemantic(e)||e.matches?.('button[type=\"submit\"]'));"
                + "const isAdjacentSend=e=>!!e&&buttonLike(e)&&!inComposer(e)&&inComposerScope(e)&&!voiceSemantic(e)&&sendSemantic(e);"
                + "const controlState=()=>{const calibrated=__srFind(" + q(sendKey) + ");const controls=composerRoot?[...composerRoot.querySelectorAll('button,[role=\"button\"]')].filter(visible):[];const adjacentControls=composerScope&&composerScope!==composerRoot?[...composerScope.querySelectorAll('button,[role=\"button\"]')].filter(visible).filter(e=>!inComposer(e)):[];if(calibrated&&visible(calibrated)&&!controls.includes(calibrated)&&!adjacentControls.includes(calibrated))adjacentControls.unshift(calibrated);const stop=controls.find(isStop);const send=calibrated&&visible(calibrated)&&(isSend(calibrated)||isAdjacentSend(calibrated))?calibrated:(controls.find(isSend)||adjacentControls.find(isAdjacentSend));const form=composer?.closest?.('form'),formSubmitReady=!!form&&typeof form.requestSubmit==='function';if(stop)return{state:'" + STOP + "',send:null};if(send){if(send.disabled||send.getAttribute('aria-disabled')==='true')return{state:'" + SEND_DISABLED + "',send};return{state:'" + SEND_ENABLED + "',send};}if(composerEditable()&&formSubmitReady)return{state:'" + COMPOSER_IDLE + "',send:null};if(composerEditable())return{state:'" + COMPOSER_IDLE + "',send:null};return{state:'" + COMPOSER_WAITING + "',send:null};};";
    }

    private static String composerOps() {
        return "const raw=()=>('value'in composer?composer.value:(composer.innerText||composer.textContent||''));const same=()=>canonical(raw())===canonical(expected);const empty=()=>canonical(raw())==='';"
                + "const setValue=v=>{const p=Object.getPrototypeOf(composer),own=Object.getOwnPropertyDescriptor(p,'value'),base=typeof HTMLTextAreaElement!=='undefined'?Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value'):null,setter=own?.set||base?.set;if(setter)setter.call(composer,v);else composer.value=v;};"
                + "const editorDocument=composer.ownerDocument||document,editorWindow=editorDocument.defaultView||window;"
                + "const editorSelection=()=>{try{return editorWindow.getSelection?.()||null;}catch(_){return null;}};"
                + "const caretTarget=()=>composer.querySelector?.('[data-lexical-text]')||composer.querySelector?.('p,div,[role=\"textbox\"]')||composer;"
                + "const richBlock=()=>composer.querySelector?.('p,div,[role=\"textbox\"]')||composer;"
                + "const placeCaret=()=>{if('value'in composer)return true;const sel=editorSelection(),range=editorDocument.createRange?.();if(!sel||!range)return false;const target=caretTarget();try{range.selectNodeContents(target);range.collapse(false);sel.removeAllRanges();sel.addRange(range);return sel.rangeCount===1&&(composer.contains(sel.anchorNode)||sel.anchorNode===composer);}catch(_){return false;}};"
                + "const selectComposerContents=()=>{const sel=editorSelection(),range=editorDocument.createRange?.();if(!sel||!range)return false;try{range.selectNodeContents(composer);sel.removeAllRanges();sel.addRange(range);return true;}catch(_){return false;}};"
                + "const beforeInput=(inputType,data)=>{try{return composer.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,cancelable:true,inputType,data}));}catch(_){return true;}};"
                + "const observeInput=operation=>{let seen=false;const mark=()=>{seen=true;};composer.addEventListener('input',mark,true);let ok=false;try{ok=!!operation();}catch(_){}finally{composer.removeEventListener('input',mark,true);}return{ok,seen};};"
                + "const clearComposer=()=>{composer.focus();if(empty())return;if('value'in composer){let deleted=!beforeInput('deleteContentBackward',null)&&empty();if(!deleted)setValue('');composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'deleteContentBackward',data:null}));composer.dispatchEvent(new Event('change',{bubbles:true}));}else{let native={ok:false,seen:false},fallbackChanged=false;if(selectComposerContents())native=observeInput(()=>editorDocument.execCommand('delete',false,null));if(!empty()){const block=richBlock();if(block&&block!==composer){while(block.firstChild)block.removeChild(block.firstChild);block.appendChild(editorDocument.createElement('br'));fallbackChanged=empty();}}if(fallbackChanged||!native.seen)composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'deleteContentBackward',data:null}));}};"
                + "const inputComposer=()=>{composer.focus();placeCaret();if('value'in composer){let inserted=!beforeInput('insertText',expected)&&same();if(!inserted)setValue(expected);composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));composer.dispatchEvent(new Event('change',{bubbles:true}));}else{placeCaret();const native=observeInput(()=>editorDocument.execCommand('insertText',false,expected));let fallbackChanged=false;if(!same()){const block=richBlock();if(block&&block!==composer){block.textContent=expected;fallbackChanged=same();}}if(fallbackChanged||!native.seen)composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));}};"
                + "const requestComposerSubmit=()=>{const form=composer?.closest?.('form');if(!form||typeof form.requestSubmit!=='function')return false;try{form.requestSubmit();return true;}catch(_){return false;}};";
    }

    private static String markerOps(String marker) {
        return "const markerKey=" + marker + ";const markerCache=window.__selfRunDriveMarkers||(window.__selfRunDriveMarkers={});const readMarker=()=>{let raw='';try{raw=localStorage.getItem(markerKey)||'';}catch(_){}if(!raw){try{raw=sessionStorage.getItem(markerKey)||'';}catch(_){}}if(!raw)raw=markerCache[markerKey]||'';try{return raw?JSON.parse(raw):{};}catch(_){return{};}};const writeMarker=data=>{const raw=JSON.stringify(data);markerCache[markerKey]=raw;let ok=false;try{localStorage.setItem(markerKey,raw);ok=localStorage.getItem(markerKey)===raw;}catch(_){}if(!ok){try{sessionStorage.setItem(markerKey,raw);}catch(_){}}};";
    }

    private static String bootstrapRouteVerification() {
        return "if(m.state==='clicked'){const profile=window.__selfRunRequestProfileEngine?.diagnostics?.();if(profile&&profile.ok===false&&profile.reason!=='not_attempted'){writeMarker({...m,state:'failed',failure:'request_profile_rejected',failedAt:Date.now()});return result('SUBMISSION_FAILED','request_profile_rejected');}const users=userMessageCount(),baseline=Number(m.baselineUserCount),conversation=after('c');if(conversation){writeMarker({...m,state:'confirmed',confirmedAt:Date.now()});return result('" + SUBMISSION_PENDING + "','bootstrap conversation route confirmed;users='+users+';baseline='+baseline+';conversation=1');}}";
    }

    private static String bootstrapClickedVerification() {
        return "if(m.state==='clicked'){const profile=window.__selfRunRequestProfileEngine?.diagnostics?.();if(profile&&profile.ok===false&&profile.reason!=='not_attempted'){writeMarker({...m,state:'failed',failure:'request_profile_rejected',failedAt:Date.now()});return result('SUBMISSION_FAILED','request_profile_rejected');}const c=controlState(),users=userMessageCount(),baseline=Number(m.baselineUserCount),conversation=after('c');if(conversation){writeMarker({...m,state:'confirmed',confirmedAt:Date.now()});return result('" + SUBMISSION_PENDING + "','bootstrap conversation route confirmed;users='+users+';baseline='+baseline+';control='+c.state+';conversation=1');}const activity=(Number.isFinite(baseline)&&users>baseline)||c.state==='" + STOP + "';return result('" + SUBMISSION_PENDING + "',activity?'bootstrap dispatch activity observed;conversation route pending':'bootstrap submission verification pending');}";
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
