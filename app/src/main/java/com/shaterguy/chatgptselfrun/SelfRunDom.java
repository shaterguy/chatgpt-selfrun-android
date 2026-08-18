package com.shaterguy.chatgptselfrun;

/** Drive runtime DOM adapter. It manipulates ChatGPT UI controls but never observes assistant responses. */
final class SelfRunDom {
    private SelfRunDom() {}

    static String prepareInitialContext(String projectUrl, String mode, String runId) {
        String projectId = SelfRunScript.projectId(projectUrl);
        String project = q(projectId);
        boolean work = SelfRunStore.MODE_WORK.equals(mode);
        boolean general = SelfRunScript.GENERAL_CHAT_SCOPE.equals(projectId);
        String requested = work ? "work" : "chat";
        String newChatTarget = general ? WebUiCalibrationStore.PURPOSE_GENERAL_NEW_CHAT : WebUiCalibrationStore.PURPOSE_PROJECT_NEW_CHAT;
        String composerTarget = general ? WebUiCalibrationStore.TARGET_GENERAL_COMPOSER : WebUiCalibrationStore.TARGET_PROJECT_COMPOSER;
        return "(() =>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,url:location.href,diagnostics});"
                + projectGuard(project) + authGuard() + calibration()
                + "const parts=location.pathname.split('/').filter(Boolean),after=k=>{const i=parts.indexOf(k);return i>=0&&i+1<parts.length?parts[i+1]:''};const actualConversation=after('c');"
                + "const fallbackNewChat=[...document.querySelectorAll('button,a,[role=\"button\"]')].filter(__srVisible).find(e=>/^(new chat|new conversation|새 채팅|새 대화)$/i.test(String(e.innerText||e.textContent||e.getAttribute?.('aria-label')||'').replace(/\\s+/g,' ').trim()));const newChatControl=__srFind(" + q(newChatTarget) + ")||fallbackNewChat;"
                + "const newChatKey='selfrun-drive:new-chat:" + esc(runId) + "';let newChatAt=0;try{const raw=sessionStorage.getItem(newChatKey)||'';newChatAt=Number(raw?JSON.parse(raw)?.at||0:0);}catch(_){}const recentNewChat=newChatAt>0&&Date.now()-newChatAt<1500;"
                + "if(actualConversation){if(newChatControl&&!recentNewChat){try{sessionStorage.setItem(newChatKey,JSON.stringify({at:Date.now()}));}catch(_){}newChatControl.focus?.();newChatControl.click();return result('UI_WAIT','보정된 새 대화 전환 반영 대기',{actualConversation,newChatSource:__srFind(" + q(newChatTarget) + ")?'calibrated':'heuristic'});}return result('EXISTING_CONVERSATION','새 대화 화면 대신 기존 conversation이 열렸습니다.',{actualConversation,newChatControl:!!newChatControl,recentNewChat});}"
                + composer(composerTarget)
                + "const requestedMode=" + q(requested) + ";const forbiddenMode=/new chat|새 채팅|새 대화|new conversation/i;"
                + "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;const exactText=s=>String(s??'').replace(/\\s+/g,' ').trim().toLowerCase();"
                + "const labelOf=e=>exactText(e?.innerText||'')||exactText(e?.getAttribute?.('aria-label')||'');"
                + "const selectedState=e=>!!e&&(e.getAttribute('aria-checked')==='true'||e.getAttribute('aria-pressed')==='true'||e.getAttribute('aria-selected')==='true'||(typeof e.checked==='boolean'&&e.checked)||e.dataset?.active==='true'||e.dataset?.selected==='true'||/^(checked|selected|active|on)$/.test(exactText(e.dataset?.state||'')));"
                + "const modeOf=s=>{const v=exactText(s);if(forbiddenMode.test(v))return'';const tokens=v.split(/[^a-z0-9가-힣]+/).filter(Boolean);if(tokens.includes('chat')||tokens.includes('채팅'))return'chat';if(tokens.includes('work')||tokens.includes('작업'))return'work';return''};"
                + "const rawModeControls=[...document.querySelectorAll('button,[role=\"button\"],[role=\"radio\"],[role=\"tab\"],input[type=\"radio\"]')].filter(visible).filter(e=>{if(e.closest('[role=\"menu\"],[role=\"listbox\"]'))return false;const m=modeOf(labelOf(e));if(!m)return false;const role=e.getAttribute('role')||'';const testId=exactText(e.dataset?.testid||'');return e.hasAttribute('aria-pressed')||e.hasAttribute('aria-checked')||e.hasAttribute('aria-selected')||role==='radio'||role==='tab'||e.matches('input[type=\"radio\"]')||/mode|experience/.test(testId)||e.tagName==='BUTTON';});"
                + "const groups=[];for(const e of rawModeControls){let p=e.parentElement;for(let depth=0;p&&depth<4;depth++,p=p.parentElement){if(!groups.includes(p))groups.push(p);}}const modeGroup=groups.find(g=>{const inside=rawModeControls.filter(e=>g.contains(e));return inside.some(e=>modeOf(labelOf(e))==='chat')&&inside.some(e=>modeOf(labelOf(e))==='work');})||null;"
                + "const modeControls=modeGroup?rawModeControls.filter(e=>modeGroup.contains(e)):[];const chatControl=modeControls.find(e=>modeOf(labelOf(e))==='chat')||null;const workControl=modeControls.find(e=>modeOf(labelOf(e))==='work')||null;const calibratedKey=requestedMode==='work'?" + q(WebUiCalibrationStore.PURPOSE_MODE_WORK) + ":" + q(WebUiCalibrationStore.PURPOSE_MODE_CHAT) + ";const calibratedTarget=__srFind(calibratedKey);const heuristicTarget=requestedMode==='work'?workControl:chatControl;const target=calibratedTarget||heuristicTarget;const targetSource=calibratedTarget?'calibrated':'heuristic';const targetFound=!!target;const targetSelected=selectedState(target);"
                + "const selectedModes=[...new Set(modeControls.filter(selectedState).map(e=>modeOf(labelOf(e))).filter(Boolean))];const currentMode=selectedModes.length===1?selectedModes[0]:(selectedModes.length>1?'ambiguous':'unknown');"
                + "const modeKey='chatgpt-selfrun:mode:" + esc(runId) + "';let priorAt=0,priorAction='',priorRequested='';try{const raw=sessionStorage.getItem(modeKey)||'';const parsed=raw?JSON.parse(raw):null;priorAt=Number(parsed?.at||0);priorAction=String(parsed?.action||'');priorRequested=String(parsed?.requested||'');}catch(_){}const retryIntervalMs=1200;const recentClick=priorAt>0&&Date.now()-priorAt<retryIntervalMs;"
                + "let action='';const calibratedImplicit=priorAction==='select-mode-calibrated'&&priorRequested===requestedMode&&priorAt>0&&Date.now()-priorAt<5000&&!!composer;const heuristicReadback=targetFound&&targetSelected&&currentMode===requestedMode&&selectedModes.length===1;let modeReadback=calibratedImplicit||(targetSource==='calibrated'?targetSelected:heuristicReadback);if(!modeReadback&&targetFound&&!recentClick){action=targetSource==='calibrated'?'select-mode-calibrated':'select-mode';try{sessionStorage.setItem(modeKey,JSON.stringify({at:Date.now(),action,requested:requestedMode}));}catch(_){}target.focus?.();target.click();modeReadback=false;}"
                + "const diagnostics={requested:requestedMode,currentMode,modeCandidates:rawModeControls.length,groupFound:!!modeGroup,targetFound,targetSelected,targetSource,selectedModes,recentClick,action,calibratedImplicit,composer:!!composer,finalReadback:modeReadback};const modeDiag=()=>('requested='+requestedMode+';source='+targetSource+';current='+currentMode+';targetFound='+(targetFound?1:0)+';targetSelected='+(targetSelected?1:0)+';attempt='+(action||'none')+';readback='+(modeReadback?1:0));"
                + "if(action)return result('UI_WAIT','모드 전환 반영 대기 · '+modeDiag(),diagnostics);if(!modeReadback)return result('UI_WAIT','실행 모드 실제 상태 대기 · '+modeDiag(),diagnostics);try{sessionStorage.removeItem(modeKey);}catch(_){}"
                + "if(!composer)return result('UI_WAIT','새 대화 입력창 대기 · '+modeDiag(),diagnostics);"
                + "return result('READY','새 대화 화면 확인 · '+modeDiag(),{...diagnostics,composer:true});})()";
    }

