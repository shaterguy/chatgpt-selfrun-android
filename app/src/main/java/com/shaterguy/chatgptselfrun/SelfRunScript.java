package com.shaterguy.chatgptselfrun;

/** DOM automation scripts. Model/reasoning selection follows Prompt Scheduler's verified approach. */
final class SelfRunScript {
    private SelfRunScript() {}

    static String bootstrap(String projectUrl, String mode, String prompt, String runId,
                            String model, String reasoning) {
        String expected = quote(prompt);
        String project = quote(projectId(projectUrl));
        String run = quote(runId + ":bootstrap");
        String modeWork = String.valueOf(SelfRunStore.MODE_WORK.equals(mode));
        return "(() =>{" + base(project, expected)
                + "if(actualConversation&&promptPresent)return result('CONFIRMED','첫 요청 확인',{conversationUrl:location.href});"
                + "const markerKey='chatgpt-selfrun:bootstrap:'+" + quote(runId) + ";let marker='';try{marker=sessionStorage.getItem(markerKey)||'';}catch(_){}"
                + "if(marker)return result('SUBMITTED','첫 요청 제출 확인 대기',{conversationUrl:location.href});"
                + modeScript(mode)
                + (SelfRunStore.MODE_WORK.equals(mode) ? preferenceScript(model, reasoning, run) : chatDiagnostics())
                + composerLookup() + "if(!composer)return result('UI_WAIT','입력창 대기');"
                + composerFunctions()
                + "if(same()){const send=findSend();if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return result('UI_WAIT','전송 버튼 대기');"
                + "const value=JSON.stringify({at:Date.now(),url:location.href});try{sessionStorage.setItem(markerKey,value);}catch(_){}send.click();return result('SUBMITTED','첫 요청 제출 클릭');}"
                + inputPrompt()
                + "return result('UI_WAIT',same()?'입력 반영 대기':'첫 요청 입력 대기');"
                + "})()";
    }

    static String observeAssistant(String conversationUrl) {
        String conversation = quote(conversationId(conversationUrl));
        return "(() =>{const result=(status,text='')=>JSON.stringify({status,text,url:location.href});"
                + routeGuard(conversation)
                + "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;"
                + "const stopping=[...document.querySelectorAll('button')].filter(visible).some(b=>/stop|중지|생성 중지/i.test((b.dataset?.testid||'')+' '+(b.getAttribute('aria-label')||'')+' '+(b.title||'')));"
                + "const assistants=[...document.querySelectorAll('[data-message-author-role=\"assistant\"],article[data-turn=\"assistant\"]')];"
                + "if(!assistants.length)return result('WAIT','assistant 응답 대기');"
                + "const latest=assistants[assistants.length-1];const text=String(latest.innerText||latest.textContent||'').trim();"
                + "if(stopping)return result('GENERATING',text);if(!text)return result('WAIT','assistant 본문 대기');"
                + "return result('COMPLETE',text);})()";
    }

    static String applyPreferences(String conversationUrl, String model, String reasoning, String runId) {
        String conversation = quote(conversationId(conversationUrl));
        return "(() =>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,diagnostics,url:location.href});"
                + routeGuard(conversation)
                + "const clip=(s,n=180)=>{s=String(s??'');return s.length>n?s.slice(0,n):s};"
                + "const routeDiagnostics={conversation:" + conversation + "};"
                + commonPreferenceHelpers()
                + modelScript(model)
                + reasoningScript(reasoning)
                + "return result('READY','Work 모델/추론 적용 확인',{model:modelDiagnostics,reasoning:reasoningDiagnostics});})()";
    }

    static String sendPrompt(String conversationUrl, String prompt, String runId, int turn) {
        String conversation = quote(conversationId(conversationUrl));
        String expected = quote(prompt);
        String marker = quote("chatgpt-selfrun:send:" + runId + ":" + turn);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + routeGuard(conversation)
                + "const norm=s=>String(s??'').replace(/[\\u200B-\\u200D\\uFEFF]/g,'').replace(/\\u00a0/g,' ').replace(/\\r\\n?/g,'\\n').trim();"
                + "const canonical=s=>norm(s).replace(/[ \\t]+/g,' ').replace(/ *\\n+ */g,'\\n');const expected=norm(" + expected + ");"
                + "const users=[...document.querySelectorAll('[data-message-author-role=\"user\"],article[data-turn=\"user\"]')].map(e=>canonical(e.innerText||e.textContent||''));"
                + "if(users.some(t=>t===canonical(expected)))return result('CONFIRMED','사용자 턴 확인');"
                + "const markerKey=" + marker + ";let prior='';try{prior=sessionStorage.getItem(markerKey)||'';}catch(_){}if(prior)return result('SUBMITTED','제출 DOM 확인 대기');"
                + composerLookup() + "if(!composer)return result('UI_WAIT','입력창 대기');" + composerFunctions()
                + "if(same()){const send=findSend();if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return result('UI_WAIT','전송 버튼 대기');const value=JSON.stringify({at:Date.now(),url:location.href});try{sessionStorage.setItem(markerKey,value);}catch(_){}send.click();return result('SUBMITTED','continuation 제출 클릭');}"
                + inputPrompt() + "return result('UI_WAIT',same()?'입력 반영 대기':'continuation 입력 대기');})()";
    }

