package com.shaterguy.chatgptselfrun;

/** Runtime DOM adapter. Startup behavior is aligned with the scheduler's proven project bootstrap flow. */
final class SelfRunDom {
    private SelfRunDom() {}

    static String prepareInitialContext(String projectUrl, String mode, String runId) {
        String project = q(SelfRunScript.projectId(projectUrl));
        boolean work = SelfRunStore.MODE_WORK.equals(mode);
        String desiredLabels = work ? "['work','작업']" : "['chat','채팅']";
        String requested = work ? "work" : "chat";
        return "(() =>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,url:location.href,diagnostics});"
                + projectGuard(project)
                + "const parts=location.pathname.split('/').filter(Boolean),after=k=>{const i=parts.indexOf(k);return i>=0&&i+1<parts.length?p[i+1]:''};const actualConversation=after('c');"
                + "if(actualConversation)return result('EXISTING_CONVERSATION','새 대화 화면 대신 기존 conversation이 열렸습니다.',{actualConversation});"
                + authGuard()
                + "const clip=(s,n=160)=>{s=String(s??'');return s.length>n?s.slice(0,n):s};const exact=s=>String(s??'').replace(/\\s+/g,' ').trim().toLowerCase();"
                + "const requestedMode=" + q(requested) + ";const desiredModeLabels=" + desiredLabels + ";const forbidden=/new chat|새 채팅|새 대화|new conversation/i;"
                + "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;"
                + "const labelOf=e=>exact((e?.innerText||'')+' '+(e?.getAttribute?.('aria-label')||''));"
                + "const modeOf=s=>{const v=exact(s);if(forbidden.test(v))return'';const tokens=v.split(/[^a-z0-9가-힣]+/).filter(Boolean);if(tokens.includes('chat')||tokens.includes('채팅'))return'chat';if(tokens.includes('work')||tokens.includes('작업'))return'work';return''};"
                + "const composerAnchor=[...document.querySelectorAll('textarea#prompt-textarea,textarea[data-testid=\"prompt-textarea\"],div#prompt-textarea[contenteditable=\"true\"],main form [contenteditable=\"true\"]')].find(visible)||null;"
                + "const composerForm=composerAnchor?.closest?.('form')||null;"
                + "const near=e=>{if(!e||!composerAnchor)return false;if(composerForm)return composerForm.contains(e);const a=e.getBoundingClientRect(),b=composerAnchor.getBoundingClientRect();return a.bottom>=b.top-240&&a.top<=b.bottom+240&&a.right>=b.left-320&&a.left<=b.right+320};"
                + "const selectedState=e=>!!e&&(e.getAttribute('aria-checked')==='true'||e.getAttribute('aria-pressed')==='true'||e.getAttribute('aria-selected')==='true'||/^(checked|selected|active|on)$/.test(exact(e.dataset?.state||'')));"
                + "const modeNodes=[...document.querySelectorAll('button,[role=\"button\"],[role=\"tab\"],[role=\"menuitemradio\"],[role=\"radio\"],[role=\"option\"],[role=\"menuitem\"]')].filter(visible);"
                + "const triggerCandidates=modeNodes.filter(e=>{const role=e.getAttribute('role')||'';if(['menuitemradio','radio','option','menuitem'].includes(role))return false;const m=modeOf(labelOf(e));if(!m)return false;return e.getAttribute('aria-haspopup')==='menu'||e.hasAttribute('aria-pressed')||e.hasAttribute('aria-checked')||(e.tagName==='BUTTON'&&near(e));});"
                + "const modeTrigger=triggerCandidates.sort((a,b)=>Number(near(b))-Number(near(a)))[0]||null;const currentMode=modeOf(labelOf(modeTrigger));const menuOpen=!!modeTrigger&&modeTrigger.getAttribute('aria-expanded')==='true';"
                + "const desiredOption=modeNodes.find(e=>['menuitemradio','radio','option','menuitem'].includes(e.getAttribute('role')||'')&&modeOf(labelOf(e))===requestedMode)||null;const optionSelected=selectedState(desiredOption);"
                + "const modeKey='chatgpt-selfrun:mode:" + esc(runId) + "';let priorAt=0;try{const raw=sessionStorage.getItem(modeKey);const data=raw?JSON.parse(raw):null;priorAt=Number(data?.at||0);}catch(_){}const recentlyClicked=priorAt>0&&Date.now()-priorAt<4000;"
                + "let action='',modeReadback=false;if(currentMode===requestedMode){modeReadback=true;if(menuOpen){modeTrigger.click();action='close-mode-menu';}}else if(desiredOption&&optionSelected){modeReadback=true;if(menuOpen&&modeTrigger){modeTrigger.click();action='close-selected-mode-menu';}}else if(desiredOption&&!recentlyClicked){desiredOption.click();try{sessionStorage.setItem(modeKey,JSON.stringify({at:Date.now(),action:'select-mode'}));}catch(_){}action='select-mode';}else if(modeTrigger&&currentMode&&currentMode!==requestedMode&&!menuOpen&&!recentlyClicked){modeTrigger.click();try{sessionStorage.setItem(modeKey,JSON.stringify({at:Date.now(),action:'open-mode-menu'}));}catch(_){}action='open-mode-menu';}"
                + "const diagnostics={requested:requestedMode,currentMode,modeTriggerFound:!!modeTrigger,triggerLabel:modeTrigger?clip(labelOf(modeTrigger)):'' ,menuOpen,desiredOptionFound:!!desiredOption,optionSelected,modeReadback,recentlyClicked,action};"
                + "if(action)return result('UI_WAIT','모드 전환 반영 대기',diagnostics);if(!modeReadback)return result('UI_WAIT','실행 모드 실제 상태 대기',diagnostics);try{sessionStorage.removeItem(modeKey);}catch(_){}"
                + composer() + "if(!composer)return result('UI_WAIT','프로젝트 새 대화 입력창 대기',diagnostics);"
                + "return result('READY','프로젝트 새 대화 화면 확인',{...diagnostics,composer:true});})()";
    }
    static String sendInitial(String projectUrl, String prompt, String runId) {
        String project = q(SelfRunScript.projectId(projectUrl));
        String expected = q(prompt);
        String marker = q("chatgpt-selfrun:bootstrap:" + runId);
        return "(() =>{const result=(status,detail='',extra={})=>JSON.stringify({status,detail,url:location.href,...extra});"
                + projectGuard(project) + authGuard() + textHelpers(expected)
                + "const p2=location.pathname.split('/').filter(Boolean);const ci=p2.indexOf('c');const conv=ci>=0&&ci+1<p2.length?p2[ci+1]:'';"
                + "const users=[...document.querySelectorAll('[data-message-author-role=\"user\"],article[data-turn=\"user\"]')].map(e=>canonical(e.innerText||e.textContent||''));const present=users.some(t=>t===canonical(expected));"
                + assistantSnapshot()
                + durableMarkerRead(marker)
                + "if(conv&&present)return result('CONFIRMED','첫 요청과 새 conversation 확인',{conversationUrl:location.href,assistantKey});"
                + "if(conv&&prior)return result('SUBMITTED','새 conversation URL 생성 후 첫 요청 DOM 확인 대기');"
                + "if(conv)return result('EXISTING_CONVERSATION','제출 전에 기존 conversation으로 이동했습니다.');"
                + "if(prior)return result('SUBMITTED','첫 요청 제출 확인 대기');"
                + composer() + "if(!composer)return result('UI_WAIT','입력창 대기');" + composerOps()
                + "if(same()){const send=findSend();if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return result('UI_WAIT','전송 버튼 대기');"
                + durableMarkerWrite(marker)
                + "if(!persisted)return result('MARKER_FAILED','중복 방지 표식을 저장하지 못했습니다.');send.click();return result('SUBMITTED','첫 요청 제출 클릭');}"
                + input() + "return result('UI_WAIT',same()?'입력 반영 확인 대기':'첫 요청 입력 대기');})()";
    }

