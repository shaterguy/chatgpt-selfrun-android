package com.shaterguy.chatgptselfrun;

/** Work model/reasoning selector with user-calibrated targets and semantic fallback. */
final class WorkPreferenceDom {
    static final String TURN_INFO_REWRITE_SENTINEL = "__SELF_RUN_TURN_INFO_REWRITE__";
    private WorkPreferenceDom() {}

    static String modelForProject(String projectUrl, String model) {
        boolean general = SelfRunScript.isGeneralChatUrl(projectUrl);
        return model(projectGuard(SelfRunScript.projectId(projectUrl)), model,
                WebUiCalibrationStore.workModelPurpose(general, true));
    }

    static String reasoningForProject(String projectUrl, String reasoning) {
        boolean general = SelfRunScript.isGeneralChatUrl(projectUrl);
        return reasoning(projectGuard(SelfRunScript.projectId(projectUrl)), reasoning,
                WebUiCalibrationStore.workReasoningPurpose(general, true));
    }

    static String modelForConversation(String conversationUrl, String model) {
        String guard = conversationGuard(SelfRunScript.conversationId(conversationUrl));
        if (TURN_INFO_REWRITE_SENTINEL.equals(model))
            return preferenceBypass(guard, "차기 WORK 모델 정보 재작성 요청 준비");
        boolean general = SelfRunScript.isGeneralChatUrl(conversationUrl);
        return model(guard, model, WebUiCalibrationStore.workModelPurpose(general, false));
    }

    static String reasoningForConversation(String conversationUrl, String reasoning) {
        String guard = conversationGuard(SelfRunScript.conversationId(conversationUrl));
        if (TURN_INFO_REWRITE_SENTINEL.equals(reasoning))
            return preferenceBypass(guard, "차기 WORK 추론 정보 재작성 요청 준비");
        boolean general = SelfRunScript.isGeneralChatUrl(conversationUrl);
        return reasoning(guard, reasoning, WebUiCalibrationStore.workReasoningPurpose(general, false));
    }

    private static String preferenceBypass(String guard, String detail) {
        return "(() =>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,diagnostics,url:location.href});"
                + guard + "return result('READY'," + q(detail) + ",{bypassed:true});})()";
    }

    private static String model(String guard, String wanted, String calibrationPurpose) {
        return "(() =>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,diagnostics,url:location.href});"
                + guard + WebUiCalibrationDom.runtimePrelude() + helpers()
                + "const wanted=" + q(wanted) + ";const calibrationPurpose=" + q(calibrationPurpose) + ";const modelOf=s=>{const v=text(s),m=v.match(/(?:^|\\s)(sol|terra|luna)(?:\\s|$)/);return m?m[1]:''};const direct=/^(?:(?:gpt-?)?5(?:\\.6)?\\s+)?(?:sol|terra|luna)(\\s|$)/;"
                + "const options=[...document.querySelectorAll('[role=\"menuitemradio\"],[role=\"radio\"],[role=\"option\"],[role=\"menuitem\"]')].filter(visible).filter(e=>{const role=e.getAttribute('role')||'',l=label(e);return !!modelOf(l)&&(role!=='menuitem'||direct.test(l))});const semanticOption=options.find(e=>modelOf(label(e))===wanted);"
                + "const calibratedTarget=__srFind(calibrationPurpose);const calibratedTrigger=menuTrigger(calibratedTarget)?calibratedTarget:null;const calibratedOption=calibratedTarget&&!calibratedTrigger?calibratedTarget:null;const calibratedWanted=calibratedOption&&modelOf(label(calibratedOption))===wanted?calibratedOption:null;const option=semanticOption||calibratedWanted;"
                + "const level=[...document.querySelectorAll('[role=\"menuitem\"]')].filter(visible).find(e=>/^(model|모델)(\\s|$)/.test(label(e)));const heuristicTrigger=[...document.querySelectorAll('button,[role=\"button\"],[aria-haspopup],[aria-expanded]')].filter(visible).filter(near).find(e=>menuTrigger(e)&&!!modelOf(label(e)));const trigger=calibratedTrigger||heuristicTrigger;const source=calibratedTrigger?'calibrated-trigger':(heuristicTrigger?'heuristic':'none');const expanded=!!trigger&&trigger.getAttribute('aria-expanded')==='true',current=trigger?modelOf(label(trigger)):'';"
                + "const workModeFallback=[...document.querySelectorAll('button,[role=\"button\"],[role=\"radio\"],[role=\"tab\"]')].filter(visible).filter(near).find(e=>/^(work|작업)(\\s|$)/.test(label(e)))||__srFind(" + q(WebUiCalibrationStore.PURPOSE_MODE_WORK) + ");"
                + "let ready=false,action='';if(option){if(selected(option)){ready=true;if(expanded){toggleMenu(trigger,false);action='close-selected-model-menu'}}else{option.click();action='select-model'}}else if(trigger&&current===wanted){ready=true}else if(level){level.click();action='open-model-menu'}else if(trigger&&!expanded){toggleMenu(trigger,true);action='open-work-settings-menu'}else if(workModeFallback){activate(workModeFallback);action='open-work-mode-fallback'}"
                + "const diagnostics={requested:wanted,calibrationPurpose,ready,action,current,source,calibratedTargetFound:!!calibratedTarget,calibratedTargetIsTrigger:!!calibratedTrigger,calibratedOptionFound:!!calibratedOption,triggerFound:!!trigger,triggerExpanded:expanded,levelFound:!!level,optionFound:!!option,workModeFallbackFound:!!workModeFallback};if(action)return result('UI_WAIT','Work 모델 반영 대기',diagnostics);if(!ready)return result('UI_WAIT','Work 모델 선택 요소 대기',diagnostics);return result('READY','모델 적용 확인',diagnostics);})()";
    }

