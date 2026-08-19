package com.shaterguy.chatgptselfrun;

/**
 * Dev8 fail-closed continuation guard. It never reads assistant bodies and never clicks STOP.
 * Freshness is bound to the document-start probe's structural proof and current main composer.
 */
final class ContinuationGuardDom {
    private ContinuationGuardDom() { }

    static String freshnessReady(String conversationUrl, String freshnessToken,
                                 String headKey, String composerKey, String stateSignature) {
        String token=q(freshnessToken), head=q(headKey), composerProof=q(composerKey), sig=q(stateSignature);
        return "(()=>{const result=(status,detail='',extra={})=>JSON.stringify({status,detail,url:location.href,...extra});"
                + conversationGuard(q(SelfRunScript.conversationId(conversationUrl))) + authGuard() + calibration()
                + composer(composerKey(conversationUrl))
                + "if(!composer)return result('UI_WAIT','conversation main composer unavailable');"
                + proofGuard(token,head,composerProof,sig,false)
                + "if(!__srProofOk)return result('FRESHNESS_STALE','probe proof mismatch');"
                + "window.__selfRunDrivePreparedContinuation=null;window.__selfRunDriveFreshnessToken="+token+";"
                + "return result('READY','conversation structural proof bound',{proof:1});})()";
    }

    static String responseIdleCheck(String conversationUrl, String freshnessToken,
                                    String headKey, String composerKey, String stateSignature) {
        String token=q(freshnessToken), head=q(headKey), composerProof=q(composerKey), sig=q(stateSignature);
        return "(()=>{const result=(status,detail='',extra={})=>JSON.stringify({status,detail,url:location.href,...extra});"
                + conversationGuard(q(SelfRunScript.conversationId(conversationUrl))) + authGuard() + calibration()
                + composer(composerKey(conversationUrl))
                + "if(!composer)return result('UI_WAIT','response idle main composer unavailable');"
                + proofGuard(token,head,composerProof,sig,true) + actionOps(sendKey(conversationUrl))
                + "if(!__srProofOk)return result('FRESHNESS_STALE','response idle proof mismatch');"
                + "const a=classifyAction();"
                + "if(a.action==='STOP')return result('RESPONSE_ACTIVE','STOP action present',{action:a.action,stopCount:a.stopCount,sendCount:a.sendCount});"
                + "if(a.action!=='SEND')return result('ACTION_UNKNOWN','explicit SEND unavailable',{action:a.action,stopCount:a.stopCount,sendCount:a.sendCount});"
                + "return result('RESPONSE_IDLE','explicit SEND confirmed',{action:a.action,stopCount:0,sendCount:a.sendCount});})()";
    }

