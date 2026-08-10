package com.shaterguy.chatgptselfrun;

/** Runtime DOM adapter. Each action is isolated and fail-closed around user-message submission. */
final class SelfRunDom {
    private SelfRunDom() {}

    static String prepareMode(String projectUrl, String mode, String runId) {
        String project = q(SelfRunScript.projectId(projectUrl));
        boolean work = SelfRunStore.MODE_WORK.equals(mode);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + projectGuard(project)
                + "const text=s=>String(s??'').replace(/\\s+/g,' ').trim().toLowerCase();const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;"
                + "const forbidden=/new chat|새 채팅|새 대화|new conversation/i;const candidates=[...document.querySelectorAll('button,[role=\"button\"],[role=\"menuitemradio\"],[role=\"radio\"],[role=\"tab\"]')].filter(visible);"
                + "const pick=labels=>candidates.find(e=>{const inner=text(e.innerText||''),aria=text(e.getAttribute('aria-label')||''),combined=text(inner+' '+aria);if(forbidden.test(combined))return false;const role=e.getAttribute('role')||'',test=text(e.dataset?.testid||'');const strong=e.hasAttribute('aria-pressed')||e.hasAttribute('aria-checked')||['menuitemradio','radio','tab'].includes(role)||e.getAttribute('aria-haspopup')==='menu'||/mode|experience/.test(test);return strong&&(labels.includes(inner)||labels.includes(aria));});"
                + "const selected=e=>!!e&&(e.getAttribute('aria-pressed')==='true'||e.getAttribute('aria-checked')==='true'||/active|selected|checked/.test(text(e.dataset?.state||'')));"
                + (work
                ? "const target=pick(['work','작업']);const key='chatgpt-selfrun:mode:" + esc(runId) + "';let prior='';try{prior=localStorage.getItem(key)||sessionStorage.getItem(key)||'';}catch(_){}if(target&&selected(target))return result('READY','Work 모드 확인');if(!target&&prior)return result('READY','Work 모드 클릭 이력 확인');if(!target)return result('UI_WAIT','Work 모드 항목 대기');const v=String(Date.now());try{localStorage.setItem(key,v);sessionStorage.setItem(key,v);}catch(_){}target.click();return result('UI_WAIT','Work 모드 반영 대기');"
                : "const chat=pick(['chat','채팅']),work=pick(['work','작업']);if(work&&selected(work)){if(!chat)return result('UI_WAIT','Chat 모드 항목 대기');chat.click();return result('UI_WAIT','Chat 모드 반영 대기');}return result('READY','Chat 모드 유지');")
                + "})()";
    }

    static String sendInitial(String projectUrl, String prompt, String runId) {
        String project = q(SelfRunScript.projectId(projectUrl));
        String expected = q(prompt);
        String marker = q("chatgpt-selfrun:bootstrap:" + runId);
        return "(() =>{const result=(status,detail='',extra={})=>JSON.stringify({status,detail,url:location.href,...extra});"
                + projectGuard(project) + textHelpers(expected)
                + "const p2=location.pathname.split('/').filter(Boolean);const ci=p2.indexOf('c');const conv=ci>=0&&ci+1<p2.length?p2[ci+1]:'';"
                + "const users=[...document.querySelectorAll('[data-message-author-role=\"user\"],article[data-turn=\"user\"]')].map(e=>canonical(e.innerText||e.textContent||''));if(conv&&users.some(t=>t===canonical(expected)))return result('CONFIRMED','첫 요청 확인',{conversationUrl:location.href});"
                + durableMarkerRead(marker)
                + "if(prior)return result('SUBMITTED','첫 요청 제출 확인 대기');"
                + composer() + "if(!composer)return result('UI_WAIT','입력창 대기');" + composerOps()
                + "if(same()){const send=findSend();if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return result('UI_WAIT','전송 버튼 대기');"
                + durableMarkerWrite(marker)
                + "if(!persisted)return result('MARKER_FAILED','중복 방지 표식을 저장하지 못했습니다.');send.click();return result('SUBMITTED','첫 요청 제출 클릭');}"
                + input() + "return result('UI_WAIT',same()?'입력 반영 대기':'첫 요청 입력 대기');})()";
    }

