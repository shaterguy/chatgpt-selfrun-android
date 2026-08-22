package com.shaterguy.chatgptselfrun;

/** Monotonic bootstrap mode gate shared by Chat and Work initial-context preparation. */
final class BootstrapModeDom {
    private BootstrapModeDom() {}

    static String inline(String requested, String runId) {
        return """
                const requestedMode=__REQUESTED__;
                const modeRunId=__RUN_ID__;
                const forbiddenMode=/new chat|새 채팅|새 대화|new conversation/i;
                const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;
                const exactText=s=>String(s??'').replace(/\\s+/g,' ').trim().toLowerCase();
                const labelOf=e=>exactText(e?.innerText||'')||exactText(e?.getAttribute?.('aria-label')||'');
                const interactiveOwner=e=>e?.closest?.('button,[role="button"],[role="radio"],[role="tab"],input[type="radio"],[aria-checked],[aria-selected],[aria-pressed]')||e||null;
                const selectedDirect=e=>{
                  if(!e)return false;
                  const current=e.getAttribute?.('aria-current');
                  return e.getAttribute?.('aria-checked')==='true'||e.getAttribute?.('aria-pressed')==='true'||e.getAttribute?.('aria-selected')==='true'||(current!=null&&current!==''&&current!=='false')||(typeof e.checked==='boolean'&&e.checked)||e.dataset?.active==='true'||e.dataset?.selected==='true'||/^(checked|selected|active|on)$/.test(exactText(e.dataset?.state||''));
                };
                const selectedState=e=>{
                  if(!e)return false;
                  const owner=interactiveOwner(e);
                  const owned=[e,owner].filter((node,index,all)=>node&&all.indexOf(node)===index);
                  if(owned.some(selectedDirect))return true;
                  const selector='[aria-checked="true"],[aria-pressed="true"],[aria-selected="true"],[aria-current]:not([aria-current="false"]),[data-active="true"],[data-selected="true"],[data-state="checked"],[data-state="selected"],[data-state="active"],[data-state="on"],input[type="radio"]:checked';
                  if(owned.some(node=>!!node.querySelector?.(selector)))return true;
                  const parents=[e.parentElement,owner?.parentElement].filter((node,index,all)=>node&&all.indexOf(node)===index);
                  return parents.some(selectedDirect);
                };
                const modeOf=s=>{const v=exactText(s);if(forbiddenMode.test(v))return'';const tokens=v.split(/[^a-z0-9가-힣]+/).filter(Boolean);if(tokens.includes('chat')||tokens.includes('채팅'))return'chat';if(tokens.includes('work')||tokens.includes('작업'))return'work';return''};
                const rawModeControls=[...document.querySelectorAll('button,[role="button"],[role="radio"],[role="tab"],input[type="radio"]')].filter(visible).filter(e=>{if(e.closest('[role="menu"],[role="listbox"]'))return false;const m=modeOf(labelOf(e));if(!m)return false;const role=e.getAttribute('role')||'';const testId=exactText(e.dataset?.testid||'');return e.hasAttribute('aria-pressed')||e.hasAttribute('aria-checked')||e.hasAttribute('aria-selected')||role==='radio'||role==='tab'||e.matches('input[type="radio"]')||/mode|experience/.test(testId)||e.tagName==='BUTTON';});
                const groups=[];for(const e of rawModeControls){let p=e.parentElement;for(let depth=0;p&&depth<4;depth++,p=p.parentElement){if(!groups.includes(p))groups.push(p);}}
                const modeGroup=groups.find(g=>{const inside=rawModeControls.filter(e=>g.contains(e));return inside.some(e=>modeOf(labelOf(e))==='chat')&&inside.some(e=>modeOf(labelOf(e))==='work');})||null;
                const modeControls=modeGroup?rawModeControls.filter(e=>modeGroup.contains(e)):[];
                const chatControl=modeControls.find(e=>modeOf(labelOf(e))==='chat')||null;
                const workControl=modeControls.find(e=>modeOf(labelOf(e))==='work')||null;
                const calibratedKey=requestedMode==='work'?__WORK_KEY__:__CHAT_KEY__;
                const calibratedRaw=__srFind(calibratedKey);
                const calibratedTarget=interactiveOwner(calibratedRaw);
                const heuristicTarget=requestedMode==='work'?workControl:chatControl;
                const target=calibratedTarget||heuristicTarget;
                const targetSource=calibratedTarget?'calibrated':'heuristic';
                const targetFound=!!target;
                const targetSelected=selectedState(calibratedRaw||target);
                const selectedModes=[...new Set(modeControls.filter(selectedState).map(e=>modeOf(labelOf(e))).filter(Boolean))];
                const currentMode=selectedModes.length===1?selectedModes[0]:(selectedModes.length>1?'ambiguous':'unknown');
                const modeKey='chatgpt-selfrun:mode:'+modeRunId;
                const stageKey='chatgpt-selfrun:bootstrap-stage:'+modeRunId;
                const modeTimeoutMs=20000,modeMaxAttempts=18;
                let stageState={stage:'MODE_PENDING',requested:'',confirmedMode:'',confirmedAt:0,regressionsBlocked:0};
                try{const raw=sessionStorage.getItem(stageKey)||localStorage.getItem(stageKey)||'';if(raw)stageState={...stageState,...JSON.parse(raw)};}catch(_){}
                const saveStage=()=>{const value=JSON.stringify(stageState);try{sessionStorage.setItem(stageKey,value);}catch(_){}try{localStorage.setItem(stageKey,value);}catch(_){}};
                if(stageState.requested&&stageState.requested!==requestedMode)stageState={stage:'MODE_PENDING',requested:requestedMode,confirmedMode:'',confirmedAt:0,regressionsBlocked:0};
                stageState.requested=requestedMode;
                let modeState={startedAt:0,attempts:0,clickAttempts:0,lastClickAt:0,lastAction:'',requested:''};
                try{const raw=sessionStorage.getItem(modeKey)||localStorage.getItem(modeKey)||'';if(raw)modeState={...modeState,...JSON.parse(raw)};}catch(_){}
                if(modeState.requested&&modeState.requested!==requestedMode)modeState={startedAt:0,attempts:0,clickAttempts:0,lastClickAt:0,lastAction:'',requested:requestedMode};
                const saveMode=()=>{const value=JSON.stringify(modeState);try{sessionStorage.setItem(modeKey,value);}catch(_){}try{localStorage.setItem(modeKey,value);}catch(_){}};
                const clearMode=()=>{try{sessionStorage.removeItem(modeKey);}catch(_){}try{localStorage.removeItem(modeKey);}catch(_){}};
                let modeLatched=stageState.stage==='MODE_CONFIRMED'&&stageState.confirmedMode===requestedMode;
                const explicitObservedMode=currentMode==='chat'||currentMode==='work'?currentMode:'';
                const explicitContradiction=modeLatched&&!!explicitObservedMode&&explicitObservedMode!==requestedMode;
                if(explicitContradiction){
                  stageState={stage:'MODE_PENDING',requested:requestedMode,confirmedMode:'',confirmedAt:0,regressionsBlocked:Number(stageState.regressionsBlocked)||0};
                  saveStage();clearMode();modeState={startedAt:0,attempts:0,clickAttempts:0,lastClickAt:0,lastAction:'',requested:requestedMode};modeLatched=false;
                }
                const modeNow=Date.now();
                if(!(Number(modeState.startedAt)>0))modeState.startedAt=modeNow;
                if(!modeLatched)modeState.attempts=Math.max(0,Number(modeState.attempts)||0)+1;
                modeState.requested=requestedMode;
                const modeElapsedMs=Math.max(0,modeNow-Number(modeState.startedAt||modeNow));
                const recentClick=Number(modeState.lastClickAt)>0&&modeNow-Number(modeState.lastClickAt)<1200;
                let action='';
                const calibratedImplicit=modeState.lastAction==='select-mode-calibrated'&&modeState.requested===requestedMode&&Number(modeState.lastClickAt)>0&&modeNow-Number(modeState.lastClickAt)<5000&&!!composer;
                const heuristicReadback=targetFound&&targetSelected&&currentMode===requestedMode&&selectedModes.length===1;
                let modeReadback=modeLatched||calibratedImplicit||(targetSource==='calibrated'?targetSelected:heuristicReadback);
                if(!modeReadback&&targetFound&&!recentClick&&Number(modeState.clickAttempts)<2){
                  action=targetSource==='calibrated'?'select-mode-calibrated':'select-mode';
                  modeState.clickAttempts=Math.max(0,Number(modeState.clickAttempts)||0)+1;
                  modeState.lastClickAt=modeNow;modeState.lastAction=action;saveMode();target.focus?.();target.click();modeReadback=false;
                }
                if(modeReadback&&!modeLatched){
                  stageState.stage='MODE_CONFIRMED';stageState.confirmedMode=requestedMode;stageState.confirmedAt=modeNow;saveStage();modeLatched=true;
                }
                const stageRegressionBlocked=modeLatched&&(currentMode==='unknown'||currentMode==='ambiguous');
                if(stageRegressionBlocked){stageState.regressionsBlocked=Math.max(0,Number(stageState.regressionsBlocked)||0)+1;saveStage();}
                const diagnostics={bootstrapStage:stageState.stage,requested:requestedMode,currentMode,confirmedMode:stageState.confirmedMode,modeLatched,stageRegressionBlocked,explicitContradiction,modeCandidates:rawModeControls.length,groupFound:!!modeGroup,targetFound,targetSelected,targetSource,selectedModes,recentClick,action,calibratedImplicit,composer:!!composer,finalReadback:modeReadback,modeAttempts:modeState.attempts,modeClickAttempts:modeState.clickAttempts,modeElapsedMs,modeTimeoutMs};
                const modeDiag=()=>('stage='+stageState.stage+';requested='+requestedMode+';confirmed='+(stageState.confirmedMode||'none')+';current='+currentMode+';latched='+(modeLatched?1:0)+';blocked='+(stageRegressionBlocked?1:0)+';source='+targetSource+';targetFound='+(targetFound?1:0)+';targetSelected='+(targetSelected?1:0)+';attempt='+(action||'none')+';readback='+(modeReadback?1:0)+';elapsedMs='+modeElapsedMs);
                if(action)return result('UI_WAIT','모드 전환 반영 대기 · '+modeDiag(),diagnostics);
                if(!modeReadback){
                  saveMode();
                  if(!targetFound&&(modeElapsedMs>=modeTimeoutMs||modeState.attempts>=modeMaxAttempts))return result('CHAT_BOOTSTRAP_MODE_CONTROL_NOT_FOUND','실행 모드 선택기를 제한시간 안에 찾지 못했습니다.',diagnostics);
                  if(targetFound&&modeState.clickAttempts>=2&&(modeElapsedMs>=4800||modeState.attempts>=8))return result('CHAT_BOOTSTRAP_MODE_READBACK_FAILED','실행 모드 선택 후 실제 상태를 확인하지 못했습니다.',diagnostics);
                  if(modeElapsedMs>=modeTimeoutMs||modeState.attempts>=modeMaxAttempts)return result('CHAT_BOOTSTRAP_MODE_READBACK_FAILED','실행 모드 실제 상태 확인이 제한시간을 초과했습니다.',diagnostics);
                  return result('UI_WAIT','실행 모드 실제 상태 대기 · '+modeDiag(),diagnostics);
                }
                if(!composer){
                  saveMode();
                  if(modeElapsedMs>=modeTimeoutMs||modeState.attempts>=modeMaxAttempts)return result('CHAT_BOOTSTRAP_COMPOSER_NOT_FOUND','새 대화 입력창을 제한시간 안에 찾지 못했습니다.',diagnostics);
                  return result('UI_WAIT','새 대화 입력창 대기 · '+modeDiag(),diagnostics);
                }
                clearMode();
                """
                .replace("__REQUESTED__", SelfRunScript.quote(requested))
                .replace("__RUN_ID__", SelfRunScript.quote(runId))
                .replace("__WORK_KEY__", SelfRunScript.quote(WebUiCalibrationStore.PURPOSE_MODE_WORK))
                .replace("__CHAT_KEY__", SelfRunScript.quote(WebUiCalibrationStore.PURPOSE_MODE_CHAT));
    }
}