    /**
     * Observe only an assistant turn that follows the latest user turn. This mirrors the scheduler's
     * proven turn-order guard and prevents a completed previous assistant message from being reused.
     */
    static String observeAssistant(String conversationUrl, String baselineKey) {
        return "(() =>{const result=(status,text='',extra={})=>JSON.stringify({status,text,url:location.href,...extra});"
                + conversationGuard(q(SelfRunScript.conversationId(conversationUrl))) + authGuard()
                + "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;const roleOf=e=>e.getAttribute('data-message-author-role')||e.getAttribute('data-turn')||e.querySelector('[data-message-author-role]')?.getAttribute('data-message-author-role')||'';"
                + "const turns=[...document.querySelectorAll('article,[data-message-author-role]')].filter((e,i,a)=>!a.some((p,j)=>j<i&&p.contains(e)));let userIndex=-1;for(let i=0;i<turns.length;i++){if(roleOf(turns[i])==='user')userIndex=i;}"
                + "if(userIndex<0)return result('WAIT','최근 사용자 턴 대기');let assistant=null,assistantIndex=-1;for(let i=userIndex+1;i<turns.length;i++){const role=roleOf(turns[i]);if(role==='user')break;if(role==='assistant'){assistant=turns[i];assistantIndex=i;break;}}"
                + "const stopping=[...document.querySelectorAll('button')].filter(visible).some(b=>b.dataset?.testid==='stop-button'||/stop generating|응답 중지|생성 중지/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||'')));"
                + "if(!assistant)return result(stopping?'GENERATING':'WAIT',stopping?'어시스턴트 응답 생성 중':'새 assistant 응답 대기');"
                + "const assistantText=String(assistant.innerText||assistant.textContent||'').trim();const streaming=assistant.getAttribute('aria-busy')==='true'||assistant.getAttribute('data-is-streaming')==='true'||!!assistant.querySelector('[aria-busy=\"true\"],[data-is-streaming=\"true\"],[class*=\"spinner\" i],[class*=\"loading\" i]');"
                + "const assistantIdentity=assistant.getAttribute('data-message-id')||assistant.dataset?.messageId||assistant.id||'index';const assistantKey=assistantIdentity+':'+assistantIndex;"
                + "if(assistantKey===" + q(baselineKey) + ")return result('STALE','',{assistantKey});if(stopping||streaming)return result('GENERATING',assistantText,{assistantKey});return assistantText?result('COMPLETE',assistantText,{assistantKey}):result('WAIT','',{assistantKey});})()";
    }

