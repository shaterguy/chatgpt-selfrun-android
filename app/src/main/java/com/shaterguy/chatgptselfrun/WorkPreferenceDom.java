package com.shaterguy.chatgptselfrun;

/** Work model/reasoning selector for the current effort popover and reasoning slider. */
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
                const __wpText=value=>String(value??'').replace(/\\s+/g,' ').trim().toLowerCase();
                const __wpLabel=e=>__wpText(e?.getAttribute?.('aria-label')||'')||__wpText(e?.innerText||'');
                const __wpVisible=e=>!!e&&e.isConnected&&e.offsetParent!==null&&!e.closest?.('[inert],[aria-hidden="true"],[data-active="false"]');
                const __wpModel=source=>{const v=__wpText(source);const precise=v.match(/(?:^|\\s)(?:gpt-?)?5(?:\\.6)?\\s+(sol|terra|luna)(?:\\s|>|$)/);if(precise)return precise[1];const bare=v.match(/(?:^|\\s)(sol|terra|luna)(?:\\s|>|$)/);return bare?bare[1]:'';};
                const __wpReasoning=source=>{const v=__wpText(source);if(/(?:^|\\s)(?:ultra|울트라)(?:\\s|>|$)/.test(v))return'ultra';if(/(?:^|\\s)(?:maximum|max|최대)(?:\\s|>|$)/.test(v))return'max';if(/(?:^|\\s)(?:extra high|very high|xhigh|매우\\s*높음)(?:\\s|>|$)/.test(v))return'xhigh';if(/(?:^|\\s)(?:medium|중간)(?:\\s|>|$)/.test(v))return'medium';if(/(?:^|\\s)(?:light|가벼움)(?:\\s|>|$)/.test(v))return'light';if(/(?:^|\\s)(?:high|높음)(?:\\s|>|$)/.test(v))return'high';return'';};
                const __wpParse=source=>__wpKind==='model'?__wpModel(source):__wpReasoning(source);
                const __wpOrder=['light','medium','high','xhigh','max','ultra'];
                const __wpPopupSelector='[role="menu"],[role="listbox"],[role="dialog"],[data-radix-popper-content-wrapper],[data-slot*="popover-content"],[data-slot*="menu-content"],[data-slot*="sheet-content"]';
                const __wpOwner=e=>e?.closest?.('button,[role="button"],[role="menuitem"],[role="menuitemradio"],[role="radio"],[role="option"],[aria-haspopup],[aria-expanded]')||e||null;
                const __wpOptionRole=e=>/^(menuitemradio|radio|option|menuitem)$/.test(__wpText(e?.getAttribute?.('role')||''));
                const __wpSelected=e=>!!e&&(e.getAttribute?.('aria-checked')==='true'||e.getAttribute?.('aria-selected')==='true'||e.getAttribute?.('aria-pressed')==='true'||/^(checked|selected|active|on)$/.test(__wpText(e.dataset?.state||'')));
                const __wpInput=document.querySelector('#prompt-textarea')||[...document.querySelectorAll('textarea,[contenteditable="true"]')].filter(__wpVisible).sort((a,b)=>b.getBoundingClientRect().bottom-a.getBoundingClientRect().bottom)[0]||null;
                const __wpForm=__wpInput?.closest?.('form')||null;
                const __wpNear=e=>{if(!e||!__wpInput)return false;if(__wpForm?.contains?.(e))return true;const a=e.getBoundingClientRect?.(),b=__wpInput.getBoundingClientRect?.();return !!a&&!!b&&a.bottom>=b.top-300&&a.top<=b.bottom+300&&a.right>=b.left-400&&a.left<=b.right+400;};
                const __wpForbidden=e=>/(send|submit|보내기|stop|중지|microphone|마이크|voice|음성|attach|첨부|upload|업로드)/.test(__wpLabel(e)+' '+__wpText(e?.dataset?.testid||''));
                const __wpRootScore=e=>{const label=__wpLabel(e),testid=__wpText(e?.dataset?.testid||''),popup=__wpText(e.getAttribute?.('aria-haspopup')||'');let score=0;if(/select effort|effort|reasoning|thinking|추론|작업 강도/.test(label+' '+testid))score+=170;if(__wpModel(label)||__wpReasoning(label))score+=90;if(/^(menu|listbox|dialog|true)$/.test(popup))score+=45;if(e.hasAttribute?.('aria-expanded'))score+=35;if(e.hasAttribute?.('aria-controls')||e.hasAttribute?.('aria-owns'))score+=25;if(__wpNear(e))score+=190;return score;};
                const __wpRootEntries=[...document.querySelectorAll('button,[role="button"],[role="combobox"],[aria-haspopup],[aria-expanded],[data-testid*="model"],[data-testid*="reason"],[data-testid*="effort"]')].filter(__wpVisible).filter(e=>!e.closest(__wpPopupSelector)).filter(e=>!__wpForbidden(e)).map((element,index)=>({element,index,score:__wpRootScore(element)})).filter(entry=>entry.score>0).sort((a,b)=>b.score-a.score||a.index-b.index);
                const __wpCalibratedRaw=__srFind(__wpPurpose),__wpCalibrated=__wpOwner(__wpCalibratedRaw);
                const __wpCalibratedRoot=__wpCalibrated&&__wpVisible(__wpCalibrated)&&!__wpCalibrated.closest(__wpPopupSelector)&&__wpNear(__wpCalibrated)&&!__wpForbidden(__wpCalibrated)?__wpCalibrated:null;
                const __wpRoot=__wpRootEntries[0]?.element||__wpCalibratedRoot||null;
                const __wpControlledIds=__wpRoot?String(__wpRoot.getAttribute('aria-controls')||__wpRoot.getAttribute('aria-owns')||'').split(/\\s+/).filter(Boolean):[];
                const __wpControlled=__wpControlledIds.map(id=>document.getElementById(id)).find(__wpVisible)||null;
                const __wpOpen=[...document.querySelectorAll(__wpPopupSelector)].filter(__wpVisible);
                const __wpPopups=[__wpControlled,...__wpOpen].filter((item,index,array)=>item&&array.indexOf(item)===index);
                const __wpSliders=[...document.querySelectorAll('[role="slider"],input[type="range"]')].filter(__wpVisible).filter(e=>e.getAttribute('aria-orientation')!=='vertical');
                const __wpSlider=__wpSliders.find(slider=>__wpPopups.some(popup=>popup.contains(slider)))||null;
                const __wpBasePopup=__wpSlider?__wpPopups.find(popup=>popup.contains(__wpSlider))||null:null;
                const __wpPopupElements=[];for(const popup of __wpPopups)for(const raw of popup.querySelectorAll('button,[role="button"],[role="menuitem"],[role="menuitemradio"],[role="radio"],[role="option"],[aria-haspopup],[aria-expanded]')){const owner=__wpOwner(raw);if(owner&&__wpVisible(owner)&&!__wpPopupElements.includes(owner))__wpPopupElements.push(owner);}
                const __wpCombinedHeaders=__wpPopupElements.filter(e=>!!__wpModel(__wpLabel(e))&&!!__wpReasoning(__wpLabel(e))&&(!__wpOptionRole(e)||e.hasAttribute?.('aria-haspopup'))&&(!__wpBasePopup||__wpBasePopup.contains(e)));
                const __wpHeader=__wpCombinedHeaders[0]||null;
                const __wpModelRows=__wpPopupElements.filter(e=>!!__wpModel(__wpLabel(e))&&(!__wpOptionRole(e)||e.hasAttribute?.('aria-haspopup'))&&!__wpCombinedHeaders.includes(e));
                const __wpReasoningRows=__wpPopupElements.filter(e=>!!__wpReasoning(__wpLabel(e))&&(/reasoning|effort|추론/.test(__wpLabel(e))||e.hasAttribute?.('aria-haspopup'))&&!__wpCombinedHeaders.includes(e));
                const __wpShowAdvanced=__wpPopupElements.find(e=>/^(?:show\\s+advanced(?:\\s+options)?|advanced(?:\\s+options)?|고급(?:\\s+옵션)?(?:\\s+표시)?)(?:\\s|$)/.test(__wpLabel(e)))||null;
                const __wpModelOptions=__wpPopupElements.map((element,index)=>({element,index,model:__wpModel(__wpLabel(element))})).filter(entry=>__wpOptionRole(entry.element)&&!!entry.model&&!entry.element.hasAttribute?.('aria-haspopup'));
                const __wpReasoningOptions=__wpPopupElements.map((element,index)=>({element,index,reasoning:__wpReasoning(__wpLabel(element))})).filter(entry=>__wpOptionRole(entry.element)&&!!entry.reasoning&&!entry.element.hasAttribute?.('aria-haspopup'));
                const __wpWantedOption=__wpKind==='model'?__wpModelOptions.find(entry=>entry.model===__wpWanted):__wpReasoningOptions.find(entry=>entry.reasoning===__wpWanted);
                const __wpSelectedOption=(__wpKind==='model'?__wpModelOptions:__wpReasoningOptions).find(entry=>__wpSelected(entry.element));
                const __wpSliderReasoning=__wpSlider?__wpReasoning(__wpSlider.getAttribute('aria-valuetext')||''):'';
                const __wpHeaderModel=__wpHeader?__wpModel(__wpLabel(__wpHeader)):'';
                const __wpHeaderReasoning=__wpHeader?__wpReasoning(__wpLabel(__wpHeader)):'';
                const __wpRootModel=__wpRoot?__wpModel(__wpLabel(__wpRoot)):'';
                const __wpRootReasoning=__wpRoot?__wpReasoning(__wpLabel(__wpRoot)):'';
                let __wpCurrentModel=__wpHeaderModel||(__wpSelectedOption&&__wpKind==='model'?__wpSelectedOption.model:'')||__wpRootModel;
                let __wpCurrentReasoning=__wpSliderReasoning||__wpHeaderReasoning||(__wpSelectedOption&&__wpKind==='reasoning'?__wpSelectedOption.reasoning:'')||__wpRootReasoning;
                const __wpStateKey='selfrun-drive:work-preference-current:'+__wpKind+':'+__wpPurpose+':'+location.pathname;
                const __wpNow=Date.now(),__wpTimeoutMs=26000,__wpRetryMs=3200,__wpMaxAttempts=32;
                let __wpState={startedAt:0,requested:'',attempts:0,rootClicks:0,advancedClicks:0,rowClicks:0,optionClicks:0,sliderMoves:0,closeAttempts:0,pending:false,pendingLevel:'',pendingDirection:0,pendingTarget:null,pendingStrategy:'',pendingWaits:0,verified:'',lastAction:'',lastActionAt:0};
                try{const raw=sessionStorage.getItem(__wpStateKey)||localStorage.getItem(__wpStateKey)||'';if(raw)__wpState={...__wpState,...JSON.parse(raw)};}catch(_){}
                if(__wpState.requested&&__wpState.requested!==__wpWanted)__wpState={startedAt:0,requested:__wpWanted,attempts:0,rootClicks:0,advancedClicks:0,rowClicks:0,optionClicks:0,sliderMoves:0,closeAttempts:0,pending:false,pendingLevel:'',pendingDirection:0,pendingTarget:null,pendingStrategy:'',pendingWaits:0,verified:'',lastAction:'',lastActionAt:0};
                if(!(Number(__wpState.startedAt)>0))__wpState.startedAt=__wpNow;__wpState.requested=__wpWanted;__wpState.attempts=Math.max(0,Number(__wpState.attempts)||0)+1;
                const __wpElapsed=Math.max(0,__wpNow-Number(__wpState.startedAt||__wpNow)),__wpSince=Number(__wpState.lastActionAt)>0?Math.max(0,__wpNow-Number(__wpState.lastActionAt)):Number.MAX_SAFE_INTEGER;
                const __wpSave=()=>{const value=JSON.stringify(__wpState);try{sessionStorage.setItem(__wpStateKey,value);}catch(_){}try{localStorage.setItem(__wpStateKey,value);}catch(_){}};
                const __wpClear=()=>{try{sessionStorage.removeItem(__wpStateKey);}catch(_){}try{localStorage.removeItem(__wpStateKey);}catch(_){}};
                const __wpMay=(count,max)=>Number(count)<1||(__wpSince>=__wpRetryMs&&Number(count)<max);
                const __wpMouse=(e,type,buttons)=>{try{return e.dispatchEvent(new MouseEvent(type,{bubbles:true,cancelable:true,composed:true,button:0,buttons,view:window}));}catch(_){return false;}};
                const __wpToggle=(e,want)=>{if(!e)return;e.focus?.();const tracked=e.getAttribute?.('aria-expanded')!==null;if(tracked&&e.getAttribute('aria-expanded')===String(want))return;__wpMouse(e,'pointerdown',1);if(tracked&&e.getAttribute('aria-expanded')===String(want))return;__wpMouse(e,'mousedown',1);if(tracked&&e.getAttribute('aria-expanded')===String(want))return;__wpMouse(e,'pointerup',0);__wpMouse(e,'mouseup',0);if(!tracked||e.getAttribute('aria-expanded')!==String(want))e.click?.();};
                const __wpActivate=e=>{const target=__wpOwner(e)||e;if(!target)return;if(target.getAttribute?.('aria-expanded')!==null||target.hasAttribute?.('aria-haspopup'))__wpToggle(target,true);else{target.focus?.();__wpMouse(target,'pointerdown',1);__wpMouse(target,'mousedown',1);__wpMouse(target,'pointerup',0);__wpMouse(target,'mouseup',0);if(target.isConnected)target.click?.();}};
                const __wpEscape=()=>{document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true,cancelable:true,composed:true}));document.dispatchEvent(new KeyboardEvent('keyup',{key:'Escape',code:'Escape',bubbles:true,cancelable:true,composed:true}));};
                const __wpClose=()=>{if(__wpRoot&&__wpRoot.getAttribute?.('aria-expanded')==='true'){__wpToggle(__wpRoot,false);return'trigger';}if(__wpRoot&&__wpPopups.length>0&&__wpRoot.getAttribute?.('aria-expanded')===null&&!__wpRoot.hasAttribute?.('aria-haspopup')){__wpActivate(__wpRoot);return'trigger-untracked';}__wpEscape();return'escape';};
                const __wpDiagnostics=extra=>({kind:__wpKind,requested:__wpWanted,calibrationPurpose:__wpPurpose,strategy:'effort-popover-slider',rootFound:!!__wpRoot,rootLabel:__wpRoot?__wpLabel(__wpRoot):'',popupCandidates:__wpPopups.length,sliderFound:!!__wpSlider,headerFound:!!__wpHeader,headerLabel:__wpHeader?__wpLabel(__wpHeader):'',currentModel:__wpCurrentModel||(__wpKind==='model'?__wpState.verified:''),currentReasoning:__wpCurrentReasoning||(__wpKind==='reasoning'?__wpState.verified:''),current:(__wpKind==='model'?__wpCurrentModel:__wpCurrentReasoning)||__wpState.verified,modelOptions:__wpModelOptions.map(entry=>entry.model),reasoningOptions:__wpReasoningOptions.map(entry=>entry.reasoning),showAdvancedFound:!!__wpShowAdvanced,modelRowFound:__wpModelRows.length>0,reasoningRowFound:__wpReasoningRows.length>0,rootClicks:__wpState.rootClicks,advancedClicks:__wpState.advancedClicks,rowClicks:__wpState.rowClicks,optionClicks:__wpState.optionClicks,sliderMoves:__wpState.sliderMoves,closeAttempts:__wpState.closeAttempts,pending:!!__wpState.pending,pendingStrategy:__wpState.pendingStrategy||'',lastAction:__wpState.lastAction||'',attempts:__wpState.attempts,elapsedMs:__wpElapsed,timeoutMs:__wpTimeoutMs,...extra});
                const __wpFailure=(suffix,detail,extra={})=>{__wpSave();return result('WORK_'+(__wpKind==='model'?'MODEL':'REASONING')+'_'+suffix,detail,__wpDiagnostics(extra));};
                const __wpResult=(status,detail,extra={})=>{__wpSave();return result(status,detail,__wpDiagnostics(extra));};
                const __wpReady=(extra={})=>{const diagnostics=__wpDiagnostics(extra);__wpClear();return result('READY',__wpKind==='model'?'모델 적용 확인':'추론 적용 확인',diagnostics);};
                if(__wpState.verified&&__wpPopups.length===0)return __wpReady({action:'verified-readback',observed:__wpState.verified});
                if(__wpKind==='model'){
                  if(__wpModelOptions.length>0){
                    if(__wpWantedOption&&__wpSelected(__wpWantedOption.element)){__wpState.verified=__wpWanted;__wpState.lastAction='close-model-menu';__wpState.lastActionAt=__wpNow;__wpSave();const method=__wpClose();return result('UI_WAIT','WORK 모델 선택 확인 후 설정 팝업 복귀 대기',__wpDiagnostics({action:'close-model-menu',closeMethod:method}));}
                    if(__wpWantedOption&&__wpMay(__wpState.optionClicks,2)){__wpState.optionClicks++;__wpState.lastAction='select-model';__wpState.lastActionAt=__wpNow;__wpSave();__wpActivate(__wpWantedOption.element);return result('UI_WAIT','WORK 모델 변경 반영 대기',__wpDiagnostics({action:'select-model'}));}
                    if(__wpElapsed>=__wpTimeoutMs)return __wpFailure('SELECTION_TIMEOUT','현재 WORK 모델 메뉴에 요청 모델이 없습니다.',{action:'model-unavailable'});
                    return __wpResult('UI_WAIT','WORK 모델 메뉴 옵션 렌더링 대기',{action:'wait-model-options'});
                  }
                  if(__wpCurrentModel===__wpWanted){__wpState.verified=__wpWanted;__wpSave();if(__wpPopups.length===0)return __wpReady({action:'already-selected'});if(__wpMay(__wpState.closeAttempts,3)){__wpState.closeAttempts++;__wpState.lastAction='close-model-target';__wpState.lastActionAt=__wpNow;__wpSave();const method=__wpClose();return result('UI_WAIT','WORK 모델 적용 확인 후 팝업 닫힘 대기',__wpDiagnostics({action:'close-model-target',closeMethod:method}));}}
                  if(__wpShowAdvanced&&__wpMay(__wpState.advancedClicks,1)){__wpState.advancedClicks++;__wpState.lastAction='open-legacy-advanced';__wpState.lastActionAt=__wpNow;__wpSave();__wpActivate(__wpShowAdvanced);return result('UI_WAIT','구형 WORK 고급 메뉴 전환 반영 대기',__wpDiagnostics({action:'open-legacy-advanced'}));}
                  const __wpModelTrigger=__wpHeader||__wpModelRows[0]||null;
                  if(__wpModelTrigger&&__wpMay(__wpState.rowClicks,2)){__wpState.rowClicks++;__wpState.lastAction='open-model-menu';__wpState.lastActionAt=__wpNow;__wpSave();__wpActivate(__wpModelTrigger);return result('UI_WAIT','WORK 현재 모델 텍스트에서 모델 메뉴 열림 대기',__wpDiagnostics({action:'open-model-menu'}));}
                }else{
                  if(__wpReasoningOptions.length>0&&!__wpSlider){
                    if(__wpWantedOption&&__wpSelected(__wpWantedOption.element)){__wpState.verified=__wpWanted;__wpState.lastAction='close-reasoning-menu';__wpState.lastActionAt=__wpNow;__wpSave();const method=__wpClose();return result('UI_WAIT','WORK 추론 옵션 선택 확인 후 메뉴 닫힘 대기',__wpDiagnostics({action:'close-reasoning-menu',closeMethod:method}));}
                    if(__wpWantedOption&&__wpMay(__wpState.optionClicks,2)){__wpState.optionClicks++;__wpState.lastAction='select-reasoning-option';__wpState.lastActionAt=__wpNow;__wpSave();__wpActivate(__wpWantedOption.element);return result('UI_WAIT','WORK 추론 옵션 반영 대기',__wpDiagnostics({action:'select-reasoning-option'}));}
                  }
                  if(__wpCurrentReasoning===__wpWanted){__wpState.verified=__wpWanted;__wpState.pending=false;__wpSave();if(__wpPopups.length===0)return __wpReady({action:'already-selected'});if(__wpMay(__wpState.closeAttempts,3)){__wpState.closeAttempts++;__wpState.lastAction='close-reasoning-target';__wpState.lastActionAt=__wpNow;__wpSave();const method=__wpClose();return result('UI_WAIT','WORK 추론 적용 확인 후 팝업 닫힘 대기',__wpDiagnostics({action:'close-reasoning-target',closeMethod:method}));}}
                  if(__wpSlider){
                    const __wpInputRange=typeof HTMLInputElement!=='undefined'&&__wpSlider instanceof HTMLInputElement&&__wpSlider.type==='range';
                    const __wpNum=(value,fallback)=>{if(value==null||String(value).trim()==='')return fallback;const n=Number(value);return Number.isFinite(n)?n:fallback;};
                    const __wpMin=__wpNum(__wpSlider.getAttribute('aria-valuemin'),__wpInputRange?__wpNum(__wpSlider.min,0):0),__wpMax=__wpNum(__wpSlider.getAttribute('aria-valuemax'),__wpInputRange?__wpNum(__wpSlider.max,100):100),__wpRange=__wpMax-__wpMin;
                    const __wpStepRaw=__wpNum(__wpSlider.getAttribute('aria-valuestep'),__wpInputRange?__wpNum(__wpSlider.step,NaN):NaN),__wpCurrent=__wpNum(__wpSlider.getAttribute('aria-valuenow'),__wpInputRange?__wpNum(__wpSlider.value,NaN):NaN);
                    const __wpExactCount=Number.isFinite(__wpStepRaw)&&__wpStepRaw>0&&__wpRange>0?Math.round(__wpRange/__wpStepRaw)+1:0;
                    if(!__wpCurrentReasoning&&Number.isFinite(__wpCurrent)&&__wpExactCount===__wpOrder.length){const step=__wpRange/(__wpOrder.length-1),index=Math.round((__wpCurrent-__wpMin)/step);if(index>=0&&index<__wpOrder.length)__wpCurrentReasoning=__wpOrder[index];}
                    if(__wpCurrentReasoning===__wpWanted){__wpState.verified=__wpWanted;__wpState.pending=false;__wpSave();if(__wpMay(__wpState.closeAttempts,3)){__wpState.closeAttempts++;__wpState.lastAction='close-reasoning-target';__wpState.lastActionAt=__wpNow;__wpSave();const method=__wpClose();return result('UI_WAIT','WORK 추론 적용 확인 후 팝업 닫힘 대기',__wpDiagnostics({action:'close-reasoning-target',closeMethod:method}));}}
                    const __wpCurrentIndex=__wpOrder.indexOf(__wpCurrentReasoning),__wpWantedIndex=__wpOrder.indexOf(__wpWanted);
                    if(__wpCurrentIndex<0||__wpWantedIndex<0){if(__wpElapsed>=__wpTimeoutMs)return __wpFailure('READBACK_MISMATCH','WORK 추론 슬라이더 의미값을 판정하지 못했습니다.',{action:'slider-semantic-missing',current:__wpCurrent});return __wpResult('UI_WAIT','WORK 추론 슬라이더 의미값 readback 대기',{action:'wait-slider-semantic',current:__wpCurrent});}
                    const __wpDirection=Math.sign(__wpWantedIndex-__wpCurrentIndex);
                    if(__wpState.pending){
                      if(__wpCurrentReasoning&&__wpCurrentReasoning!==__wpState.pendingLevel){const delta=__wpOrder.indexOf(__wpCurrentReasoning)-__wpOrder.indexOf(__wpState.pendingLevel);if(delta!==0&&Math.sign(delta)===Math.sign(__wpState.pendingDirection)){__wpState.pending=false;__wpState.pendingWaits=0;__wpState.pendingStrategy='';__wpSave();}else return __wpFailure('READBACK_MISMATCH','WORK 추론 슬라이더가 요청 반대 방향으로 변경됐습니다.',{action:'slider-direction-mismatch'});}
                      else{__wpState.pendingWaits++;if(__wpState.pendingStrategy==='keyboard'&&__wpState.pendingWaits>=1&&Number.isFinite(Number(__wpState.pendingTarget))){const target=Number(__wpState.pendingTarget),rect0=__wpSlider.getBoundingClientRect();let track=rect0.width>=120?__wpSlider:null,node=__wpSlider.parentElement;for(let depth=0;!track&&node&&depth<6;depth++,node=node.parentElement){const r=node.getBoundingClientRect();if(r.width>=120&&r.height<=96){track=node;break;}}if(track){const r=track.getBoundingClientRect(),ratio=Math.max(0.01,Math.min(0.99,(target-__wpMin)/__wpRange)),x=r.left+r.width*ratio,y=r.top+r.height/2,common={bubbles:true,cancelable:true,composed:true,clientX:x,clientY:y,button:0};for(const targetNode of [__wpSlider,track].filter((item,index,array)=>item&&array.indexOf(item)===index)){try{if(typeof PointerEvent==='function'){targetNode.dispatchEvent(new PointerEvent('pointerdown',{...common,buttons:1,pointerId:1,pointerType:'touch',isPrimary:true}));targetNode.dispatchEvent(new PointerEvent('pointerup',{...common,buttons:0,pointerId:1,pointerType:'touch',isPrimary:true}));}}catch(_){}targetNode.dispatchEvent(new MouseEvent('mousedown',{...common,buttons:1}));targetNode.dispatchEvent(new MouseEvent('mouseup',{...common,buttons:0}));targetNode.dispatchEvent(new MouseEvent('click',{...common,buttons:0}));}__wpState.pendingStrategy='pointer';__wpState.pendingWaits=0;__wpState.lastAction='slider-pointer-fallback';__wpState.lastActionAt=__wpNow;__wpSave();return result('UI_WAIT','WORK 추론 슬라이더 포인터 보정 반영 대기',__wpDiagnostics({action:'slider-pointer-fallback',target}));}}
                        if(__wpState.pendingWaits>=2)return __wpFailure('READBACK_MISMATCH','WORK 추론 슬라이더 입력 후 의미값이 갱신되지 않았습니다.',{action:'slider-readback-timeout'});__wpSave();return result('UI_WAIT','WORK 추론 슬라이더 변경 readback 대기',__wpDiagnostics({action:'wait-slider-readback'}));}
                    }
                    if(!Number.isFinite(__wpCurrent)||!(__wpRange>0))return __wpFailure('READBACK_MISMATCH','WORK 추론 슬라이더 숫자 범위를 확인할 수 없습니다.',{action:'slider-range-invalid',current:__wpCurrent,min:__wpMin,max:__wpMax});
                    if(__wpState.sliderMoves>=12)return __wpFailure('READBACK_MISMATCH','WORK 추론 슬라이더가 제한 이동 횟수 안에 목표에 도달하지 못했습니다.',{action:'slider-move-limit'});
                    const __wpStep=Number.isFinite(__wpStepRaw)&&__wpStepRaw>0?__wpStepRaw:__wpRange/Math.max(1,__wpOrder.length-1),__wpTarget=Math.max(__wpMin,Math.min(__wpMax,__wpCurrent+__wpDirection*__wpStep));
                    let __wpStrategy='keyboard';if(__wpInputRange){const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')?.set;if(setter)setter.call(__wpSlider,String(__wpTarget));else __wpSlider.value=String(__wpTarget);__wpSlider.dispatchEvent(new Event('input',{bubbles:true,composed:true}));__wpSlider.dispatchEvent(new Event('change',{bubbles:true}));__wpStrategy='native-range';}else{__wpSlider.focus?.();const key=__wpDirection>0?'ArrowRight':'ArrowLeft';__wpSlider.dispatchEvent(new KeyboardEvent('keydown',{key,code:key,bubbles:true,cancelable:true,composed:true}));__wpSlider.dispatchEvent(new KeyboardEvent('keyup',{key,code:key,bubbles:true,cancelable:true,composed:true}));}
                    __wpState.sliderMoves++;__wpState.pending=true;__wpState.pendingLevel=__wpCurrentReasoning;__wpState.pendingDirection=__wpDirection;__wpState.pendingTarget=__wpTarget;__wpState.pendingStrategy=__wpStrategy;__wpState.pendingWaits=0;__wpState.lastAction='set-slider';__wpState.lastActionAt=__wpNow;__wpSave();return result('UI_WAIT','WORK 추론 슬라이더 이동 반영 대기',__wpDiagnostics({action:'set-slider',strategy:__wpStrategy,target:__wpTarget,current:__wpCurrent}));
                  }
                  if(__wpShowAdvanced&&__wpMay(__wpState.advancedClicks,1)){__wpState.advancedClicks++;__wpState.lastAction='open-legacy-advanced';__wpState.lastActionAt=__wpNow;__wpSave();__wpActivate(__wpShowAdvanced);return result('UI_WAIT','구형 WORK 고급 메뉴 전환 반영 대기',__wpDiagnostics({action:'open-legacy-advanced'}));}
                  const __wpReasoningTrigger=__wpReasoningRows[0]||null;if(__wpReasoningTrigger&&__wpMay(__wpState.rowClicks,2)){__wpState.rowClicks++;__wpState.lastAction='open-reasoning-menu';__wpState.lastActionAt=__wpNow;__wpSave();__wpActivate(__wpReasoningTrigger);return result('UI_WAIT','구형 WORK 추론 메뉴 열림 대기',__wpDiagnostics({action:'open-reasoning-menu'}));}
                }
                if(__wpPopups.length===0&&__wpRoot&&__wpMay(__wpState.rootClicks,2)){__wpState.rootClicks++;__wpState.lastAction='open-effort-popover';__wpState.lastActionAt=__wpNow;__wpSave();__wpActivate(__wpRoot);return result('UI_WAIT','WORK 추론 설정 팝업 열림 대기',__wpDiagnostics({action:'open-effort-popover'}));}
                if(__wpElapsed>=__wpTimeoutMs||__wpState.attempts>=__wpMaxAttempts)return __wpFailure('SELECTION_TIMEOUT','WORK 모델·추론 선택 UI를 제한시간 안에 준비하지 못했습니다.',{action:'selector-timeout'});
                return __wpResult('UI_WAIT','WORK 모델·추론 선택 UI 렌더링 대기',{action:'wait-selector'});
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
