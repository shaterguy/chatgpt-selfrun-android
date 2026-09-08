package com.shaterguy.chatgptselfrun;

/** V3 continuation-only composer transport. Preparation is marker-driven and restart-safe. */
final class SelfRun3ComposerTransport {
    static final String READY_TO_SUBMIT = "READY_TO_SUBMIT";
    static final String COMPOSER_WAITING = "COMPOSER_WAITING";
    static final String COMPOSER_CLEARING = "COMPOSER_CLEARING";
    static final String COMPOSER_INPUTTING = "COMPOSER_INPUTTING";
    static final String SUBMISSION_PENDING = "SUBMISSION_PENDING";
    static final String STOP = "STOP";
    static final String SEND_DISABLED = "SEND_DISABLED";
    static final String SEND_UNAVAILABLE = "SEND_UNAVAILABLE";
    static final String AUTH_REQUIRED = "AUTH_REQUIRED";
    static final String TARGET_ERROR = "TARGET_ERROR";

    private SelfRun3ComposerTransport() {}

    static String prepareContinuation(String conversationUrl, String prompt) {
        return "(()=>{const result=(status,detail='')=>JSON.stringify({status,detail});"
                + conversationGuard(conversationUrl)
                + "const expected=" + q(prompt) + ";"
                + locatorPrelude()
                + pinnedComposerPrelude()
                + continuationEditorPrelude()
                + protocolIdleGuard()
                + continuationMarkerPrelude()
                + "const composer=resolveComposer();"
                + "if(!composer)return result('" + COMPOSER_WAITING + "','pinned or live composer not present yet');"
                + "const m=readMarker();"
                + "if(m.state==='clicked')return result('" + SUBMISSION_PENDING + "','continuation submission already pending');"
                + "if(!m.state||m.state==='failed'){writeMarker({state:'clearing',at:Date.now()});clearComposer(composer);return result('" + COMPOSER_CLEARING + "','continuation composer clearing started');}"
                + "if(m.state==='clearing'){if(!emptyText(composer)){clearComposer(composer);return result('" + COMPOSER_CLEARING + "','waiting for empty composer readback');}writeMarker({state:'inputting',at:Date.now()});inputComposer(composer,expected);return result('" + COMPOSER_INPUTTING + "','continuation input issued');}"
                + "if(m.state==='inputting'){if(!sameText(composer,expected)){if(emptyText(composer)){inputComposer(composer,expected);return result('" + COMPOSER_INPUTTING + "','continuation input retried after empty readback');}const ds=divergedStatus(composer,expected);writeMarker({state:'clearing',at:Date.now()});clearComposer(composer);return result(ds,'continuation readback diverged');}if(!submitReady(composer))return result('" + COMPOSER_WAITING + "','exact continuation readback present; submit path not ready');writeMarker({state:'prepared',at:Date.now()});return result('" + READY_TO_SUBMIT + "','exact continuation prepared');}"
                + "if(m.state==='prepared'){if(!sameText(composer,expected)){const ds=divergedStatus(composer,expected);writeMarker({state:'clearing',at:Date.now()});clearComposer(composer);return result(ds,'prepared continuation changed');}if(!submitReady(composer))return result('" + COMPOSER_WAITING + "','prepared continuation submit path not ready');return result('" + READY_TO_SUBMIT + "','exact continuation prepared');}"
                + "writeMarker({state:'clearing',at:Date.now()});clearComposer(composer);return result('" + COMPOSER_CLEARING + "','unknown continuation marker reset');"
                + "})()";
    }

    static String submitContinuation(String conversationUrl, String prompt) {
        return "(()=>{const result=(status,detail='')=>JSON.stringify({status,detail});"
                + conversationGuard(conversationUrl)
                + "const expected=" + q(prompt) + ";"
                + locatorPrelude()
                + pinnedComposerPrelude()
                + continuationEditorPrelude()
                + protocolIdleGuard()
                + continuationMarkerPrelude()
                + "const composer=resolveComposer();"
                + "if(!composer)return result('" + COMPOSER_WAITING + "','pinned or live composer disappeared before submit');"
                + "const m=readMarker();if(m.state==='clicked')return result('" + SUBMISSION_PENDING + "','continuation submission already pending');"
                + "if(m.state!=='prepared')return result('" + COMPOSER_INPUTTING + "','continuation prepared marker missing before submit');"
                + "if(!sameText(composer,expected))return result(divergedStatus(composer,expected),'exact continuation readback changed before submit');"
                + "if(!submitReady(composer))return result('" + SEND_UNAVAILABLE + "','no functional continuation submit path');"
                + "writeMarker({state:'clicked',at:Date.now()});"
                + "const form=findOwningForm(composer);if(form&&typeof form.requestSubmit==='function'){try{form.requestSubmit();return result('" + SUBMISSION_PENDING + "','dispatch=form_request_submit');}catch(_){}}"
                + "if(dispatchEnter(composer))return result('" + SUBMISSION_PENDING + "','dispatch=editor_enter');"
                + "writeMarker({state:'prepared',at:Date.now()});return result('" + SEND_UNAVAILABLE + "','continuation submit path failed');"
                + "})()";
    }

