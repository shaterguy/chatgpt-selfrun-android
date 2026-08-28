package com.shaterguy.chatgptselfrun;

/** Work model/reasoning selector for the current composer effort picker. */
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
                const __wpText=value=>String(value??'').replace(/[\\s\\u00a0]+/g,' ').trim().toLowerCase();
                const __wpVisible=e=>!!e&&e.isConnected&&e.offsetParent!==null&&!e.closest?.('[inert],[aria-hidden="true"],[data-active="false"]');
                const __wpLabel=e=>__wpText(e?.getAttribute?.('aria-label')||e?.innerText||e?.textContent||'');
                const __wpModel=source=>{const v=__wpText(source);if(v.includes('luna'))return'luna';if(v.includes('terra'))return'terra';if(v.includes('sol'))return'sol';return'';};
                const __wpReasoning=source=>{const v=__wpText(source);if(v.includes('ultra')||v.includes('울트라'))return'ultra';if(v.includes('maximum')||v.includes('최대')||v==='max'||v.endsWith(' max'))return'max';if(v.includes('extra high')||v.includes('very high')||v.includes('xhigh')||v.includes('매우 높음'))return'xhigh';if(v.includes('medium')||v.includes('중간'))return'medium';if(v.includes('light')||v.includes('가벼움'))return'light';if(v.includes('high')||v.includes('높음'))return'high';return'';};
                const __wpOrder=['light','medium','high','xhigh','max','ultra'];
                const __wpPopupSelector='[role="menu"],[role="listbox"],[role="dialog"],[data-testid="composer-intelligence-picker-content"],[data-radix-popper-content-wrapper],[data-slot*="popover-content"],[data-slot*="menu-content"]';
                const __wpInput=document.querySelector('#prompt-textarea')||[...document.querySelectorAll('textarea,[contenteditable="true"]')].filter(__wpVisible).sort((a,b)=>b.getBoundingClientRect().bottom-a.getBoundingClientRect().bottom)[0]||null;
                const __wpForm=__wpInput?.closest?.('form')||null;
                const __wpNear=e=>{if(!e||!__wpInput)return false;if(__wpForm?.contains?.(e))return true;const a=e.getBoundingClientRect(),b=__wpInput.getBoundingClientRect();return a.bottom>=b.top-320&&a.top<=b.bottom+320&&a.right>=b.left-420&&a.left<=b.right+420;};
                const __wpForbidden=e=>/(send|submit|보내기|stop|중지|voice|음성|microphone|마이크|attach|첨부|upload|업로드)/.test(__wpLabel(e)+' '+__wpText(e?.dataset?.testid||''));
                const __wpRoots=[...document.querySelectorAll('button,[role="button"],[aria-haspopup],[aria-expanded],[data-testid*="model"],[data-testid*="reason"],[data-testid*="effort"]')].filter(__wpVisible).filter(e=>!e.closest(__wpPopupSelector)).filter(e=>!__wpForbidden(e)).map((element,index)=>{const label=__wpLabel(element),testid=__wpText(element.dataset?.testid||'');let score=0;if(/select effort|effort|reasoning|thinking|추론|작업 강도/.test(label+' '+testid))score+=170;if(__wpModel(label)||__wpReasoning(label))score+=100;if(element.hasAttribute('aria-haspopup'))score+=45;if(element.hasAttribute('aria-expanded'))score+=35;if(__wpNear(element))score+=190;return{element,index,score};}).filter(x=>x.score>0).sort((a,b)=>b.score-a.score||a.index-b.index);
                const __wpCalibratedRaw=__srFind(__wpPurpose),__wpCalibrated=__wpCalibratedRaw?.closest?.('button,[role="button"],[aria-haspopup],[aria-expanded]')||__wpCalibratedRaw;
                const __wpRoot=__wpRoots[0]?.element||(__wpCalibrated&&__wpVisible(__wpCalibrated)&&__wpNear(__wpCalibrated)?__wpCalibrated:null);
                const __wpOpen=[...document.querySelectorAll(__wpPopupSelector)].filter(__wpVisible);
                const __wpExplicitModelRow=[...document.querySelectorAll('[role="menuitem"][aria-label]')].filter(__wpVisible).find(e=>{const v=__wpLabel(e);return v==='select model'||v==='모델 선택';})||null;
                const __wpCombined=[...document.querySelectorAll('[aria-haspopup],[role="menuitem"],button,[role="button"]')].filter(__wpVisible).filter(e=>__wpOpen.some(p=>p.contains(e))).find(e=>__wpModel(__wpLabel(e))&&__wpReasoning(__wpLabel(e)))||null;
                const __wpModelRow=__wpExplicitModelRow||__wpCombined;
                const __wpPerformance=[...document.querySelectorAll('[role="menuitem"][aria-label]')].filter(__wpVisible).find(e=>{const v=__wpLabel(e);return v==='성능'||v==='performance';})||null;
                const __wpSliders=[...document.querySelectorAll('[role="slider"],input[type="range"]')].filter(__wpVisible).filter(e=>e.getAttribute('aria-orientation')!=='vertical');
                const __wpSlider=__wpPerformance?.querySelector?.('[role="slider"],input[type="range"]')||__wpSliders.find(e=>__wpPerformance?.contains?.(e))||__wpSliders.find(e=>__wpOpen.some(p=>p.contains(e)))||null;
                const __wpOptions=[...document.querySelectorAll('[role="menuitemradio"],[role="radio"],[role="option"]')].filter(__wpVisible);
                const __wpModelOptions=__wpOptions.map(element=>({element,model:__wpModel(__wpLabel(element)),selected:element.getAttribute('aria-checked')==='true'||element.getAttribute('aria-selected')==='true'||element.dataset?.state==='checked'})).filter(x=>x.model);
                const __wpReasoningOptions=__wpOptions.map(element=>({element,reasoning:__wpReasoning(__wpLabel(element)),selected:element.getAttribute('aria-checked')==='true'||element.getAttribute('aria-selected')==='true'||element.dataset?.state==='checked'})).filter(x=>x.reasoning);
                const __wpShowAdvanced=[...document.querySelectorAll('button,[role="button"],[role="menuitem"]')].filter(__wpVisible).find(e=>{const v=__wpLabel(e);return v.startsWith('show advanced')||v.startsWith('advanced')||v.startsWith('고급');})||null;
                let __wpCurrentModel=__wpModel(__wpModelRow?.innerText||'')||__wpModel(__wpRoot?.innerText||'')||__wpModelOptions.find(x=>x.selected)?.model||'';
                let __wpCurrentReasoning=__wpReasoning(__wpModelRow?.innerText||'')||__wpReasoning(__wpRoot?.innerText||'')||__wpReasoning(__wpSlider?.getAttribute?.('aria-valuetext')||'')||__wpReasoningOptions.find(x=>x.selected)?.reasoning||'';
                const __wpStateKey='selfrun-drive:work-preference-current:'+__wpKind+':'+__wpPurpose+':'+location.pathname;
                const __wpNow=Date.now(),__wpTimeoutMs=26000,__wpRetryMs=2400;
                let __wpState={startedAt:0,requested:'',attempts:0,rootClicks:0,advancedClicks:0,rowClicks:0,optionClicks:0,sliderMoves:0,closeAttempts:0,verified:'',lastAction:'',lastActionAt:0};
                try{const raw=sessionStorage.getItem(__wpStateKey)||localStorage.getItem(__wpStateKey)||'';if(raw)__wpState={...__wpState,...JSON.parse(raw)};}catch(_){}
                if(__wpState.requested&&__wpState.requested!==__wpWanted)__wpState={startedAt:0,requested:__wpWanted,attempts:0,rootClicks:0,advancedClicks:0,rowClicks:0,optionClicks:0,sliderMoves:0,closeAttempts:0,verified:'',lastAction:'',lastActionAt:0};
                if(!(Number(__wpState.startedAt)>0))__wpState.startedAt=__wpNow;__wpState.requested=__wpWanted;__wpState.attempts++;
                const __wpElapsed=__wpNow-__wpState.startedAt,__wpSince=__wpState.lastActionAt?__wpNow-__wpState.lastActionAt:999999;
                const __wpSave=()=>{const value=JSON.stringify(__wpState);try{sessionStorage.setItem(__wpStateKey,value);}catch(_){}try{localStorage.setItem(__wpStateKey,value);}catch(_){}};
                const __wpClear=()=>{try{sessionStorage.removeItem(__wpStateKey);}catch(_){}try{localStorage.removeItem(__wpStateKey);}catch(_){}};
                const __wpMay=(count,max)=>count<1||(__wpSince>=__wpRetryMs&&count<max);
                const __wpFire=(e,type,x,y,buttons)=>{if(!e)return;const init={bubbles:true,cancelable:true,composed:true,clientX:x,clientY:y,button:0,buttons};try{if(type.startsWith('pointer')&&typeof PointerEvent==='function')e.dispatchEvent(new PointerEvent(type,{...init,pointerId:1,pointerType:'mouse',isPrimary:true}));else e.dispatchEvent(new MouseEvent(type,init));}catch(_){}};
                const __wpClick=e=>{if(!e)return;const r=e.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;e.focus?.();__wpFire(e,'pointerdown',x,y,1);__wpFire(e,'mousedown',x,y,1);__wpFire(e,'pointerup',x,y,0);__wpFire(e,'mouseup',x,y,0);__wpFire(e,'click',x,y,0);};
                const __wpClose=()=>{if(__wpRoot?.getAttribute?.('aria-expanded')==='true'){__wpClick(__wpRoot);return'trigger';}if(__wpRoot&&__wpOpen.length>0&&__wpRoot.getAttribute?.('aria-expanded')===null&&!__wpRoot.hasAttribute?.('aria-haspopup')){__wpClick(__wpRoot);return'trigger-untracked';}document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true,cancelable:true,composed:true}));document.dispatchEvent(new KeyboardEvent('keyup',{key:'Escape',code:'Escape',bubbles:true,cancelable:true,composed:true}));return'escape';};
                const __wpDiagnostics=extra=>({kind:__wpKind,requested:__wpWanted,calibrationPurpose:__wpPurpose,strategy:'composer-detent-picker',rootFound:!!__wpRoot,rootLabel:__wpLabel(__wpRoot),popupCandidates:__wpOpen.length,modelRowFound:!!__wpModelRow,performanceFound:!!__wpPerformance,sliderFound:!!__wpSlider,currentModel:__wpCurrentModel||(__wpKind==='model'?__wpState.verified:''),currentReasoning:__wpCurrentReasoning||(__wpKind==='reasoning'?__wpState.verified:''),current:(__wpKind==='model'?__wpCurrentModel:__wpCurrentReasoning)||__wpState.verified,modelOptions:__wpModelOptions.map(x=>x.model),reasoningOptions:__wpReasoningOptions.map(x=>x.reasoning),showAdvancedFound:!!__wpShowAdvanced,rootClicks:__wpState.rootClicks,advancedClicks:__wpState.advancedClicks,rowClicks:__wpState.rowClicks,optionClicks:__wpState.optionClicks,sliderMoves:__wpState.sliderMoves,closeAttempts:__wpState.closeAttempts,lastAction:__wpState.lastAction,attempts:__wpState.attempts,elapsedMs:__wpElapsed,timeoutMs:__wpTimeoutMs,...extra});
                const __wpFailure=(suffix,detail,extra={})=>{__wpSave();return result('WORK_'+(__wpKind==='model'?'MODEL':'REASONING')+'_'+suffix,detail,__wpDiagnostics(extra));};
                const __wpWait=(detail,extra={})=>{__wpSave();return result('UI_WAIT',detail,__wpDiagnostics(extra));};
                const __wpReady=(extra={})=>{const d=__wpDiagnostics(extra);__wpClear();return result('READY',__wpKind==='model'?'모델 적용 확인':'추론 적용 확인',d);};
                if(__wpState.verified&&__wpOpen.length===0)return __wpReady({action:'verified-readback',observed:__wpState.verified});
                if(__wpKind==='model'){
                  if(__wpModelOptions.length>0){const option=__wpModelOptions.find(x=>x.model===__wpWanted);if(option?.selected){__wpState.verified=__wpWanted;__wpState.lastAction='close-model-menu';__wpState.lastActionAt=__wpNow;__wpSave();__wpClose();return result('UI_WAIT','WORK 모델 선택 확인 후 설정 팝업 복귀 대기',__wpDiagnostics({action:'close-model-menu'}));}if(option&&__wpMay(__wpState.optionClicks,2)){__wpState.optionClicks++;__wpState.lastAction='select-model';__wpState.lastActionAt=__wpNow;__wpSave();__wpClick(option.element);return result('UI_WAIT','WORK 모델 변경 반영 대기',__wpDiagnostics({action:'select-model'}));}if(__wpElapsed>=__wpTimeoutMs)return __wpFailure('SELECTION_TIMEOUT','현재 WORK 모델 메뉴에 요청 모델이 없습니다.',{action:'model-unavailable'});return __wpWait('WORK 모델 메뉴 옵션 렌더링 대기',{action:'wait-model-options'});}
                  if(__wpCurrentModel===__wpWanted){__wpState.verified=__wpWanted;__wpSave();if(__wpOpen.length===0)return __wpReady({action:'already-selected',observed:__wpWanted});if(__wpMay(__wpState.closeAttempts,3)){__wpState.closeAttempts++;__wpState.lastAction='close-model-target';__wpState.lastActionAt=__wpNow;__wpSave();__wpClose();return result('UI_WAIT','WORK 모델 적용 확인 후 팝업 닫힘 대기',__wpDiagnostics({action:'close-model-target'}));}}
                  if(__wpShowAdvanced&&__wpMay(__wpState.advancedClicks,1)){__wpState.advancedClicks++;__wpState.lastAction='open-legacy-advanced';__wpState.lastActionAt=__wpNow;__wpSave();__wpClick(__wpShowAdvanced);return result('UI_WAIT','구형 WORK 고급 메뉴 전환 반영 대기',__wpDiagnostics({action:'open-legacy-advanced'}));}
                  if(__wpModelRow&&__wpMay(__wpState.rowClicks,2)){__wpState.rowClicks++;__wpState.lastAction='open-model-menu';__wpState.lastActionAt=__wpNow;__wpSave();__wpClick(__wpModelRow);return result('UI_WAIT','WORK 현재 모델 텍스트에서 모델 메뉴 열림 대기',__wpDiagnostics({action:'open-model-menu'}));}
                }else{
                  if(__wpReasoningOptions.length>0&&!__wpSlider){const option=__wpReasoningOptions.find(x=>x.reasoning===__wpWanted);if(option?.selected){__wpState.verified=__wpWanted;__wpSave();__wpClose();return result('UI_WAIT','WORK 추론 옵션 선택 확인 후 메뉴 닫힘 대기',__wpDiagnostics({action:'close-reasoning-menu'}));}if(option&&__wpMay(__wpState.optionClicks,2)){__wpState.optionClicks++;__wpState.lastAction='select-reasoning-option';__wpState.lastActionAt=__wpNow;__wpSave();__wpClick(option.element);return result('UI_WAIT','WORK 추론 옵션 반영 대기',__wpDiagnostics({action:'select-reasoning-option'}));}}
                  if(__wpCurrentReasoning===__wpWanted){__wpState.verified=__wpWanted;__wpSave();if(__wpOpen.length===0)return __wpReady({action:'already-selected',observed:__wpWanted});if(__wpMay(__wpState.closeAttempts,3)){__wpState.closeAttempts++;__wpState.lastAction='close-reasoning-target';__wpState.lastActionAt=__wpNow;__wpSave();__wpClose();return result('UI_WAIT','WORK 추론 적용 확인 후 팝업 닫힘 대기',__wpDiagnostics({action:'close-reasoning-target'}));}}
                  if(__wpSlider){const wantedIndex=__wpOrder.indexOf(__wpWanted),currentIndex=__wpOrder.indexOf(__wpCurrentReasoning);if(wantedIndex<0||currentIndex<0){if(__wpElapsed>=__wpTimeoutMs)return __wpFailure('READBACK_MISMATCH','WORK 추론 슬라이더 의미값을 판정하지 못했습니다.',{action:'slider-semantic-missing'});return __wpWait('WORK 추론 슬라이더 의미값 readback 대기',{action:'wait-slider-semantic'});}const __wpInputRange=typeof HTMLInputElement!=='undefined'&&__wpSlider instanceof HTMLInputElement&&__wpSlider.type==='range',raw=__wpInputRange?__wpSlider.value:__wpSlider.getAttribute('aria-valuenow'),num=raw==null||String(raw).trim()===''?NaN:Number(raw);if(Number.isFinite(num)){const direction=Math.sign(wantedIndex-currentIndex);if(direction!==0&&__wpState.sliderMoves<12){if(__wpInputRange){const min=Number(__wpSlider.min||0),max=Number(__wpSlider.max||5),step=Number(__wpSlider.step||1)||1,target=Math.max(min,Math.min(max,num+direction*step)),setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')?.set;if(setter)setter.call(__wpSlider,String(target));else __wpSlider.value=String(target);__wpSlider.dispatchEvent(new Event('input',{bubbles:true,composed:true}));__wpSlider.dispatchEvent(new Event('change',{bubbles:true}));}else{__wpSlider.focus?.();const key=direction>0?'ArrowRight':'ArrowLeft';__wpSlider.dispatchEvent(new KeyboardEvent('keydown',{key,code:key,bubbles:true,cancelable:true,composed:true}));__wpSlider.dispatchEvent(new KeyboardEvent('keyup',{key,code:key,bubbles:true,cancelable:true,composed:true}));}__wpState.sliderMoves++;__wpState.lastAction='set-slider';__wpState.lastActionAt=__wpNow;return __wpWait('WORK 추론 슬라이더 이동 반영 대기',{action:'set-slider',strategy:__wpInputRange?'native-range':'keyboard'});}}
                    if(__wpPerformance){const sr=__wpSlider.getBoundingClientRect(),cy=sr.top+sr.height/2,small=[...__wpPerformance.querySelectorAll('span')].filter(e=>e!==__wpSlider&&__wpVisible(e)).map(e=>({e,r:e.getBoundingClientRect()})).filter(x=>x.r.width>=4&&x.r.width<=12&&x.r.height>=4&&x.r.height<=12&&Math.abs((x.r.top+x.r.height/2)-cy)<=16).sort((a,b)=>a.r.left-b.r.left),detents=[];for(const x of small){const cx=x.r.left+x.r.width/2;if(!detents.some(d=>Math.abs(d.cx-cx)<3))detents.push({e:x.e,cx,left:x.r.left});}if(detents.length>=2&&__wpState.sliderMoves<12){let targetIndex;if(detents.length===__wpOrder.length)targetIndex=wantedIndex;else{let currentDetent=0,best=1e9;for(let i=0;i<detents.length;i++){const diff=Math.abs(detents[i].left-sr.left);if(diff<best){best=diff;currentDetent=i;}}targetIndex=currentDetent+(wantedIndex-currentIndex);}if(targetIndex>=0&&targetIndex<detents.length){__wpState.sliderMoves++;__wpState.lastAction='set-slider-detent';__wpState.lastActionAt=__wpNow;__wpSave();__wpClick(detents[targetIndex].e);return result('UI_WAIT','WORK 추론 detent 반영 대기',__wpDiagnostics({action:'set-slider-detent',strategy:'detent',detentCount:detents.length,targetIndex}));}}}
                    let track=__wpSlider.parentElement;for(let depth=0;track&&depth<5;depth++){const r=track.getBoundingClientRect();if(r.width>=120&&r.height<=48)break;track=track.parentElement;}if(track&&__wpState.sliderMoves<12){const r=track.getBoundingClientRect(),ratio=wantedIndex/Math.max(1,__wpOrder.length-1),x=r.left+r.width*ratio,y=r.top+r.height/2,hit=document.elementFromPoint?.(x,y)||null,target=hit&&track.contains(hit)?hit:track,init={bubbles:true,cancelable:true,composed:true,clientX:x,clientY:y,button:0};try{if(typeof PointerEvent==='function'){target.dispatchEvent(new PointerEvent('pointerdown',{...init,buttons:1,pointerId:1,pointerType:'mouse',isPrimary:true}));target.dispatchEvent(new PointerEvent('pointerup',{...init,buttons:0,pointerId:1,pointerType:'mouse',isPrimary:true}));}}catch(_){}target.dispatchEvent(new MouseEvent('mousedown',{...init,buttons:1}));target.dispatchEvent(new MouseEvent('mouseup',{...init,buttons:0}));target.dispatchEvent(new MouseEvent('click',{...init,buttons:0}));__wpState.sliderMoves++;__wpState.lastAction='set-slider-track';__wpState.lastActionAt=__wpNow;return __wpWait('WORK 추론 track 반영 대기',{action:'set-slider-track',strategy:'geometry'});}}
                  if(__wpShowAdvanced&&__wpMay(__wpState.advancedClicks,1)){__wpState.advancedClicks++;__wpState.lastAction='open-legacy-advanced';__wpState.lastActionAt=__wpNow;__wpSave();__wpClick(__wpShowAdvanced);return result('UI_WAIT','구형 WORK 고급 메뉴 전환 반영 대기',__wpDiagnostics({action:'open-legacy-advanced'}));}
                }
                if(__wpOpen.length===0&&__wpRoot&&__wpMay(__wpState.rootClicks,2)){__wpState.rootClicks++;__wpState.lastAction='open-effort-popover';__wpState.lastActionAt=__wpNow;__wpSave();__wpClick(__wpRoot);return result('UI_WAIT','WORK 추론 설정 팝업 열림 대기',__wpDiagnostics({action:'open-effort-popover'}));}
                if(__wpElapsed>=__wpTimeoutMs||__wpState.attempts>=32)return __wpFailure('SELECTION_TIMEOUT','WORK 모델·추론 선택 UI를 제한시간 안에 준비하지 못했습니다.',{action:'selector-timeout'});
                return __wpWait('WORK 모델·추론 선택 UI 렌더링 대기',{action:'wait-selector'});
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
