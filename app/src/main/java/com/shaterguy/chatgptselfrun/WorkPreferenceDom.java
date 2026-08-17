package com.shaterguy.chatgptselfrun;

/** Work model/reasoning selector with user-calibrated targets and semantic fallback. */
final class WorkPreferenceDom {
    static final String TURN_INFO_REWRITE_SENTINEL = "__SELF_RUN_TURN_INFO_REWRITE__";
    private WorkPreferenceDom() {}

    static String modelForProject(String projectUrl, String model) {
        return model(projectGuard(SelfRunScript.projectId(projectUrl)), model);
    }

    static String reasoningForProject(String projectUrl, String reasoning) {
        return reasoning(projectGuard(SelfRunScript.projectId(projectUrl)), reasoning);
    }

    static String modelForConversation(String conversationUrl, String model) {
        String guard = conversationGuard(SelfRunScript.conversationId(conversationUrl));
        return TURN_INFO_REWRITE_SENTINEL.equals(model)
                ? preferenceBypass(guard, "차기 WORK 모델 정보 재작성 요청 준비")
                : model(guard, model);
    }

    static String reasoningForConversation(String conversationUrl, String reasoning) {
        String guard = conversationGuard(SelfRunScript.conversationId(conversationUrl));
        return TURN_INFO_REWRITE_SENTINEL.equals(reasoning)
                ? preferenceBypass(guard, "차기 WORK 추론 정보 재작성 요청 준비")
                : reasoning(guard, reasoning);
    }

    private static String preferenceBypass(String guard, String detail) {
        return "(() =>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,diagnostics,url:location.href});"
                + guard + "return result('READY'," + q(detail) + ",{bypassed:true});})()";
    }

    private static String model(String guard, String wanted) {
        return "(() =>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,diagnostics,url:location.href});"
                + guard + WebUiCalibrationDom.runtimePrelude() + helpers()
                + "const wanted=" + q(wanted) + ";const modelOf=s=>{const v=text(s),m=v.match(/(?:^|\\s)(sol|terra|luna)(?:\\s|$)/);return m?m[1]:''};const direct=/^(?:(?:gpt-?)?5(?:\\.6)?\\s+)?(?:sol|terra|luna)(\\s|$)/;"
                + "const options=[...document.querySelectorAll('[role=\"menuitemradio\"],[role=\"radio\"],[role=\"option\"],[role=\"menuitem\"]')].filter(visible).filter(e=>{const role=e.getAttribute('role')||'',l=label(e);return !!modelOf(l)&&(role!=='menuitem'||direct.test(l))});const semanticOption=options.find(e=>modelOf(label(e))===wanted);"
                + "const calibratedTarget=__srFind(" + q(WebUiCalibrationStore.PURPOSE_WORK_MODEL) + ");const calibratedTrigger=menuTrigger(calibratedTarget)?calibratedTarget:null;const calibratedOption=calibratedTarget&&!calibratedTrigger?calibratedTarget:null;const calibratedWanted=calibratedOption&&modelOf(label(calibratedOption))===wanted?calibratedOption:null;const option=semanticOption||calibratedWanted;"
                + "const level=[...document.querySelectorAll('[role=\"menuitem\"]')].filter(visible).find(e=>/^(model|모델)(\\s|$)/.test(label(e)));const heuristicTrigger=[...document.querySelectorAll('button[aria-haspopup=\"menu\"],[role=\"button\"][aria-haspopup=\"menu\"]')].filter(visible).filter(near).find(e=>!!modelOf(label(e)));const trigger=calibratedTrigger||heuristicTrigger;const source=calibratedTrigger?'calibrated-trigger':(heuristicTrigger?'heuristic':'none');const expanded=!!trigger&&trigger.getAttribute('aria-expanded')==='true',current=trigger?modelOf(label(trigger)):'';"
                + "const workModeFallback=[...document.querySelectorAll('button,[role=\"button\"],[role=\"radio\"],[role=\"tab\"]')].filter(visible).filter(near).find(e=>/^(work|작업)(\\s|$)/.test(label(e)))||__srFind(" + q(WebUiCalibrationStore.PURPOSE_MODE_WORK) + ");"
                + "let ready=false,action='';if(option){if(selected(option)){ready=true;if(expanded){openMenu(trigger);action='close-selected-model-menu'}}else{option.click();action='select-model'}}else if(trigger&&current===wanted){ready=true}else if(level){level.click();action='open-model-menu'}else if(trigger&&!expanded){openMenu(trigger);action='open-work-settings-menu'}else if(workModeFallback){activate(workModeFallback);action='open-work-mode-fallback'}"
                + "const diagnostics={requested:wanted,ready,action,current,source,calibratedTargetFound:!!calibratedTarget,calibratedTargetIsTrigger:!!calibratedTrigger,calibratedOptionFound:!!calibratedOption,triggerFound:!!trigger,triggerExpanded:expanded,levelFound:!!level,optionFound:!!option,workModeFallbackFound:!!workModeFallback};if(action)return result('UI_WAIT','Work 모델 반영 대기',diagnostics);if(!ready)return result('UI_WAIT','Work 모델 선택 요소 대기',diagnostics);return result('READY','모델 적용 확인',diagnostics);})()";
    }