    /** Pins the exact ranked live composer immediately before generation-time Surface detach. */
    static String pinComposerExpression() {
        return "(()=>{" + locatorPrelude()
                + "const composer=findComposer();if(!composer)return false;"
                + "window.__selfRunV3PinnedComposer=composer;"
                + "window.__selfRunV3PinnedComposerPath=location.pathname;return true;})()";
    }

    /** Used only by bounded reconciliation and accepts a connected pinned composer before rediscovery. */
    static String composerReadyExpression() {
        return "(()=>{" + locatorPrelude() + pinnedComposerPrelude() + "return !!resolveComposer();})()";
    }

    private static String protocolIdleGuard() {
        return "const protocol=window.__selfRunTurnProtocol?.snapshot?.();"
                + "if(protocol&&(protocol.phase==='THINKING'||protocol.phase==='ANSWERING'))return result('TURN_PROTOCOL_BUSY','previous response protocol is still active');";
    }

    private static String continuationMarkerPrelude() {
        return "const turnToken=String(protocol?.turnToken||'');if(!turnToken)return result('TURN_PROTOCOL_UNAVAILABLE','continuation request is not bound');"
                + "const markerKey='selfrun-drive:v3-cont:'+turnToken;const markerCache=window.__selfRunV3ContinuationMarkers||(window.__selfRunV3ContinuationMarkers={});"
                + "const readMarker=()=>{let raw='';try{raw=sessionStorage.getItem(markerKey)||'';}catch(_){}if(!raw)raw=markerCache[markerKey]||'';try{return raw?JSON.parse(raw):{};}catch(_){return{};}};"
                + "const writeMarker=data=>{const raw=JSON.stringify(data);markerCache[markerKey]=raw;try{sessionStorage.setItem(markerKey,raw);}catch(_){}};";
    }

