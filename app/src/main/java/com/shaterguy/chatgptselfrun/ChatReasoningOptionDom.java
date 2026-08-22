package com.shaterguy.chatgptselfrun;

/** Direct-option adapter for the current Chat model/reasoning picker before slider fallback. */
final class ChatReasoningOptionDom {
    private ChatReasoningOptionDom() {}

    static String inline(String selection, String runId) {
        String wanted = ChatReasoningPreferenceStore.normalize(selection);
        int ordinal = ChatReasoningPreferenceStore.ordinal(wanted);
        if (ordinal < 0) return "";
        return """
                const __sroWanted=__WANTED__,__sroWantedOrdinal=__ORDINAL__,__sroRunId=__RUN_ID__;
                const __sroLevel=source=>{
                  const v=exactText(source);
                  if(v.includes('extra high')||v.includes('very high')||v.includes('xhigh')||v.includes('maximum')||v.includes('매우 높음')||v.includes('최대'))return'xhigh';
                  if(v==='pro'||v.startsWith('pro ')||v.endsWith(' pro')||v.includes('프로'))return'pro';
                  if(v.includes('medium')||v.includes('중간')||v.includes('표준')||v.includes('standard'))return'medium';
                  if(v.includes('high')||v.includes('extended')||v.includes('높음')||v.includes('확장'))return'high';
                  if(v.includes('instant')||v.includes('flash')||v.includes('빠른')||v.includes('즉시'))return'instant';
                  return'';
                };
                const __sroElementLevel=element=>__sroLevel([labelOf(element),element?.getAttribute?.('aria-valuetext')||'',element?.getAttribute?.('data-value')||'',element?.getAttribute?.('data-level')||'',element?.getAttribute?.('value')||'',element?.title||''].join(' '));
                const __sroPopupSelector='[role="menu"],[role="listbox"],[role="dialog"],[data-radix-popper-content-wrapper],[data-slot*="popover-content"],[data-slot*="menu-content"]';
                const __sroOptionSelector='[role="option"],[role="menuitemradio"],[role="radio"],[role="menuitem"],button,[data-value]';
                const __sroForbidden=element=>/(send|submit|보내기|stop|중지|microphone|마이크|voice|음성|attach|첨부|upload|업로드|new chat|new conversation|새 채팅|새 대화)/.test(labelOf(element)+' '+exactText(element?.dataset?.testid||''));
                const __sroTriggerScore=element=>{const label=labelOf(element),testid=exactText(element?.dataset?.testid||''),popup=element.getAttribute('aria-haspopup')||'';let score=0;if(__sroLevel(label))score+=120;if(/reason|thinking|추론/.test(label+' '+testid))score+=70;if(/model|모델|gpt|flash/.test(label+' '+testid))score+=45;if(popup&&popup!=='false')score+=35;if(element.hasAttribute('aria-expanded'))score+=30;if(element.hasAttribute('aria-controls')||element.hasAttribute('aria-owns'))score+=25;return score;};
                const __sroTriggerEntries=[...document.querySelectorAll('button,[role="button"],[role="combobox"],[aria-haspopup],[aria-expanded],[data-testid*="model"],[data-testid*="reason"]')].filter(visible).filter(element=>!element.closest(__sroPopupSelector)).filter(element=>!__sroForbidden(element)).map((element,index)=>({element,index,score:__sroTriggerScore(element)})).filter(entry=>entry.score>0).sort((a,b)=>b.score-a.score||a.index-b.index);
                const __sroTrigger=__sroTriggerEntries[0]?.element||null;
                const __sroTriggerLevel=__sroTrigger?__sroLevel(labelOf(__sroTrigger)):'';
                const __sroControlledIds=__sroTrigger?String(__sroTrigger.getAttribute('aria-controls')||__sroTrigger.getAttribute('aria-owns')||'').split(/\\s+/).filter(Boolean):[];
                const __sroControlled=__sroControlledIds.map(id=>document.getElementById(id)).find(visible)||null;
                const __sroOpenPopups=[...document.querySelectorAll(__sroPopupSelector)].filter(visible);
                const __sroPopup=__sroControlled||__sroOpenPopups.find(popup=>[...popup.querySelectorAll(__sroOptionSelector)].some(element=>!!__sroElementLevel(element)))||null;
                const __sroDirectOptions=__sroPopup?[...__sroPopup.querySelectorAll(__sroOptionSelector)].filter(visible).map((element,index)=>({element,index,level:__sroElementLevel(element)})).filter(entry=>!!entry.level):[];
                const __sroWantedOption=__sroDirectOptions.find(entry=>entry.level===__sroWanted)||null;
                const __sroSelectedOptions=__sroDirectOptions.filter(entry=>selectedState(entry.element));
                const __sroSelectedLevel=__sroSelectedOptions.length===1?__sroSelectedOptions[0].level:'';
                const __sroStateKey='selfrun-drive:chat-reasoning-option:'+__sroRunId;
                const __sroNow=Date.now(),__sroTimeoutMs=12000,__sroMaxAttempts=10;
                let __sroState={startedAt:0,attempts:0,optionClickAttempts:0,closeAttempts:0,pending:false};
                try{const saved=sessionStorage.getItem(__sroStateKey)||localStorage.getItem(__sroStateKey)||'';if(saved)__sroState={...__sroState,...JSON.parse(saved)};}catch(_){}
                if(!(Number(__sroState.startedAt)>0))__sroState.startedAt=__sroNow;
                const __sroElapsedMs=Math.max(0,__sroNow-Number(__sroState.startedAt||__sroNow));
                const __sroSave=()=>{const value=JSON.stringify(__sroState);try{sessionStorage.setItem(__sroStateKey,value);}catch(_){}try{localStorage.setItem(__sroStateKey,value);}catch(_){}};
                const __sroClear=()=>{try{sessionStorage.removeItem(__sroStateKey);}catch(_){}try{localStorage.removeItem(__sroStateKey);}catch(_){}};
                const __sroDiagnostics=extra=>({strategy:'direct-option',requested:__sroWanted,requestedOrdinal:__sroWantedOrdinal,triggerFound:!!__sroTrigger,triggerLevel:__sroTriggerLevel,popupFound:!!__sroPopup,directOptionCandidates:__sroDirectOptions.length,wantedOptionFound:!!__sroWantedOption,selectedLevel:__sroSelectedLevel,optionClickAttempts:__sroState.optionClickAttempts,closeAttempts:__sroState.closeAttempts,pending:!!__sroState.pending,elapsedMs:__sroElapsedMs,timeoutMs:__sroTimeoutMs,...extra});
                const __sroReady=observed=>{__sroClear();return result('READY','Chat 추론 직접 옵션 적용 확인',__sroDiagnostics({observed,directOptionApplied:true}));};
                if(__sroTriggerLevel===__sroWanted&&!__sroPopup)return __sroReady(__sroTriggerLevel);
                if(__sroState.pending&&!__sroPopup){
                  __sroState.attempts=Math.max(0,Number(__sroState.attempts)||0)+1;__sroSave();
                  if(__sroElapsedMs>=__sroTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return result('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 직접 옵션 클릭 후 의미값을 확인하지 못했습니다.',__sroDiagnostics({action:'direct-option-readback-timeout'}));
                  return result('UI_WAIT','Chat 추론 직접 옵션 readback 대기',__sroDiagnostics({action:'wait-direct-option-readback'}));
                }
                if(__sroWantedOption){
                  const __sroOptionSelected=selectedState(__sroWantedOption.element);
                  if(__sroOptionSelected){
                    __sroState.pending=true;
                    if(__sroPopup){
                      __sroState.closeAttempts=Math.max(0,Number(__sroState.closeAttempts)||0)+1;__sroSave();
                      if(__sroTrigger&&__sroTrigger.getAttribute('aria-expanded')==='true')__sroTrigger.click();
                      else document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true}));
                      return result('UI_WAIT','Chat 추론 직접 옵션 메뉴 닫힘 확인 대기',__sroDiagnostics({action:'close-direct-option-menu'}));
                    }
                    return __sroReady(__sroWanted);
                  }
                  if(Number(__sroState.optionClickAttempts)<1){
                    __sroState.optionClickAttempts=1;__sroState.pending=true;__sroSave();__sroWantedOption.element.focus?.();__sroWantedOption.element.click();
                    return result('UI_WAIT','Chat 추론 직접 옵션 클릭 반영 대기',__sroDiagnostics({action:'direct-option-click'}));
                  }
                  __sroState.attempts=Math.max(0,Number(__sroState.attempts)||0)+1;__sroSave();
                  if(__sroElapsedMs>=__sroTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return result('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 직접 옵션 선택 상태를 확인하지 못했습니다.',__sroDiagnostics({action:'direct-option-selection-timeout'}));
                  return result('UI_WAIT','Chat 추론 직접 옵션 선택 상태 대기',__sroDiagnostics({action:'wait-direct-option-selection'}));
                }
                if(__sroState.pending){
                  __sroState.attempts=Math.max(0,Number(__sroState.attempts)||0)+1;__sroSave();
                  if(__sroElapsedMs>=__sroTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return result('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 직접 옵션이 재렌더링 중 소실되었습니다.',__sroDiagnostics({action:'direct-option-disappeared'}));
                  return result('UI_WAIT','Chat 추론 직접 옵션 재렌더링 대기',__sroDiagnostics({action:'wait-direct-option-rerender'}));
                }
                const __sroFallback='legacy-slider-fallback';
                """
                .replace("__WANTED__", SelfRunScript.quote(wanted))
                .replace("__ORDINAL__", String.valueOf(ordinal))
                .replace("__RUN_ID__", SelfRunScript.quote(runId));
    }
}
