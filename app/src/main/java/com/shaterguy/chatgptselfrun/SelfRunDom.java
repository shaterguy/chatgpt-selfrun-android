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
        String chatReasoning = work ? ChatReasoningPreferenceStore.KEEP
                : ChatReasoningPreferenceStore.selectionForRun(runId);
        String newChatTarget = general ? WebUiCalibrationStore.PURPOSE_GENERAL_NEW_CHAT : WebUiCalibrationStore.PURPOSE_PROJECT_NEW_CHAT;
        String composerTarget = general ? WebUiCalibrationStore.TARGET_GENERAL_COMPOSER : WebUiCalibrationStore.TARGET_PROJECT_COMPOSER;
        return "(() =>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,url:location.href,diagnostics});"
                + projectGuard(project) + authGuard() + calibration()
                + "const parts=location.pathname.split('/').filter(Boolean),after=k=>{const i=parts.indexOf(k);return i>=0&&i+1<parts.length?parts[i+1]:''};const actualConversation=after('c');"
                + "const fallbackNewChat=[...document.querySelectorAll('button,a,[role=\"button\"]')].filter(__srVisible).find(e=>/^(new chat|new conversation|새 채팅|새 대화)$/i.test(String(e.innerText||e.textContent||e.getAttribute?.('aria-label')||'').replace(/\\s+/g,' ').trim()));const newChatControl=__srFind(" + q(newChatTarget) + ")||fallbackNewChat;"
                + "const newChatKey='selfrun-drive:new-chat:" + esc(runId) + "';const newChatNow=Date.now(),newChatRetryMs=1800,newChatFailureMs=10000;let newChatState={startedAt:0,lastClickAt:0,clicks:0};try{const raw=sessionStorage.getItem(newChatKey)||localStorage.getItem(newChatKey)||'';if(raw)newChatState={...newChatState,...JSON.parse(raw)};}catch(_){}if(!(Number(newChatState.startedAt)>0))newChatState.startedAt=newChatNow;const newChatElapsed=Math.max(0,newChatNow-Number(newChatState.startedAt||newChatNow)),recentNewChat=Number(newChatState.lastClickAt)>0&&newChatNow-Number(newChatState.lastClickAt)<newChatRetryMs;const saveNewChat=()=>{const value=JSON.stringify(newChatState);try{sessionStorage.setItem(newChatKey,value);}catch(_){}try{localStorage.setItem(newChatKey,value);}catch(_){}};const clearNewChat=()=>{try{sessionStorage.removeItem(newChatKey);}catch(_){}try{localStorage.removeItem(newChatKey);}catch(_){}};"
                + "if(actualConversation){if(newChatControl&&!recentNewChat&&Number(newChatState.clicks)<2){newChatState.clicks=Math.max(0,Number(newChatState.clicks)||0)+1;newChatState.lastClickAt=newChatNow;saveNewChat();newChatControl.focus?.();newChatControl.click();return result('UI_WAIT','보정된 새 대화 전환 반영 대기',{actualConversation,newChatSource:__srFind(" + q(newChatTarget) + ")?'calibrated':'heuristic',newChatClicks:newChatState.clicks,newChatElapsedMs:newChatElapsed});}saveNewChat();if(newChatElapsed>=newChatFailureMs||(Number(newChatState.clicks)>=2&&Number(newChatState.lastClickAt)>0&&newChatNow-Number(newChatState.lastClickAt)>=2500))return result('CHAT_BOOTSTRAP_NEW_CHAT_FAILED','새 대화 화면 전환을 제한시간 안에 확인하지 못했습니다.',{actualConversation,newChatControl:!!newChatControl,recentNewChat,newChatClicks:newChatState.clicks,newChatElapsedMs:newChatElapsed});return result('UI_WAIT','새 대화 화면 전환 확인 대기',{actualConversation,newChatControl:!!newChatControl,recentNewChat,newChatClicks:newChatState.clicks,newChatElapsedMs:newChatElapsed});}clearNewChat();"
                + composer(composerTarget)
                + BootstrapModeDom.inline(requested, runId)
                + ChatReasoningOptionDom.inline(chatReasoning, runId)
                + "return result('READY','새 대화 화면 확인 · '+modeDiag(),{...diagnostics,composer:true,chatReasoning:" + q(ChatReasoningPreferenceStore.label(chatReasoning)) + "});})()";
    }

    /** Stage the continuation line while keeping the Drive commit ID internal. */
    static String prepareDriveTurn(String conversationUrl,String prompt,String markerId){String expected=q(prompt),comparable=q(continuationComparablePrompt(prompt)),marker=q("selfrun-drive:command:"+markerId),inputMarker=q("selfrun-drive:input:"+markerId),composerKey=composerKey(conversationUrl),sendKey=sendKey(conversationUrl);return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"+conversationGuard(q(SelfRunScript.conversationId(conversationUrl)))+authGuard()+calibration()+textHelpers(expected)+composer(composerKey)+"if(!composer)return result('UI_WAIT','continuation 입력창 대기');"+composerOps(sendKey)+continuationEquivalence(comparable)+guardedContinuationInput(inputMarker)+"const send=findSend();if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return result('UI_WAIT','continuation 전송 버튼 대기');const markerKey2="+marker+",v=JSON.stringify({state:'prepared',at:Date.now()});let persisted=false;try{localStorage.setItem(markerKey2,v);persisted=localStorage.getItem(markerKey2)===v;}catch(_){}if(!persisted){try{sessionStorage.setItem(markerKey2,v);persisted=sessionStorage.getItem(markerKey2)===v;}catch(_){}}if(!persisted)return result('MARKER_FAILED','continuation 제출 표식 저장 실패');return result('READY_TO_SUBMIT','continuation 제출 준비 완료');})()";}

    /** Clicks at most once per attempt, only after Android durably stores the baseline and SUBMISSION_STARTED. */
    static String clickPreparedDriveTurn(String conversationUrl,String prompt,String markerId){String expected=q(prompt),comparable=q(continuationComparablePrompt(prompt)),marker=q("selfrun-drive:command:"+markerId),composerKey=composerKey(conversationUrl),sendKey=sendKey(conversationUrl);return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"+conversationGuard(q(SelfRunScript.conversationId(conversationUrl)))+authGuard()+calibration()+textHelpers(expected)+durableMarkerRead(marker)+"if(!prior)return result('MARKER_FAILED','continuation 제출 준비 표식 없음');"+composer(composerKey)+"if(!composer)return result('SUBMISSION_AMBIGUOUS','제출 시점 입력창 소실');"+composerOps(sendKey)+continuationEquivalence(comparable)+"if(!acceptable())return result('SUBMISSION_AMBIGUOUS','제출 시점 입력 내용 변경');const send=findSend();if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return result('SUBMISSION_AMBIGUOUS','전송 버튼 사용 불가');const markerKey2="+marker+";try{const data=JSON.parse(prior);data.state='clicked';data.clickedAt=Date.now();localStorage.setItem(markerKey2,JSON.stringify(data));}catch(_){}send.click();return result('SUBMITTED','continuation 클릭 완료');})()";}

    static String continuationComparablePrompt(String prompt) {
        if (prompt == null) return "";
        return prompt.trim().replaceFirst("^\\[\\d{4}\\.\\d{2}\\.\\d{2} \\| \\d{2}:\\d{2}:\\d{2}\\] (?=\\[SELF_RUN_CONTINUE )", "");
    }

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
        return "let composer=__srFind(" + q(targetKey) + ");const selectors=['textarea#prompt-textarea','textarea[data-testid=\"prompt-textarea\"]','div#prompt-textarea[contenteditable=\"true\"]','main form [contenteditable=\"true\"][data-lexical-editor=\"true\"]','main form [contenteditable=\"true\"]'];if(!composer){for(const s of selectors){composer=[...document.querySelectorAll(s)].find(e=>e&&e.isConnected&&e.offsetParent!==null);if(composer)break;}}";
    }

    private static String composerOps(String sendKey) {
        return "composer.focus();const raw=()=>('value'in composer?composer.value:(composer.innerText||composer.textContent||''));const same=()=>canonical(raw())===canonical(expected);const findSend=()=>{const calibrated=__srFind(" + q(sendKey) + ");if(calibrated)return calibrated;const scope=composer.closest('form')||document;return [...scope.querySelectorAll('button')].find(b=>b.dataset.testid==='send-button'||b.dataset.testid==='composer-submit-button'||/send|보내기|submit/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||'')))};";
    }

    private static String continuationEquivalence(String comparableExpected) {
        return "const comparableExpected=canonical(" + comparableExpected + ");const comparable=s=>canonical(s).replace(/^\\[\\d{4}\\.\\d{2}\\.\\d{2} \\| \\d{2}:\\d{2}:\\d{2}\\] (?=\\[SELF_RUN_CONTINUE )/,'');const acceptable=()=>same()||comparable(raw())===comparableExpected;";
    }

    private static String guardedContinuationInput(String inputMarker) {
        return "const inputMarkerKey=" + inputMarker + ";let inputState={count:0,at:0};try{const saved=sessionStorage.getItem(inputMarkerKey);if(saved)inputState=JSON.parse(saved)||inputState;}catch(_){}"
                + "if(!acceptable()){const now=Date.now(),count=Math.max(0,Number(inputState.count)||0),at=Math.max(0,Number(inputState.at)||0);if(count>0&&now-at<2500)return result('UI_WAIT','입력 반영 확인 대기');"
                + "if(count>=2){" + forceContinuationInput() + "}else{" + continuationInput() + "}const nextCount=count+1;try{sessionStorage.setItem(inputMarkerKey,JSON.stringify({count:nextCount,at:now}));}catch(_){}"
                + "if(!acceptable()){if(nextCount>=3)return result('SUBMISSION_PENDING','continuation 입력 반영 실패 · 다음 재시도 대기');return result('UI_WAIT','입력 반영 확인 대기');}}try{sessionStorage.removeItem(inputMarkerKey);}catch(_){}";
    }

    private static String continuationInput() {
        return "composer.focus();if('value'in composer){const p=Object.getPrototypeOf(composer),own=Object.getOwnPropertyDescriptor(p,'value'),base=typeof HTMLTextAreaElement!=='undefined'?Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value'):null,setter=own?.set||base?.set;if(setter)setter.call(composer,expected);else composer.value=expected;composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));composer.dispatchEvent(new Event('change',{bubbles:true}));}else{const sel=window.getSelection(),range=document.createRange();range.selectNodeContents(composer);sel.removeAllRanges();sel.addRange(range);let inserted=false;try{inserted=document.execCommand('insertText',false,expected);}catch(_){}if(!inserted){composer.textContent=expected;composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));}}";
    }

    private static String forceContinuationInput() {
        return "composer.focus();if('value'in composer){const p=Object.getPrototypeOf(composer),own=Object.getOwnPropertyDescriptor(p,'value'),base=typeof HTMLTextAreaElement!=='undefined'?Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value'):null,setter=own?.set||base?.set;if(setter)setter.call(composer,expected);else composer.value=expected;composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertReplacementText',data:expected}));composer.dispatchEvent(new Event('change',{bubbles:true}));}else{composer.replaceChildren(document.createTextNode(expected));composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertReplacementText',data:expected}));}";
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
