package com.shaterguy.chatgptselfrun;

/**
 * Chat reasoning selector for the current Advanced -> Reasoning level hierarchy.
 * Flat direct options remain supported, while the legacy slider path is entered only
 * after a real slider is positively observed.
 */
final class ChatReasoningOptionDom {
    private ChatReasoningOptionDom() {}

    static String inline(String selection, String runId) {
        String wanted = ChatReasoningPreferenceStore.normalize(selection);
        int ordinal = ChatReasoningPreferenceStore.ordinal(wanted);
        if (ordinal < 0) return "";
        return """
                const __sroWanted=__WANTED__,__sroWantedOrdinal=__ORDINAL__,__sroRunId=__RUN_ID__;
                const __sroLevel=source=>{
                  let v=exactText(source).replace(/^[✓✔☑●•·\\s]+/,'');
                  if(/^(extra high|very high|xhigh|maximum|매우\\s*높음|최대)(?:\\s|$)/.test(v))return'xhigh';
                  if(/^(pro|프로)(?:\\s|$)/.test(v))return'pro';
                  if(/^(medium|중간|표준|standard)(?:\\s|$)/.test(v))return'medium';
                  if(/^(high|높음|extended|확장)(?:\\s|$)/.test(v))return'high';
                  if(/^(instant|flash|빠른|즉시)(?:\\s|$)/.test(v))return'instant';
                  return'';
                };
                const __sroPopupSelector='[role="menu"],[role="listbox"],[role="dialog"],[data-radix-popper-content-wrapper],[data-slot*="popover-content"],[data-slot*="menu-content"]';
                const __sroInteractiveSelector='button,[role="button"],[role="menuitem"],[role="menuitemradio"],[role="radio"],[role="option"],[aria-haspopup],[aria-expanded],[data-value]';
                const __sroForbidden=element=>/(send|submit|보내기|stop|중지|microphone|마이크|voice|음성|attach|첨부|upload|업로드|new chat|new conversation|새 채팅|새 대화)/.test(labelOf(element)+' '+exactText(element?.dataset?.testid||''));
                const __sroOwner=element=>element?.closest?.(__sroInteractiveSelector)||element||null;
                const __sroLabel=element=>labelOf(__sroOwner(element)||element);
                const __sroDirectLevel=element=>{
                  const owner=__sroOwner(element);
                  if(!owner)return'';
                  const label=__sroLabel(owner);
                  if(/^(reasoning(?:\\s+(?:level|effort))?|추론(?:\\s*(?:수준|강도|정도)))(?:\\s|$)/.test(label))return'';
                  if(/^(model|모델)(?:\\s|$)/.test(label))return'';
                  const role=exactText(owner.getAttribute?.('role')||'');
                  if(!/^(menuitemradio|radio|option|menuitem)$/.test(role)&&owner.tagName!=='BUTTON'&&!owner.hasAttribute?.('data-value'))return'';
                  return __sroLevel(label);
                };
                const __sroReasoningRowLabel=label=>/^(reasoning(?:\\s+(?:level|effort))?|추론(?:\\s*(?:수준|강도|정도)))(?:\\s|$)/.test(label);
                const __sroInput=(typeof composer!=='undefined'&&composer)||document.querySelector('#prompt-textarea')||[...document.querySelectorAll('textarea,[contenteditable="true"]')].filter(visible).sort((a,b)=>b.getBoundingClientRect().bottom-a.getBoundingClientRect().bottom)[0]||null;
                const __sroForm=__sroInput?.closest?.('form')||null;
                const __sroNear=element=>{
                  if(!element||!__sroInput)return false;
                  if(__sroForm?.contains?.(element))return true;
                  const a=element.getBoundingClientRect?.(),b=__sroInput.getBoundingClientRect?.();
                  if(!a||!b)return false;
                  return a.bottom>=b.top-260&&a.top<=b.bottom+260&&a.right>=b.left-360&&a.left<=b.right+360;
                };
                const __sroTriggerScore=element=>{
                  const label=labelOf(element),testid=exactText(element?.dataset?.testid||''),popup=exactText(element.getAttribute?.('aria-haspopup')||'');
                  let score=0;
                  if(__sroLevel(label))score+=150;
                  if(/reason|thinking|추론/.test(label+' '+testid))score+=70;
                  if(/model|모델|gpt|flash/.test(label+' '+testid))score+=35;
                  if(/^(menu|listbox|dialog|true)$/.test(popup))score+=45;
                  if(element.hasAttribute?.('aria-expanded'))score+=35;
                  if(element.hasAttribute?.('aria-controls')||element.hasAttribute?.('aria-owns'))score+=25;
                  if(__sroNear(element))score+=180;
                  if(element.closest?.('header'))score+=5;
                  return score;
                };
                const __sroTriggerEntries=[...document.querySelectorAll('button,[role="button"],[role="combobox"],[aria-haspopup],[aria-expanded],[data-testid*="model"],[data-testid*="reason"]')]
                  .filter(visible).filter(element=>!element.closest(__sroPopupSelector)).filter(element=>!__sroForbidden(element))
                  .map((element,index)=>({element,index,score:__sroTriggerScore(element)})).filter(entry=>entry.score>0)
                  .sort((a,b)=>b.score-a.score||a.index-b.index);
                const __sroTrigger=__sroTriggerEntries[0]?.element||null;
                const __sroTriggerLevel=__sroTrigger?__sroLevel(labelOf(__sroTrigger)):'';
                const __sroControlledIds=__sroTrigger?String(__sroTrigger.getAttribute('aria-controls')||__sroTrigger.getAttribute('aria-owns')||'').split(/\\s+/).filter(Boolean):[];
                const __sroControlled=__sroControlledIds.map(id=>document.getElementById(id)).find(visible)||null;
                const __sroOpenPopups=[...document.querySelectorAll(__sroPopupSelector)].filter(visible);
                const __sroPopups=[__sroControlled,...__sroOpenPopups].filter((popup,index,all)=>popup&&all.indexOf(popup)===index);
                const __sroPopupElements=[];
                for(const popup of __sroPopups){
                  for(const raw of popup.querySelectorAll(__sroInteractiveSelector)){
                    const owner=__sroOwner(raw);
                    if(owner&&visible(owner)&&!__sroPopupElements.includes(owner))__sroPopupElements.push(owner);
                  }
                }
                const __sroReasoningRows=__sroPopupElements.filter(element=>__sroReasoningRowLabel(__sroLabel(element))&&!__sroDirectLevel(element));
                const __sroReasoningRow=__sroReasoningRows[0]||null;
                const __sroDirectEntries=__sroPopupElements.map((element,index)=>({element,index,level:__sroDirectLevel(element)})).filter(entry=>!!entry.level);
                const __sroWantedOption=__sroDirectEntries.find(entry=>entry.level===__sroWanted)||null;
                const __sroSelectedEntries=__sroDirectEntries.filter(entry=>selectedState(entry.element));
                const __sroSelectedLevels=[...new Set(__sroSelectedEntries.map(entry=>entry.level))];
                const __sroRawSliders=[...document.querySelectorAll('[role="slider"],input[type="range"]')].filter(visible).filter(element=>element.getAttribute('aria-orientation')!=='vertical');
                const __sroSliderFound=__sroRawSliders.some(slider=>__sroPopups.length===0||__sroPopups.some(popup=>popup.contains(slider)));
                const __sroAdvancedContext=__sroPopups.some(popup=>/(^|\\s)(advanced|고급)(\\s|$)/.test(exactText(popup.innerText||popup.textContent||'')))||(__sroReasoningRows.length>0);
                const __sroStateKey='selfrun-drive:chat-reasoning-option:'+__sroRunId;
                const __sroNow=Date.now(),__sroOverallTimeoutMs=20000,__sroRenderTimeoutMs=9000,__sroRetryMs=4200,__sroMaxAttempts=24;
                let __sroState={startedAt:0,requested:'',attempts:0,triggerClicks:0,reasoningClicks:0,optionClicks:0,closeAttempts:0,entered:false,pending:false,applied:false,lastAction:'',lastActionAt:0,verifiedValue:''};
                try{const saved=sessionStorage.getItem(__sroStateKey)||localStorage.getItem(__sroStateKey)||'';if(saved)__sroState={...__sroState,...JSON.parse(saved)};}catch(_){}
                if(__sroState.requested&&__sroState.requested!==__sroWanted)__sroState={startedAt:0,requested:__sroWanted,attempts:0,triggerClicks:0,reasoningClicks:0,optionClicks:0,closeAttempts:0,entered:false,pending:false,applied:false,lastAction:'',lastActionAt:0,verifiedValue:''};
                if(!(Number(__sroState.startedAt)>0))__sroState.startedAt=__sroNow;
                __sroState.requested=__sroWanted;__sroState.attempts=Math.max(0,Number(__sroState.attempts)||0)+1;
                const __sroElapsedMs=Math.max(0,__sroNow-Number(__sroState.startedAt||__sroNow));
                const __sroSinceActionMs=Number(__sroState.lastActionAt)>0?Math.max(0,__sroNow-Number(__sroState.lastActionAt)):Number.MAX_SAFE_INTEGER;
                const __sroSave=()=>{const value=JSON.stringify(__sroState);try{sessionStorage.setItem(__sroStateKey,value);}catch(_){}try{localStorage.setItem(__sroStateKey,value);}catch(_){}};
                const __sroClear=()=>{try{sessionStorage.removeItem(__sroStateKey);}catch(_){}try{localStorage.removeItem(__sroStateKey);}catch(_){}};
                const __sroDiagnostics=extra=>({strategy:'hierarchical-menu',requested:__sroWanted,requestedOrdinal:__sroWantedOrdinal,triggerFound:!!__sroTrigger,triggerCandidates:__sroTriggerEntries.length,triggerScore:__sroTriggerEntries[0]?.score||0,triggerLevel:__sroTriggerLevel,triggerExpanded:__sroTrigger?.getAttribute?.('aria-expanded')==='true',popupCandidates:__sroPopups.length,advancedContext:__sroAdvancedContext,reasoningRows:__sroReasoningRows.length,directOptionCandidates:__sroDirectEntries.length,wantedOptionFound:!!__sroWantedOption,selectedLevels:__sroSelectedLevels,sliderFound:__sroSliderFound,attempts:__sroState.attempts,triggerClicks:__sroState.triggerClicks,reasoningClicks:__sroState.reasoningClicks,optionClicks:__sroState.optionClicks,closeAttempts:__sroState.closeAttempts,entered:!!__sroState.entered,pending:!!__sroState.pending,applied:!!__sroState.applied,lastAction:__sroState.lastAction||'',elapsedMs:__sroElapsedMs,overallTimeoutMs:__sroOverallTimeoutMs,renderTimeoutMs:__sroRenderTimeoutMs,...extra});
                const __sroResult=(status,detail,extra={})=>{__sroSave();return result(status,detail,__sroDiagnostics(extra));};
                const __sroReady=(observed,extra={})=>{const diagnostics=__sroDiagnostics({observed,...extra});__sroClear();return result('READY','Chat 추론 메뉴 의미값 적용 확인',diagnostics);};
                const __sroActivate=element=>{const target=__sroOwner(element)||element;if(!target)return;target.focus?.();target.click?.();};
                const __sroClose=()=>{
                  if(__sroTrigger&&__sroTrigger.getAttribute?.('aria-expanded')==='true'){__sroActivate(__sroTrigger);return'trigger';}
                  const active=document.activeElement;active?.dispatchEvent?.(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true,cancelable:true}));
                  document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true,cancelable:true}));
                  return'escape';
                };
                if(__sroTriggerLevel===__sroWanted&&__sroPopups.length===0)return __sroReady(__sroTriggerLevel,{action:'already-selected'});
                if(__sroWantedOption&&selectedState(__sroWantedOption.element)){
                  __sroState.entered=__sroState.entered||__sroAdvancedContext;__sroState.pending=true;__sroState.applied=true;__sroState.verifiedValue=__sroWanted;
                  if(__sroPopups.length>0){
                    if(__sroState.closeAttempts<3&&__sroSinceActionMs>=250){
                      __sroState.closeAttempts++;__sroState.lastAction='close-reasoning-menu';__sroState.lastActionAt=__sroNow;const method=__sroClose();
                      return __sroResult('UI_WAIT','Chat 추론 메뉴 닫힘 확인 대기',{action:'close-reasoning-menu',closeMethod:method});
                    }
                    if(__sroElapsedMs>=__sroOverallTimeoutMs)return __sroResult('CHAT_REASONING_MENU_CLOSE_FAILED','Chat 추론 선택 후 메뉴가 닫히지 않았습니다.',{action:'menu-close-timeout'});
                    return __sroResult('UI_WAIT','Chat 추론 메뉴 닫힘 대기',{action:'wait-menu-close'});
                  }
                  return __sroReady(__sroWanted,{action:'selected-option-readback'});
                }
                if(__sroWantedOption){
                  const mayClick=Number(__sroState.optionClicks)<1||(__sroSinceActionMs>=__sroRetryMs&&Number(__sroState.optionClicks)<2);
                  if(mayClick){
                    __sroState.entered=__sroState.entered||__sroAdvancedContext;__sroState.pending=true;__sroState.optionClicks=Math.max(0,Number(__sroState.optionClicks)||0)+1;__sroState.lastAction=__sroAdvancedContext?'nested-option-click':'direct-option-click';__sroState.lastActionAt=__sroNow;__sroSave();__sroActivate(__sroWantedOption.element);
                    return result('UI_WAIT','Chat 추론 옵션 클릭 반영 대기',__sroDiagnostics({action:__sroState.lastAction}));
                  }
                  if(__sroElapsedMs>=__sroOverallTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return __sroResult('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 옵션 클릭 후 선택 상태를 확인하지 못했습니다.',{action:'option-readback-timeout'});
                  return __sroResult('UI_WAIT','Chat 추론 옵션 선택 상태 대기',{action:'wait-option-readback'});
                }
                if(__sroState.pending){
                  if(__sroTriggerLevel===__sroWanted&&__sroPopups.length===0)return __sroReady(__sroTriggerLevel,{action:'trigger-readback'});
                  if(__sroElapsedMs>=__sroOverallTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return __sroResult('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 옵션 적용 후 의미값을 확인하지 못했습니다.',{action:'pending-readback-timeout'});
                  return __sroResult('UI_WAIT','Chat 추론 옵션 적용 readback 대기',{action:'wait-pending-readback'});
                }
                if(__sroReasoningRow){
                  __sroState.entered=true;
                  const expanded=__sroReasoningRow.getAttribute?.('aria-expanded')==='true';
                  const mayClick=Number(__sroState.reasoningClicks)<1||(!expanded&&__sroSinceActionMs>=__sroRetryMs&&Number(__sroState.reasoningClicks)<2);
                  if(mayClick){
                    __sroState.reasoningClicks=Math.max(0,Number(__sroState.reasoningClicks)||0)+1;__sroState.lastAction='open-reasoning-level';__sroState.lastActionAt=__sroNow;__sroSave();__sroActivate(__sroReasoningRow);
                    return result('UI_WAIT','고급 메뉴의 추론 수준 하위 메뉴 열기 반영 대기',__sroDiagnostics({action:'open-reasoning-level'}));
                  }
                  if(__sroSliderFound){__sroState.lastAction='positive-slider-fallback';__sroState.lastActionAt=__sroNow;__sroSave();}
                  else{
                    if(__sroElapsedMs>=__sroOverallTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return __sroResult('CHAT_REASONING_OPTION_UNAVAILABLE','추론 수준 하위 메뉴에서 요청 옵션을 찾지 못했습니다.',{action:'reasoning-options-timeout'});
                    return __sroResult('UI_WAIT','추론 수준 하위 메뉴 옵션 렌더링 대기',{action:'wait-reasoning-options'});
                  }
                }
                if(__sroState.entered||__sroAdvancedContext){
                  __sroState.entered=true;
                  if(__sroSliderFound){__sroState.lastAction='positive-slider-fallback';__sroState.lastActionAt=__sroNow;__sroSave();}
                  else{
                    if(__sroElapsedMs>=__sroOverallTimeoutMs||(__sroElapsedMs>=__sroRenderTimeoutMs&&__sroState.attempts>=12))return __sroResult('CHAT_REASONING_OPTION_UNAVAILABLE','고급 추론 메뉴에서 요청 옵션을 찾지 못했습니다.',{action:'advanced-menu-timeout'});
                    return __sroResult('UI_WAIT','고급 추론 메뉴 렌더링 대기',{action:'advanced-menu-wait'});
                  }
                }
                if(__sroPopups.length===0&&__sroTrigger){
                  const expanded=__sroTrigger.getAttribute?.('aria-expanded')==='true';
                  if(expanded&&__sroSinceActionMs<__sroRenderTimeoutMs)return __sroResult('UI_WAIT','Chat 고급 메뉴 렌더링 대기',{action:'wait-advanced-menu'});
                  const mayClick=Number(__sroState.triggerClicks)<1||(__sroSinceActionMs>=__sroRetryMs&&Number(__sroState.triggerClicks)<2);
                  if(mayClick){
                    __sroState.triggerClicks=Math.max(0,Number(__sroState.triggerClicks)||0)+1;__sroState.lastAction='open-advanced-menu';__sroState.lastActionAt=__sroNow;__sroSave();__sroActivate(__sroTrigger);
                    return result('UI_WAIT','Chat 고급 메뉴 열기 반영 대기',__sroDiagnostics({action:'open-advanced-menu'}));
                  }
                  if(__sroElapsedMs>=__sroOverallTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return __sroResult('CHAT_REASONING_TRIGGER_NOT_FOUND','Chat 추론 메뉴를 제한시간 안에 열지 못했습니다.',{action:'advanced-trigger-timeout'});
                  return __sroResult('UI_WAIT','Chat 고급 메뉴 열림 확인 대기',{action:'wait-advanced-trigger'});
                }
                if(__sroDirectEntries.length>0)return __sroResult('CHAT_REASONING_OPTION_UNAVAILABLE','현재 Chat 추론 메뉴에 요청한 옵션이 없습니다.',{action:'requested-option-unavailable'});
                if(__sroSliderFound){__sroState.lastAction='positive-slider-fallback';__sroState.lastActionAt=__sroNow;__sroSave();}
                else if(__sroPopups.length>0){
                  if(__sroElapsedMs>=__sroRenderTimeoutMs)return __sroResult('CHAT_REASONING_OPTION_UNAVAILABLE','열린 Chat 메뉴에서 추론 옵션 또는 슬라이더를 찾지 못했습니다.',{action:'unrecognized-menu-timeout'});
                  return __sroResult('UI_WAIT','열린 Chat 메뉴 내용 렌더링 대기',{action:'wait-menu-content'});
                }else{
                  __sroClear();
                }
                """
                .replace("__WANTED__", SelfRunScript.quote(wanted))
                .replace("__ORDINAL__", String.valueOf(ordinal))
                .replace("__RUN_ID__", SelfRunScript.quote(runId));
    }
}