    static String observeAssistant(String conversationUrl) {
        return "(() =>{const result=(status,text='')=>JSON.stringify({status,text,url:location.href});"
                + conversationGuard(q(SelfRunScript.conversationId(conversationUrl)))
                + "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;const stopping=[...document.querySelectorAll('button')].filter(visible).some(b=>/stop|중지|생성 중지/i.test((b.dataset?.testid||'')+' '+(b.getAttribute('aria-label')||'')+' '+(b.title||'')));"
                + "const a=[...document.querySelectorAll('[data-message-author-role=\"assistant\"],article[data-turn=\"assistant\"]')];if(!a.length)return result('WAIT','');const latest=a[a.length-1],text=String(latest.innerText||latest.textContent||'').trim();if(stopping)return result('GENERATING',text);return text?result('COMPLETE',text):result('WAIT','');})()";
    }

    static String sendTurn(String conversationUrl, String prompt, String runId, int turn) {
        String expected = q(prompt);
        String marker = q("chatgpt-selfrun:turn:" + runId + ":" + turn);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + conversationGuard(q(SelfRunScript.conversationId(conversationUrl))) + textHelpers(expected)
                + "const users=[...document.querySelectorAll('[data-message-author-role=\"user\"],article[data-turn=\"user\"]')].map(e=>canonical(e.innerText||e.textContent||''));if(users.some(t=>t===canonical(expected)))return result('CONFIRMED','사용자 턴 확인');"
                + durableMarkerRead(marker)
                + "if(prior)return result('SUBMITTED','사용자 턴 DOM 확인 대기');"
                + composer() + "if(!composer)return result('UI_WAIT','입력창 대기');" + composerOps()
                + "if(same()){const send=findSend();if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return result('UI_WAIT','전송 버튼 대기');"
                + durableMarkerWrite(marker)
                + "if(!persisted)return result('MARKER_FAILED','중복 방지 표식을 저장하지 못했습니다.');send.click();return result('SUBMITTED','사용자 턴 제출 클릭');}"
                + input() + "return result('UI_WAIT',same()?'입력 반영 대기':'사용자 턴 입력 대기');})()";
    }

    private static String projectGuard(String project) {
        return "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','호스트 불일치');const parts=location.pathname.split('/').filter(Boolean);const after=k=>{const i=parts.indexOf(k);return i>=0&&i+1<parts.length?parts[i+1]:''};if(after('g')!==" + project + ")return result('TARGET_ERROR','프로젝트 불일치');";
    }

    private static String conversationGuard(String conversation) {
        return "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','호스트 불일치');const parts=location.pathname.split('/').filter(Boolean);const after=k=>{const i=parts.indexOf(k);return i>=0&&i+1<parts.length?parts[i+1]:''};if(after('c')!==" + conversation + ")return result('TARGET_ERROR','canonical conversation 이탈');";
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

    private static String durableMarkerRead(String marker) {
        return "const markerKey=" + marker + ";let prior='';try{prior=localStorage.getItem(markerKey)||sessionStorage.getItem(markerKey)||'';}catch(_){}";
    }

    private static String durableMarkerWrite(String marker) {
        return "const markerKey2=" + marker + ",v=JSON.stringify({at:Date.now(),url:location.href});let persisted=false;try{localStorage.setItem(markerKey2,v);persisted=localStorage.getItem(markerKey2)===v;}catch(_){}if(!persisted){try{sessionStorage.setItem(markerKey2,v);persisted=sessionStorage.getItem(markerKey2)===v;}catch(_){}}";
    }

    private static String q(String value) { return SelfRunScript.quote(value); }
    private static String esc(String value) { return value.replace("\\", "\\\\").replace("'", "\\'"); }
}
