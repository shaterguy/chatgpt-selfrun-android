package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Set;

/**
 * Independent DOM completion fallback for turns whose transport protocol remains stale.
 *
 * <p>The primary protocol and the existing STOP-to-idle observer remain authoritative fast paths.
 * This fallback completes only when the current submission owns a new assistant response, final
 * response actions are rendered, and the composer is idle. If those final actions never become
 * observable, a bounded ambiguity watchdog requests rebind, one read-only Drive probe, and then
 * controlled recovery instead of waiting forever.</p>
 */
final class TurnCompletionDomFallbackScript {
    static final String ENGINE_VERSION = "dom-turn-fallback-v2";
    static final long STABILITY_MS = 5_000L;
    static final long REBIND_MS = 30_000L;
    static final long DRIVE_PROBE_MS = 60_000L;
    static final long RECOVERY_MS = 120_000L;
    private static final Set<String> CHATGPT_ORIGINS = Set.of(
            "https://chatgpt.com", "https://www.chatgpt.com");

    private TurnCompletionDomFallbackScript() {}

    static void installDocumentStart(WebView webView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            throw new IllegalStateException("DOCUMENT_START_SCRIPT unsupported: DOM completion fallback unavailable");
        }
        WebViewCompat.addDocumentStartJavaScript(webView, documentStartScript(), CHATGPT_ORIGINS);
    }

    static String documentStartScript() {
        return documentStartScript(STABILITY_MS, REBIND_MS, DRIVE_PROBE_MS, RECOVERY_MS);
    }

    static String documentStartScript(long stabilityMs) {
        return documentStartScript(stabilityMs, REBIND_MS, DRIVE_PROBE_MS, RECOVERY_MS);
    }

    /** Test hook keeps production behavior identical while allowing deterministic WebView timing. */
    static String documentStartScript(long stabilityMs, long rebindMs, long driveProbeMs, long recoveryMs) {
        long stable = Math.max(1L, stabilityMs);
        long rebind = Math.max(stable, rebindMs);
        long probe = Math.max(rebind + 1L, driveProbeMs);
        long recovery = Math.max(probe + 1L, recoveryMs);
        return """
                (()=>{
                  const ENGINE_VERSION=__ENGINE_VERSION__,STABILITY_MS=__STABILITY_MS__,REBIND_MS=__REBIND_MS__,
                    DRIVE_PROBE_MS=__DRIVE_PROBE_MS__,RECOVERY_MS=__RECOVERY_MS__,PROBE_TIMEOUT_MS=Math.max(1,RECOVERY_MS-DRIVE_PROBE_MS);
                  if(window.__selfRunDomAssistantFallback?.version===ENGINE_VERSION)return;
                  const COMPLETION_SCHEME='selfrun-drive',COMPLETION_HOST='turn-completed',
                    WATCHDOG_REBIND_HOST='turn-watchdog-rebind',WATCHDOG_PROBE_HOST='turn-watchdog-probe',
                    WATCHDOG_RECOVER_HOST='turn-watchdog-recover';
                  const BASELINE_PREFIX='selfrun-drive:dom-fallback-baseline:v2:',WATCH_PREFIX='selfrun-drive:dom-fallback-watch:v2:';
                  const role=node=>String(node?.getAttribute?.('data-message-author-role')||'').toLowerCase();
                  const safe=value=>String(value??'').slice(0,256);
                  const rendered=node=>{
                    if(!node||!node.isConnected||node.hidden||node.getAttribute?.('aria-hidden')==='true')return false;
                    try{const style=getComputedStyle(node);return style.display!=='none'&&style.visibility!=='hidden';}catch(_){return true;}
                  };
                  const textOf=node=>String(node?.innerText||node?.textContent||'').replace(/\\s+/g,' ').trim();
                  const labelOf=node=>String((node?.getAttribute?.('aria-label')||'')+' '+(node?.title||'')+' '+(node?.innerText||node?.textContent||''))
                    .replace(/\\s+/g,' ').trim().toLowerCase();
                  const testIdOf=node=>String(node?.dataset?.testid||'').trim().toLowerCase();
                  const messageNodes=()=>[...document.querySelectorAll('[data-message-author-role]')].filter(node=>node?.isConnected);
                  const countRole=(nodes,wanted)=>nodes.reduce((count,node)=>count+(role(node)===wanted?1:0),0);
                  const latestByRole=(nodes,wanted)=>{for(let i=nodes.length-1;i>=0;i--)if(role(nodes[i])===wanted)return nodes[i];return null;};
                  const currentTurnPair=()=>{
                    const nodes=messageNodes();let userIndex=-1;
                    for(let i=nodes.length-1;i>=0;i--)if(role(nodes[i])==='user'){userIndex=i;break;}
                    if(userIndex<0)return{nodes,user:null,assistant:null};
                    let assistant=null;for(let i=userIndex+1;i<nodes.length;i++)if(role(nodes[i])==='assistant')assistant=nodes[i];
                    return{nodes,user:nodes[userIndex],assistant};
                  };
                  const responseScope=assistant=>assistant?.closest?.('article,[data-testid^="conversation-turn-"],[data-testid*="conversation-turn"]')
                    ||assistant?.parentElement?.parentElement||assistant?.parentElement||assistant;
                  const finalActionSemantic=node=>{
                    if(!rendered(node)||!node.matches?.('button,[role="button"]'))return false;
                    const label=labelOf(node),id=testIdOf(node);
                    if(label.includes('copy code')||label.includes('코드 복사'))return false;
                    if(id.includes('copy-turn')||id.includes('thumbs-up')||id.includes('thumbs-down'))return true;
                    if(label==='copy'||label==='복사')return true;
                    return ['read aloud','good response','bad response','regenerate','retry','more actions',
                      '소리 내어 읽기','읽어주기','좋은 답변','나쁜 답변','다시 생성','재생성','재시도','더보기']
                      .some(value=>label.includes(value));
                  };
                  const finalActionEvidence=assistant=>{
                    const scope=responseScope(assistant);if(!scope)return false;
                    return [...scope.querySelectorAll('button,[role="button"]')].some(finalActionSemantic);
                  };
                  const composer=()=>{
                    const selectors=['textarea#prompt-textarea','textarea[data-testid="prompt-textarea"]',
                      'div#prompt-textarea[contenteditable="true"]','main form [contenteditable="true"][data-lexical-editor="true"]',
                      'main form [contenteditable="true"]'];
                    for(const selector of selectors){const found=[...document.querySelectorAll(selector)].find(rendered);if(found)return found;}
                    return null;
                  };
                  const stopSemantic=node=>{
                    const id=testIdOf(node),label=labelOf(node);
                    return id.includes('stop')||label.includes('stop generating')||label.includes('stop responding')
                      ||label==='stop'||label.includes('생성 중지')||label.includes('응답 중지')||label==='중지'||label==='정지';
                  };
                  const sendSemantic=node=>{
                    const id=testIdOf(node),label=labelOf(node);
                    return id.includes('send-button')||id.includes('composer-submit-button')||label==='send'
                      ||label==='submit'||label.includes('send message')||label.includes('send prompt')||label==='보내기';
                  };
                  const composerIdle=()=>{
                    const input=composer();if(!input)return false;
                    const root=input.closest?.('form')||input.closest?.('[data-type="unified-composer"]')
                      ||input.closest?.('[class*="composer"]')||input.parentElement;if(!root)return false;
                    const buttons=[...root.querySelectorAll('button,[role="button"]')].filter(rendered);
                    if(buttons.some(stopSemantic))return false;
                    const send=buttons.find(sendSemantic)||buttons.find(node=>node.matches?.('button[type="submit"]'));
                    const editable=input.getAttribute?.('aria-disabled')!=='true'&&!input.disabled&&!input.readOnly
                      &&(('value'in input)||input.isContentEditable);
                    return !!send||editable;
                  };
                  const protocolDiagnostics=()=>{try{return window.__selfRunTurnProtocol?.diagnostics?.()||null;}catch(_){return null;}};
                  const runId=()=>{try{return safe(window.__selfRunRequestProfileEngine?.target?.()?.runId||'');}catch(_){return'';}};
                  const readJson=key=>{try{const raw=localStorage.getItem(key)||'';return raw?JSON.parse(raw):null;}catch(_){return null;}};
                  const writeJson=(key,value)=>{try{localStorage.setItem(key,JSON.stringify(value));}catch(_){}};
                  const removeKey=key=>{try{localStorage.removeItem(key);}catch(_){}};
                  const baselineKey=token=>BASELINE_PREFIX+safe(token),watchKey=token=>WATCH_PREFIX+safe(token);
                  let timer=0,scheduled=false,observerSlot=window.__selfRunDriveTurnObserver;
                  let state={token:'',baselineUserCount:0,baselineAssistantCount:0,baselineAssistantHadText:false,
                    assistant:null,scope:null,lastMutationAt:0,candidateSince:0,ambiguousSince:0,
                    rebindSent:false,probeSent:false,probeSentAt:0,probeComplete:false,recoverSent:false,fired:false};
                  const cancelTimer=()=>{if(timer)clearTimeout(timer);timer=0;};
                  const persistWatch=()=>{if(!state.token)return;writeJson(watchKey(state.token),{
                    ambiguousSince:state.ambiguousSince,rebindSent:state.rebindSent,probeSent:state.probeSent,
                    probeSentAt:state.probeSentAt,probeComplete:state.probeComplete,recoverSent:state.recoverSent});};
                  const resetCandidate=()=>{state.candidateSince=0;cancelTimer();};
                  const clearAmbiguous=()=>{if(state.ambiguousSince||state.rebindSent||state.probeSent||state.probeComplete||state.recoverSent){
                    state.ambiguousSince=0;state.rebindSent=false;state.probeSent=false;state.probeSentAt=0;state.probeComplete=false;state.recoverSent=false;persistWatch();}
                    cancelTimer();};
                  const reset=(removePersisted=false)=>{const token=state.token;cancelTimer();state={token:'',baselineUserCount:0,baselineAssistantCount:0,baselineAssistantHadText:false,
                    assistant:null,scope:null,lastMutationAt:0,candidateSince:0,ambiguousSince:0,rebindSent:false,probeSent:false,probeSentAt:0,probeComplete:false,recoverSent:false,fired:false};
                    if(removePersisted&&token){removeKey(baselineKey(token));removeKey(watchKey(token));}};
                  const captureBaseline=token=>{
                    const nodes=messageNodes(),assistant=latestByRole(nodes,'assistant');
                    const baseline={userCount:countRole(nodes,'user'),assistantCount:countRole(nodes,'assistant'),assistantHadText:!!assistant&&!!textOf(assistant)};
                    writeJson(baselineKey(token),baseline);return baseline;
                  };
                  const arm=token=>{
                    const normalized=safe(token);cancelTimer();const persisted=readJson(baselineKey(normalized))||captureBaseline(normalized),watch=readJson(watchKey(normalized))||{};
                    state={token:normalized,baselineUserCount:Math.max(0,Number(persisted.userCount)||0),
                      baselineAssistantCount:Math.max(0,Number(persisted.assistantCount)||0),baselineAssistantHadText:!!persisted.assistantHadText,
                      assistant:null,scope:null,lastMutationAt:0,candidateSince:0,ambiguousSince:Math.max(0,Number(watch.ambiguousSince)||0),
                      rebindSent:!!watch.rebindSent,probeSent:!!watch.probeSent,probeSentAt:Math.max(0,Number(watch.probeSentAt)||0),
                      probeComplete:!!watch.probeComplete,recoverSent:!!watch.recoverSent,fired:false};
                  };
                  const observerState=()=>window.__selfRunDriveTurnObserver;
                  const emitWatchdog=host=>{const run=runId(),token=state.token;if(!run||!token)return false;
                    location.href=COMPLETION_SCHEME+'://'+host+'?run='+encodeURIComponent(run)+'&token='+encodeURIComponent(token);return true;};
                  const fire=()=>{
                    if(state.fired)return false;
                    const current=observerState(),token=safe(current?.token||'');const run=runId();
                    if(!token||token!==state.token||!run)return false;
                    const protocol=protocolDiagnostics();if(protocol?.phase==='ERROR'||protocol?.completionDispatched===true)return false;
                    state.fired=true;cancelTimer();removeKey(baselineKey(token));removeKey(watchKey(token));
                    try{current.fired=true;current.observer?.disconnect?.();if(current.timer)clearTimeout(current.timer);}catch(_){}
                    try{window.__selfRunDriveTurnObserver=null;}catch(_){}
                    location.href=COMPLETION_SCHEME+'://'+COMPLETION_HOST+'?run='+encodeURIComponent(run)
                      +'&token='+encodeURIComponent(token)+'&source=dom_assistant_final_ui';return true;
                  };
                  const scheduleAfter=remaining=>{cancelTimer();timer=setTimeout(()=>{timer=0;evaluate();},Math.max(1,remaining));};
                  const watchdog=now=>{
                    if(!state.ambiguousSince)return false;const elapsed=Math.max(0,now-state.ambiguousSince);
                    if(!state.rebindSent&&elapsed>=REBIND_MS){state.rebindSent=true;persistWatch();emitWatchdog(WATCHDOG_REBIND_HOST);scheduleAfter(100);return true;}
                    if(!state.probeSent&&elapsed>=DRIVE_PROBE_MS){state.probeSent=true;state.probeSentAt=now;state.probeComplete=false;persistWatch();emitWatchdog(WATCHDOG_PROBE_HOST);scheduleAfter(100);return true;}
                    const probeTimedOut=state.probeSent&&state.probeSentAt>0&&now-state.probeSentAt>=PROBE_TIMEOUT_MS;
                    if(!state.recoverSent&&elapsed>=RECOVERY_MS&&state.probeSent&&(state.probeComplete||probeTimedOut)){
                      state.recoverSent=true;persistWatch();emitWatchdog(WATCHDOG_RECOVER_HOST);return true;}
                    let next=RECOVERY_MS-elapsed;
                    if(!state.rebindSent)next=Math.min(next,REBIND_MS-elapsed);
                    else if(!state.probeSent)next=Math.min(next,DRIVE_PROBE_MS-elapsed);
                    else if(!state.probeComplete&&!probeTimedOut)next=Math.min(next,PROBE_TIMEOUT_MS-(now-state.probeSentAt));
                    scheduleAfter(Math.max(1,next));return false;
                  };
                  function evaluate(){
                    const current=observerState(),token=safe(current?.token||'');
                    if(!token){if(state.token)reset(true);return;}
                    if(token!==state.token)arm(token);
                    if(state.fired)return;
                    const protocol=protocolDiagnostics();
                    if(protocol?.phase==='ERROR'||protocol?.completionDispatched===true){clearAmbiguous();resetCandidate();return;}
                    const pair=currentTurnPair(),assistant=pair.assistant,assistantCount=countRole(pair.nodes,'assistant');
                    if(!pair.user||!assistant){state.assistant=null;state.scope=null;clearAmbiguous();resetCandidate();return;}
                    const assistantText=textOf(assistant);
                    const belongsToCurrentTurn=assistantCount>state.baselineAssistantCount
                      ||(assistantCount===state.baselineAssistantCount&&!state.baselineAssistantHadText&&!!assistantText);
                    if(!belongsToCurrentTurn){state.assistant=null;state.scope=null;clearAmbiguous();resetCandidate();return;}
                    if(state.assistant!==assistant){state.assistant=assistant;state.scope=responseScope(assistant);state.lastMutationAt=Date.now();clearAmbiguous();resetCandidate();}
                    if(!assistantText){clearAmbiguous();resetCandidate();return;}
                    const idle=composerIdle(),finalUi=finalActionEvidence(assistant),now=Date.now();
                    if(finalUi&&idle){clearAmbiguous();if(!state.candidateSince)state.candidateSince=now;
                      const stableSince=Math.max(state.lastMutationAt||0,state.candidateSince),elapsed=Math.max(0,now-stableSince);
                      if(elapsed>=STABILITY_MS){fire();return;}scheduleAfter(STABILITY_MS-elapsed);return;}
                    resetCandidate();
                    if(idle&&!finalUi){if(!state.ambiguousSince){state.ambiguousSince=now;persistWatch();}watchdog(now);return;}
                    clearAmbiguous();
                  }
                  const schedule=()=>{if(scheduled)return;scheduled=true;setTimeout(()=>{scheduled=false;evaluate();},50);};
                  const domObserver=new MutationObserver(records=>{
                    if(state.assistant||state.scope){for(const record of records){const target=record.target;
                      if((state.scope?.contains?.(target))||(state.assistant?.contains?.(target))){state.lastMutationAt=Date.now();state.candidateSince=0;
                        if(state.ambiguousSince){state.ambiguousSince=0;state.rebindSent=false;state.probeSent=false;state.probeSentAt=0;state.probeComplete=false;state.recoverSent=false;persistWatch();}
                        cancelTimer();break;}}}
                    schedule();
                  });
                  try{domObserver.observe(document,{childList:true,subtree:true,characterData:true,attributes:true,
                    attributeFilter:['disabled','aria-disabled','aria-label','data-testid','title','class','hidden']});}catch(_){}
                  try{
                    const descriptor=Object.getOwnPropertyDescriptor(window,'__selfRunDriveTurnObserver');
                    if(!descriptor||descriptor.configurable){
                      observerSlot=window.__selfRunDriveTurnObserver;
                      Object.defineProperty(window,'__selfRunDriveTurnObserver',{configurable:true,enumerable:false,
                        get(){return observerSlot;},set(value){const priorToken=safe(observerSlot?.token||'');observerSlot=value;
                          const nextToken=safe(value?.token||'');if(nextToken&&nextToken!==priorToken)arm(nextToken);if(!nextToken&&state.token)reset(true);schedule();}});
                    }
                  }catch(_){}
                  const driveProbeResult=result=>{if(!state.token||!state.probeSent)return false;state.probeComplete=true;persistWatch();schedule();return result==='completion';};
                  window.__selfRunDomAssistantFallback={version:ENGINE_VERSION,evaluate,driveProbeResult,diagnostics:()=>({
                    token:state.token,assistantBound:!!state.assistant,assistantStableMs:state.assistant?Math.max(0,Date.now()-state.lastMutationAt):0,
                    finalUi:!!state.assistant&&finalActionEvidence(state.assistant),idle:composerIdle(),ambiguous:state.ambiguousSince>0,
                    ambiguousSince:state.ambiguousSince,ambiguousMs:state.ambiguousSince?Math.max(0,Date.now()-state.ambiguousSince):0,
                    rebindSent:state.rebindSent,probeSent:state.probeSent,probeComplete:state.probeComplete,recoverSent:state.recoverSent,
                    baselineUserCount:state.baselineUserCount,baselineAssistantCount:state.baselineAssistantCount,fired:state.fired})};
                  schedule();
                })();
                """
                .replace("__ENGINE_VERSION__", SelfRunScript.quote(ENGINE_VERSION))
                .replace("__STABILITY_MS__", String.valueOf(stable))
                .replace("__REBIND_MS__", String.valueOf(rebind))
                .replace("__DRIVE_PROBE_MS__", String.valueOf(probe))
                .replace("__RECOVERY_MS__", String.valueOf(recovery));
    }
}