    static String prepareDriveTurn(String conversationUrl, String prompt, String markerId,
                                   String freshnessToken, String headKey,
                                   String composerKey, String stateSignature) {
        String expected=q(prompt), marker=q("selfrun-drive:command:"+markerId), token=q(freshnessToken);
        String head=q(headKey), composerProof=q(composerKey), sig=q(stateSignature);
        return "(()=>{const result=(status,detail='',extra={})=>JSON.stringify({status,detail,url:location.href,...extra});"
                + conversationGuard(q(SelfRunScript.conversationId(conversationUrl))) + authGuard() + calibration()
                + textHelpers(expected) + composer(composerKey(conversationUrl))
                + "if(!composer)return result('UI_WAIT','continuation main composer unavailable');"
                + proofGuard(token,head,composerProof,sig,true) + actionOps(sendKey(conversationUrl))
                + "if(!__srProofOk)return result('FRESHNESS_STALE','prepare proof mismatch');"
                + "let a=classifyAction();"
                + "if(a.action==='STOP')return result('RESPONSE_ACTIVE','STOP before input',{action:a.action,stopCount:a.stopCount,sendCount:a.sendCount});"
                + "if(a.action!=='SEND')return result('ACTION_UNKNOWN','SEND unknown before input',{action:a.action,stopCount:a.stopCount,sendCount:a.sendCount});"
                + "const persistPrepared=()=>{const markerKey2="+marker+",v=JSON.stringify({state:'prepared',at:Date.now(),freshnessToken:"+token+"});let persisted=false;try{localStorage.setItem(markerKey2,v);persisted=localStorage.getItem(markerKey2)===v;}catch(_){}if(!persisted){try{sessionStorage.setItem(markerKey2,v);persisted=sessionStorage.getItem(markerKey2)===v;}catch(_){}}if(!persisted)return false;window.__selfRunDrivePreparedContinuation={markerKey:markerKey2,composer,freshnessToken:"+token+",head:"+head+",composerKey:"+composerProof+",sig:"+sig+",clicked:false};return true;};"
                + "if(same()){if(!persistPrepared())return result('MARKER_FAILED','continuation marker persist failed');return result('READY_TO_SUBMIT','continuation prepared',{action:'SEND'});}"
                + input()
                + "const latest=__srLatestComposer();if(!composer.isConnected||latest!==composer)return result('FRESHNESS_STALE','composer replaced during input');"
                + "const probeAfter=globalThis.__selfRunDriveSyncProbeV2;if(!probeAfter||probeAfter.currentComposer!==composer)return result('FRESHNESS_STALE','probe composer changed during input');"
                + "a=classifyAction();"
                + "if(a.action==='STOP')return result('RESPONSE_ACTIVE_AFTER_INPUT','STOP after input',{action:a.action,stopCount:a.stopCount,sendCount:a.sendCount});"
                + "if(a.action!=='SEND')return result('ACTION_UNKNOWN','SEND unknown after input',{action:a.action,stopCount:a.stopCount,sendCount:a.sendCount});"
                + "if(!same())return result('UI_WAIT','input reflection pending',{action:'SEND'});"
                + "if(!persistPrepared())return result('MARKER_FAILED','continuation marker persist failed');"
                + "return result('READY_TO_SUBMIT','continuation prepared after input',{action:'SEND'});})()";
    }

    static String clickPreparedDriveTurn(String conversationUrl, String prompt, String markerId,
                                         String freshnessToken, String headKey,
                                         String composerKey, String stateSignature) {
        String expected=q(prompt), marker=q("selfrun-drive:command:"+markerId), token=q(freshnessToken);
        String head=q(headKey), composerProof=q(composerKey), sig=q(stateSignature);
        return "(()=>{const result=(status,detail='',extra={})=>JSON.stringify({status,detail,url:location.href,...extra});"
                + conversationGuard(q(SelfRunScript.conversationId(conversationUrl))) + authGuard() + calibration()
                + textHelpers(expected) + durableMarkerRead(marker) + composer(composerKey(conversationUrl))
                + "if(!prior)return result('MARKER_FAILED','prepared marker missing');"
                + "if(!composer)return result('SUBMIT_BLOCKED_FRESHNESS','current composer missing');"
                + proofGuard(token,head,composerProof,sig,true) + actionOps(sendKey(conversationUrl))
                + "if(!__srProofOk)return result('SUBMIT_BLOCKED_FRESHNESS','final proof mismatch');"
                + "let priorData=null;try{priorData=JSON.parse(prior);}catch(_){}if(!priorData||priorData.freshnessToken!=="+token+")return result('SUBMIT_BLOCKED_FRESHNESS','marker proof mismatch');"
                + "const prepared=window.__selfRunDrivePreparedContinuation;"
                + "if(!prepared||prepared.markerKey!==markerKey||prepared.composer!==composer||prepared.freshnessToken!=="+token+"||prepared.head!=="+head+"||prepared.composerKey!=="+composerProof+"||prepared.sig!=="+sig+"){window.__selfRunDrivePreparedContinuation=null;return result('SUBMIT_BLOCKED_FRESHNESS','prepared identity changed');}"
                + "if(prepared.clicked)return result('SUBMISSION_PENDING','continuation click already recorded');"
                + "if(!same())return result('SUBMIT_BLOCKED_FRESHNESS','prompt is not exact');"
                + "const a=classifyAction();"
                + "if(a.action==='STOP')return result('SUBMIT_BLOCKED_STOP','STOP at final click',{action:a.action,stopCount:a.stopCount,sendCount:a.sendCount});"
                + "if(a.action!=='SEND'||!a.send)return result('SUBMIT_BLOCKED_ACTION','explicit SEND missing',{action:a.action,stopCount:a.stopCount,sendCount:a.sendCount});"
                + "if(a.send.disabled||a.send.getAttribute('aria-disabled')==='true')return result('SUBMIT_BLOCKED_ACTION','SEND disabled',{action:'UNKNOWN'});"
                + "try{const data=priorData;data.state='clicked';data.clickedAt=Date.now();localStorage.setItem(markerKey,JSON.stringify(data));}catch(_){}"
                + "prepared.clicked=true;a.send.click();return result('SUBMITTED','continuation click complete',{action:'SEND'});})()";
    }