    /** Stages Drive V1 bootstrap without clicking. */
    static String sendDriveInitial(String projectUrl,String prompt,String markerId){String project=q(SelfRunScript.projectId(projectUrl)),expected=q(prompt),marker=q("selfrun-drive:bootstrap:"+markerId),composerKey=composerKey(projectUrl),sendKey=sendKey(projectUrl);return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"+projectGuard(project)+authGuard()+calibration()+textHelpers(expected)+composer(composerKey)+"if(!composer)return result('UI_WAIT','입력창 대기');"+composerOps(sendKey)+"if(same()){const send=findSend();if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return result('UI_WAIT','전송 버튼 대기');const markerKey2="+marker+",v=JSON.stringify({state:'prepared',at:Date.now()});let persisted=false;try{localStorage.setItem(markerKey2,v);persisted=localStorage.getItem(markerKey2)===v;}catch(_){}if(!persisted){try{sessionStorage.setItem(markerKey2,v);persisted=sessionStorage.getItem(markerKey2)===v;}catch(_){}}if(!persisted)return result('MARKER_FAILED','bootstrap 제출 표식 저장 실패');return result('READY_TO_SUBMIT','첫 요청 제출 준비 완료');}"+input()+"return result('UI_WAIT',same()?'입력 반영 확인 대기':'첫 요청 입력 대기');})()";}

    /** Clicks Drive V1 bootstrap once, after Android durably marks submission started. */
    static String clickPreparedDriveInitial(String projectUrl,String prompt,String markerId){String project=q(SelfRunScript.projectId(projectUrl)),expected=q(prompt),marker=q("selfrun-drive:bootstrap:"+markerId),composerKey=composerKey(projectUrl),sendKey=sendKey(projectUrl);return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"+projectGuard(project)+authGuard()+calibration()+textHelpers(expected)+durableMarkerRead(marker)+"if(!prior)return result('MARKER_FAILED','bootstrap 제출 준비 표식 없음');"+composer(composerKey)+"if(!composer)return result('UI_WAIT','제출 직전 최신 입력창 대기');"+composerOps(sendKey)+"if(!same()){ "+input()+"return result('UI_WAIT','제출 직전 최신 입력창 재확보 · 입력 재반영');}const send=findSend();if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return result('UI_WAIT','제출 직전 최신 전송 버튼 대기');const markerKey2="+marker+";try{const data=JSON.parse(prior);data.state='clicked';data.clickedAt=Date.now();localStorage.setItem(markerKey2,JSON.stringify(data));}catch(_){}send.click();return result('BOOTSTRAP_SUBMITTED','첫 요청 클릭 완료');})()";}

    /** Stage the continuation line while keeping the Drive commit ID internal. */
    static String prepareDriveTurn(String conversationUrl,String prompt,String markerId){String expected=q(prompt),marker=q("selfrun-drive:command:"+markerId),composerKey=composerKey(conversationUrl),sendKey=sendKey(conversationUrl);return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"+conversationGuard(q(SelfRunScript.conversationId(conversationUrl)))+authGuard()+calibration()+textHelpers(expected)+composer(composerKey)+"if(!composer)return result('UI_WAIT','continuation 입력창 대기');"+composerOps(sendKey)+"if(same()){const send=findSend();if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return result('UI_WAIT','continuation 전송 버튼 대기');const markerKey2="+marker+",v=JSON.stringify({state:'prepared',at:Date.now()});let persisted=false;try{localStorage.setItem(markerKey2,v);persisted=localStorage.getItem(markerKey2)===v;}catch(_){}if(!persisted){try{sessionStorage.setItem(markerKey2,v);persisted=sessionStorage.getItem(markerKey2)===v;}catch(_){}}if(!persisted)return result('MARKER_FAILED','continuation 제출 표식 저장 실패');return result('READY_TO_SUBMIT','continuation 제출 준비 완료');}"+input()+"return result('UI_WAIT',same()?'입력 반영 확인 대기':'continuation 입력 대기');})()";}

    /** Clicks at most once per attempt, only after Android durably stores the baseline and SUBMISSION_STARTED. */
    static String clickPreparedDriveTurn(String conversationUrl,String prompt,String markerId){String expected=q(prompt),marker=q("selfrun-drive:command:"+markerId),composerKey=composerKey(conversationUrl),sendKey=sendKey(conversationUrl);return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"+conversationGuard(q(SelfRunScript.conversationId(conversationUrl)))+authGuard()+calibration()+textHelpers(expected)+durableMarkerRead(marker)+"if(!prior)return result('MARKER_FAILED','continuation 제출 준비 표식 없음');"+composer(composerKey)+"if(!composer)return result('UI_WAIT','제출 직전 최신 continuation 입력창 대기');"+composerOps(sendKey)+"if(!same()){ "+input()+"return result('UI_WAIT','제출 직전 최신 continuation 입력창 재확보 · 입력 재반영');}const send=findSend();if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return result('UI_WAIT','제출 직전 최신 continuation 전송 버튼 대기');const markerKey2="+marker+";try{const data=JSON.parse(prior);data.state='clicked';data.clickedAt=Date.now();localStorage.setItem(markerKey2,JSON.stringify(data));}catch(_){}send.click();return result('SUBMITTED','continuation 클릭 완료');})()";}

    private static String projectGuard(String project) {
        String general = q(SelfRunScript.GENERAL_CHAT_SCOPE);
        return "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','호스트 불일치');const p=location.pathname.split('/').filter(Boolean);const afterProject=k=>{const i=p.indexOf(k);return i>=0&&i+1<p.length?p[i+1]:''};const expectedProject=" + project + ";const actualProject=afterProject('g');"
                + "if(expectedProject===" + general + "){const generalNew=p.length===0;const generalConversation=p.length===2&&p[0]==='c'&&!!p[1];if(!generalNew&&!generalConversation)return result('TARGET_ERROR','일반 Chat 범위 이탈');}else if(actualProject!==expectedProject)return result('TARGET_ERROR','프로젝트 불일치');";
    }

    private static String conversationGuard(String conversation) {
        return "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','호스트 불일치');const p=location.pathname.split('/').filter(Boolean);const afterConversation=k=>{const i=p.indexOf(k);return i>=0&&i+1<p.length?p[i+1]:''};if(afterConversation('c')!==" + conversation + ")return result('TARGET_ERROR','canonical conversation 이탈');";
    }

    private static String authGuard() {
        return "const authVisible=e=>!!e&&e.isConnected&&e.offsetParent!==null;const auth=[...document.querySelectorAll('[data-testid*=login],a[href*=\"/auth/login\"],button')].filter(authVisible).some(e=>/^(log in|sign up|로그인|가입)$/i.test(String(e.innerText||e.getAttribute('aria-label')||'').trim()));if(auth)return result('AUTH_REQUIRED','ChatGPT 로그인이 필요합니다.');";
    }

    private static String calibration() { return WebUiCalibrationDom.runtimePrelude(); }

    private static String textHelpers(String expected) {
        return "const norm=s=>String(s??'').replace(/[\\u200B-\\u200D\\uFEFF]/g,'').replace(/\\u00a0/g,' ').replace(/\\r\\n?/g,'\\n').trim();const canonical=s=>norm(s).replace(/[ \\t]+/g,' ').replace(/ *\\n+ */g,'\\n');const expected=norm(" + expected + ");";
    }

    private static String composer(String targetKey) {
        return "const __srComposerSelector='textarea#prompt-textarea,textarea[data-testid=\"prompt-textarea\"],div#prompt-textarea[contenteditable=\"true\"],main form [contenteditable=\"true\"][data-lexical-editor=\"true\"],main form [contenteditable=\"true\"]';const __srTurnContained=e=>!!e&&!!e.closest('[data-message-author-role],[data-testid^=\"conversation-turn\"],article[data-testid^=\"conversation-turn\"]');const __srComposerPool=()=>[...document.querySelectorAll(__srComposerSelector)].filter(e=>e&&e.isConnected&&e.offsetParent!==null&&!__srTurnContained(e));const __srLatestComposer=()=>{const xs=__srComposerPool();return xs.length?xs[xs.length-1]:null;};const calibratedComposer=__srFind(" + q(targetKey) + ");const safeCalibratedComposer=calibratedComposer&&!__srTurnContained(calibratedComposer)?calibratedComposer:null;let composer=__srLatestComposer()||safeCalibratedComposer;";
    }

    private static String composerOps(String sendKey) {
        return "composer.focus();const raw=()=>('value'in composer?composer.value:(composer.innerText||composer.textContent||''));const same=()=>canonical(raw())===canonical(expected);const findSend=()=>{const scope=composer.closest('form')||composer.closest('[data-testid*=\"composer\"]')||composer.parentElement;if(!scope)return null;const calibrated=__srFind(" + q(sendKey) + ");if(calibrated&&calibrated.isConnected&&calibrated.offsetParent!==null&&scope.contains(calibrated))return calibrated;const buttons=[...scope.querySelectorAll('button')].filter(b=>b&&b.isConnected&&b.offsetParent!==null&&(b.dataset.testid==='send-button'||b.dataset.testid==='composer-submit-button'||/send|보내기|submit/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||''))));return buttons.length?buttons[buttons.length-1]:null;};";
    }

    private static String input() {
        return "composer.focus();if('value'in composer){const p=Object.getPrototypeOf(composer),own=Object.getOwnPropertyDescriptor(p,'value'),base=typeof HTMLTextAreaElement!=='undefined'?Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value'):null,setter=own?.set||base?.set;if(setter)setter.call(composer,expected);else composer.value=expected;composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));composer.dispatchEvent(new Event('change',{bubbles:true}));}else{const sel=window.getSelection(),range=document.createRange();range.selectNodeContents(composer);sel.removeAllRanges();sel.addRange(range);try{document.execCommand('delete',false,null);document.execCommand('insertText',false,expected);}catch(_){composer.textContent=expected;composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));}}";
    }

    private static String durableMarkerRead(String marker) {
        return "const markerKey=" + marker + ";let prior='';try{prior=localStorage.getItem(markerKey)||sessionStorage.getItem(markerKey)||'';}catch(_){}";
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
    private static String esc(String value) { return value.replace("\\", "\\\\").replace("'", "\\'"); }
}
