package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Set;

/**
 * Independent DOM completion fallback for turns whose transport protocol remains stale.
 *
 * <p>The primary protocol and the existing STOP-to-idle observer remain authoritative fast paths.
 * This fallback only completes a turn when a new assistant response belonging to the current
 * submission is present, response-level final actions are rendered, and the composer has remained
 * idle while the assistant turn is stable.</p>
 */
final class TurnCompletionDomFallbackScript {
    static final String ENGINE_VERSION = "dom-turn-fallback-v1";
    static final long STABILITY_MS = 5_000L;
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
        return documentStartScript(STABILITY_MS);
    }

    /** Test hook keeps production behavior identical while allowing deterministic WebView regression timing. */
    static String documentStartScript(long stabilityMs) {
        long stable = Math.max(1L, stabilityMs);
        long evaluationDelay = Math.min(250L, Math.max(50L, stable / 2L));
        return """
                (()=>{
                  const ENGINE_VERSION=__ENGINE_VERSION__,STABILITY_MS=__STABILITY_MS__,EVALUATION_DELAY_MS=__EVALUATION_DELAY_MS__;
                  const PROTOCOL_STALE_MS=1500;
                  if(window.__selfRunDomAssistantFallback?.version===ENGINE_VERSION)return;
                  const COMPLETION_SCHEME='selfrun-drive',COMPLETION_HOST='turn-completed';
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
                  const latestByRole=(nodes,wanted)=>{for(let i=nodes.length-1;i>=0;i--)if(role(nodes[i])===wanted)return nodes[i];return null;};
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
                  const protocolDiagnostics=()=>{try{return window.__selfRunTurnProtocol?.diagnostics?.()||null;}catch(_){return null;}};
                  const runId=()=>{try{return safe(window.__selfRunRequestProfileEngine?.target?.()?.runId||'');}catch(_){return'';}};
                  const emitObserverBound=token=>{try{const sink=window.selfRunTurnLog;if(!sink||typeof sink.postMessage!=='function')return;const protocol=protocolDiagnostics();sink.postMessage(JSON.stringify({runId:runId(),stage:'observer_bound',source:'dom_fallback_binding',phase:safe(protocol?.phase||'IDLE'),observerToken:safe(token)}));}catch(_){}};
                  let timer=0,scheduled=false,observerSlot=window.__selfRunDriveTurnObserver,domObserver=null,observerConnected=false;
                  const metrics={mutationCallbacks:0,evaluations:0,expensiveEvaluations:0,observerConnections:0};
                  let state={token:'',baselineUser:null,baselineAssistant:null,currentUser:null,assistant:null,scope:null,composer:null,composerRoot:null,
                    assistantHasText:false,finalActionSeen:false,lastMutationAt:0,candidateSince:0,fired:false,dirty:false};
                  const protocolActiveForToken=protocol=>{const token=safe(protocol?.observerToken||'');return !!token&&token===state.token&&(protocol?.phase==='THINKING'||protocol?.phase==='ANSWERING');};
                  const protocolHealthyForToken=protocol=>protocolActiveForToken(protocol)&&Date.now()-Math.max(0,state.lastMutationAt)<PROTOCOL_STALE_MS;
                  const cancelTimer=()=>{if(timer)clearTimeout(timer);timer=0;const current=window.__selfRunDriveTurnObserver;if(current)current.timer=0;};
                  const disconnectObserver=()=>{if(domObserver)try{domObserver.disconnect();}catch(_){}observerConnected=false;const current=window.__selfRunDriveTurnObserver;if(current?.observer===domObserver)current.observer=null;};
                  const resetCandidate=()=>{state.candidateSince=0;cancelTimer();};
                  const reset=()=>{cancelTimer();disconnectObserver();state={token:'',baselineUser:null,baselineAssistant:null,currentUser:null,assistant:null,scope:null,composer:null,composerRoot:null,
                    assistantHasText:false,finalActionSeen:false,lastMutationAt:0,candidateSince:0,fired:false,dirty:false};};
                  const schedule=()=>{if(scheduled||!state.token||state.fired)return;scheduled=true;setTimeout(()=>{scheduled=false;evaluate();},EVALUATION_DELAY_MS);};
                  const observerRoot=()=>document.querySelector('main')||document.body||document.documentElement||document;
                  const ensureObserver=()=>{
                    if(!state.token||state.fired||observerConnected)return;
                    if(!domObserver)domObserver=new MutationObserver(()=>{
                      metrics.mutationCallbacks++;state.dirty=true;state.lastMutationAt=Date.now();state.candidateSince=0;cancelTimer();schedule();
                    });
                    try{domObserver.observe(observerRoot(),{childList:true,subtree:true,characterData:true,attributes:true,
                      attributeFilter:['disabled','aria-disabled','aria-label','data-testid','hidden']});observerConnected=true;metrics.observerConnections++;
                      const current=window.__selfRunDriveTurnObserver;if(current)current.observer=domObserver;
                    }catch(_){}
                  };
                  const arm=token=>{
                    const nodes=messageNodes();cancelTimer();disconnectObserver();
                    state={token:safe(token),baselineUser:latestByRole(nodes,'user'),baselineAssistant:latestByRole(nodes,'assistant'),currentUser:null,
                      assistant:null,scope:null,composer:null,composerRoot:null,assistantHasText:false,finalActionSeen:false,lastMutationAt:Date.now(),candidateSince:0,fired:false,dirty:false};
                    ensureObserver();schedule();
                  };
                  const observerState=()=>window.__selfRunDriveTurnObserver;
                  const resolveCurrentTurn=()=>{
                    if(state.currentUser?.isConnected&&state.assistant?.isConnected)return{user:state.currentUser,assistant:state.assistant};
                    const nodes=messageNodes();let userIndex=-1;
                    for(let i=nodes.length-1;i>=0;i--)if(role(nodes[i])==='user'){userIndex=i;break;}
                    if(userIndex<0)return{user:null,assistant:null};
                    let assistant=null;for(let i=userIndex+1;i<nodes.length;i++)if(role(nodes[i])==='assistant')assistant=nodes[i];
                    state.currentUser=nodes[userIndex];state.assistant=assistant;state.scope=assistant?responseScope(assistant):null;
                    state.assistantHasText=false;state.finalActionSeen=false;return{user:state.currentUser,assistant};
                  };
                  const finalActionEvidence=assistant=>{
                    if(state.finalActionSeen)return true;
                    const scope=state.scope&&state.scope.isConnected?state.scope:responseScope(assistant);if(!scope)return false;state.scope=scope;
                    state.finalActionSeen=[...scope.querySelectorAll('button,[role="button"]')].some(finalActionSemantic);return state.finalActionSeen;
                  };
                  const composer=()=>{
                    if(state.composer?.isConnected)return state.composer;
                    const selectors=['textarea#prompt-textarea','textarea[data-testid="prompt-textarea"]',
                      'div#prompt-textarea[contenteditable="true"]','main form [contenteditable="true"][data-lexical-editor="true"]',
                      'main form [contenteditable="true"]'];
                    for(const selector of selectors){const found=[...document.querySelectorAll(selector)].find(rendered);if(found){state.composer=found;state.composerRoot=null;return found;}}
                    return null;
                  };
                  const stopSemantic=node=>{const id=testIdOf(node),label=labelOf(node);return id.includes('stop')||label.includes('stop generating')||label.includes('stop responding')
                    ||label==='stop'||label.includes('생성 중지')||label.includes('응답 중지')||label==='중지'||label==='정지';};
                  const sendSemantic=node=>{const id=testIdOf(node),label=labelOf(node);return id.includes('send-button')||id.includes('composer-submit-button')||label==='send'
                    ||label==='submit'||label.includes('send message')||label.includes('send prompt')||label==='보내기';};
                  const composerIdle=()=>{
                    const input=composer();if(!input)return false;
                    const root=state.composerRoot?.isConnected?state.composerRoot:(input.closest?.('form')||input.closest?.('[data-type="unified-composer"]')
                      ||input.closest?.('[class*="composer"]')||input.parentElement);if(!root)return false;state.composerRoot=root;
                    const buttons=[...root.querySelectorAll('button,[role="button"]')].filter(rendered);if(buttons.some(stopSemantic))return false;
                    const send=buttons.find(sendSemantic)||buttons.find(node=>node.matches?.('button[type="submit"]'));
                    const editable=input.getAttribute?.('aria-disabled')!=='true'&&!input.disabled&&!input.readOnly&&(('value'in input)||input.isContentEditable);
                    return !!send||editable;
                  };
                  const fire=()=>{
                    if(state.fired)return false;
                    const current=observerState(),token=safe(current?.token||''),run=runId();if(!token||token!==state.token||!run)return false;
                    const protocol=protocolDiagnostics();if(protocol?.phase==='ERROR'||protocol?.completionDispatched===true||protocolHealthyForToken(protocol))return false;
                    state.fired=true;cancelTimer();disconnectObserver();try{current.fired=true;}catch(_){}
                    try{window.__selfRunDriveTurnObserver=null;}catch(_){}
                    location.href=COMPLETION_SCHEME+'://'+COMPLETION_HOST+'?run='+encodeURIComponent(run)
                      +'&token='+encodeURIComponent(token)+'&source=dom_assistant_final_ui';return true;
                  };
                  const scheduleStable=remaining=>{cancelTimer();timer=setTimeout(()=>{timer=0;evaluate();},Math.max(1,remaining));const current=observerState();if(current)current.timer=timer;};
                  function evaluate(){
                    metrics.evaluations++;
                    const current=observerState(),token=safe(current?.token||'');
                    if(!token){if(state.token)reset();return;}
                    if(token!==state.token)arm(token);if(state.fired)return;ensureObserver();
                    const protocol=protocolDiagnostics();if(protocol?.phase==='ERROR'||protocol?.completionDispatched===true){resetCandidate();disconnectObserver();return;}
                    if(protocolHealthyForToken(protocol)){resetCandidate();return;}
                    metrics.expensiveEvaluations++;
                    const pair=resolveCurrentTurn(),assistant=pair.assistant;
                    if(!pair.user||!assistant){state.currentUser=null;state.assistant=null;state.scope=null;resetCandidate();return;}
                    const belongsToCurrentTurn=pair.user!==state.baselineUser||assistant!==state.baselineAssistant;
                    if(!belongsToCurrentTurn){state.currentUser=null;state.assistant=null;state.scope=null;resetCandidate();return;}
                    if(!state.assistantHasText)state.assistantHasText=!!textOf(assistant);if(!state.assistantHasText){resetCandidate();return;}
                    if(!finalActionEvidence(assistant)||!composerIdle()){resetCandidate();return;}
                    const now=Date.now();if(!state.candidateSince)state.candidateSince=now;
                    const stableSince=Math.max(state.lastMutationAt||0,state.candidateSince),elapsed=Math.max(0,now-stableSince);
                    if(elapsed>=STABILITY_MS){fire();return;}scheduleStable(STABILITY_MS-elapsed);
                  }
                  try{
                    const descriptor=Object.getOwnPropertyDescriptor(window,'__selfRunDriveTurnObserver');
                    if(!descriptor||descriptor.configurable){
                      observerSlot=window.__selfRunDriveTurnObserver;
                      Object.defineProperty(window,'__selfRunDriveTurnObserver',{configurable:true,enumerable:false,
                        get(){return observerSlot;},set(value){const priorToken=safe(observerSlot?.token||'');observerSlot=value;const nextToken=safe(value?.token||'');
                          if(nextToken!==priorToken)emitObserverBound(nextToken);if(nextToken&&nextToken!==priorToken)arm(nextToken);else if(!nextToken&&state.token)reset();}});
                    }
                  }catch(_){}
                  const initialToken=safe(observerSlot?.token||'');if(initialToken)arm(initialToken);
                  window.__selfRunDomAssistantFallback={version:ENGINE_VERSION,evaluate,diagnostics:()=>({
                    token:state.token,observerConnected,assistantBound:!!state.assistant,assistantStableMs:state.assistant?Math.max(0,Date.now()-state.lastMutationAt):0,
                    finalUi:state.finalActionSeen,idle:state.composer?composerIdle():false,fired:state.fired,
                    mutationCallbacks:metrics.mutationCallbacks,evaluations:metrics.evaluations,expensiveEvaluations:metrics.expensiveEvaluations,observerConnections:metrics.observerConnections})};
                })();
                """
                .replace("__ENGINE_VERSION__", SelfRunScript.quote(ENGINE_VERSION))
                .replace("__STABILITY_MS__", String.valueOf(stable))
                .replace("__EVALUATION_DELAY_MS__", String.valueOf(evaluationDelay));
    }
}