    /**
     * Continuation prompts are intentionally identical across turns. A previous matching user turn
     * must therefore never count as confirmation for the current turn. The per-turn marker stores
     * the matching-user baseline before the click and confirms only after that count increases.
     */
    static String sendTurn(String conversationUrl, String prompt, String runId, int turn) {
        String expected = q(prompt);
        String marker = q("chatgpt-selfrun:turn:" + runId + ":" + turn);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + conversationGuard(q(SelfRunScript.conversationId(conversationUrl))) + authGuard() + textHelpers(expected)
                + "const users=[...document.querySelectorAll('[data-message-author-role=\"user\"],article[data-turn=\"user\"]')].map(e=>canonical(e.innerText||e.textContent||''));const matching=users.filter(t=>t===canonical(expected)).length;"
                + assistantSnapshot() + durableMarkerRead(marker)
                + "if(prior){let markerData=null;try{markerData=JSON.parse(prior);}catch(_){}if(markerData&&Number.isFinite(Number(markerData.baseline))){const baseline=Number(markerData.baseline);if(matching>baseline)return JSON.stringify({status:'CONFIRMED',detail:'현재 사용자 턴 확인',url:location.href,assistantKey});return result('SUBMITTED','현재 사용자 턴 DOM 확인 대기');}return result('SUBMITTED','이전 버전 제출 표식 확인 대기');}"
                + composer() + "if(!composer)return result('UI_WAIT','입력창 대기');" + composerOps()
                + "if(same()){const send=findSend();if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return result('UI_WAIT','전송 버튼 대기');"
                + durableMarkerWriteWithBaseline(marker, "matching")
                + "if(!persisted)return result('MARKER_FAILED','중복 방지 표식을 저장하지 못했습니다.');send.click();return result('SUBMITTED','현재 사용자 턴 제출 클릭');}"
                + input() + "return result('UI_WAIT',same()?'입력 반영 확인 대기':'사용자 턴 입력 대기');})()";
    }

    private static String projectGuard(String project) {
        return "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','호스트 불일치');const p=location.pathname.split('/').filter(Boolean);const afterProject=k=>{const i=p.indexOf(k);return i>=0&&i+1<p.length?p[i+1]:''};if(afterProject('g')!==" + project + ")return result('TARGET_ERROR','프로젝트 불일치');";
    }

    private static String conversationGuard(String conversation) {
        return "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','호스트 불일치');const p=location.pathname.split('/').filter(Boolean);const afterConversation=k=>{const i=p.indexOf(k);return i>=0&&i+1<p.length?p[i+1]:''};if(afterConversation('c')!==" + conversation + ")return result('TARGET_ERROR','canonical conversation 이탈');";
    }