    private static String reasoning(String guard, String wanted, String calibrationPurpose) {
        return "(() =>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,diagnostics,url:location.href});"
                + guard + WebUiCalibrationDom.runtimePrelude() + helpers()
                + "const wanted=" + q(wanted) + ";const calibrationPurpose=" + q(calibrationPurpose) + ";const effort=s=>{const v=text(s);if(v.includes('ultra')||v.includes('울트라'))return'ultra';if(v.includes('xhigh')||v.includes('extra high')||v.includes('very high')||v.includes('매우 높음'))return'xhigh';if(v.includes('maximum')||v.includes('max')||v.includes('최대'))return'max';if(v.includes('medium')||v.includes('중간'))return'medium';if(v.includes('light')||v.includes('가벼움'))return'light';if(v.includes('high')||v.includes('높음'))return'high';return''};const direct=/^(ultra|울트라|very high|extra high|xhigh|매우 높음|maximum|max|최대|medium|중간|light|가벼움|high|높음)(\\s|$)/;"
                + "const options=[...document.querySelectorAll('[role=\"menuitemradio\"],[role=\"radio\"],[role=\"option\"],[role=\"menuitem\"]')].filter(visible).filter(e=>{const role=e.getAttribute('role')||'',l=label(e);return !!effort(l)&&(role!=='menuitem'||direct.test(l))});const semanticOption=options.find(e=>effort(label(e))===wanted);"
                + "const calibratedTarget=__srFind(calibrationPurpose);const calibratedTrigger=menuTrigger(calibratedTarget)?calibratedTarget:null;const calibratedOption=calibratedTarget&&!calibratedTrigger?calibratedTarget:null;const calibratedWanted=calibratedOption&&effort(label(calibratedOption))===wanted?calibratedOption:null;const option=semanticOption||calibratedWanted;"
                + "const level=[...document.querySelectorAll('[role=\"menuitem\"]')].filter(visible).find(e=>/^(reasoning (level|effort)|추론 (수준|강도|정도))(\\s|$)/.test(label(e)));const heuristicTrigger=[...document.querySelectorAll('button,[role=\"button\"],[aria-haspopup],[aria-expanded]')].filter(visible).filter(near).find(e=>menuTrigger(e)&&!!effort(label(e)));const trigger=calibratedTrigger||heuristicTrigger;const source=calibratedTrigger?'calibrated-trigger':(heuristicTrigger?'heuristic':'none');const expanded=!!trigger&&trigger.getAttribute('aria-expanded')==='true',current=trigger?effort(label(trigger)):'';"
                + "const workModeFallback=[...document.querySelectorAll('button,[role=\"button\"],[role=\"radio\"],[role=\"tab\"]')].filter(visible).filter(near).find(e=>/^(work|작업)(\\s|$)/.test(label(e)))||__srFind(" + q(WebUiCalibrationStore.PURPOSE_MODE_WORK) + ");"
                + "let ready=false,action='';if(option){if(selected(option)){ready=true;if(expanded){toggleMenu(trigger,false);action='close-selected-effort-menu'}}else{option.click();action='select-effort'}}else if(trigger&&current===wanted){ready=true;if(expanded){toggleMenu(trigger,false);action='close-selected-effort-menu'}}else if(level){level.click();action='open-effort-menu'}else if(trigger&&!expanded){toggleMenu(trigger,true);action='open-reasoning-menu'}else if(workModeFallback){activate(workModeFallback);action='open-work-mode-fallback'}"
                + "const diagnostics={requested:wanted,calibrationPurpose,ready,action,current,source,calibratedTargetFound:!!calibratedTarget,calibratedTargetIsTrigger:!!calibratedTrigger,calibratedOptionFound:!!calibratedOption,triggerFound:!!trigger,triggerExpanded:expanded,levelFound:!!level,optionFound:!!option,workModeFallbackFound:!!workModeFallback};if(action)return result('UI_WAIT','추론 강도 반영 대기',diagnostics);if(!ready)return result('UI_WAIT','추론 강도 선택 요소 대기',diagnostics);return result('READY','추론 적용 확인',diagnostics);})()";
    }