    private static String reasoning(String guard, String wanted) {
        return "(() =>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,diagnostics,url:location.href});"
                + guard + WebUiCalibrationDom.runtimePrelude() + helpers()
                + "const wanted=" + q(wanted) + ";const effort=s=>{const v=text(s);if(v.includes('ultra')||v.includes('울트라'))return'ultra';if(v.includes('xhigh')||v.includes('extra high')||v.includes('very high')||v.includes('매우 높음'))return'xhigh';if(v.includes('maximum')||v.includes('max')||v.includes('최대'))return'max';if(v.includes('medium')||v.includes('중간'))return'medium';if(v.includes('light')||v.includes('가벼움'))return'light';if(v.includes('high')||v.includes('높음'))return'high';return''};const direct=/^(ultra|울트라|very high|extra high|xhigh|매우 높음|maximum|max|최대|medium|중간|light|가벼움|high|높음)(\\s|$)/;"
                + "const options=[...document.querySelectorAll('[role=\"menuitemradio\"],[role=\"radio\"],[role=\"option\"],[role=\"menuitem\"]')].filter(visible).filter(e=>{const role=e.getAttribute('role')||'',l=label(e);return !!effort(l)&&(role!=='menuitem'||direct.test(l))});const semanticOption=options.find(e=>effort(label(e))===wanted);"
                + "const calibratedTarget=__srFind(" + q(WebUiCalibrationStore.PURPOSE_WORK_REASONING) + ");const calibratedTrigger=menuTrigger(calibratedTarget)?calibratedTarget:null;const calibratedOption=calibratedTarget&&!calibratedTrigger?calibratedTarget:null;const calibratedWanted=calibratedOption&&effort(label(calibratedOption))===wanted?calibratedOption:null;const option=semanticOption||calibratedWanted;"
                + "const level=[...document.querySelectorAll('[role=\"menuitem\"]')].filter(visible).find(e=>/^(reasoning (level|effort)|추론 (수준|강도|정도))(\\s|$)/.test(label(e)));const heuristicTrigger=[...document.querySelectorAll('button[aria-haspopup=\"menu\"],[role=\"button\"][aria-haspopup=\"menu\"]')].filter(visible).filter(near).find(e=>!!effort(label(e)));const trigger=calibratedTrigger||heuristicTrigger;const source=calibratedTrigger?'calibrated-trigger':(heuristicTrigger?'heuristic':'none');const expanded=!!trigger&&trigger.getAttribute('aria-expanded')==='true',current=trigger?effort(label(trigger)):'';"
                + "const workModeFallback=[...document.querySelectorAll('button,[role=\"button\"],[role=\"radio\"],[role=\"tab\"]')].filter(visible).filter(near).find(e=>/^(work|작업)(\\s|$)/.test(label(e)))||__srFind(" + q(WebUiCalibrationStore.PURPOSE_MODE_WORK) + ");"
                + "let ready=false,action='';if(option){if(selected(option)){ready=true;if(expanded){openMenu(trigger);action='close-selected-effort-menu'}}else{option.click();action='select-effort'}}else if(trigger&&current===wanted){ready=true;if(expanded){openMenu(trigger);action='close-selected-effort-menu'}}else if(level){level.click();action='open-effort-menu'}else if(trigger&&!expanded){openMenu(trigger);action='open-reasoning-menu'}else if(workModeFallback){activate(workModeFallback);action='open-work-mode-fallback'}"
                + "const diagnostics={requested:wanted,ready,action,current,source,calibratedTargetFound:!!calibratedTarget,calibratedTargetIsTrigger:!!calibratedTrigger,calibratedOptionFound:!!calibratedOption,triggerFound:!!trigger,triggerExpanded:expanded,levelFound:!!level,optionFound:!!option,workModeFallbackFound:!!workModeFallback};if(action)return result('UI_WAIT','추론 강도 반영 대기',diagnostics);if(!ready)return result('UI_WAIT','추론 강도 선택 요소 대기',diagnostics);return result('READY','추론 적용 확인',diagnostics);})()";
    }