    private static String authGuard() {
        return "const authVisible=e=>!!e&&e.isConnected&&e.offsetParent!==null;const auth=[...document.querySelectorAll('[data-testid*=login],a[href*=\"/auth/login\"],button')].filter(authVisible).some(e=>/^(log in|sign up|로그인|가입)$/i.test(String(e.innerText||e.getAttribute('aria-label')||'').trim()));if(auth)return result('AUTH_REQUIRED','ChatGPT 로그인이 필요합니다.');";
    }

    private static String textHelpers(String expected) {
        return "const norm=s=>String(s??'').replace(/[\\u200B-\\u200D\\uFEFF]/g,'').replace(/\\u00a0/g,' ').replace(/\\r\\n?/g,'\\n').trim();const canonical=s=>norm(s).replace(/[ \\t]+/g,' ').replace(/ *\\n+ */g,'\\n');const expected=norm(" + expected + ");";
    }

    private static String composer() {
        return "const selectors=['textarea#prompt-textarea','textarea[data-testid=\"prompt-textarea\"]','div#prompt-textarea[contenteditable=\"true\"]','main form [contenteditable=\"true\"][data-lexical-editor=\"true\"]','main form [contenteditable=\"true\"]'];let composer=null;for(const s of selectors){composer=[...document.querySelectorAll(s)].find(e=>e&&e.isConnected&&e.offsetParent!==null);if(composer)break;}";
    }

    private static String composerOps() {
        return "const raw=()=>('value'in composer?composer.value:(composer.innerText||composer.textContent||''));const same=()=>canonical(raw())===canonical(expected);const findSend=()=>{const scope=composer.closest('form')||document;return [...scope.querySelectorAll('button')].find(b=>b.dataset.testid==='send-button'||b.dataset.testid==='composer-submit-button'||/send|보내기|submit/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||'')))};";
    }

    private static String input() {
        return "composer.focus();if('value'in composer){const p=Object.getPrototypeOf(composer),own=Object.getOwnPropertyDescriptor(p,'value'),base=typeof HTMLTextAreaElement!=='undefined'?Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value'):null,setter=own?.set||base?.set;if(setter)setter.call(composer,expected);else composer.value=expected;composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));composer.dispatchEvent(new Event('change',{bubbles:true}));}else{const sel=window.getSelection(),range=document.createRange();range.selectNodeContents(composer);sel.removeAllRanges();sel.addRange(range);try{document.execCommand('delete',false,null);document.execCommand('insertText',false,expected);}catch(_){composer.textContent=expected;composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));}}";
    }

    /** Stable identity excludes assistant text so late DOM decoration cannot masquerade as a new turn. */
    private static String assistantSnapshot() {
        return "const assistantNodes=[...document.querySelectorAll('[data-message-author-role=\"assistant\"],article[data-turn=\"assistant\"]')];const assistantLatest=assistantNodes[assistantNodes.length-1]||null;const assistantIdentity=assistantLatest?(assistantLatest.getAttribute('data-message-id')||assistantLatest.dataset?.messageId||assistantLatest.id||'index'):'none';const assistantKey=assistantLatest?(assistantIdentity+':'+(assistantNodes.length-1)) : '';";
    }

    private static String durableMarkerRead(String marker) {
        return "const markerKey=" + marker + ";let prior='';try{prior=localStorage.getItem(markerKey)||sessionStorage.getItem(markerKey)||'';}catch(_){}";
    }

    private static String durableMarkerWrite(String marker) {
        return "const markerKey2=" + marker + ",v=JSON.stringify({at:Date.now(),url:location.href});let persisted=false;try{localStorage.setItem(markerKey2,v);persisted=localStorage.getItem(markerKey2)===v;}catch(_){}if(!persisted){try{sessionStorage.setItem(markerKey2,v);persisted=sessionStorage.getItem(markerKey2)===v;}catch(_){}}";
    }

    private static String durableMarkerWriteWithBaseline(String marker, String baselineExpression) {
        return "const markerKey2=" + marker + ",v=JSON.stringify({at:Date.now(),url:location.href,baseline:" + baselineExpression + "});let persisted=false;try{localStorage.setItem(markerKey2,v);persisted=localStorage.getItem(markerKey2)===v;}catch(_){}if(!persisted){try{sessionStorage.setItem(markerKey2,v);persisted=sessionStorage.getItem(markerKey2)===v;}catch(_){}}";
    }

    private static String q(String value) { return SelfRunScript.quote(value); }
    private static String esc(String value) { return value.replace("\\", "\\\\").replace("'", "\\'"); }
}
