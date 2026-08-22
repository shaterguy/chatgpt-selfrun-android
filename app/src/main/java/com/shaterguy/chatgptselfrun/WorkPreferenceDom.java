package com.shaterguy.chatgptselfrun;

/** Work model/reasoning selector with semantic calibration validation and finite retries. */
final class WorkPreferenceDom {
    static final String TURN_INFO_REWRITE_SENTINEL = "__SELF_RUN_TURN_INFO_REWRITE__";
    private WorkPreferenceDom() {}

    static String modelForProject(String projectUrl, String model) {
        boolean general = SelfRunScript.isGeneralChatUrl(projectUrl);
        return preference(projectGuard(SelfRunScript.projectId(projectUrl)), "model", model,
                WebUiCalibrationStore.workModelPurpose(general, true));
    }

    static String reasoningForProject(String projectUrl, String reasoning) {
        boolean general = SelfRunScript.isGeneralChatUrl(projectUrl);
        return preference(projectGuard(SelfRunScript.projectId(projectUrl)), "reasoning", reasoning,
                WebUiCalibrationStore.workReasoningPurpose(general, true));
    }

    static String modelForConversation(String conversationUrl, String model) {
        String guard = conversationGuard(SelfRunScript.conversationId(conversationUrl));
        if (TURN_INFO_REWRITE_SENTINEL.equals(model)) return preferenceBypass(guard, "차기 WORK 모델 정보 재작성 요청 준비");
        boolean general = SelfRunScript.isGeneralChatUrl(conversationUrl);
        return preference(guard, "model", model, WebUiCalibrationStore.workModelPurpose(general, false));
    }

    static String reasoningForConversation(String conversationUrl, String reasoning) {
        String guard = conversationGuard(SelfRunScript.conversationId(conversationUrl));
        if (TURN_INFO_REWRITE_SENTINEL.equals(reasoning)) return preferenceBypass(guard, "차기 WORK 추론 정보 재작성 요청 준비");
        boolean general = SelfRunScript.isGeneralChatUrl(conversationUrl);
        return preference(guard, "reasoning", reasoning, WebUiCalibrationStore.workReasoningPurpose(general, false));
    }

    private static String preferenceBypass(String guard, String detail) {
        return "(() =>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,diagnostics,url:location.href});"
                + guard + "return result('READY'," + q(detail) + ",{bypassed:true});})()";
    }

