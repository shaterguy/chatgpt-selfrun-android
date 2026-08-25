package com.shaterguy.chatgptselfrun;

/**
 * Chat reasoning selector for the current UI sequence:
 * current reasoning control -> slider sheet -> Advanced -> menu options.
 * The slider is observed only to identify the intermediate sheet and is never mutated.
 */
final class ChatReasoningOptionDom {
    private ChatReasoningOptionDom() {}

    static String inline(String selection, String runId) {
        String wanted = ChatReasoningPreferenceStore.normalize(selection);
        boolean captureOnly = ChatReasoningPreferenceStore.KEEP.equals(wanted);
        int ordinal = ChatReasoningPreferenceStore.ordinal(wanted);
        if (ordinal < 0 && !captureOnly) return "";
        return """
                const __sroWanted=__WANTED__,__sroWantedOrdinal=__ORDINAL__,__sroRunId=__RUN_ID__,__sroCaptureOnly=__CAPTURE_ONLY__;
                if(__sroCaptureOnly&&typeof requestedMode!=='undefined'&&requestedMode==='work')return result('READY','WORK 모드에서는 Chat picker 현재값 캡처를 생략합니다.',{strategy:'advanced-menu',action:'skip-chat-picker-work',currentMode:'work'});
                const __sroLevel=source=>{
                  let v=exactText(source).replace(/^[✓✔☑●•·\\s]+/,'');
                  if(/^(extra high|very high|xhigh|maximum|매우\\s*높음|최대)(?:\\s|$)/.test(v))return'xhigh';
                  if(/^(?:pro[\\s·:—-]*standard|프로[\\s·:—-]*표준)(?:\\s|$)/.test(v))return'pro_standard';
                  if(/^(?:pro[\\s·:—-]*extended|프로[\\s·:—-]*확장)(?:\\s|$)/.test(v))return'pro_extended';
                  if(/^(?:pro|프로)(?:\\s|$)/.test(v))return'pro';
                  if(/^(medium|중간|표준|standard)(?:\\s|$)/.test(v))return'medium';
                  if(/^(high|높음|extended|확장)(?:\\s|$)/.test(v))return'high';
                  if(/^(instant|flash|빠른|즉시)(?:\\s|$)/.test(v))return'instant';
                  return'';
                };
                const __sroPopupSelector='[role="menu"],[role="listbox"],[role="dialog"],[data-radix-popper-content-wrapper],[data-slot*="popover-content"],[data-slot*="menu-content"],[data-slot*="sheet-content"]';
                const __sroInteractive='button,[role="button"],[role="menuitem"],[role="menuitemradio"],[role="radio"],[role="option"],[aria-haspopup],[aria-expanded],[data-value]';
                const __sroForbidden=element=>/(send|submit|보내기|stop|중지|microphone|마이크|voice|음성|attach|첨부|upload|업로드|new chat|new conversation|새 채팅|새 대화)/.test(labelOf(element)+' '+exactText(element?.dataset?.testid||''));
                const __sroOwner=element=>element?.closest?.(__sroInteractive)||element||null;
                const __sroLabel=element=>{const owner=__sroOwner(element)||element;return exactText(owner?.getAttribute?.('aria-label')||'')||labelOf(owner);};
                const __sroActiveView=element=>!!element&&!element.closest?.('[inert],[aria-hidden="true"],[data-active="false"]');
                const __sroReasoningRowLabel=label=>/^(reasoning(?:\\s+(?:level|effort))?|추론(?:\\s*(?:수준|강도|정도)))(?:\\s|$)/.test(label);
                const __sroShowAdvancedLabel=label=>/^(?:show\\s+advanced(?:\\s+options)?|advanced(?:\\s+options)?|고급(?:\\s+옵션)?(?:\\s+표시)?)(?:\\s|$)/.test(label);
                const __sroDirectLevel=element=>{
                  const owner=__sroOwner(element);if(!owner)return'';
                  const label=__sroLabel(owner);
                  if(__sroReasoningRowLabel(label)||/^(model|모델)(?:\\s|$)/.test(label))return'';
                  const role=exactText(owner.getAttribute?.('role')||'');
                  if(!/^(menuitemradio|radio|option|menuitem)$/.test(role)&&owner.tagName!=='BUTTON'&&!owner.hasAttribute?.('data-value'))return'';
                  return __sroLevel(label);
                };
                const __sroInput=(typeof composer!=='undefined'&&composer)||document.querySelector('#prompt-textarea')||[...document.querySelectorAll('textarea,[contenteditable="true"]')].filter(visible).sort((a,b)=>b.getBoundingClientRect().bottom-a.getBoundingClientRect().bottom)[0]||null;
                const __sroForm=__sroInput?.closest?.('form')||null;
                const __sroNear=element=>{
                  if(!element||!__sroInput)return false;
                  if(__sroForm?.contains?.(element))return true;
                  const a=element.getBoundingClientRect?.(),b=__sroInput.getBoundingClientRect?.();
                  return !!a&&!!b&&a.bottom>=b.top-280&&a.top<=b.bottom+280&&a.right>=b.left-380&&a.left<=b.right+380;
                };
                const __sroTriggerScore=element=>{
                  const label=labelOf(element),testid=exactText(element?.dataset?.testid||''),popup=exactText(element.getAttribute?.('aria-haspopup')||'');
                  let score=0;if(__sroLevel(label))score+=170;if(/reason|thinking|추론/.test(label+' '+testid))score+=75;if(/model|모델|gpt|flash/.test(label+' '+testid))score+=30;
                  if(/^(menu|listbox|dialog|true)$/.test(popup))score+=45;if(element.hasAttribute?.('aria-expanded'))score+=35;if(element.hasAttribute?.('aria-controls')||element.hasAttribute?.('aria-owns'))score+=25;if(__sroNear(element))score+=190;return score;
                };
                const __sroTriggerEntries=[...document.querySelectorAll('button,[role="button"],[role="combobox"],[aria-haspopup],[aria-expanded],[data-testid*="model"],[data-testid*="reason"]')]
                  .filter(visible).filter(element=>!element.closest(__sroPopupSelector)).filter(element=>!__sroForbidden(element))
                  .map((element,index)=>({element,index,score:__sroTriggerScore(element)})).filter(entry=>entry.score>0)
                  .sort((a,b)=>b.score-a.score||a.index-b.index);
                const __sroAnimatedMark=[...document.querySelectorAll('[data-animated-slider-trigger="true"]')].find(visible)||null;
                const __sroAnimatedTrigger=__sroOwner(__sroAnimatedMark);
                const __sroExactTrigger=__sroAnimatedTrigger&&visible(__sroAnimatedTrigger)&&!__sroAnimatedTrigger.closest(__sroPopupSelector)&&!__sroForbidden(__sroAnimatedTrigger)&&__sroNear(__sroAnimatedTrigger)?__sroAnimatedTrigger:null;
                const __sroTrigger=__sroExactTrigger||__sroTriggerEntries[0]?.element||null;
                const __sroTriggerLevel=__sroTrigger?__sroLevel(labelOf(__sroTrigger)):'';
                const __sroControlledIds=__sroTrigger?String(__sroTrigger.getAttribute('aria-controls')||__sroTrigger.getAttribute('aria-owns')||'').split(/\\s+/).filter(Boolean):[];
                const __sroControlled=__sroControlledIds.map(id=>document.getElementById(id)).find(visible)||null;
                const __sroOpenPopups=[...document.querySelectorAll(__sroPopupSelector)].filter(visible);
                const __sroPopups=[__sroControlled,...__sroOpenPopups].filter((popup,index,all)=>popup&&all.indexOf(popup)===index);
                const __sroPopupElements=[];
                for(const popup of __sroPopups)for(const raw of popup.querySelectorAll(__sroInteractive)){const owner=__sroOwner(raw);if(owner&&visible(owner)&&__sroActiveView(owner)&&!__sroPopupElements.includes(owner))__sroPopupElements.push(owner);}
                const __sroSliders=[...document.querySelectorAll('[role="slider"],input[type="range"]')].filter(visible);
                const __sroSliderObserved=__sroSliders.some(slider=>__sroPopups.some(popup=>popup.contains(slider)));
                const __sroAdvancedButtons=__sroPopupElements.filter(element=>__sroShowAdvancedLabel(__sroLabel(element))&&!__sroDirectLevel(element));
                const __sroAdvancedButton=__sroAdvancedButtons.find(element=>__sroPopups.some(popup=>popup.contains(element)&&!!popup.querySelector('[role="slider"],input[type="range"]')))||__sroAdvancedButtons[0]||null;
                const __sroReasoningRows=__sroPopupElements.filter(element=>__sroReasoningRowLabel(__sroLabel(element))&&!__sroDirectLevel(element));
                const __sroReasoningRow=__sroReasoningRows[0]||null;
                const __sroDirectEntries=__sroPopupElements.map((element,index)=>({element,index,level:__sroDirectLevel(element)})).filter(entry=>!!entry.level);
                const __sroWantedOption=__sroDirectEntries.find(entry=>entry.level===__sroWanted)||null;
                const __sroSelectedLevels=[...new Set(__sroDirectEntries.filter(entry=>selectedState(entry.element)).map(entry=>entry.level))];
                const __sroStateKey='selfrun-drive:chat-reasoning-menu:'+__sroRunId;
                const __sroNow=Date.now(),__sroOverallTimeoutMs=24000,__sroRenderTimeoutMs=9000,__sroRetryMs=3600,__sroMaxAttempts=28;
                let __sroState={startedAt:0,requested:'',attempts:0,triggerClicks:0,advancedClicks:0,reasoningClicks:0,optionClicks:0,closeAttempts:0,pending:false,lastAction:'',lastActionAt:0};
                try{const saved=sessionStorage.getItem(__sroStateKey)||localStorage.getItem(__sroStateKey)||'';if(saved)__sroState={...__sroState,...JSON.parse(saved)};}catch(_){}
                if(__sroState.requested&&__sroState.requested!==__sroWanted)__sroState={startedAt:0,requested:__sroWanted,attempts:0,triggerClicks:0,advancedClicks:0,reasoningClicks:0,optionClicks:0,closeAttempts:0,pending:false,lastAction:'',lastActionAt:0};
                if(!(Number(__sroState.startedAt)>0))__sroState.startedAt=__sroNow;
                __sroState.requested=__sroWanted;__sroState.attempts=Math.max(0,Number(__sroState.attempts)||0)+1;
                const __sroElapsedMs=Math.max(0,__sroNow-Number(__sroState.startedAt||__sroNow));
                const __sroSinceActionMs=Number(__sroState.lastActionAt)>0?Math.max(0,__sroNow-Number(__sroState.lastActionAt)):Number.MAX_SAFE_INTEGER;
                const __sroSave=()=>{const value=JSON.stringify(__sroState);try{sessionStorage.setItem(__sroStateKey,value);}catch(_){}try{localStorage.setItem(__sroStateKey,value);}catch(_){}};
                const __sroClear=()=>{try{sessionStorage.removeItem(__sroStateKey);}catch(_){}try{localStorage.removeItem(__sroStateKey);}catch(_){}};
                const __sroStage=__sroWantedOption?'OPTION':(__sroAdvancedButton?'ADVANCED_BUTTON':(__sroReasoningRow?'REASONING_MENU':(__sroSliderObserved?'SLIDER_SHEET':(__sroPopups.length?'UNKNOWN_POPUP':'TRIGGER'))));
                const __sroDiagnostics=extra=>({strategy:'advanced-menu',stage:__sroStage,requested:__sroWanted,requestedOrdinal:__sroWantedOrdinal,triggerFound:!!__sroTrigger,exactAnimatedTrigger:!!__sroExactTrigger,triggerCandidates:__sroTriggerEntries.length,triggerLabel:__sroTrigger?__sroLabel(__sroTrigger):'',triggerLevel:__sroTriggerLevel,triggerExpanded:__sroTrigger?.getAttribute?.('aria-expanded')||'',triggerState:__sroTrigger?.getAttribute?.('data-state')||'',popupCandidates:__sroPopups.length,sliderObserved:__sroSliderObserved,advancedButtonFound:!!__sroAdvancedButton,reasoningRowFound:!!__sroReasoningRow,directOptionCandidates:__sroDirectEntries.length,wantedOptionFound:!!__sroWantedOption,selectedLevels:__sroSelectedLevels,attempts:__sroState.attempts,triggerClicks:__sroState.triggerClicks,advancedClicks:__sroState.advancedClicks,reasoningClicks:__sroState.reasoningClicks,optionClicks:__sroState.optionClicks,closeAttempts:__sroState.closeAttempts,pending:!!__sroState.pending,lastAction:__sroState.lastAction||'',elapsedMs:__sroElapsedMs,overallTimeoutMs:__sroOverallTimeoutMs,...extra});
                const __sroResult=(status,detail,extra={})=>{__sroSave();return result(status,detail,__sroDiagnostics(extra));};
                const __sroReady=(observed,extra={})=>{const diagnostics=__sroDiagnostics({observed,...extra});__sroClear();return result('READY','Chat 추론 고급 메뉴 의미값 적용 확인',diagnostics);};
                const __sroDesired=element=>element?.getAttribute?.('aria-expanded');
                const __sroReached=(element,want)=>__sroDesired(element)!==null&&__sroDesired(element)===String(want);
                const __sroMouse=(element,type,buttons)=>{try{return element.dispatchEvent(new MouseEvent(type,{bubbles:true,cancelable:true,composed:true,button:0,buttons,view:window}));}catch(_){return false;}};
                const __sroToggleMenu=(element,want)=>{if(!element)return;element.focus?.();const tracked=__sroDesired(element)!==null;if(tracked&&__sroReached(element,want))return;__sroMouse(element,'pointerdown',1);if(tracked&&__sroReached(element,want))return;__sroMouse(element,'mousedown',1);if(tracked&&__sroReached(element,want))return;__sroMouse(element,'pointerup',0);__sroMouse(element,'mouseup',0);if(!tracked||!__sroReached(element,want))element.click?.();};
                const __sroActivate=element=>{const target=__sroOwner(element)||element;if(!target)return;if(target.getAttribute?.('aria-expanded')!==null||target.hasAttribute?.('aria-haspopup'))__sroToggleMenu(target,true);else{target.focus?.();__sroMouse(target,'pointerdown',1);__sroMouse(target,'mousedown',1);__sroMouse(target,'pointerup',0);__sroMouse(target,'mouseup',0);if(target.isConnected)target.click?.();}};
                const __sroClose=()=>{if(__sroTrigger&&__sroTrigger.getAttribute?.('aria-expanded')==='true'){__sroToggleMenu(__sroTrigger,false);return'trigger';}document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true,cancelable:true}));return'escape';};
                const __sroMayClick=(count,max)=>Number(count)<1||(__sroSinceActionMs>=__sroRetryMs&&Number(count)<max);
                if(__sroCaptureOnly){
                  const __sroObserved=__sroSelectedLevels.length===1?__sroSelectedLevels[0]:__sroTriggerLevel;
                  if(__sroObserved){
                    if(__sroPopups.length===0)return __sroReady(__sroObserved,{action:'capture-current'});
                    if(__sroMayClick(__sroState.closeAttempts,3)){__sroState.closeAttempts++;__sroState.lastAction='close-captured-current';__sroState.lastActionAt=__sroNow;__sroSave();const method=__sroClose();return result('UI_WAIT','현재 Chat picker 선택값 확인 후 메뉴 닫힘 대기',__sroDiagnostics({action:'close-captured-current',observed:__sroObserved,closeMethod:method}));}
                    if(__sroElapsedMs>=__sroOverallTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return __sroResult('CHAT_REASONING_MENU_CLOSE_FAILED','현재 Chat picker 선택값 확인 후 메뉴가 닫히지 않았습니다.',{action:'capture-close-timeout',observed:__sroObserved});
                    return __sroResult('UI_WAIT','현재 Chat picker 선택값 확인 후 메뉴 닫힘 대기',{action:'wait-capture-close',observed:__sroObserved});
                  }
                  if(__sroPopups.length===0&&__sroTrigger){
                    if(__sroMayClick(__sroState.triggerClicks,2)){__sroState.triggerClicks++;__sroState.lastAction='open-picker-for-capture';__sroState.lastActionAt=__sroNow;__sroSave();__sroActivate(__sroTrigger);return result('UI_WAIT','현재 Chat picker 선택값 readback을 위한 메뉴 열림 대기',__sroDiagnostics({action:'open-picker-for-capture'}));}
                    if(__sroElapsedMs>=__sroOverallTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return __sroResult('CHAT_REASONING_READBACK_MISMATCH','현재 Chat picker 선택값을 확인하지 못했습니다.',{action:'capture-trigger-timeout'});
                    return __sroResult('UI_WAIT','현재 Chat picker 메뉴 열림 확인 대기',{action:'wait-capture-trigger'});
                  }
                  if(!__sroTrigger&&__sroPopups.length===0&&(__sroElapsedMs>=__sroOverallTimeoutMs||__sroState.attempts>=__sroMaxAttempts))return __sroResult('CHAT_REASONING_TRIGGER_NOT_FOUND','현재 Chat picker를 찾지 못했습니다.',{action:'capture-missing-trigger'});
                  if(__sroPopups.length>0&&(__sroElapsedMs>=__sroRenderTimeoutMs||__sroState.attempts>=14))return __sroResult('CHAT_REASONING_READBACK_MISMATCH','열린 Chat picker에서 현재 선택값을 확인하지 못했습니다.',{action:'capture-open-popup-timeout'});
                  return __sroResult('UI_WAIT','현재 Chat picker 선택값 readback 대기',{action:'wait-capture-readback'});
                }
                if(__sroTriggerLevel===__sroWanted){
                  if(__sroPopups.length===0)return __sroReady(__sroTriggerLevel,{action:'already-selected'});
                  if(__sroMayClick(__sroState.closeAttempts,3)){__sroState.closeAttempts++;__sroState.lastAction='close-current-match';__sroState.lastActionAt=__sroNow;__sroSave();const method=__sroClose();return result('UI_WAIT','현재 추론 수준이 목표와 같아 열린 메뉴 닫힘 확인 대기',__sroDiagnostics({action:'close-current-match',closeMethod:method}));}
                  if(__sroElapsedMs>=__sroOverallTimeoutMs)return __sroResult('CHAT_REASONING_MENU_CLOSE_FAILED','현재 추론 수준 확인 후 메뉴가 닫히지 않았습니다.',{action:'current-match-close-timeout'});
                  return __sroResult('UI_WAIT','현재 추론 수준 확인 후 메뉴 닫힘 대기',{action:'wait-current-match-close'});
                }
                if(__sroWantedOption&&selectedState(__sroWantedOption.element)){
                  if(__sroPopups.length===0)return __sroReady(__sroWanted,{action:'selected-option-readback'});
                  if(__sroMayClick(__sroState.closeAttempts,3)){__sroState.closeAttempts++;__sroState.lastAction='close-menu';__sroState.lastActionAt=__sroNow;__sroSave();const method=__sroClose();return result('UI_WAIT','Chat 추론 메뉴 닫힘 확인 대기',__sroDiagnostics({action:'close-menu',closeMethod:method}));}
                  if(__sroElapsedMs>=__sroOverallTimeoutMs)return __sroResult('CHAT_REASONING_MENU_CLOSE_FAILED','Chat 추론 선택 후 메뉴가 닫히지 않았습니다.',{action:'menu-close-timeout'});
                  return __sroResult('UI_WAIT','Chat 추론 메뉴 닫힘 대기',{action:'wait-menu-close'});
                }
                if(__sroWantedOption){
                  const __sroOptionAction=(Number(__sroState.advancedClicks)>0||Number(__sroState.reasoningClicks)>0||__sroSliderObserved)?'nested-option-click':'direct-option-click';
                  if(__sroMayClick(__sroState.optionClicks,2)){__sroState.pending=true;__sroState.optionClicks++;__sroState.lastAction=__sroOptionAction;__sroState.lastActionAt=__sroNow;__sroSave();__sroActivate(__sroWantedOption.element);return result('UI_WAIT','Chat 추론 메뉴 옵션 반영 대기',__sroDiagnostics({action:__sroOptionAction}));}
                  if(__sroElapsedMs>=__sroOverallTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return __sroResult('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 메뉴 옵션 선택 상태를 확인하지 못했습니다.',{action:'option-readback-timeout'});
                  return __sroResult('UI_WAIT','Chat 추론 옵션 선택 상태 대기',{action:'wait-option-readback'});
                }
                if(__sroState.pending){
                  if(__sroTriggerLevel===__sroWanted&&__sroPopups.length===0)return __sroReady(__sroTriggerLevel,{action:'trigger-readback'});
                  if(__sroElapsedMs>=__sroOverallTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return __sroResult('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 옵션 적용 후 의미값을 확인하지 못했습니다.',{action:'pending-readback-timeout'});
                  return __sroResult('UI_WAIT','Chat 추론 옵션 적용 readback 대기',{action:'wait-pending-readback'});
                }
                if(__sroAdvancedButton){
                  if(__sroMayClick(__sroState.advancedClicks,2)){__sroState.advancedClicks++;__sroState.lastAction='open-advanced-control';__sroState.lastActionAt=__sroNow;__sroSave();__sroActivate(__sroAdvancedButton);return result('UI_WAIT','추론 슬라이드의 고급 버튼 반영 대기',__sroDiagnostics({action:'open-advanced-control'}));}
                  if(__sroElapsedMs>=__sroOverallTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return __sroResult('CHAT_REASONING_OPTION_UNAVAILABLE','고급 메뉴 전환 후 추론 옵션을 찾지 못했습니다.',{action:'advanced-transition-timeout'});
                  return __sroResult('UI_WAIT','고급 메뉴 전환 확인 대기',{action:'wait-advanced-transition'});
                }
                if(__sroReasoningRow){
                  if(__sroMayClick(__sroState.reasoningClicks,2)){__sroState.reasoningClicks++;__sroState.lastAction='open-reasoning-menu';__sroState.lastActionAt=__sroNow;__sroSave();__sroActivate(__sroReasoningRow);return result('UI_WAIT','고급 메뉴 추론 수준 선택기 열기 반영 대기',__sroDiagnostics({action:'open-reasoning-menu'}));}
                  if(__sroElapsedMs>=__sroOverallTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return __sroResult('CHAT_REASONING_OPTION_UNAVAILABLE','추론 수준 메뉴에서 요청 옵션을 찾지 못했습니다.',{action:'reasoning-menu-timeout'});
                  return __sroResult('UI_WAIT','추론 수준 메뉴 옵션 렌더링 대기',{action:'wait-reasoning-options'});
                }
                if(__sroSliderObserved){
                  if(__sroElapsedMs>=__sroRenderTimeoutMs||__sroState.attempts>=14)return __sroResult('CHAT_REASONING_ADVANCED_CONTROL_NOT_FOUND','추론 슬라이드에서 고급 버튼을 찾지 못했습니다.',{action:'advanced-control-timeout'});
                  return __sroResult('UI_WAIT','추론 슬라이드 고급 버튼 렌더링 대기',{action:'wait-advanced-control'});
                }
                if(__sroPopups.length===0&&__sroTrigger){
                  if(__sroMayClick(__sroState.triggerClicks,2)){__sroState.triggerClicks++;__sroState.lastAction='open-reasoning-sheet';__sroState.lastActionAt=__sroNow;__sroSave();__sroActivate(__sroTrigger);return result('UI_WAIT','현재 추론 정도 클릭 후 슬라이드 열림 대기',__sroDiagnostics({action:'open-reasoning-sheet'}));}
                  if(__sroElapsedMs>=__sroOverallTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return __sroResult('CHAT_REASONING_TRIGGER_NOT_FOUND','현재 추론 정도 제어를 제한시간 안에 열지 못했습니다.',{action:'trigger-timeout'});
                  return __sroResult('UI_WAIT','현재 추론 정도 슬라이드 열림 확인 대기',{action:'wait-reasoning-sheet'});
                }
                if(__sroDirectEntries.length>0)return __sroResult('CHAT_REASONING_OPTION_UNAVAILABLE','현재 고급 추론 메뉴에 요청한 옵션이 없습니다.',{action:'requested-option-unavailable'});
                if(__sroPopups.length>0){
                  if(__sroElapsedMs>=__sroRenderTimeoutMs||__sroState.attempts>=14)return __sroResult('CHAT_REASONING_OPTION_UNAVAILABLE','열린 추론 UI에서 고급 버튼 또는 메뉴 옵션을 찾지 못했습니다.',{action:'unrecognized-popup-timeout'});
                  return __sroResult('UI_WAIT','추론 UI 렌더링 대기',{action:'wait-popup-content'});
                }
                if(__sroElapsedMs>=__sroOverallTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return __sroResult('CHAT_REASONING_TRIGGER_NOT_FOUND','현재 추론 정도 제어를 찾지 못했습니다.',{action:'missing-trigger-timeout'});
                return __sroResult('UI_WAIT','현재 추론 정도 제어 탐색 대기',{action:'wait-trigger'});
                """
                .replace("__WANTED__", SelfRunScript.quote(wanted))
                .replace("__ORDINAL__", String.valueOf(ordinal))
                .replace("__RUN_ID__", SelfRunScript.quote(runId))
                .replace("__CAPTURE_ONLY__", String.valueOf(captureOnly));
    }
}
