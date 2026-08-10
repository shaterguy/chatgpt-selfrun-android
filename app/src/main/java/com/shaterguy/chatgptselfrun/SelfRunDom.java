package com.shaterguy.chatgptselfrun;

/** Runtime DOM adapter. Startup behavior is aligned with the scheduler's proven project bootstrap flow. */
final class SelfRunDom {
    private SelfRunDom() {}

    static String prepareInitialContext(String projectUrl, String mode, String runId) {
        String project = q(SelfRunScript.projectId(projectUrl));
        boolean work = SelfRunStore.MODE_WORK.equals(mode);
        String desired = work ? "['work','작업']" : "['chat','채팅']";
        String requested = work ? "work" : "chat";
        return "(() =>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,url:location.href,diagnostics});"
                + projectGuard(project)
                + "const parts=location.pathname.split('/').filter(Boolean),after=k=>{const i=parts.indexOf(k);return i>=0&&i+1<parts.length?parts[i+1]:''};const actualConversation=after('c');"
                + "if(actualConversation)return result('EXISTING_CONVERSATION','새 대화 화면 대신 기존 conversation이 열렸습니다.',{actualConversation});"
                + authGuard()
                + "const clip=(s,n=160)=>{s=String(s??'');return s.length>n?s.slice(0,n):s};const exact=s=>String(s??'').replace(/\\s+/g,' ').trim().toLowerCase();"
                + "const desiredModeLabels=" + desired + ";const forbidden=/new chat|새 채팅|새 대화|new conversation/i;"
                + "const modeCandidates=[...document.querySelectorAll('button,[role=\"button\"],[role=\"menuitemradio\"],[role=\"radio\"],[role=\"tab\"]')];"
                + "const mode=modeCandidates.find(e=>{const inner=exact(e.innerText||''),aria=exact(e.getAttribute('aria-label')||''),combined=exact(inner+' '+aria);if(forbidden.test(combined))return false;const role=e.getAttribute('role')||'',test=exact(e.dataset?.testid||'');const strong=e.hasAttribute('aria-pressed')||e.hasAttribute('aria-checked')||['menuitemradio','radio','tab'].includes(role)||e.getAttribute('aria-haspopup')==='menu'||/mode|experience/.test(test);return strong&&(desiredModeLabels.includes(inner)||desiredModeLabels.includes(aria));});"
                + "const modeKey='chatgpt-selfrun:mode:" + esc(runId) + "';let prior='';try{prior=sessionStorage.getItem(modeKey)||'';}catch(_){}"
                + "const selected=!!mode&&(mode.getAttribute('aria-pressed')==='true'||mode.getAttribute('aria-checked')==='true'||/active|selected|checked/.test(exact(mode.dataset?.state||'')));"
                + "const diagnostics={requested:'" + requested + "',candidateFound:!!mode,candidateLabel:mode?clip(exact((mode.innerText||'')+' '+(mode.getAttribute('aria-label')||''))):'',selected,priorClick:!!prior};"
                + "if(mode&&!selected&&!prior){const value=JSON.stringify({at:Date.now(),label:diagnostics.candidateLabel});try{sessionStorage.setItem(modeKey,value);}catch(_){}mode.click();return result('UI_WAIT','모드 전환 반영 대기',diagnostics);}"
                + (work ? "if(!selected)return result('UI_WAIT','Work 모드 실제 적용 상태 대기',diagnostics);" : "")
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

    static String observeAssistant(String conversationUrl, String baselineKey) {
        return "(() =>{const result=(status,text='',extra={})=>JSON.stringify({status,text,url:location.href,...extra});"
                + conversationGuard(q(SelfRunScript.conversationId(conversationUrl)))
                + "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;const stopping=[...document.querySelectorAll('button')].filter(visible).some(b=>/stop|중지|생성 중지/i.test((b.dataset?.testid||'')+' '+(b.getAttribute('aria-label')||'')+' '+(b.title||'')));"
                + assistantSnapshot()
                + "if(!assistantKey)return result('WAIT','');if(assistantKey===" + q(baselineKey) + ")return result('STALE','', {assistantKey});if(stopping)return result('GENERATING',assistantText,{assistantKey});return assistantText?result('COMPLETE',assistantText,{assistantKey}):result('WAIT','',{assistantKey});})()";
    }

    static String sendTurn(String conversationUrl, String prompt, String runId, int turn) {
        String expected = q(prompt);
        String marker = q("chatgpt-selfrun:turn:" + runId + ":" + turn);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + conversationGuard(q(SelfRunScript.conversationId(conversationUrl))) + authGuard() + textHelpers(expected)
                + "const users=[...document.querySelectorAll('[data-message-author-role=\"user\"],article[data-turn=\"user\"]')].map(e=>canonical(e.innerText||e.textContent||''));"
                + assistantSnapshot()
                + "if(users.some(t=>t===canonical(expected)))return JSON.stringify({status:'CONFIRMED',detail:'사용자 턴 확인',url:location.href,assistantKey});"
                + durableMarkerRead(marker)
                + "if(prior)return result('SUBMITTED','사용자 턴 DOM 확인 대기');"
                + composer() + "if(!composer)return result('UI_WAIT','입력창 대기');" + composerOps()
                + "if(same()){const send=findSend();if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return result('UI_WAIT','전송 버튼 대기');"
                + durableMarkerWrite(marker)
                + "if(!persisted)return result('MARKER_FAILED','중복 방지 표식을 저장하지 못했습니다.');send.click();return result('SUBMITTED','사용자 턴 제출 클릭');}"
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

    private static String assistantSnapshot() {
        return "const assistantNodes=[...document.querySelectorAll('[data-message-author-role=\"assistant\"],article[data-turn=\"assistant\"]')];const assistantLatest=assistantNodes[assistantNodes.length-1]||null;const assistantText=assistantLatest?String(assistantLatest.innerText||assistantLatest.textContent||'').trim():'';const assistantDigest=s=>{let h=2166136261;for(let i=0;i<s.length;i++)h=Math.imul(h^s.charCodeAt(i),16777619);return (h>>>0).toString(36)};const assistantIdentity=assistantLatest?(assistantLatest.getAttribute('data-message-id')||assistantLatest.dataset?.messageId||assistantLatest.id||'index'):'none';const assistantKey=assistantLatest?(assistantIdentity+':'+(assistantNodes.length-1)+':'+assistantDigest(assistantText)) : '';";
    }

    private static String durableMarkerRead(String marker) {
        return "const markerKey=" + marker + ";let prior='';try{prior=localStorage.getItem(markerKey)||sessionStorage.getItem(markerKey)||'';}catch(_){}";
    }

    private static String durableMarkerWrite(String marker) {
        return "const markerKey2=" + marker + ",v=JSON.stringify({at:Date.now(),url:location.href});let persisted=false;try{localStorage.setItem(markerKey2,v);persisted=localStorage.getItem(markerKey2)===v;}catch(_){}if(!persisted){try{sessionStorage.setItem(markerKey2,v);persisted=sessionStorage.getItem(markerKey2)===v;}catch(_){}}";
    }

    private static String q(String value) { return SelfRunScript.quote(value); }
    private static String esc(String value) { return value.replace("\\", "\\\\").replace("'", "\\'"); }
}