    private static String proofGuard(String token,String head,String composerKey,String sig,boolean requireBoundToken) {
        String tokenCheck=requireBoundToken?"&&window.__selfRunDriveFreshnessToken===__srProofToken":"";
        return "const __srProofToken="+token+",__srExpectedHead="+head+",__srExpectedComposer="+composerKey+",__srExpectedSig="+sig+";"
                + "const __srProbe=globalThis.__selfRunDriveSyncProbeV2,__srCurrent=__srProbe?.current;"
                + "const __srProofOk=!!__srProofToken"+tokenCheck+"&&!!__srProbe&&!!__srCurrent&&__srCurrent.head===__srExpectedHead&&__srCurrent.composer===__srExpectedComposer&&__srCurrent.sig===__srExpectedSig&&__srProbe.currentComposer===composer;";
    }

    private static String actionOps(String sendKey) {
        return "const __srActionNorm=s=>String(s??'').replace(/\\s+/g,' ').trim().toLowerCase();"
                + "const __srActionMeta=e=>__srActionNorm([e?.dataset?.testid,e?.getAttribute?.('aria-label'),e?.getAttribute?.('title'),e?.innerText,e?.textContent,e?.getAttribute?.('role')].filter(Boolean).join(' '));"
                + "const __srStop=e=>{const m=__srActionMeta(e),tid=__srActionNorm(e?.dataset?.testid);return /stop|cancel[-_ ]?(response|generation)|생성[ _-]?중지|응답[ _-]?중지|생성중지|중단/.test(tid+' '+m);};"
                + "const __srSend=e=>{if(__srStop(e))return false;const tid=__srActionNorm(e?.dataset?.testid),aria=__srActionNorm(e?.getAttribute?.('aria-label')),title=__srActionNorm(e?.getAttribute?.('title')),text=__srActionNorm(e?.innerText||e?.textContent);return tid==='send-button'||tid==='composer-submit-button'||/^(send|submit|send message|보내기|전송|메시지 보내기)$/.test(aria)||/^(send|submit|send message|보내기|전송|메시지 보내기)$/.test(title)||/^(send|submit|보내기|전송)$/.test(text);};"
                + "const classifyAction=()=>{const scope=__srComposerScope(composer);if(!scope||__srTurnContained(scope)||__srEditContext(scope))return{action:'UNKNOWN',send:null,stopCount:0,sendCount:0};const nodes=[...scope.querySelectorAll('button,[role=\"button\"]')].filter(e=>e&&e.isConnected&&e.offsetParent!==null);const calibrated=__srFind("+q(sendKey)+");if(calibrated&&calibrated.isConnected&&calibrated.offsetParent!==null&&scope.contains(calibrated))nodes.push(calibrated);const xs=[...new Set(nodes)],stops=xs.filter(__srStop),sends=xs.filter(__srSend);if(stops.length)return{action:'STOP',send:null,stopCount:stops.length,sendCount:sends.length};if(sends.length!==1)return{action:'UNKNOWN',send:null,stopCount:0,sendCount:sends.length};return{action:'SEND',send:sends[0],stopCount:0,sendCount:1};};";
    }

    private static String conversationGuard(String conversation) {
        return "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','host mismatch');const p=location.pathname.split('/').filter(Boolean);const afterConversation=k=>{const i=p.indexOf(k);return i>=0&&i+1<p.length?p[i+1]:''};if(afterConversation('c')!=="+conversation+")return result('TARGET_ERROR','canonical conversation mismatch');";
    }