    private static String helpers() {
        return "const text=s=>String(s??'').replace(/\\s+/g,' ').trim().toLowerCase();const label=e=>text(e?.innerText||'')||text(e?.getAttribute?.('aria-label')||'');const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;"
                + "const input=document.querySelector('#prompt-textarea')||[...document.querySelectorAll('textarea,[contenteditable=\"true\"]')].filter(visible).sort((a,b)=>b.getBoundingClientRect().bottom-a.getBoundingClientRect().bottom)[0]||null;const form=input?.closest?.('form')||null;const rectNear=e=>{const a=e.getBoundingClientRect(),b=input.getBoundingClientRect();return a.bottom>=b.top-240&&a.top<=b.bottom+240&&a.right>=b.left-320&&a.left<=b.right+320};const near=e=>!!e&&!!input&&(!form||form.contains(e)||rectNear(e));"
                + "const selected=e=>!!e&&(e.getAttribute('aria-checked')==='true'||e.getAttribute('aria-pressed')==='true'||e.getAttribute('aria-selected')==='true'||/^(checked|selected|active|on)$/.test(text(e.dataset?.state||'')));const optionRole=e=>/^(menuitemradio|radio|option|menuitem)$/.test(text(e?.getAttribute?.('role')||''));const insideChoice=e=>!!e?.closest?.('[role=\"menu\"],[role=\"listbox\"],[role=\"dialog\"]');const menuTrigger=e=>{if(!e||optionRole(e)||insideChoice(e))return false;const popup=text(e.getAttribute?.('aria-haspopup')||'');return/^(menu|listbox|dialog|true)$/.test(popup)||e.getAttribute?.('aria-expanded')!==null||!!e.matches?.('button,[role=\"button\"]')};"
                + "const desired=e=>e?.getAttribute?.('aria-expanded');const reached=(e,want)=>desired(e)!==null&&desired(e)===String(want);const pointer=e=>{const init={bubbles:true,cancelable:true,composed:true,button:0,buttons:1,pointerId:1,pointerType:'mouse',isPrimary:true};if(typeof PointerEvent==='function')e.dispatchEvent(new PointerEvent('pointerdown',init));else return false;return true};const mouse=e=>{e.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true,composed:true,button:0,buttons:1}));};const toggleMenu=(e,want)=>{if(!e)return;e.focus?.();const tracked=desired(e)!==null;e.click?.();if(!tracked||reached(e,want))return;if(pointer(e)&&reached(e,want))return;mouse(e)};const activate=e=>{if(!e)return;if(menuTrigger(e))toggleMenu(e,true);else{e.focus?.();e.click?.();}};";
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