    private static String preference(String guard, String kind, String wanted, String calibrationPurpose) {
        String body = """
                const __wpKind=__KIND__,__wpWanted=__WANTED__,__wpPurpose=__PURPOSE__;
                const __wpText=s=>String(s??'').replace(/\\s+/g,' ').trim().toLowerCase();
                const __wpLabel=e=>__wpText(e?.getAttribute?.('aria-label')||'')||__wpText(e?.innerText||'');
                const __wpVisible=e=>!!e&&e.isConnected&&e.offsetParent!==null;
                const __wpParse=source=>{
                  const v=__wpText(source);
                  if(__wpKind==='model'){const m=v.match(/(?:^|\\s)(sol|terra|luna)(?:\\s|$)/);return m?m[1]:'';}
                  if(v.includes('ultra')||v.includes('울트라'))return'ultra';
                  if(v.includes('xhigh')||v.includes('extra high')||v.includes('very high')||v.includes('매우 높음'))return'xhigh';
                  if(v.includes('maximum')||v.includes('max')||v.includes('최대'))return'max';
                  if(v.includes('medium')||v.includes('중간'))return'medium';
                  if(v.includes('light')||v.includes('가벼움'))return'light';
                  if(v.includes('high')||v.includes('높음'))return'high';
                  return'';
                };
                const __wpRowLabel=label=>__wpKind==='model'?/^(model|모델)(?:\\s|$)/.test(label):/^(reasoning(?:\\s+(?:level|effort))?|추론(?:\\s*(?:수준|강도|정도)))(?:\\s|$)/.test(label);
                const __wpShowAdvancedLabel=label=>/^(?:show\\s+advanced(?:\\s+options)?|advanced(?:\\s+options)?|고급(?:\\s+옵션)?(?:\\s+표시)?)(?:\\s|$)/.test(label);
                const __wpDirect=label=>__wpKind==='model'?/^(?:(?:gpt-?)?5(?:\\.6)?\\s+)?(?:sol|terra|luna)(?:\\s|$)/.test(label):/^(ultra|울트라|very high|extra high|xhigh|매우 높음|maximum|max|최대|medium|중간|light|가벼움|high|높음)(?:\\s|$)/.test(label);
                const __wpPopupSelector='[role="menu"],[role="listbox"],[role="dialog"],[data-radix-popper-content-wrapper],[data-slot*="popover-content"],[data-slot*="menu-content"]';
                const __wpOwner=e=>e?.closest?.('button,[role="button"],[role="menuitem"],[role="menuitemradio"],[role="radio"],[role="option"],[aria-haspopup],[aria-expanded]')||e||null;
                const __wpActiveView=e=>!!e&&!e.closest?.('[inert],[aria-hidden="true"],[data-active="false"]');
                const __wpOptionRole=e=>/^(menuitemradio|radio|option|menuitem)$/.test(__wpText(e?.getAttribute?.('role')||''));
                const __wpInsideChoice=e=>!!e?.closest?.(__wpPopupSelector);
                const __wpSelected=e=>!!e&&(e.getAttribute?.('aria-checked')==='true'||e.getAttribute?.('aria-pressed')==='true'||e.getAttribute?.('aria-selected')==='true'||/^(checked|selected|active|on)$/.test(__wpText(e.dataset?.state||'')));
                const __wpMenuTrigger=e=>{if(!e||__wpOptionRole(e)||__wpInsideChoice(e))return false;const popup=__wpText(e.getAttribute?.('aria-haspopup')||'');return/^(menu|listbox|dialog|true)$/.test(popup)||e.getAttribute?.('aria-expanded')!==null||!!e.matches?.('button,[role="button"]');};
                const __wpInput=document.querySelector('#prompt-textarea')||[...document.querySelectorAll('textarea,[contenteditable="true"]')].filter(__wpVisible).sort((a,b)=>b.getBoundingClientRect().bottom-a.getBoundingClientRect().bottom)[0]||null;
                const __wpForm=__wpInput?.closest?.('form')||null;
                const __wpNear=e=>{if(!e||!__wpInput)return false;if(__wpForm?.contains?.(e))return true;const a=e.getBoundingClientRect?.(),b=__wpInput.getBoundingClientRect?.();return !!a&&!!b&&a.bottom>=b.top-260&&a.top<=b.bottom+260&&a.right>=b.left-360&&a.left<=b.right+360;};
                const __wpOpenPopups=[...document.querySelectorAll(__wpPopupSelector)].filter(__wpVisible);
                const __wpPopupElements=[];for(const popup of __wpOpenPopups)for(const raw of popup.querySelectorAll('button,[role="button"],[role="menuitem"],[role="menuitemradio"],[role="radio"],[role="option"]')){const owner=__wpOwner(raw);if(owner&&__wpVisible(owner)&&__wpActiveView(owner)&&!__wpPopupElements.includes(owner))__wpPopupElements.push(owner);}
                const __wpOptions=__wpPopupElements.filter(e=>{const label=__wpLabel(e);return !!__wpParse(label)&&(__wpOptionRole(e)?__wpDirect(label):false);});
                const __wpSemanticOption=__wpOptions.find(e=>__wpParse(__wpLabel(e))===__wpWanted)||null;
                const __wpLevel=__wpPopupElements.find(e=>__wpRowLabel(__wpLabel(e)))||null;
                const __wpShowAdvanced=__wpPopupElements.find(e=>__wpShowAdvancedLabel(__wpLabel(e)))||null;
                const __wpCalibratedRaw=__srFind(__wpPurpose),__wpCalibrated=__wpOwner(__wpCalibratedRaw);
                const __wpCalibratedLabel=__wpLabel(__wpCalibrated),__wpCalibratedMeaning=__wpParse(__wpCalibratedLabel);
                const __wpCalibratedOptionValid=!!__wpCalibrated&&__wpOptionRole(__wpCalibrated)&&__wpCalibratedMeaning===__wpWanted;
                const __wpCalibratedTriggerValid=!!__wpCalibrated&&__wpMenuTrigger(__wpCalibrated)&&(!!__wpCalibratedMeaning||__wpRowLabel(__wpCalibratedLabel));
                const __wpCalibratedValid=__wpCalibratedOptionValid||__wpCalibratedTriggerValid;
                const __wpCalibratedOption=__wpCalibratedOptionValid?__wpCalibrated:null;
                const __wpCalibratedTrigger=__wpCalibratedTriggerValid?__wpCalibrated:null;
                const __wpHeuristicTriggers=[...document.querySelectorAll('button,[role="button"],[aria-haspopup],[aria-expanded]')].filter(__wpVisible).filter(e=>__wpMenuTrigger(e)&&!!__wpParse(__wpLabel(e)));
                const __wpHeuristicTrigger=__wpHeuristicTriggers.find(__wpNear)||__wpHeuristicTriggers[0]||null;
                const __wpTrigger=__wpCalibratedTrigger||__wpHeuristicTrigger;
                const __wpSource=__wpCalibratedTrigger?'calibrated-trigger':(__wpHeuristicTrigger?'heuristic-trigger':(__wpCalibratedOption?'calibrated-option':'none'));
                const __wpOption=__wpSemanticOption||__wpCalibratedOption;
                const __wpCurrent=__wpTrigger?__wpParse(__wpLabel(__wpTrigger)):'';
                const __wpSelectedLevels=[...new Set(__wpOptions.filter(__wpSelected).map(e=>__wpParse(__wpLabel(e))).filter(Boolean))];
                const __wpWorkFallback=[...document.querySelectorAll('button,[role="button"],[role="radio"],[role="tab"]')].filter(__wpVisible).filter(__wpNear).find(e=>/^(work|작업)(?:\\s|$)/.test(__wpLabel(e)))||__srFind('MODE_WORK');
                const __wpStateKey='selfrun-drive:work-preference:'+__wpKind+':'+__wpPurpose+':'+location.pathname;
                const __wpNow=Date.now(),__wpTimeoutMs=20000,__wpRetryMs=3500,__wpMaxAttempts=24;
                let __wpState={startedAt:0,requested:'',attempts:0,triggerClicks:0,advancedClicks:0,rowClicks:0,optionClicks:0,fallbackClicks:0,closeAttempts:0,pending:false,lastAction:'',lastActionAt:0};
                try{const raw=sessionStorage.getItem(__wpStateKey)||localStorage.getItem(__wpStateKey)||'';if(raw)__wpState={...__wpState,...JSON.parse(raw)};}catch(_){}
                if(__wpState.requested&&__wpState.requested!==__wpWanted)__wpState={startedAt:0,requested:__wpWanted,attempts:0,triggerClicks:0,advancedClicks:0,rowClicks:0,optionClicks:0,fallbackClicks:0,closeAttempts:0,pending:false,lastAction:'',lastActionAt:0};
                if(!(Number(__wpState.startedAt)>0))__wpState.startedAt=__wpNow;
                __wpState.requested=__wpWanted;__wpState.attempts=Math.max(0,Number(__wpState.attempts)||0)+1;
                const __wpElapsedMs=Math.max(0,__wpNow-Number(__wpState.startedAt||__wpNow));
                const __wpSinceActionMs=Number(__wpState.lastActionAt)>0?Math.max(0,__wpNow-Number(__wpState.lastActionAt)):Number.MAX_SAFE_INTEGER;
                const __wpSave=()=>{const value=JSON.stringify(__wpState);try{sessionStorage.setItem(__wpStateKey,value);}catch(_){}try{localStorage.setItem(__wpStateKey,value);}catch(_){}};
                const __wpClear=()=>{try{sessionStorage.removeItem(__wpStateKey);}catch(_){}try{localStorage.removeItem(__wpStateKey);}catch(_){}};
                const __wpMayClick=(count,max)=>Number(count)<1||(__wpSinceActionMs>=__wpRetryMs&&Number(count)<max);
                const __wpDesired=e=>e?.getAttribute?.('aria-expanded');
                const __wpReached=(e,want)=>__wpDesired(e)!==null&&__wpDesired(e)===String(want);
                const __wpPointer=e=>{if(typeof PointerEvent!=='function')return false;e.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,cancelable:true,composed:true,button:0,buttons:1,pointerId:1,pointerType:'mouse',isPrimary:true}));return true;};
                const __wpMouse=e=>e.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true,composed:true,button:0,buttons:1}));
                const __wpToggleMenu=(e,want)=>{if(!e)return;e.focus?.();const tracked=__wpDesired(e)!==null;e.click?.();if(!tracked||__wpReached(e,want))return;if(__wpPointer(e)&&__wpReached(e,want))return;__wpMouse(e);};
                const __wpActivate=e=>{if(!e)return;if(__wpMenuTrigger(e))__wpToggleMenu(e,true);else{e.focus?.();e.click?.();}};
                const __wpClose=()=>{const expanded=__wpTrigger?.getAttribute?.('aria-expanded');if(__wpTrigger&&(expanded==='true'||(expanded===null&&__wpOpenPopups.length>0))){__wpToggleMenu(__wpTrigger,false);return expanded===null?'trigger-untracked':'trigger';}document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true,cancelable:true}));return'escape';};
                const __wpDiagnostics=()=>({kind:__wpKind,requested:__wpWanted,calibrationPurpose:__wpPurpose,current:__wpCurrent,source:__wpSource,calibratedTargetFound:!!__wpCalibrated,calibratedTargetValid:__wpCalibratedValid,calibratedMeaning:__wpCalibratedMeaning,triggerFound:!!__wpTrigger,triggerExpanded:__wpTrigger?.getAttribute?.('aria-expanded')==='true',showAdvancedFound:!!__wpShowAdvanced,levelFound:!!__wpLevel,optionFound:!!__wpOption,openPopupCandidates:__wpOpenPopups.length,selectedLevels:__wpSelectedLevels,attempts:__wpState.attempts,triggerClicks:__wpState.triggerClicks,advancedClicks:__wpState.advancedClicks,rowClicks:__wpState.rowClicks,optionClicks:__wpState.optionClicks,fallbackClicks:__wpState.fallbackClicks,closeAttempts:__wpState.closeAttempts,pending:!!__wpState.pending,lastAction:__wpState.lastAction||'',elapsedMs:__wpElapsedMs,timeoutMs:__wpTimeoutMs});
                const __wpFailure=(suffix,detail,extra={})=>{__wpSave();return result('WORK_'+(__wpKind==='model'?'MODEL':'REASONING')+'_'+suffix,detail,{...__wpDiagnostics(),...extra});};
                const __wpResult=(status,detail,extra={})=>{__wpSave();return result(status,detail,{...__wpDiagnostics(),...extra});};
                const __wpReady=(extra={})=>{const diagnostics={...__wpDiagnostics(),...extra};__wpClear();return result('READY',__wpKind==='model'?'모델 적용 확인':'추론 적용 확인',diagnostics);};
                if(__wpCurrent===__wpWanted){
                  if(__wpOpenPopups.length===0)return __wpReady({action:'already-selected'});
                  if(__wpMayClick(__wpState.closeAttempts,3)){__wpState.closeAttempts++;__wpState.lastAction='close-current-match';__wpState.lastActionAt=__wpNow;__wpSave();const method=__wpClose();return result('UI_WAIT','현재 WORK 값이 목표와 같아 열린 메뉴 닫힘 확인 대기',{...__wpDiagnostics(),action:'close-current-match',closeMethod:method});}
                  if(__wpElapsedMs>=__wpTimeoutMs||__wpState.attempts>=__wpMaxAttempts)return __wpFailure('READBACK_MISMATCH','현재 WORK 값 확인 후 메뉴가 닫히지 않았습니다.',{action:'current-match-close-timeout'});
                  return __wpResult('UI_WAIT','현재 WORK 값 확인 후 메뉴 닫힘 대기',{action:'wait-current-match-close'});
                }
                if(__wpOption&&__wpSelected(__wpOption)){
                  if(__wpOpenPopups.length===0)return __wpReady({action:'selected-option-readback'});
                  if(__wpMayClick(__wpState.closeAttempts,3)){__wpState.closeAttempts++;__wpState.lastAction='close-menu';__wpState.lastActionAt=__wpNow;__wpSave();const method=__wpClose();return result('UI_WAIT','WORK 선택 메뉴 닫힘 확인 대기',{...__wpDiagnostics(),action:'close-menu',closeMethod:method});}
                  if(__wpElapsedMs>=__wpTimeoutMs||__wpState.attempts>=__wpMaxAttempts)return __wpFailure('READBACK_MISMATCH','WORK 선택 후 메뉴 닫힘 또는 의미값을 확인하지 못했습니다.',{action:'menu-close-timeout'});
                  return __wpResult('UI_WAIT','WORK 선택 메뉴 닫힘 대기',{action:'wait-menu-close'});
                }
                if(__wpOption){
                  if(__wpMayClick(__wpState.optionClicks,2)){__wpState.pending=true;__wpState.optionClicks++;__wpState.lastAction='select-option';__wpState.lastActionAt=__wpNow;__wpSave();__wpActivate(__wpOption);return result('UI_WAIT','WORK 옵션 반영 대기',{...__wpDiagnostics(),action:'select-option'});}
                  if(__wpElapsedMs>=__wpTimeoutMs||__wpState.attempts>=__wpMaxAttempts)return __wpFailure('READBACK_MISMATCH','WORK 옵션 선택 상태를 확인하지 못했습니다.',{action:'option-readback-timeout'});
                  return __wpResult('UI_WAIT','WORK 옵션 선택 상태 대기',{action:'wait-option-readback'});
                }
                if(__wpState.pending){
                  if(__wpCurrent===__wpWanted&&__wpOpenPopups.length===0)return __wpReady({action:'trigger-readback'});
                  if(__wpElapsedMs>=__wpTimeoutMs||__wpState.attempts>=__wpMaxAttempts)return __wpFailure('READBACK_MISMATCH','WORK 옵션 적용 후 의미값을 확인하지 못했습니다.',{action:'pending-readback-timeout'});
                  return __wpResult('UI_WAIT','WORK 옵션 의미값 readback 대기',{action:'wait-pending-readback'});
                }
                if(__wpShowAdvanced){
                  if(__wpMayClick(__wpState.advancedClicks,2)){__wpState.advancedClicks++;__wpState.lastAction='open-advanced-control';__wpState.lastActionAt=__wpNow;__wpSave();__wpActivate(__wpShowAdvanced);return result('UI_WAIT','WORK 고급 메뉴 전환 반영 대기',{...__wpDiagnostics(),action:'open-advanced-control'});}
                  if(__wpElapsedMs>=__wpTimeoutMs||__wpState.attempts>=__wpMaxAttempts)return __wpFailure('SELECTION_TIMEOUT','WORK 고급 메뉴 전환 후 선택 행을 찾지 못했습니다.',{action:'advanced-transition-timeout'});
                  return __wpResult('UI_WAIT','WORK 고급 메뉴 전환 확인 대기',{action:'wait-advanced-transition'});
                }
                if(__wpLevel){
                  if(__wpMayClick(__wpState.rowClicks,2)){__wpState.rowClicks++;__wpState.lastAction='open-level-menu';__wpState.lastActionAt=__wpNow;__wpSave();__wpActivate(__wpLevel);return result('UI_WAIT','WORK 하위 선택 메뉴 열기 반영 대기',{...__wpDiagnostics(),action:'open-level-menu'});}
                  if(__wpElapsedMs>=__wpTimeoutMs||__wpState.attempts>=__wpMaxAttempts)return __wpFailure('SELECTION_TIMEOUT','WORK 하위 선택 메뉴가 나타나지 않았습니다.',{action:'level-menu-timeout'});
                  return __wpResult('UI_WAIT','WORK 하위 선택 메뉴 렌더링 대기',{action:'wait-level-menu'});
                }
                if(__wpTrigger){
                  const expanded=__wpTrigger.getAttribute?.('aria-expanded')==='true';
                  if((!expanded||__wpOpenPopups.length===0)&&__wpMayClick(__wpState.triggerClicks,2)){__wpState.triggerClicks++;__wpState.lastAction='open-settings-menu';__wpState.lastActionAt=__wpNow;__wpSave();__wpActivate(__wpTrigger);return result('UI_WAIT','WORK 설정 메뉴 열기 반영 대기',{...__wpDiagnostics(),action:'open-settings-menu'});}
                  if(__wpElapsedMs>=__wpTimeoutMs||__wpState.attempts>=__wpMaxAttempts)return __wpFailure('SELECTION_TIMEOUT','WORK 설정 메뉴에서 요청 선택기를 찾지 못했습니다.',{action:'settings-menu-timeout'});
                  return __wpResult('UI_WAIT','WORK 설정 메뉴 렌더링 대기',{action:'wait-settings-menu'});
                }
                if(__wpWorkFallback&&__wpMayClick(__wpState.fallbackClicks,1)){__wpState.fallbackClicks++;__wpState.lastAction='open-work-mode-fallback';__wpState.lastActionAt=__wpNow;__wpSave();__wpActivate(__wpWorkFallback);return result('UI_WAIT','WORK 모드 제어에서 설정 진입 대기',{...__wpDiagnostics(),action:'open-work-mode-fallback'});}
                if(__wpElapsedMs>=__wpTimeoutMs||__wpState.attempts>=__wpMaxAttempts)return __wpFailure('SELECTION_TIMEOUT','WORK 모델·추론 선택 요소를 제한시간 안에 찾지 못했습니다.',{action:'selector-timeout'});
                return __wpResult('UI_WAIT','WORK 모델·추론 선택 요소 대기',{action:'wait-selector'});
                """;
        return "(() =>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,diagnostics,url:location.href});"
                + guard + WebUiCalibrationDom.runtimePrelude()
                + body.replace("__KIND__", q(kind)).replace("__WANTED__", q(wanted)).replace("__PURPOSE__", q(calibrationPurpose))
                + "})()";
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