    private static String base(String project, String expected) {
        return "const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,diagnostics,url:location.href});"
                + "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','ChatGPT 호스트 불일치');"
                + "const norm=s=>String(s??'').replace(/[\\u200B-\\u200D\\uFEFF]/g,'').replace(/\\u00a0/g,' ').replace(/\\r\\n?/g,'\\n').trim();"
                + "const canonical=s=>norm(s).replace(/[ \\t]+/g,' ').replace(/ *\\n+ */g,'\\n');const expected=norm(" + expected + "),expectedProject=" + project + ";"
                + "const parts=location.pathname.split('/').filter(Boolean);const after=k=>{const i=parts.indexOf(k);return i>=0&&i+1<parts.length?parts[i+1]:''};const actualProject=after('g'),actualConversation=after('c');"
                + "if(actualProject!==expectedProject)return result('TARGET_ERROR','프로젝트 불일치');"
                + "const users=[...document.querySelectorAll('[data-message-author-role=\"user\"],article[data-turn=\"user\"]')].map(e=>canonical(e.innerText||e.textContent||''));const promptPresent=users.some(t=>t===canonical(expected));"
                + "const auth=[...document.querySelectorAll('button,a')].some(e=>e.offsetParent!==null&&/^(log in|sign up|로그인|가입)$/i.test(String(e.innerText||e.getAttribute('aria-label')||'').trim()));if(auth)return result('AUTH_REQUIRED','ChatGPT 로그인이 필요합니다.');";
    }

    private static String routeGuard(String conversation) {
        return "const parts=location.pathname.split('/').filter(Boolean);const after=k=>{const i=parts.indexOf(k);return i>=0&&i+1<parts.length?parts[i+1]:''};const actualConversation=after('c');"
                + "if(actualConversation!==" + conversation + ")return result('TARGET_ERROR','canonical conversation 이탈');";
    }

    private static String modeScript(String mode) {
        if (SelfRunStore.MODE_CHAT.equals(mode)) {
            return commonModeHelpers()
                    + "const chat=modeCandidate(['chat','채팅']),work=modeCandidate(['work','작업']);const workSelected=modeIsSelected(work);"
                    + "if(workSelected){if(!chat)return result('UI_WAIT','Chat 모드 항목 대기');chat.click();return result('UI_WAIT','Chat 모드 반영 대기');}"
                    + "const modeDiagnostics={requested:'chat',ready:true};";
        }
        return commonModeHelpers()
                + "const work=modeCandidate(['work','작업']);const key='chatgpt-selfrun:mode:work';let prior='';try{prior=sessionStorage.getItem(key)||'';}catch(_){}const selected=modeIsSelected(work);"
                + "if(work&&!selected&&!prior){try{sessionStorage.setItem(key,String(Date.now()));}catch(_){}work.click();return result('UI_WAIT','Work 모드 반영 대기');}"
                + "if(!work&&!prior)return result('UI_WAIT','Work 모드 항목 대기');const modeDiagnostics={requested:'work',ready:true,selected};";
    }

    private static String commonModeHelpers() {
        return "const exactText=s=>String(s??'').replace(/\\s+/g,' ').trim().toLowerCase();const forbiddenMode=/new chat|새 채팅|새 대화|new conversation/i;"
                + "const modeCandidates=[...document.querySelectorAll('button,[role=\"button\"],[role=\"menuitemradio\"],[role=\"radio\"],[role=\"tab\"]')];"
                + "const modeCandidate=labels=>modeCandidates.find(e=>{const inner=exactText(e.innerText||''),aria=exactText(e.getAttribute('aria-label')||''),combined=exactText(inner+' '+aria);if(forbiddenMode.test(combined))return false;const role=e.getAttribute('role')||'',testId=exactText(e.dataset?.testid||'');const strong=e.hasAttribute('aria-pressed')||e.hasAttribute('aria-checked')||['menuitemradio','radio','tab'].includes(role)||e.getAttribute('aria-haspopup')==='menu'||/mode|experience/.test(testId);return strong&&(labels.includes(inner)||labels.includes(aria));});"
                + "const modeIsSelected=e=>!!e&&(e.getAttribute('aria-pressed')==='true'||e.getAttribute('aria-checked')==='true'||/active|selected|checked/.test(exactText(e.dataset?.state||'')));";
    }

