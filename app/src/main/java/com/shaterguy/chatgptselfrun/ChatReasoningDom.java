package com.shaterguy.chatgptselfrun;

/** Chat bootstrap adapter for the one-tap model menu and horizontal reasoning slider. */
final class ChatReasoningDom {
    private ChatReasoningDom() {}

    static String inline(String selection, String runId) {
        String wanted = ChatReasoningPreferenceStore.normalize(selection);
        int ordinal = ChatReasoningPreferenceStore.ordinal(wanted);
        if (ordinal < 0) return "";
        String stateKey = "selfrun-drive:chat-reasoning:" + runId;
        String script = """
                const __srcWanted=__WANTED__,__srcWantedOrdinal=__ORDINAL__,__srcStateKey=__STATE_KEY__;
                const __srcLevels=['instant','medium','high','xhigh','pro'];
                const __srcTriggerTimeoutMs=20000,__srcSliderTimeoutMs=24000,__srcMenuRetryMs=4800,__srcTriggerMaxAttempts=20,__srcSliderMaxAttempts=19;
                const __srcIndex=value=>__srcLevels.indexOf(value);
                const __srcLevel=source=>{
                  const v=exactText(source);
                  if(v.includes('extra high')||v.includes('very high')||v.includes('xhigh')||v.includes('maximum')||v.includes('매우 높음')||v.includes('최대'))return'xhigh';
                  if(v==='pro'||v.startsWith('pro ')||v.endsWith(' pro')||v.includes('프로'))return'pro';
                  if(v.includes('medium')||v.includes('중간')||v.includes('표준')||v.includes('standard'))return'medium';
                  if(v.includes('high')||v.includes('extended')||v.includes('높음')||v.includes('확장'))return'high';
                  if(v.includes('instant')||v.includes('flash')||v.includes('빠른')||v.includes('즉시'))return'instant';
                  return'';
                };
                const __srcElementLevel=element=>__srcLevel([
                  labelOf(element),element?.getAttribute?.('aria-valuetext')||'',element?.getAttribute?.('data-value')||'',
                  element?.getAttribute?.('data-level')||'',element?.getAttribute?.('value')||'',element?.title||''
                ].join(' '));
                const __srcPopupSelector='[role="menu"],[role="listbox"],[role="dialog"],[data-radix-popper-content-wrapper],[data-slot*="popover-content"],[data-slot*="menu-content"]';
                const __srcForbidden=element=>/(send|submit|보내기|stop|중지|microphone|마이크|voice|음성|attach|첨부|upload|업로드|new chat|new conversation|새 채팅|새 대화)/.test(labelOf(element)+' '+exactText(element?.dataset?.testid||''));
                const __srcTriggerScore=element=>{
                  const label=labelOf(element),testid=exactText(element?.dataset?.testid||''),popup=element.getAttribute('aria-haspopup')||'';
                  let score=0;
                  if(__srcLevel(label))score+=120;
                  if(/reason|thinking|추론/.test(label+' '+testid))score+=70;
                  if(/model|모델|gpt|flash/.test(label+' '+testid))score+=45;
                  if(popup&&popup!=='false')score+=35;
                  if(element.hasAttribute('aria-expanded'))score+=30;
                  if(element.hasAttribute('aria-controls')||element.hasAttribute('aria-owns'))score+=25;
                  if(element.closest('header'))score+=10;
                  return score;
                };
                const __srcTriggerEntries=[...document.querySelectorAll('button,[role="button"],[role="combobox"],[aria-haspopup],[aria-expanded],[data-testid*="model"],[data-testid*="reason"]')]
                  .filter(visible).filter(element=>!element.closest(__srcPopupSelector)).filter(element=>!__srcForbidden(element))
                  .map((element,index)=>({element,index,score:__srcTriggerScore(element)})).filter(entry=>entry.score>0)
                  .sort((a,b)=>b.score-a.score||a.index-b.index);
                const __srcTriggerEntry=__srcTriggerEntries[0]||null;
                const __srcTrigger=__srcTriggerEntry?.element||null;
                const __srcTriggerLevel=__srcTrigger?__srcLevel(labelOf(__srcTrigger)):'';
                const __srcControlledIds=__srcTrigger?String(__srcTrigger.getAttribute('aria-controls')||__srcTrigger.getAttribute('aria-owns')||'').split(/\s+/).filter(Boolean):[];
                const __srcControlled=__srcControlledIds.map(id=>document.getElementById(id)).find(visible)||null;
                const __srcRawSliders=[...document.querySelectorAll('[role="slider"],input[type="range"]')].filter(visible).filter(element=>element.getAttribute('aria-orientation')!=='vertical');
                const __srcOpenPopups=[...document.querySelectorAll(__srcPopupSelector)].filter(visible);
                const __srcPopup=__srcControlled||__srcOpenPopups.find(popup=>__srcRawSliders.some(slider=>popup.contains(slider)))||null;
                const __srcSliderScore=element=>{
                  let score=0;
                  if(__srcControlled&&__srcControlled.contains(element))score+=140;
                  if(__srcPopup&&__srcPopup.contains(element))score+=100;
                  if(element.closest(__srcPopupSelector))score+=60;
                  if(__srcLevel(element.getAttribute('aria-valuetext')||''))score+=35;
                  if(element.matches('input[type="range"]'))score+=20;
                  return score;
                };
                const __srcSliderEntries=__srcRawSliders.map((element,index)=>({element,index,score:__srcSliderScore(element)})).sort((a,b)=>b.score-a.score||a.index-b.index);
                const __srcSlider=__srcSliderEntries[0]?.element||null;
                const __srcNow=Date.now();
                let __srcState={searchStartedAt:0,triggerFirstSeenAt:0,menuClickAttempts:0,menuClickedAt:0,menuAcknowledgedAt:0,sliderWaitStartedAt:0,findAttempts:0,triggerAttempts:0,sliderAttempts:0,moveAttempts:0,closeAttempts:0,readAttempts:0,readbackWaits:0,pendingReadback:false,pendingSemantic:'',pendingDirection:0,pendingNumeric:null,applied:false,verifiedBySemantic:false,at:0};
                try{const saved=sessionStorage.getItem(__srcStateKey);if(saved)__srcState={...__srcState,...JSON.parse(saved)};}catch(_){}
                if(!(Number(__srcState.searchStartedAt)>0))__srcState.searchStartedAt=__srcNow;
                const __srcSave=()=>{try{sessionStorage.setItem(__srcStateKey,JSON.stringify(__srcState));}catch(_){}};
                const __srcElapsed=at=>{const value=Number(at)||0;return value>0?Math.max(0,__srcNow-value):0;};
                const __srcExpanded=!!__srcTrigger&&(__srcTrigger.getAttribute('aria-expanded')==='true'||!!__srcControlled||!!__srcPopup||(__srcState.menuClickAttempts>0&&__srcOpenPopups.length===1));
                const __srcSliderKind=__srcSlider?[__srcSlider.tagName||'',__srcSlider.getAttribute('role')||'',__srcSlider.getAttribute('type')||''].filter(Boolean).join(':'):'';
                const __srcDiagnostics=extra=>({requested:__srcWanted,requestedOrdinal:__srcWantedOrdinal,sliderFound:!!__srcSlider,sliderCandidates:__srcRawSliders.length,sliderKind:__srcSliderKind,triggerFound:!!__srcTrigger,triggerCandidates:__srcTriggerEntries.length,triggerScore:__srcTriggerEntry?.score||0,triggerLabel:__srcTrigger?labelOf(__srcTrigger):'',triggerLevel:__srcTriggerLevel,triggerExpanded:__srcExpanded,controlledPopup:!!__srcControlled,popupFound:!!__srcPopup,openPopupCandidates:__srcOpenPopups.length,menuClickAttempts:__srcState.menuClickAttempts,findAttempts:__srcState.findAttempts,triggerAttempts:__srcState.triggerAttempts,sliderAttempts:__srcState.sliderAttempts,moveAttempts:__srcState.moveAttempts,closeAttempts:__srcState.closeAttempts,readAttempts:__srcState.readAttempts,readbackWaits:__srcState.readbackWaits,pendingReadback:!!__srcState.pendingReadback,applied:!!__srcState.applied,searchElapsedMs:__srcElapsed(__srcState.searchStartedAt),menuElapsedMs:__srcElapsed(__srcState.menuClickedAt),sliderWaitElapsedMs:__srcElapsed(__srcState.sliderWaitStartedAt),triggerTimeoutMs:__srcTriggerTimeoutMs,sliderTimeoutMs:__srcSliderTimeoutMs,menuRetryMs:__srcMenuRetryMs,...extra});
                const __srcFail=(status,detail,extra={})=>{__srcState.at=__srcNow;__srcSave();return result(status,detail,__srcDiagnostics(extra));};
                if(!__srcSlider){
                  __srcState.findAttempts++;__srcState.at=__srcNow;
                  if(__srcState.applied){
                    __srcSave();
                    if(!__srcState.verifiedBySemantic&&__srcTriggerLevel&&__srcTriggerLevel!==__srcWanted)return __srcFail('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 적용 후 선택기 의미값이 요청과 다릅니다.',{observed:__srcTriggerLevel});
                    try{sessionStorage.removeItem(__srcStateKey);}catch(_){}
                    return result('READY','Chat 추론 의미값 적용 확인',__srcDiagnostics({menuClosed:true}));
                  }
                  if(!__srcTrigger){
                    if(__srcState.menuClickAttempts>0||Number(__srcState.sliderWaitStartedAt)>0){
                      if(!(Number(__srcState.sliderWaitStartedAt)>0))__srcState.sliderWaitStartedAt=Number(__srcState.menuClickedAt)||__srcNow;
                      __srcState.sliderAttempts++;__srcSave();
                      const elapsed=__srcElapsed(__srcState.sliderWaitStartedAt);
                      if(elapsed>=__srcSliderTimeoutMs||__srcState.sliderAttempts>=__srcSliderMaxAttempts)return __srcFail('CHAT_REASONING_SLIDER_NOT_FOUND','Chat 모델 메뉴를 연 뒤 현재 DOM에서 슬라이더를 찾지 못했습니다.',{action:'slider-timeout-trigger-replaced'});
                      return result('UI_WAIT','Chat 모델 메뉴 클릭 후 선택기 재렌더링 대기',__srcDiagnostics({action:'wait-trigger-after-menu'}));
                    }
                    __srcState.triggerAttempts++;__srcSave();
                    const elapsed=__srcElapsed(__srcState.searchStartedAt);
                    if(elapsed>=__srcTriggerTimeoutMs||__srcState.triggerAttempts>=__srcTriggerMaxAttempts)return __srcFail('CHAT_REASONING_TRIGGER_NOT_FOUND','Chat 추론 선택기를 준비시간 안에 찾지 못했습니다.',{action:'trigger-timeout'});
                    return result('UI_WAIT','Chat 추론 선택기 탐색 대기',__srcDiagnostics({action:'wait-trigger'}));
                  }
                  if(!(Number(__srcState.triggerFirstSeenAt)>0))__srcState.triggerFirstSeenAt=__srcNow;
                  if(__srcExpanded){
                    if(!(Number(__srcState.menuAcknowledgedAt)>0))__srcState.menuAcknowledgedAt=__srcNow;
                    if(!(Number(__srcState.sliderWaitStartedAt)>0))__srcState.sliderWaitStartedAt=Number(__srcState.menuClickedAt)||__srcNow;
                    __srcState.sliderAttempts++;__srcSave();
                    const elapsed=__srcElapsed(__srcState.sliderWaitStartedAt);
                    if(elapsed>=__srcSliderTimeoutMs||__srcState.sliderAttempts>=__srcSliderMaxAttempts)return __srcFail('CHAT_REASONING_SLIDER_NOT_FOUND','Chat 모델 메뉴는 열렸지만 준비시간 안에 슬라이더가 나타나지 않았습니다.',{action:'slider-timeout-menu-open'});
                    return result('UI_WAIT','Chat 모델 메뉴 내부 슬라이더 준비 대기',__srcDiagnostics({action:'wait-slider'}));
                  }
                  if(__srcState.menuClickAttempts<1){
                    __srcState.menuClickAttempts=1;__srcState.menuClickedAt=__srcNow;__srcState.sliderWaitStartedAt=__srcNow;__srcSave();__srcTrigger.focus?.();__srcTrigger.click();
                    return result('UI_WAIT','Chat 모델 메뉴 열기 반영 대기',__srcDiagnostics({action:'open-menu'}));
                  }
                  if(!(Number(__srcState.sliderWaitStartedAt)>0))__srcState.sliderWaitStartedAt=Number(__srcState.menuClickedAt)||__srcNow;
                  __srcState.sliderAttempts++;
                  const elapsed=__srcElapsed(__srcState.sliderWaitStartedAt);
                  if(__srcState.menuClickAttempts<2&&elapsed>=__srcMenuRetryMs&&elapsed<__srcSliderTimeoutMs&&__srcState.sliderAttempts<__srcSliderMaxAttempts){
                    __srcState.menuClickAttempts++;__srcState.menuClickedAt=__srcNow;__srcSave();__srcTrigger.focus?.();__srcTrigger.click();
                    return result('UI_WAIT','현재 Chat 모델 선택기로 메뉴 열기 1회 재시도',__srcDiagnostics({action:'open-menu-retry'}));
                  }
                  __srcSave();
                  if(elapsed>=__srcSliderTimeoutMs||__srcState.sliderAttempts>=__srcSliderMaxAttempts)return __srcFail('CHAT_REASONING_SLIDER_NOT_FOUND','Chat 모델 메뉴 열림 확인 또는 슬라이더 준비가 제한시간을 초과했습니다.',{action:'slider-timeout-menu-unacknowledged'});
                  return result('UI_WAIT','Chat 모델 메뉴 열림 및 슬라이더 준비 대기',__srcDiagnostics({action:'wait-menu'}));
                }
                if(!(Number(__srcState.triggerFirstSeenAt)>0)&&__srcTrigger)__srcState.triggerFirstSeenAt=__srcNow;
                if(!(Number(__srcState.menuAcknowledgedAt)>0))__srcState.menuAcknowledgedAt=__srcNow;
                if(!(Number(__srcState.sliderWaitStartedAt)>0))__srcState.sliderWaitStartedAt=Number(__srcState.menuClickedAt)||__srcNow;
                __srcSave();
                const __srcInput=typeof HTMLInputElement!=='undefined'&&__srcSlider instanceof HTMLInputElement&&__srcSlider.type==='range';
                const __srcNum=(value,fallback)=>{if(value==null||String(value).trim()==='')return fallback;const number=Number(value);return Number.isFinite(number)?number:fallback;};
                const __srcMin=__srcNum(__srcSlider.getAttribute('aria-valuemin'),__srcInput?__srcNum(__srcSlider.min,0):0);
                const __srcMax=__srcNum(__srcSlider.getAttribute('aria-valuemax'),__srcInput?__srcNum(__srcSlider.max,100):100);
                const __srcRange=__srcMax-__srcMin;
                if(!Number.isFinite(__srcMin)||!Number.isFinite(__srcMax)||!(__srcRange>0)){
                  __srcState.readAttempts++;__srcSave();
                  if(__srcState.readAttempts>=3)return __srcFail('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 슬라이더 범위를 확인할 수 없습니다.',{min:__srcMin,max:__srcMax});
                  return result('UI_WAIT','Chat 추론 슬라이더 범위 정보 대기',__srcDiagnostics({min:__srcMin,max:__srcMax}));
                }
                const __srcDeclaredStep=__srcNum(__srcSlider.getAttribute('aria-valuestep'),__srcInput?__srcNum(__srcSlider.step,NaN):NaN);
                const __srcRawCount=Number.isFinite(__srcDeclaredStep)&&__srcDeclaredStep>0?Math.round(__srcRange/__srcDeclaredStep)+1:0;
                const __srcExactCount=__srcRawCount>=2&&__srcRawCount<=101&&Math.abs(__srcMin+(__srcRawCount-1)*__srcDeclaredStep-__srcMax)<=Math.max(0.0001,__srcDeclaredStep/100)?__srcRawCount:0;
                const __srcCurrent=__srcNum(__srcSlider.getAttribute('aria-valuenow'),__srcInput?__srcNum(__srcSlider.value,NaN):NaN);
                const __srcSliderLevel=__srcLevel(__srcSlider.getAttribute('aria-valuetext')||'');
                const __srcSemanticCurrent=__srcSliderLevel||__srcTriggerLevel;
                const __srcPopupNodes=__srcPopup?[...__srcPopup.querySelectorAll('[role="option"],[role="radio"],[data-value],[data-level],[aria-label],[aria-valuetext],datalist option,label,button,span')]:[];
                const __srcExplicitNodes=__srcPopup?[...__srcPopup.querySelectorAll('[role="option"],[role="radio"],[data-value],[data-level],datalist option')]:[];
                const __srcCollectLevels=nodes=>[...new Set(nodes.map(__srcElementLevel).filter(level=>__srcIndex(level)>=0))].sort((a,b)=>__srcIndex(a)-__srcIndex(b));
                const __srcAllLevels=__srcCollectLevels(__srcPopupNodes);
                const __srcExplicitLevels=__srcCollectLevels(__srcExplicitNodes);
                const __srcAvailableLevels=__srcExplicitLevels.length>=2?__srcExplicitLevels:__srcAllLevels;
                const __srcAvailableComplete=__srcExplicitLevels.length>=2||(__srcAllLevels.length>=2&&__srcExactCount===__srcAllLevels.length);
                if(__srcAvailableComplete&&!__srcAvailableLevels.includes(__srcWanted))return __srcFail('CHAT_REASONING_OPTION_UNAVAILABLE','선택한 Chat 추론 단계가 현재 계정 또는 워크스페이스에서 제공되지 않습니다.',{available:__srcAvailableLevels});
                let __srcMappingLevels=[];
                if(__srcAvailableComplete)__srcMappingLevels=__srcAvailableLevels;
                else if(__srcExactCount===5)__srcMappingLevels=__srcLevels;
                const __srcNumericLevelFor=levels=>{
                  if(!Number.isFinite(__srcCurrent)||levels.length<2)return'';
                  const step=__srcRange/(levels.length-1),index=Math.round((__srcCurrent-__srcMin)/step);
                  if(index<0||index>=levels.length||Math.abs(__srcMin+index*step-__srcCurrent)>Math.max(0.0001,step/3))return'';
                  return levels[index];
                };
                const __srcNumericLevel=__srcNumericLevelFor(__srcMappingLevels);
                const __srcNumericTrusted=!__srcSemanticCurrent&&!!__srcNumericLevel;
                const __srcEffectiveCurrent=__srcSemanticCurrent||__srcNumericLevel;
                if(__srcState.pendingReadback){
                  const observed=__srcSemanticCurrent||(__srcNumericTrusted?__srcNumericLevel:'');
                  if(observed&&observed!==__srcState.pendingSemantic){
                    const delta=__srcIndex(observed)-__srcIndex(__srcState.pendingSemantic);
                    if(delta===0||Math.sign(delta)!==Math.sign(__srcState.pendingDirection))return __srcFail('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 슬라이더가 요청 방향과 다른 의미값을 반환했습니다.',{observed,previous:__srcState.pendingSemantic});
                    __srcState.pendingReadback=false;__srcState.readbackWaits=0;__srcState.pendingSemantic='';__srcState.pendingDirection=0;__srcState.pendingNumeric=null;__srcSave();
                  }else{
                    __srcState.readbackWaits++;__srcState.at=__srcNow;__srcSave();
                    if(__srcState.readbackWaits>=2)return __srcFail('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 슬라이더 숫자값은 변했지만 의미값이 갱신되지 않았습니다.',{observed,current:__srcCurrent,pendingTarget:__srcState.pendingNumeric});
                    return result('UI_WAIT','Chat 추론 의미값 readback 대기',__srcDiagnostics({action:'wait-semantic-readback',observed,current:__srcCurrent,pendingTarget:__srcState.pendingNumeric}));
                  }
                }
                if(!__srcEffectiveCurrent){
                  __srcState.readAttempts++;__srcState.at=__srcNow;__srcSave();
                  if(__srcState.readAttempts>=3)return __srcFail('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 슬라이더의 현재 의미값을 확인할 수 없습니다.',{current:__srcCurrent,available:__srcAvailableLevels,exactCount:__srcExactCount});
                  return result('UI_WAIT','Chat 추론 현재 의미값 readback 대기',__srcDiagnostics({action:'wait-current-readback',current:__srcCurrent,available:__srcAvailableLevels,exactCount:__srcExactCount}));
                }
                const __srcAtTarget=__srcSemanticCurrent?__srcSemanticCurrent===__srcWanted:(__srcNumericTrusted&&__srcNumericLevel===__srcWanted);
                if(__srcAtTarget){
                  __srcState.applied=true;__srcState.verifiedBySemantic=!!__srcSemanticCurrent;__srcState.at=__srcNow;__srcSave();
                  if(__srcState.closeAttempts>=2)return __srcFail('CHAT_REASONING_MENU_CLOSE_FAILED','Chat 추론 적용 후 모델 메뉴를 닫지 못했습니다.',{current:__srcCurrent,observed:__srcEffectiveCurrent});
                  __srcState.closeAttempts++;__srcSave();
                  if(__srcTrigger){__srcTrigger.focus?.();__srcTrigger.click();}
                  else{
                    __srcSlider.focus?.();
                    try{__srcSlider.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true,cancelable:true,composed:true}));__srcSlider.dispatchEvent(new KeyboardEvent('keyup',{key:'Escape',code:'Escape',bubbles:true,cancelable:true,composed:true}));}catch(_){}
                  }
                  return result('UI_WAIT','Chat 추론 적용 완료 · 메뉴 닫힘 확인',__srcDiagnostics({action:'close-menu',current:__srcCurrent,observed:__srcEffectiveCurrent}));
                }
                const __srcCurrentOrdinal=__srcIndex(__srcEffectiveCurrent),__srcWantedIndex=__srcIndex(__srcWanted);
                if(__srcCurrentOrdinal<0||__srcWantedIndex<0)return __srcFail('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 의미값 순서를 판정할 수 없습니다.',{observed:__srcEffectiveCurrent});
                const __srcDirection=Math.sign(__srcWantedIndex-__srcCurrentOrdinal);
                const __srcTolerance=Math.max(0.0001,Number.isFinite(__srcDeclaredStep)&&__srcDeclaredStep>0?__srcDeclaredStep/3:__srcRange/400);
                if(!Number.isFinite(__srcCurrent))return __srcFail('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 슬라이더 숫자값을 확인할 수 없습니다.',{observed:__srcEffectiveCurrent});
                if((__srcDirection>0&&__srcCurrent>=__srcMax-__srcTolerance)||(__srcDirection<0&&__srcCurrent<=__srcMin+__srcTolerance))return __srcFail('CHAT_REASONING_OPTION_UNAVAILABLE','선택한 Chat 추론 단계가 현재 슬라이더 범위에 없습니다.',{observed:__srcEffectiveCurrent,current:__srcCurrent,min:__srcMin,max:__srcMax,available:__srcAvailableLevels});
                if(__srcState.moveAttempts>=8)return __srcFail('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 의미값이 제한된 이동 횟수 안에 요청값에 도달하지 못했습니다.',{observed:__srcEffectiveCurrent,current:__srcCurrent});
                const __srcNavStep=__srcMappingLevels.length>=2?__srcRange/(__srcMappingLevels.length-1):(__srcExactCount>=2&&__srcExactCount<=9?__srcDeclaredStep:__srcRange/4);
                const __srcTarget=Math.max(__srcMin,Math.min(__srcMax,__srcCurrent+__srcDirection*__srcNavStep));
                let __srcChanged=false;
                if(__srcInput){
                  const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')?.set;
                  if(setter)setter.call(__srcSlider,String(__srcTarget));else __srcSlider.value=String(__srcTarget);
                  __srcSlider.dispatchEvent(new Event('input',{bubbles:true,composed:true}));__srcSlider.dispatchEvent(new Event('change',{bubbles:true}));__srcChanged=true;
                }else{
                  const thumb=__srcSlider.getBoundingClientRect();let node=__srcSlider.parentElement,track=null;
                  for(let depth=0;node&&depth<6;depth++,node=node.parentElement){const rect=node.getBoundingClientRect();if(rect.width>=120&&rect.width>=Math.max(thumb.width*2,120)&&rect.height<=96){track=node;break;}}
                  if(track){
                    const rect=track.getBoundingClientRect(),ratio=Math.max(0.01,Math.min(0.99,(__srcTarget-__srcMin)/__srcRange)),x=rect.left+rect.width*ratio,y=rect.top+rect.height/2,hit=document.elementFromPoint?.(x,y)||track;
                    const common={bubbles:true,cancelable:true,composed:true,clientX:x,clientY:y,button:0};
                    try{if(typeof PointerEvent==='function'){hit.dispatchEvent(new PointerEvent('pointerdown',{...common,buttons:1,pointerId:1,pointerType:'touch',isPrimary:true}));hit.dispatchEvent(new PointerEvent('pointermove',{...common,buttons:1,pointerId:1,pointerType:'touch',isPrimary:true}));hit.dispatchEvent(new PointerEvent('pointerup',{...common,buttons:0,pointerId:1,pointerType:'touch',isPrimary:true}));}}catch(_){}
                    hit.dispatchEvent(new MouseEvent('mousedown',{...common,buttons:1}));hit.dispatchEvent(new MouseEvent('mousemove',{...common,buttons:1}));hit.dispatchEvent(new MouseEvent('mouseup',{...common,buttons:0}));hit.dispatchEvent(new MouseEvent('click',{...common,buttons:0}));__srcChanged=true;
                  }
                }
                __srcState.moveAttempts++;__srcState.at=__srcNow;
                if(__srcChanged){__srcState.pendingReadback=true;__srcState.pendingSemantic=__srcEffectiveCurrent;__srcState.pendingDirection=__srcDirection;__srcState.pendingNumeric=__srcTarget;__srcState.readbackWaits=0;}
                __srcSave();
                if(!__srcChanged&&__srcState.moveAttempts>=4)return __srcFail('CHAT_REASONING_READBACK_MISMATCH','Chat 추론 슬라이더 입력 이벤트를 적용하지 못했습니다.',{observed:__srcEffectiveCurrent,current:__srcCurrent,target:__srcTarget});
                return result('UI_WAIT','Chat 추론 슬라이더 이동 후 의미값 확인 대기',__srcDiagnostics({action:'set-slider',observed:__srcEffectiveCurrent,current:__srcCurrent,target:__srcTarget,changed:__srcChanged}));
                """;
        return script.replace("__WANTED__", q(wanted))
                .replace("__ORDINAL__", String.valueOf(ordinal))
                .replace("__STATE_KEY__", q(stateKey));
    }

    private static String q(String value) { return SelfRunScript.quote(value); }
}