    private static String authGuard() {
        return "const authVisible=e=>!!e&&e.isConnected&&e.offsetParent!==null;const auth=[...document.querySelectorAll('[data-testid*=login],a[href*=\"/auth/login\"],button')].filter(authVisible).some(e=>/^(log in|sign up|로그인|가입)$/i.test(String(e.innerText||e.getAttribute('aria-label')||'').trim()));if(auth)return result('AUTH_REQUIRED','ChatGPT login required');";
    }

    private static String calibration() { return WebUiCalibrationDom.runtimePrelude(); }

    private static String textHelpers(String expected) {
        return "const norm=s=>String(s??'').replace(/[\\u200B-\\u200D\\uFEFF]/g,'').replace(/\\u00a0/g,' ').replace(/\\r\\n?/g,'\\n').trim();const canonical=s=>norm(s).replace(/[ \\t]+/g,' ').replace(/ *\\n+ */g,'\\n');const expected=norm("+expected+");";
    }

    private static String composer(String targetKey) {
        return "const __srComposerSelector='textarea#prompt-textarea,textarea[data-testid=\"prompt-textarea\"],div#prompt-textarea[contenteditable=\"true\"],main form [contenteditable=\"true\"][data-lexical-editor=\"true\"],main form [contenteditable=\"true\"]';const __srTurnContained=e=>!!e&&!!e.closest('[data-message-author-role],[data-testid^=\"conversation-turn\"],article[data-testid^=\"conversation-turn\"]');const __srEditContext=e=>{for(let n=e;n;n=n.parentElement){const tid=String(n.dataset?.testid||'').toLowerCase(),cls=String(n.className||'').toLowerCase(),meta=String((n.getAttribute?.('aria-label')||'')+' '+(n.getAttribute?.('title')||'')).toLowerCase(),role=String(n.getAttribute?.('role')||'').toLowerCase();if(role==='dialog'||tid==='edit'||tid.startsWith('edit-')||tid.includes('message-edit')||tid.includes('edit-message')||tid.includes('composer-edit')||tid.includes('edit-composer')||cls.includes('message-edit')||cls.includes('edit-message')||cls.includes('composer-edit')||cls.includes('edit-composer')||meta.includes('edit message')||meta.includes('수정')||meta.includes('편집'))return true;}return false;};const __srComposerScope=e=>e?(e.closest('form')||e.closest('[data-testid*=\"composer\"]')||e.parentElement):null;const __srMainComposer=e=>{if(!e||!e.isConnected||e.offsetParent===null||__srTurnContained(e)||__srEditContext(e))return false;const scope=__srComposerScope(e);if(!scope||__srTurnContained(scope)||__srEditContext(scope))return false;const inMain=!!e.closest('main'),scopeTest=String(scope.dataset?.testid||'').toLowerCase(),strong=e.id==='prompt-textarea'||e.dataset?.testid==='prompt-textarea'||e.getAttribute?.('data-lexical-editor')==='true';return inMain||(strong&&/composer|prompt/.test(scopeTest));};const __srComposerPool=()=>[...document.querySelectorAll(__srComposerSelector)].filter(__srMainComposer);const __srLatestComposer=()=>{const xs=__srComposerPool();return xs.length?xs[xs.length-1]:null;};const calibratedComposer=__srFind("+q(targetKey)+");const safeCalibratedComposer=__srMainComposer(calibratedComposer)?calibratedComposer:null;let composer=__srLatestComposer()||safeCalibratedComposer;";
    }

    private static String input() {
        return "composer.focus();if('value'in composer){const p=Object.getPrototypeOf(composer),own=Object.getOwnPropertyDescriptor(p,'value'),base=typeof HTMLTextAreaElement!=='undefined'?Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value'):null,setter=own?.set||base?.set;if(setter)setter.call(composer,expected);else composer.value=expected;composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));composer.dispatchEvent(new Event('change',{bubbles:true}));}else{const sel=window.getSelection(),range=document.createRange();range.selectNodeContents(composer);sel.removeAllRanges();sel.addRange(range);try{document.execCommand('delete',false,null);document.execCommand('insertText',false,expected);}catch(_){composer.textContent=expected;composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));}}";
    }

    private static String durableMarkerRead(String marker) {
        return "const markerKey="+marker+";let prior='';try{prior=localStorage.getItem(markerKey)||sessionStorage.getItem(markerKey)||'';}catch(_){}";
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