    private static String chatDiagnostics() {
        return "const modelDiagnostics={requested:'unchanged',ready:true,skipped:true};const reasoningDiagnostics={requested:'unchanged',ready:true,skipped:true};";
    }

    private static String commonPreferenceHelpers() {
        return "const exactText=s=>String(s??'').replace(/\\s+/g,' ').trim().toLowerCase();"
                + "const elementLabel=e=>exactText(e?.innerText||'')||exactText(e?.getAttribute?.('aria-label')||'');const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;"
                + "const composerInput=document.querySelector('#prompt-textarea')||[...document.querySelectorAll('textarea,[contenteditable=\"true\"]')].filter(visible).sort((a,b)=>b.getBoundingClientRect().bottom-a.getBoundingClientRect().bottom)[0]||null;"
                + "const composerForm=composerInput?.closest?.('form')||null;const inComposer=e=>{if(!e||!composerInput)return false;if(composerForm)return composerForm.contains(e);const a=e.getBoundingClientRect(),b=composerInput.getBoundingClientRect();return a.bottom>=b.top-240&&a.top<=b.bottom+240&&a.right>=b.left-320&&a.left<=b.right+320};"
                + "const selectedState=e=>!!e&&(e.getAttribute('aria-checked')==='true'||e.getAttribute('aria-pressed')==='true'||e.getAttribute('aria-selected')==='true'||/^(checked|selected|active|on)$/.test(exactText(e.dataset?.state||'')));"
                + "const openMenu=e=>{if(!e)return;e.focus?.();const init={bubbles:true,cancelable:true,composed:true,button:0,buttons:1,pointerId:1,pointerType:'mouse',isPrimary:true};if(typeof PointerEvent==='function')e.dispatchEvent(new PointerEvent('pointerdown',init));else e.dispatchEvent(new MouseEvent('mousedown',init));};";
    }

    private static String preferenceScript(String model, String reasoning, String run) {
        return commonPreferenceHelpers() + modelScript(model) + reasoningScript(reasoning);
    }

    private static String modelScript(String requestedModel) {
        return "const desiredModel=" + quote(requestedModel) + ";const modelOf=s=>{const value=exactText(s);const match=value.match(/(?:^|\\s)(sol|terra|luna)(?:\\s|$)/);return match?match[1]:''};"
                + "const directModelLabel=/^(?:(?:gpt-?)?5(?:\\.6)?\\s+)?(?:sol|terra|luna)(\\s|$)/;const modelOptions=[...document.querySelectorAll('[role=\"menuitemradio\"],[role=\"radio\"],[role=\"option\"],[role=\"menuitem\"]')].filter(visible).filter(e=>{const role=e.getAttribute('role')||'',label=elementLabel(e);return !!modelOf(label)&&(role!=='menuitem'||directModelLabel.test(label))});"
                + "const desiredModelOption=modelOptions.find(e=>modelOf(elementLabel(e))===desiredModel);const modelLevelItem=[...document.querySelectorAll('[role=\"menuitem\"]')].filter(visible).find(e=>/^(model|모델)(\\s|$)/.test(elementLabel(e)));"
                + "const trigger=[...document.querySelectorAll('button[aria-haspopup=\"menu\"],[role=\"button\"][aria-haspopup=\"menu\"]')].filter(visible).filter(inComposer).find(e=>!!modelOf(elementLabel(e)));const expanded=!!trigger&&trigger.getAttribute('aria-expanded')==='true';const current=trigger?modelOf(elementLabel(trigger)):'';let ready=false,action='';"
                + "if(desiredModelOption){if(selectedState(desiredModelOption)){ready=true;if(expanded){openMenu(trigger);action='close-model-menu';}}else{desiredModelOption.click();action='select-model';}}else if(trigger&&current===desiredModel){ready=true;}else if(modelLevelItem){modelLevelItem.click();action='open-model-menu';}else if(trigger&&!expanded){openMenu(trigger);action='open-work-settings';}"
                + "const modelDiagnostics={requested:desiredModel,ready,action,current,optionFound:!!desiredModelOption};if(action)return result('UI_WAIT','Work 모델 반영 대기',{model:modelDiagnostics});if(!ready)return result('UI_WAIT','Work 모델 선택 요소 대기',{model:modelDiagnostics});";
    }