    private static String locatorPrelude() {
        return """
                const safeText=v=>String(v??'').trim().toLowerCase();
                const hiddenByContract=e=>!!e?.closest?.('[hidden],[aria-hidden="true"]');
                const inputKind=e=>String(e?.getAttribute?.('type')||'text').toLowerCase();
                const editable=e=>{
                  if(!e||!e.isConnected||hiddenByContract(e))return false;
                  if(e.disabled||e.readOnly||e.getAttribute?.('aria-disabled')==='true')return false;
                  const tag=String(e.tagName||'').toLowerCase();
                  if(tag==='textarea')return true;
                  if(tag==='input')return inputKind(e)==='text';
                  return e.isContentEditable||e.getAttribute?.('contenteditable')==='true'
                    ||String(e.getAttribute?.('role')||'').toLowerCase()==='textbox';
                };
                const semantic=e=>safeText([e?.id,e?.dataset?.testid,e?.getAttribute?.('aria-label'),e?.getAttribute?.('placeholder'),e?.getAttribute?.('role'),e?.getAttribute?.('aria-multiline'),e?.getAttribute?.('contenteditable')].join(' '));
                const nearestForm=e=>{let node=e,depth=0;while(node&&depth++<20){if(String(node.tagName||'').toLowerCase()==='form')return node;if(node.parentElement){node=node.parentElement;continue;}const root=node.getRootNode?.();node=root&&root.host?root.host:null;}return null;};
                const inMain=e=>{let node=e,depth=0;while(node&&depth++<20){if(String(node.tagName||'').toLowerCase()==='main')return true;if(node.parentElement){node=node.parentElement;continue;}const root=node.getRootNode?.();node=root&&root.host?root.host:null;}return false;};
                const roots=()=>{const out=[document],seen=new Set(out);for(let i=0;i<out.length&&i<32;i++){const root=out[i];for(const element of root.querySelectorAll?.('*')||[]){const shadow=element.shadowRoot;if(shadow&&!seen.has(shadow)){seen.add(shadow);out.push(shadow);}}}return out;};
                const allCandidates=()=>{const out=[],seen=new Set();for(const root of roots()){for(const e of root.querySelectorAll?.('textarea,input,[contenteditable],[role="textbox"]')||[]){if(editable(e)&&!seen.has(e)){seen.add(e);out.push(e);}}}return out;};
                const score=(e,index)=>{let s=index/10000;const text=semantic(e),tag=String(e.tagName||'').toLowerCase();const id=safeText(e?.id),testid=safeText(e?.dataset?.testid);if(id==='prompt-textarea'||testid==='prompt-textarea')s+=1000;if(e?.getAttribute?.('data-lexical-editor')==='true')s+=500;if(nearestForm(e))s+=180;if(inMain(e))s+=120;if(tag==='textarea')s+=50;if(e.isContentEditable||e.getAttribute?.('contenteditable')==='true')s+=50;if(String(e.getAttribute?.('role')||'').toLowerCase()==='textbox')s+=40;if(String(e.getAttribute?.('aria-multiline')||'').toLowerCase()==='true')s+=30;if(text.includes('message')||text.includes('chat')||text.includes('ask')||text.includes('question')||text.includes('prompt')||text.includes('메시지')||text.includes('질문')||text.includes('입력'))s+=100;if(text.includes('search')||text.includes('검색'))s-=500;return s;};
                const ranked=nodes=>nodes.map((e,i)=>({e,s:score(e,i)})).sort((a,b)=>b.s-a.s);
                const findComposer=()=>{const rankedAll=ranked(allCandidates());return rankedAll.length&&rankedAll[0].s>0?rankedAll[0].e:null;};
                const findOwningForm=composer=>nearestForm(composer);
                const composerSignature=e=>{const tag=String(e?.tagName||'X').toUpperCase();const id=safeText(e?.id)==='prompt-textarea'?'P':'N';const lex=e?.getAttribute?.('data-lexical-editor')==='true'?'L':'N';return tag+'_'+(nearestForm(e)?'F':'N')+'_'+(inMain(e)?'M':'N')+'_'+id+'_'+lex;};
                const dispatchEnter=composer=>{try{composer.focus?.();const options={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true,shiftKey:false};composer.dispatchEvent(new KeyboardEvent('keydown',options));composer.dispatchEvent(new KeyboardEvent('keypress',options));composer.dispatchEvent(new KeyboardEvent('keyup',options));return true;}catch(_){return false;}};
                """;
    }

    private static String pinnedComposerPrelude() {
        return """
                const pinnedComposer=()=>{const e=window.__selfRunV3PinnedComposer;return e&&e.isConnected&&window.__selfRunV3PinnedComposerPath===location.pathname?e:null;};
                const resolveComposer=()=>{const pinned=pinnedComposer();if(pinned)return pinned;const live=findComposer();if(live){window.__selfRunV3PinnedComposer=live;window.__selfRunV3PinnedComposerPath=location.pathname;}return live;};
                """;
    }

    private static String normalizationPrelude() {
        return """
                const canonical=v=>{let s=String(v??'');for(const code of [8203,8204,8205,65279])s=s.split(String.fromCharCode(code)).join('');s=s.split(String.fromCharCode(160)).join(' ');s=s.split(String.fromCharCode(13)).join('');s=s.split(String.fromCharCode(9)).join(' ');const lf=String.fromCharCode(10);const lines=s.split(lf).map(line=>line.trim().split(' ').filter(Boolean).join(' ')).filter(Boolean);return lines.join(lf).trim();};
                """;
    }

