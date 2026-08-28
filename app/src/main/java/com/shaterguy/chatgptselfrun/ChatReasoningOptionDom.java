package com.shaterguy.chatgptselfrun;

/**
 * Chat reasoning/model selector for the current composer popover.
 * Reasoning is changed on the visible slider; Pro transitions use the model row/menu.
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
                if(__sroCaptureOnly&&typeof requestedMode!=='undefined'&&requestedMode==='work')return result('READY','WORK 모드에서는 Chat picker 현재값 캡처를 생략합니다.',{strategy:'slider-model-popover',action:'skip-chat-picker-work',currentMode:'work'});
                const __sroText=value=>exactText(value);
                const __sroLevel=source=>{
                  const v=__sroText(source).replace(/^[✓✔☑●•·\\s]+/,'');
                  if(/^(?:pro[\\s·:—-]*extended|프로[\\s·:—-]*확장)(?:\\s|>|$)/.test(v))return'pro_extended';
                  if(/^(?:pro[\\s·:—-]*standard|프로[\\s·:—-]*표준)(?:\\s|>|$)/.test(v))return'pro_standard';
                  if(/^(?:extra high|very high|xhigh|매우\\s*높음)(?:\\s|>|$)/.test(v))return'xhigh';
                  if(/^(?:high|높음)(?:\\s|>|$)/.test(v))return'high';
                  if(/^(?:medium|중간|표준|standard)(?:\\s|>|$)/.test(v))return'medium';
                  if(/^(?:instant|flash|빠른|즉시)(?:\\s|>|$)/.test(v))return'instant';
                  if(/^(?:pro|프로)(?:\\s|>|$)/.test(v))return'pro';
                  return'';
                };
                const __sroModel=source=>{
                  const v=__sroText(source);
                  if(/(?:^|\\s)(?:gpt-?)?5(?:\\.6)?\\s+sol(?:\\s|$)/.test(v))return'sol';
                  if(/(?:^|\\s)(?:gpt-?)?5(?:\\.5)?(?:\\s|$)/.test(v)&&!/5(?:\\.6)?\\s+sol/.test(v))return'legacy';
                  if(/(?:^|\\s)(?:gpt-?5(?:\\.6)?\\s+sol\\s+)?pro(?:\\s|$)/.test(v)||/^(?:프로)(?:\\s|$)/.test(v))return'pro';
                  return'';
                };
                const __sroStandard=['instant','medium','high','xhigh'],__sroPro=['pro_standard','pro_extended'];
                const __sroWantedPro=__sroWanted==='pro'||__sroPro.includes(__sroWanted);
                const __sroPopupSelector='[role="menu"],[role="listbox"],[role="dialog"],[data-radix-popper-content-wrapper],[data-slot*="popover-content"],[data-slot*="menu-content"],[data-slot*="sheet-content"]';
                const __sroOwner=e=>e?.closest?.('button,[role="button"],[role="menuitem"],[role="menuitemradio"],[role="radio"],[role="option"],[aria-haspopup],[aria-expanded]')||e||null;
                const __sroLabel=e=>{const owner=__sroOwner(e)||e;return __sroText(owner?.getAttribute?.('aria-label')||'')||labelOf(owner);};
                const __sroVisible=e=>!!e&&e.isConnected&&visible(e)&&!e.closest?.('[inert],[aria-hidden="true"],[data-active="false"]');
                const __sroForbidden=e=>/(send|submit|보내기|stop|중지|microphone|마이크|voice|음성|attach|첨부|upload|업로드|new chat|new conversation|새 채팅|새 대화)/.test(labelOf(e)+' '+__sroText(e?.dataset?.testid||''));
                const __sroInput=(typeof composer!=='undefined'&&composer)||document.querySelector('#prompt-textarea')||[...document.querySelectorAll('textarea,[contenteditable="true"]')].filter(visible).sort((a,b)=>b.getBoundingClientRect().bottom-a.getBoundingClientRect().bottom)[0]||null;
                const __sroForm=__sroInput?.closest?.('form')||null;
                const __sroNear=e=>{if(!e||!__sroInput)return false;if(__sroForm?.contains?.(e))return true;const a=e.getBoundingClientRect?.(),b=__sroInput.getBoundingClientRect?.();return !!a&&!!b&&a.bottom>=b.top-300&&a.top<=b.bottom+300&&a.right>=b.left-400&&a.left<=b.right+400;};
                const __sroRootScore=e=>{const label=labelOf(e),testid=__sroText(e?.dataset?.testid||''),popup=__sroText(e.getAttribute?.('aria-haspopup')||'');let score=0;if(/reason|thinking|effort|추론/.test(label+' '+testid))score+=130;if(__sroLevel(label))score+=90;if(/model|모델|gpt/.test(label+' '+testid))score+=30;if(/^(menu|listbox|dialog|true)$/.test(popup))score+=45;if(e.hasAttribute?.('aria-expanded'))score+=35;if(e.hasAttribute?.('aria-controls')||e.hasAttribute?.('aria-owns'))score+=25;if(__sroNear(e))score+=190;return score;};
                const __sroRootEntries=[...document.querySelectorAll('button,[role="button"],[role="combobox"],[aria-haspopup],[aria-expanded],[data-testid*="model"],[data-testid*="reason"],[data-testid*="effort"]')].filter(__sroVisible).filter(e=>!e.closest(__sroPopupSelector)).filter(e=>!__sroForbidden(e)).map((element,index)=>({element,index,score:__sroRootScore(element)})).filter(entry=>entry.score>0).sort((a,b)=>b.score-a.score||a.index-b.index);
                const __sroAnimated=[...document.querySelectorAll('[data-animated-slider-trigger="true"]')].find(__sroVisible)||null;
                const __sroAnimatedRoot=__sroOwner(__sroAnimated);
                const __sroRoot=__sroAnimatedRoot&&__sroVisible(__sroAnimatedRoot)&&!__sroAnimatedRoot.closest(__sroPopupSelector)&&!__sroForbidden(__sroAnimatedRoot)&&__sroNear(__sroAnimatedRoot)?__sroAnimatedRoot:(__sroRootEntries[0]?.element||null);
                const __sroControlledIds=__sroRoot?String(__sroRoot.getAttribute('aria-controls')||__sroRoot.getAttribute('aria-owns')||'').split(/\\s+/).filter(Boolean):[];
                const __sroControlled=__sroControlledIds.map(id=>document.getElementById(id)).find(__sroVisible)||null;
                const __sroOpen=[...document.querySelectorAll(__sroPopupSelector)].filter(__sroVisible);
                const __sroPopups=[__sroControlled,...__sroOpen].filter((item,index,array)=>item&&array.indexOf(item)===index);
                const __sroSliders=[...document.querySelectorAll('[role="slider"],input[type="range"]')].filter(__sroVisible).filter(e=>e.getAttribute('aria-orientation')!=='vertical');
                const __sroSlider=__sroSliders.find(slider=>__sroPopups.some(popup=>popup.contains(slider)))||null;
                const __sroBasePopup=__sroSlider?__sroPopups.find(popup=>popup.contains(__sroSlider))||null:null;
                const __sroPopupElements=[];for(const popup of __sroPopups)for(const raw of popup.querySelectorAll('button,[role="button"],[role="menuitem"],[role="menuitemradio"],[role="radio"],[role="option"],[aria-haspopup],[aria-expanded]')){const owner=__sroOwner(raw);if(owner&&__sroVisible(owner)&&!__sroPopupElements.includes(owner))__sroPopupElements.push(owner);}
                const __sroOptionRole=e=>/^(menuitemradio|radio|option|menuitem)$/.test(__sroText(e?.getAttribute?.('role')||''));
                const __sroSelected=e=>!!e&&(e.getAttribute?.('aria-checked')==='true'||e.getAttribute?.('aria-selected')==='true'||e.getAttribute?.('aria-pressed')==='true'||/^(checked|selected|active|on)$/.test(__sroText(e.dataset?.state||'')));
                const __sroHeaders=__sroPopupElements.filter(e=>!__sroOptionRole(e)&&!!__sroLevel(__sroLabel(e))&&(!__sroBasePopup||__sroBasePopup.contains(e)));
                const __sroHeader=__sroHeaders[0]||null;
                const __sroModelOptions=__sroPopupElements.map((element,index)=>({element,index,model:__sroModel(__sroLabel(element))})).filter(entry=>__sroOptionRole(entry.element)&&!!entry.model);
                const __sroSelectedModel=__sroModelOptions.find(entry=>__sroSelected(entry.element))?.model||'';
                const __sroWantedModelOption=__sroModelOptions.find(entry=>entry.model===(__sroWantedPro?'pro':'sol'))||null;
                const __sroSliderLevel=__sroSlider?__sroLevel(__sroSlider.getAttribute('aria-valuetext')||''):'';
                const __sroHeaderLevel=__sroHeader?__sroLevel(__sroLabel(__sroHeader)):'';
                const __sroRootLevel=__sroRoot?__sroLevel(__sroLabel(__sroRoot)):'';
                let __sroObserved=__sroSliderLevel||__sroHeaderLevel||__sroRootLevel;
                const __sroStateKey='selfrun-drive:chat-reasoning-current:'+__sroRunId;
                const __sroNow=Date.now(),__sroTimeoutMs=30000,__sroRetryMs=3200,__sroMaxAttempts=32;
                let __sroState={startedAt:0,requested:'',attempts:0,rootClicks:0,headerClicks:0,modelClicks:0,sliderMoves:0,closeAttempts:0,pending:false,pendingLevel:'',pendingDirection:0,pendingTarget:null,pendingStrategy:'',pendingWaits:0,captured:'',verified:'',lastAction:'',lastActionAt:0};
                try{const raw=sessionStorage.getItem(__sroStateKey)||localStorage.getItem(__sroStateKey)||'';if(raw)__sroState={...__sroState,...JSON.parse(raw)};}catch(_){}
                if(__sroState.requested&&__sroState.requested!==__sroWanted)__sroState={startedAt:0,requested:__sroWanted,attempts:0,rootClicks:0,headerClicks:0,modelClicks:0,sliderMoves:0,closeAttempts:0,pending:false,pendingLevel:'',pendingDirection:0,pendingTarget:null,pendingStrategy:'',pendingWaits:0,captured:'',verified:'',lastAction:'',lastActionAt:0};
                if(!(Number(__sroState.startedAt)>0))__sroState.startedAt=__sroNow;__sroState.requested=__sroWanted;__sroState.attempts=Math.max(0,Number(__sroState.attempts)||0)+1;
                const __sroElapsed=Math.max(0,__sroNow-Number(__sroState.startedAt||__sroNow)),__sroSince=Number(__sroState.lastActionAt)>0?Math.max(0,__sroNow-Number(__sroState.lastActionAt)):Number.MAX_SAFE_INTEGER;
                const __sroSave=()=>{const value=JSON.stringify(__sroState);try{sessionStorage.setItem(__sroStateKey,value);}catch(_){}try{localStorage.setItem(__sroStateKey,value);}catch(_){}};
                const __sroClear=()=>{try{sessionStorage.removeItem(__sroStateKey);}catch(_){}try{localStorage.removeItem(__sroStateKey);}catch(_){}};
                const __sroMay=(count,max)=>Number(count)<1||(__sroSince>=__sroRetryMs&&Number(count)<max);
                const __sroMouse=(e,type,buttons)=>{try{return e.dispatchEvent(new MouseEvent(type,{bubbles:true,cancelable:true,composed:true,button:0,buttons,view:window}));}catch(_){return false;}};
                const __sroToggle=(e,want)=>{if(!e)return;e.focus?.();const tracked=e.getAttribute?.('aria-expanded')!==null;if(tracked&&e.getAttribute('aria-expanded')===String(want))return;__sroMouse(e,'pointerdown',1);if(tracked&&e.getAttribute('aria-expanded')===String(want))return;__sroMouse(e,'mousedown',1);if(tracked&&e.getAttribute('aria-expanded')===String(want))return;__sroMouse(e,'pointerup',0);__sroMouse(e,'mouseup',0);if(!tracked||e.getAttribute('aria-expanded')!==String(want))e.click?.();};
                const __sroActivate=e=>{const target=__sroOwner(e)||e;if(!target)return;if(target.getAttribute?.('aria-expanded')!==null||target.hasAttribute?.('aria-haspopup'))__sroToggle(target,true);else{target.focus?.();__sroMouse(target,'pointerdown',1);__sroMouse(target,'mousedown',1);__sroMouse(target,'pointerup',0);__sroMouse(target,'mouseup',0);if(target.isConnected)target.click?.();}};
                const __sroEscape=()=>{document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true,cancelable:true,composed:true}));document.dispatchEvent(new KeyboardEvent('keyup',{key:'Escape',code:'Escape',bubbles:true,cancelable:true,composed:true}));};
                const __sroClose=()=>{if(__sroRoot&&__sroRoot.getAttribute?.('aria-expanded')==='true'){__sroToggle(__sroRoot,false);return'trigger';}__sroEscape();return'escape';};
                const __sroDiagnostics=extra=>({strategy:'slider-model-popover',requested:__sroWanted,requestedOrdinal:__sroWantedOrdinal,captureOnly:__sroCaptureOnly,rootFound:!!__sroRoot,rootLabel:__sroRoot?__sroLabel(__sroRoot):'',popupCandidates:__sroPopups.length,sliderFound:!!__sroSlider,headerFound:!!__sroHeader,headerLabel:__sroHeader?__sroLabel(__sroHeader):'',observed:__sroObserved,selectedModel:__sroSelectedModel,modelOptions:__sroModelOptions.map(entry=>entry.model),rootClicks:__sroState.rootClicks,headerClicks:__sroState.headerClicks,modelClicks:__sroState.modelClicks,sliderMoves:__sroState.sliderMoves,closeAttempts:__sroState.closeAttempts,pending:!!__sroState.pending,pendingStrategy:__sroState.pendingStrategy||'',lastAction:__sroState.lastAction||'',attempts:__sroState.attempts,elapsedMs:__sroElapsed,timeoutMs:__sroTimeoutMs,...extra});
                const __sroResult=(status,detail,extra={})=>{__sroSave();return result(status,detail,__sroDiagnostics(extra));};
                const __sroReady=(observed,extra={})=>{const diagnostics=__sroDiagnostics({observed,...extra});__sroClear();return result('READY','Chat 추론·모델 적용 확인',diagnostics);};
                if(__sroCaptureOnly){
                  if(__sroState.captured&&__sroPopups.length===0)return __sroReady(__sroState.captured,{action:'capture-current'});
                  if(__sroObserved){
                    if(__sroPopups.length===0)return __sroReady(__sroObserved,{action:'capture-current'});
                    __sroState.captured=__sroObserved;
                    if(__sroMay(__sroState.closeAttempts,3)){__sroState.closeAttempts++;__sroState.lastAction='close-after-capture';__sroState.lastActionAt=__sroNow;__sroSave();const method=__sroClose();return result('UI_WAIT','현재 Chat 추론값 캡처 후 팝업 닫힘 확인 대기',__sroDiagnostics({action:'close-after-capture',closeMethod:method}));}
                  }
                  if(__sroPopups.length===0&&__sroRoot&&__sroMay(__sroState.rootClicks,2)){__sroState.rootClicks++;__sroState.lastAction='open-picker-for-capture';__sroState.lastActionAt=__sroNow;__sroSave();__sroActivate(__sroRoot);return result('UI_WAIT','현재 Chat 추론값 캡처를 위해 선택기 열림 대기',__sroDiagnostics({action:'open-picker-for-capture'}));}
                  if(__sroElapsed>=__sroTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return __sroResult('CHAT_REASONING_READBACK_MISMATCH','현재 Chat 추론 선택값을 확인하지 못했습니다.',{action:'capture-timeout'});
                  return __sroResult('UI_WAIT','현재 Chat 추론 선택값 readback 대기',{action:'wait-capture-readback'});
                }
                const __sroObservedPro=__sroObserved==='pro'||__sroPro.includes(__sroObserved)||__sroSelectedModel==='pro';
                const __sroModelMenuOpen=__sroModelOptions.length>0;
                if(__sroModelMenuOpen){
                  if(__sroWantedModelOption&&__sroSelected(__sroWantedModelOption.element)){
                    __sroState.lastAction='close-model-menu';__sroState.lastActionAt=__sroNow;__sroSave();__sroEscape();return result('UI_WAIT','Chat 모델 선택 확인 후 추론 팝업 복귀 대기',__sroDiagnostics({action:'close-model-menu'}));
                  }
                  if(__sroWantedModelOption&&__sroMay(__sroState.modelClicks,2)){__sroState.modelClicks++;__sroState.lastAction='select-model';__sroState.lastActionAt=__sroNow;__sroSave();__sroActivate(__sroWantedModelOption.element);return result('UI_WAIT','Chat 모델 변경 반영 대기',__sroDiagnostics({action:'select-model',targetModel:__sroWantedPro?'pro':'sol'}));}
                  if(__sroElapsed>=__sroTimeoutMs)return __sroResult('CHAT_REASONING_OPTION_UNAVAILABLE','현재 Chat 모델 메뉴에 필요한 모델이 없습니다.',{action:'model-unavailable'});
                  return __sroResult('UI_WAIT','Chat 모델 메뉴 옵션 렌더링 대기',{action:'wait-model-options'});
                }
                const __sroModelMismatch=(__sroWantedPro&&!__sroObservedPro&&!!__sroObserved)||(!__sroWantedPro&&__sroObservedPro);
                if(__sroModelMismatch){
                  if(__sroHeader&&__sroMay(__sroState.headerClicks,2)){__sroState.headerClicks++;__sroState.lastAction='open-model-menu';__sroState.lastActionAt=__sroNow;__sroSave();__sroActivate(__sroHeader);return result('UI_WAIT','Chat 현재 추론 텍스트에서 모델 메뉴 열림 대기',__sroDiagnostics({action:'open-model-menu'}));}
                  if(__sroElapsed>=__sroTimeoutMs)return __sroResult('CHAT_REASONING_OPTION_UNAVAILABLE','Chat 모델 전환용 현재 추론 텍스트를 찾지 못했습니다.',{action:'model-trigger-unavailable'});
                  return __sroResult('UI_WAIT','Chat 모델 전환 제어 렌더링 대기',{action:'wait-model-trigger'});
                }
                if(__sroState.verified&&__sroPopups.length===0)return __sroReady(__sroState.verified,{action:'verified-readback'});
                if(__sroWanted==='pro'&&__sroObservedPro){
                  __sroState.verified=__sroObserved||'pro';
                  if(__sroPopups.length===0)return __sroReady(__sroState.verified,{action:'pro-selected'});
                  if(__sroMay(__sroState.closeAttempts,3)){__sroState.closeAttempts++;__sroState.lastAction='close-pro-selected';__sroState.lastActionAt=__sroNow;__sroSave();const method=__sroClose();return result('UI_WAIT','Chat Pro 선택 확인 후 팝업 닫힘 대기',__sroDiagnostics({action:'close-pro-selected',closeMethod:method}));}
                }
                if(__sroSlider){
                  const __sroInputRange=typeof HTMLInputElement!=='undefined'&&__sroSlider instanceof HTMLInputElement&&__sroSlider.type==='range';
                  const __sroNum=(value,fallback)=>{if(value==null||String(value).trim()==='')return fallback;const n=Number(value);return Number.isFinite(n)?n:fallback;};
                  const __sroMin=__sroNum(__sroSlider.getAttribute('aria-valuemin'),__sroInputRange?__sroNum(__sroSlider.min,0):0),__sroMax=__sroNum(__sroSlider.getAttribute('aria-valuemax'),__sroInputRange?__sroNum(__sroSlider.max,100):100),__sroRange=__sroMax-__sroMin;
                  const __sroStepRaw=__sroNum(__sroSlider.getAttribute('aria-valuestep'),__sroInputRange?__sroNum(__sroSlider.step,NaN):NaN),__sroCurrent=__sroNum(__sroSlider.getAttribute('aria-valuenow'),__sroInputRange?__sroNum(__sroSlider.value,NaN):NaN);
                  const __sroOrder=__sroWantedPro?__sroPro:__sroStandard;
                  const __sroExactCount=Number.isFinite(__sroStepRaw)&&__sroStepRaw>0&&__sroRange>0?Math.round(__sroRange/__sroStepRaw)+1:0;
                  if(!__sroObserved&&Number.isFinite(__sroCurrent)&&__sroExactCount===__sroOrder.length){const step=__sroRange/(__sroOrder.length-1),index=Math.round((__sroCurrent-__sroMin)/step);if(index>=0&&index<__sroOrder.length)__sroObserved=__sroOrder[index];}
                  const __sroTargetMatch=__sroWanted==='pro'?__sroObservedPro:__sroObserved===__sroWanted;
                  if(__sroTargetMatch){__sroState.verified=__sroObserved||__sroWanted;__sroState.pending=false;__sroSave();if(__sroMay(__sroState.closeAttempts,3)){__sroState.closeAttempts++;__sroState.lastAction='close-target';__sroState.lastActionAt=__sroNow;__sroSave();const method=__sroClose();return result('UI_WAIT','Chat 추론 적용 확인 후 팝업 닫힘 대기',__sroDiagnostics({action:'close-target',closeMethod:method}));}return __sroResult('UI_WAIT','Chat 추론 적용 후 팝업 닫힘 확인 대기',{action:'wait-close-target'});}
                  const __sroCurrentIndex=__sroOrder.indexOf(__sroObserved),__sroWantedIndex=__sroOrder.indexOf(__sroWanted);
                  if(__sroCurrentIndex<0||__sroWantedIndex<0){if(__sroElapsed>=__sroTimeoutMs)return __sroResult('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 슬라이더의 현재 의미값을 판정할 수 없습니다.',{current:__sroCurrent,min:__sroMin,max:__sroMax});return __sroResult('UI_WAIT','Chat 추론 슬라이더 의미값 readback 대기',{action:'wait-slider-semantic',current:__sroCurrent});}
                  const __sroDirection=Math.sign(__sroWantedIndex-__sroCurrentIndex);
                  if(__sroState.pending){
                    if(__sroObserved&&__sroObserved!==__sroState.pendingLevel){const delta=__sroOrder.indexOf(__sroObserved)-__sroOrder.indexOf(__sroState.pendingLevel);if(delta!==0&&Math.sign(delta)===Math.sign(__sroState.pendingDirection)){__sroState.pending=false;__sroState.pendingWaits=0;__sroState.pendingStrategy='';__sroSave();}else return __sroResult('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 슬라이더가 요청 반대 방향으로 변경됐습니다.',{action:'slider-direction-mismatch'});}
                    else{__sroState.pendingWaits++;if(__sroState.pendingStrategy==='keyboard'&&__sroState.pendingWaits>=1&&Number.isFinite(Number(__sroState.pendingTarget))){const target=Number(__sroState.pendingTarget),rect0=__sroSlider.getBoundingClientRect();let track=rect0.width>=120?__sroSlider:null,node=__sroSlider.parentElement;for(let depth=0;!track&&node&&depth<6;depth++,node=node.parentElement){const r=node.getBoundingClientRect();if(r.width>=120&&r.height<=96){track=node;break;}}if(track){const r=track.getBoundingClientRect(),ratio=Math.max(0.01,Math.min(0.99,(target-__sroMin)/__sroRange)),x=r.left+r.width*ratio,y=r.top+r.height/2,common={bubbles:true,cancelable:true,composed:true,clientX:x,clientY:y,button:0};for(const targetNode of [__sroSlider,track].filter((item,index,array)=>item&&array.indexOf(item)===index)){try{if(typeof PointerEvent==='function'){targetNode.dispatchEvent(new PointerEvent('pointerdown',{...common,buttons:1,pointerId:1,pointerType:'touch',isPrimary:true}));targetNode.dispatchEvent(new PointerEvent('pointerup',{...common,buttons:0,pointerId:1,pointerType:'touch',isPrimary:true}));}}catch(_){}targetNode.dispatchEvent(new MouseEvent('mousedown',{...common,buttons:1}));targetNode.dispatchEvent(new MouseEvent('mouseup',{...common,buttons:0}));targetNode.dispatchEvent(new MouseEvent('click',{...common,buttons:0}));}__sroState.pendingStrategy='pointer';__sroState.pendingWaits=0;__sroState.lastAction='slider-pointer-fallback';__sroState.lastActionAt=__sroNow;__sroSave();return result('UI_WAIT','Chat 추론 슬라이더 포인터 보정 반영 대기',__sroDiagnostics({action:'slider-pointer-fallback',target}));}}
                      if(__sroState.pendingWaits>=2)return __sroResult('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 슬라이더 입력 후 의미값이 갱신되지 않았습니다.',{action:'slider-readback-timeout'});__sroSave();return result('UI_WAIT','Chat 추론 슬라이더 변경 readback 대기',__sroDiagnostics({action:'wait-slider-readback'}));}
                  }
                  if(!Number.isFinite(__sroCurrent)||!(__sroRange>0))return __sroResult('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 슬라이더 숫자 범위를 확인할 수 없습니다.',{action:'slider-range-invalid',current:__sroCurrent,min:__sroMin,max:__sroMax});
                  if(__sroState.sliderMoves>=10)return __sroResult('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 슬라이더가 제한된 이동 횟수 안에 목표값에 도달하지 못했습니다.',{action:'slider-move-limit'});
                  const __sroStep=Number.isFinite(__sroStepRaw)&&__sroStepRaw>0?__sroStepRaw:__sroRange/Math.max(1,__sroOrder.length-1),__sroTarget=Math.max(__sroMin,Math.min(__sroMax,__sroCurrent+__sroDirection*__sroStep));
                  let __sroStrategy='keyboard';if(__sroInputRange){const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')?.set;if(setter)setter.call(__sroSlider,String(__sroTarget));else __sroSlider.value=String(__sroTarget);__sroSlider.dispatchEvent(new Event('input',{bubbles:true,composed:true}));__sroSlider.dispatchEvent(new Event('change',{bubbles:true}));__sroStrategy='native-range';}else{__sroSlider.focus?.();const key=__sroDirection>0?'ArrowRight':'ArrowLeft';__sroSlider.dispatchEvent(new KeyboardEvent('keydown',{key,code:key,bubbles:true,cancelable:true,composed:true}));__sroSlider.dispatchEvent(new KeyboardEvent('keyup',{key,code:key,bubbles:true,cancelable:true,composed:true}));}
                  __sroState.sliderMoves++;__sroState.pending=true;__sroState.pendingLevel=__sroObserved;__sroState.pendingDirection=__sroDirection;__sroState.pendingTarget=__sroTarget;__sroState.pendingStrategy=__sroStrategy;__sroState.pendingWaits=0;__sroState.lastAction='set-slider';__sroState.lastActionAt=__sroNow;__sroSave();return result('UI_WAIT','Chat 추론 슬라이더 이동 반영 대기',__sroDiagnostics({action:'set-slider',strategy:__sroStrategy,target:__sroTarget,current:__sroCurrent}));
                }
                if(__sroPopups.length===0&&__sroRoot&&__sroMay(__sroState.rootClicks,2)){__sroState.rootClicks++;__sroState.lastAction='open-reasoning-popover';__sroState.lastActionAt=__sroNow;__sroSave();__sroActivate(__sroRoot);return result('UI_WAIT','Chat 추론 선택기 열림 대기',__sroDiagnostics({action:'open-reasoning-popover'}));}
                if(__sroPopups.length>0&&!__sroSlider&&__sroElapsed<__sroTimeoutMs)return __sroResult('UI_WAIT','Chat 추론 팝업의 슬라이더 렌더링 대기',{action:'wait-slider'});
                if(__sroElapsed>=__sroTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return __sroResult(__sroRoot?'CHAT_REASONING_SLIDER_NOT_FOUND':'CHAT_REASONING_TRIGGER_NOT_FOUND','현재 Chat 추론 UI를 제한시간 안에 준비하지 못했습니다.',{action:'selector-timeout'});
                return __sroResult('UI_WAIT','현재 Chat 추론 제어 탐색 대기',{action:'wait-trigger'});
                """
                .replace("__WANTED__", SelfRunScript.quote(wanted))
                .replace("__ORDINAL__", String.valueOf(ordinal))
                .replace("__RUN_ID__", SelfRunScript.quote(runId))
                .replace("__CAPTURE_ONLY__", String.valueOf(captureOnly));
    }
}