    private static String reasoningScript(String requestedEffort) {
        return "const desiredEffort=" + quote(requestedEffort) + ";const effortOf=s=>{const value=exactText(s);if(['울트라','ultra'].some(v=>value.includes(v)))return'ultra';if(['매우 높음','extra high','very high','xhigh'].some(v=>value.includes(v)))return'xhigh';if(['maximum','max','최대'].some(v=>value.includes(v)))return'max';if(['high','높음'].some(v=>value.includes(v)))return'high';return''};"
                + "const direct=/^(ultra|울트라|very high|extra high|xhigh|매우 높음|maximum|max|최대|high|높음)(\\s|$)/;const options=[...document.querySelectorAll('[role=\"menuitemradio\"],[role=\"radio\"],[role=\"option\"],[role=\"menuitem\"]')].filter(visible).filter(e=>{const role=e.getAttribute('role')||'',label=elementLabel(e);return !!effortOf(label)&&(role!=='menuitem'||direct.test(label))});"
                + "const desired=options.find(e=>effortOf(elementLabel(e))===desiredEffort);const level=[...document.querySelectorAll('[role=\"menuitem\"]')].filter(visible).find(e=>/^(reasoning (level|effort)|추론 (수준|강도|정도))(\\s|$)/.test(elementLabel(e)));"
                + "const trigger=[...document.querySelectorAll('button[aria-haspopup=\"menu\"],[role=\"button\"][aria-haspopup=\"menu\"]')].filter(visible).filter(inComposer).find(e=>!!effortOf(elementLabel(e)));const expanded=!!trigger&&trigger.getAttribute('aria-expanded')==='true';const current=trigger?effortOf(elementLabel(trigger)):'';let ready=false,action='';"
                + "if(desired){if(selectedState(desired)){ready=true;if(expanded){openMenu(trigger);action='close-reasoning-menu';}}else{desired.click();action='select-reasoning';}}else if(trigger&&current===desiredEffort){ready=true;if(expanded){openMenu(trigger);action='close-reasoning-menu';}}else if(level){level.click();action='open-reasoning-menu';}else if(trigger&&!expanded){openMenu(trigger);action='open-reasoning-trigger';}"
                + "const reasoningDiagnostics={requested:desiredEffort,ready,action,current,optionFound:!!desired};if(action)return result('UI_WAIT','추론 강도 반영 대기',{model:modelDiagnostics,reasoning:reasoningDiagnostics});if(!ready)return result('UI_WAIT','추론 강도 선택 요소 대기',{model:modelDiagnostics,reasoning:reasoningDiagnostics});";
    }

    private static String composerLookup() {
        return "const selectors=['textarea#prompt-textarea','textarea[data-testid=\"prompt-textarea\"]','div#prompt-textarea[contenteditable=\"true\"]','main form [contenteditable=\"true\"][data-lexical-editor=\"true\"]','main form [contenteditable=\"true\"]'];let composer=null;for(const s of selectors){composer=[...document.querySelectorAll(s)].find(e=>e&&e.isConnected&&e.offsetParent!==null);if(composer)break;}";
    }

    private static String composerFunctions() {
        return "const raw=()=>('value'in composer?composer.value:(composer.innerText||composer.textContent||''));const same=()=>canonical(raw())===canonical(expected);const findSend=()=>{const scope=composer.closest('form')||document;return [...scope.querySelectorAll('button')].find(b=>b.dataset.testid==='send-button'||b.dataset.testid==='composer-submit-button'||/send|보내기|submit/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||'')))};";
    }

    private static String inputPrompt() {
        return "composer.focus();if('value'in composer){const proto=Object.getPrototypeOf(composer);const own=Object.getOwnPropertyDescriptor(proto,'value');const base=typeof HTMLTextAreaElement!=='undefined'?Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value'):null;const setter=own?.set||base?.set;if(setter)setter.call(composer,expected);else composer.value=expected;composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));composer.dispatchEvent(new Event('change',{bubbles:true}));}else{const selection=window.getSelection(),range=document.createRange();range.selectNodeContents(composer);selection.removeAllRanges();selection.addRange(range);try{document.execCommand('delete',false,null);document.execCommand('insertText',false,expected);}catch(_){composer.textContent=expected;composer.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:expected}));}}";
    }

    static String projectId(String url) {
        if (url == null) return "";
        String[] parts = url.split("/");
        for (int i = 0; i + 1 < parts.length; i++) if ("g".equals(parts[i])) return parts[i + 1];
        return "";
    }

    static String conversationId(String url) {
        if (url == null) return "";
        String[] parts = url.split("/");
        for (int i = 0; i + 1 < parts.length; i++) if ("c".equals(parts[i])) return parts[i + 1];
        return "";
    }

    static String quote(String value) {
        if (value == null) value = "";
        StringBuilder out = new StringBuilder(value.length() + 16).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