    private static String helpers() {
        return "const text=s=>String(s??'').replace(/\\s+/g,' ').trim().toLowerCase();const label=e=>text(e?.innerText||'')||text(e?.getAttribute?.('aria-label')||'');const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;"
                + "const input=document.querySelector('#prompt-textarea')||[...document.querySelectorAll('textarea,[contenteditable=\"true\"]')].filter(visible).sort((a,b)=>b.getBoundingClientRect().bottom-a.getBoundingClientRect().bottom)[0]||null;const form=input?.closest?.('form')||null;const near=e=>{if(!e||!input)return false;if(form)return form.contains(e);const a=e.getBoundingClientRect(),b=input.getBoundingClientRect();return a.bottom>=b.top-240&&a.top<=b.bottom+240&&a.right>=b.left-320&&a.left<=b.right+320};"
                + "const selected=e=>!!e&&(e.getAttribute('aria-checked')==='true'||e.getAttribute('aria-pressed')==='true'||e.getAttribute('aria-selected')==='true'||/^(checked|selected|active|on)$/.test(text(e.dataset?.state||'')));const menuTrigger=e=>!!e&&/^(menu|listbox)$/.test(text(e.getAttribute?.('aria-haspopup')||''));"
                + "const openMenu=e=>{if(!e)return;e.focus?.();const init={bubbles:true,cancelable:true,composed:true,button:0,buttons:1,pointerId:1,pointerType:'mouse',isPrimary:true};if(typeof PointerEvent==='function')e.dispatchEvent(new PointerEvent('pointerdown',init));else e.dispatchEvent(new MouseEvent('mousedown',init));};const activate=e=>{if(!e)return;if(menuTrigger(e))openMenu(e);else{e.focus?.();e.click?.();}};";
    }

    private static String projectGuard(String projectId) {
        String expected = q(projectId), general = q(SelfRunScript.GENERAL_CHAT_SCOPE);
        return "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','호스트 불일치');const p=location.pathname.split('/').filter(Boolean);const after=k=>{const i=p.indexOf(k);return i>=0&&i+1<p.length?p[i+1]:''};const expectedProject=" + expected + ";const actualProject=after('g');"
                + "if(expectedProject===" + general + "){const generalNew=p.length===0;const generalConversation=p.length===2&&p[0]==='c'&&!!p[1];if(!generalNew&&!generalConversation)return result('TARGET_ERROR','일반 Chat 범위 이탈');}else if(actualProject!==expectedProject)return result('TARGET_ERROR','프로젝트 불일치');";
    }

    private static String conversationGuard(String conversationId) {
        return "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','호스트 불일치');const p=location.pathname.split('/').filter(Boolean);const after=k=>{const i=p.indexOf(k);return i>=0&&i+1<p.length?p[i+1]:''};if(after('c')!==" + q(conversationId) + ")return result('TARGET_ERROR','canonical conversation 이탈');";
    }

    private static String q(String value) { return SelfRunScript.quote(value); }
}