    private static String continuationEditorPrelude() {
        return normalizationPrelude() + """
                const rawVariants=e=>{if(!e)return[''];if('value'in e)return[String(e.value??'')];const out=[];const add=v=>{const c=canonical(v);if(!out.includes(c))out.push(c);};try{add(e.innerText||'');}catch(_){}try{const blocks=[...e.querySelectorAll?.('p,div,li')||[]];if(blocks.length)add(blocks.map(x=>String(x.innerText||x.textContent||'')).join(String.fromCharCode(10)));}catch(_){}try{add(e.textContent||'');}catch(_){}return out.length?out:[''];};
                const expectedModel=value=>canonical(value);
                const sameText=(e,value)=>!!e&&e.isConnected&&rawVariants(e).includes(expectedModel(value));
                const emptyText=e=>!!e&&e.isConnected&&rawVariants(e).every(v=>v==='');
                const hashText=v=>{let h=2166136261;for(let i=0;i<v.length;i++){h^=v.charCodeAt(i);h=Math.imul(h,16777619);}return(h>>>0).toString(16);};
                const divergedStatus=(e,value)=>{const models=rawVariants(e),actual=models.sort((a,b)=>b.length-a.length)[0]||'',expected=expectedModel(value);return 'COMPOSER_DIVERGED_A'+actual.length+'H'+hashText(actual)+'_E'+expected.length+'H'+hashText(expected)+'_C'+composerSignature(e)+'_N'+allCandidates().length;};
                const emitBefore=(e,inputType,data)=>{try{return e.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,cancelable:true,inputType,data}));}catch(_){return true;}};
                const emitInput=(e,inputType,data)=>{if(!e?.isConnected)return;try{e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType,data}));}catch(_){try{e.dispatchEvent(new Event('input',{bubbles:true}));}catch(__){}}try{e.dispatchEvent(new Event('change',{bubbles:true}));}catch(_){}};
                const fieldSetter=e=>{const p=Object.getPrototypeOf(e),own=p?Object.getOwnPropertyDescriptor(p,'value'):null,textarea=typeof HTMLTextAreaElement!=='undefined'?Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value'):null,input=typeof HTMLInputElement!=='undefined'?Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value'):null;return own?.set||textarea?.set||input?.set||null;};
                const selectContents=e=>{try{const doc=e.ownerDocument||document,win=doc.defaultView||window,sel=win.getSelection?.(),range=doc.createRange?.();if(!sel||!range)return false;range.selectNodeContents(e);sel.removeAllRanges();sel.addRange(range);return true;}catch(_){return false;}};
                const clearComposer=e=>{if(!e?.isConnected||emptyText(e))return;try{e.focus?.();}catch(_){}if('value'in e){emitBefore(e,'deleteContentBackward',null);if(!e.isConnected)return;const setter=fieldSetter(e);if(setter)setter.call(e,'');else e.value='';emitInput(e,'deleteContentBackward',null);return;}selectContents(e);emitBefore(e,'deleteContentBackward',null);if(!e.isConnected)return;if(!emptyText(e)){try{e.ownerDocument?.execCommand?.('delete',false,null);}catch(_){} }if(!e.isConnected)return;if(!emptyText(e)){while(e.firstChild)e.removeChild(e.firstChild);try{e.appendChild((e.ownerDocument||document).createElement('br'));}catch(_){}}emitInput(e,'deleteContentBackward',null);};
                const inputComposer=(e,value)=>{if(!e?.isConnected)return;try{e.focus?.();}catch(_){}if('value'in e){emitBefore(e,'insertText',value);if(!e.isConnected)return;if(!sameText(e,value)){const setter=fieldSetter(e);if(setter)setter.call(e,value);else e.value=value;}emitInput(e,'insertText',value);return;}selectContents(e);emitBefore(e,'insertText',value);if(!e.isConnected)return;if(sameText(e,value))return;selectContents(e);try{e.ownerDocument?.execCommand?.('insertText',false,value);}catch(_){}if(!e.isConnected)return;if(!sameText(e,value)){e.textContent=value;}emitInput(e,'insertText',value);};
                const submitReady=e=>{if(!e?.isConnected)return false;const form=findOwningForm(e);return !!(form&&typeof form.requestSubmit==='function')||e.isContentEditable||String(e.tagName||'').toLowerCase()==='textarea'||String(e.tagName||'').toLowerCase()==='input';};
                """;
    }

    private static String conversationGuard(String conversationUrl) {
        String conversation = q(SelfRunScript.conversationId(conversationUrl));
        return "if(location.protocol!=='https:'||!['chatgpt.com','www.chatgpt.com'].includes(location.hostname))return result('" + TARGET_ERROR + "','host mismatch');"
                + "if(/\\/(?:auth|login)(?:\\/|$)/.test(location.pathname))return result('" + AUTH_REQUIRED + "','authentication route active');"
                + "const __srParts=location.pathname.split('/').filter(Boolean);const __srAfter=k=>{const i=__srParts.indexOf(k);return i>=0&&i+1<__srParts.length?__srParts[i+1]:''};"
                + "if(__srAfter('c')!==" + conversation + ")return result('" + TARGET_ERROR + "','conversation route mismatch');";
    }

    private static String q(String value) {
        return SelfRunScript.quote(value == null ? "" : value);
    }
}
