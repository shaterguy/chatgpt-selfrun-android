package com.shaterguy.chatgptselfrun;

/** V3-only composer transport. Continuation preparation is marker-driven and restart-safe. */
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

    static String prepareInitial(String projectUrl, String prompt) {
        return genericPrepare(projectGuard(projectUrl), prompt);
    }

    static String submitInitial(String projectUrl, String prompt) {
        return genericSubmit(projectGuard(projectUrl), prompt);
    }

    static String prepareContinuation(String conversationUrl, String prompt) {
        return "(()=>{const result=(status,detail='')=>JSON.stringify({status,detail});"
                + conversationGuard(conversationUrl)
                + "const expected=" + q(prompt) + ";"
                + locatorPrelude()
                + continuationEditorPrelude()
                + protocolIdleGuard()
                + continuationMarkerPrelude()
                + "const composer=findComposer();"
                + "if(!composer)return result('" + COMPOSER_WAITING + "','editable composer not present yet');"
                + "const m=readMarker();"
                + "if(m.state==='clicked')return result('" + SUBMISSION_PENDING + "','continuation submission already pending');"
                + "if(!m.state||m.state==='failed'){writeMarker({state:'clearing',at:Date.now()});clearComposer(composer);return result('" + COMPOSER_CLEARING + "','continuation composer clearing started');}"
                + "if(m.state==='clearing'){if(!emptyText(composer)){clearComposer(composer);return result('" + COMPOSER_CLEARING + "','waiting for empty composer readback');}writeMarker({state:'inputting',at:Date.now()});inputComposer(composer,expected);return result('" + COMPOSER_INPUTTING + "','continuation input issued');}"
                + "if(m.state==='inputting'){if(!sameText(composer,expected)){if(emptyText(composer)){inputComposer(composer,expected);return result('" + COMPOSER_INPUTTING + "','continuation input retried after empty readback');}writeMarker({state:'clearing',at:Date.now()});clearComposer(composer);return result('" + COMPOSER_CLEARING + "','continuation diverged; restarting');}if(!submitReady(composer))return result('" + COMPOSER_WAITING + "','exact continuation readback present; submit path not ready');writeMarker({state:'prepared',at:Date.now()});return result('" + READY_TO_SUBMIT + "','exact continuation prepared');}"
                + "if(m.state==='prepared'){if(!sameText(composer,expected)){writeMarker({state:'clearing',at:Date.now()});clearComposer(composer);return result('" + COMPOSER_CLEARING + "','prepared continuation changed; restarting');}if(!submitReady(composer))return result('" + COMPOSER_WAITING + "','prepared continuation submit path not ready');return result('" + READY_TO_SUBMIT + "','exact continuation prepared');}"
                + "writeMarker({state:'clearing',at:Date.now()});clearComposer(composer);return result('" + COMPOSER_CLEARING + "','unknown continuation marker reset');"
                + "})()";
    }

    static String submitContinuation(String conversationUrl, String prompt) {
        return "(()=>{const result=(status,detail='')=>JSON.stringify({status,detail});"
                + conversationGuard(conversationUrl)
                + "const expected=" + q(prompt) + ";"
                + locatorPrelude()
                + continuationEditorPrelude()
                + protocolIdleGuard()
                + continuationMarkerPrelude()
                + "const composer=findComposer();"
                + "if(!composer)return result('" + COMPOSER_WAITING + "','editable composer disappeared before submit');"
                + "const m=readMarker();if(m.state==='clicked')return result('" + SUBMISSION_PENDING + "','continuation submission already pending');"
                + "if(m.state!=='prepared')return result('" + COMPOSER_INPUTTING + "','continuation prepared marker missing before submit');"
                + "if(!sameText(composer,expected))return result('" + COMPOSER_INPUTTING + "','exact continuation readback changed before submit');"
                + "if(!submitReady(composer))return result('" + SEND_UNAVAILABLE + "','no functional continuation submit path');"
                + "writeMarker({state:'clicked',at:Date.now()});"
                + "const form=findOwningForm(composer);if(form&&typeof form.requestSubmit==='function'){try{form.requestSubmit();return result('" + SUBMISSION_PENDING + "','dispatch=form_request_submit');}catch(_){}}"
                + "if(dispatchEnter(composer))return result('" + SUBMISSION_PENDING + "','dispatch=editor_enter');"
                + "writeMarker({state:'prepared',at:Date.now()});return result('" + SEND_UNAVAILABLE + "','continuation submit path failed');"
                + "})()";
    }

    /** Used only by bounded reconciliation and shares the live transport's capability probe. */
    static String composerReadyExpression() {
        return "(()=>{" + locatorPrelude() + "return !!findComposer();})()";
    }

    private static String genericPrepare(String guard, String prompt) {
        return "(()=>{const result=(status,detail='')=>JSON.stringify({status,detail});"
                + guard + "const expected=" + q(prompt) + ";" + locatorPrelude() + genericEditorPrelude()
                + protocolIdleGuard() + "const composer=findComposer();"
                + "if(!composer)return result('" + COMPOSER_WAITING + "','editable composer not present yet');"
                + "if(sameText(composer,expected))return result('" + READY_TO_SUBMIT + "','exact prompt already prepared');"
                + "writeExact(composer,expected);return result('" + COMPOSER_INPUTTING + "','prompt mutation issued');})()";
    }

    private static String genericSubmit(String guard, String prompt) {
        return "(()=>{const result=(status,detail='')=>JSON.stringify({status,detail});"
                + guard + "const expected=" + q(prompt) + ";" + locatorPrelude() + genericEditorPrelude()
                + protocolIdleGuard() + "const composer=findComposer();"
                + "if(!composer)return result('" + COMPOSER_WAITING + "','editable composer disappeared before submit');"
                + "if(!sameText(composer,expected))return result('" + COMPOSER_INPUTTING + "','exact prompt model readback changed before submit');"
                + "const form=findOwningForm(composer);if(form&&typeof form.requestSubmit==='function'){try{form.requestSubmit();return result('" + SUBMISSION_PENDING + "','dispatch=form_request_submit');}catch(_){}}"
                + "if(dispatchEnter(composer))return result('" + SUBMISSION_PENDING + "','dispatch=editor_enter');"
                + "return result('" + SEND_UNAVAILABLE + "','no functional submit path');})()";
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
                const safeText=v=>String(v??'').replace(/\s+/g,' ').trim().toLowerCase();
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
                const semantic=e=>safeText([e?.getAttribute?.('aria-label'),e?.getAttribute?.('placeholder'),e?.getAttribute?.('role'),e?.getAttribute?.('aria-multiline'),e?.getAttribute?.('contenteditable')].join(' '));
                const nearestForm=e=>{let node=e,depth=0;while(node&&depth++<16){if(String(node.tagName||'').toLowerCase()==='form')return node;if(node.parentElement){node=node.parentElement;continue;}const root=node.getRootNode?.();node=root&&root.host?root.host:null;}return null;};
                const inMain=e=>{let node=e,depth=0;while(node&&depth++<16){if(String(node.tagName||'').toLowerCase()==='main')return true;if(node.parentElement){node=node.parentElement;continue;}const root=node.getRootNode?.();node=root&&root.host?root.host:null;}return false;};
                const score=(e,index)=>{let s=index/10000;const text=semantic(e),tag=String(e.tagName||'').toLowerCase();if(nearestForm(e))s+=70;if(inMain(e))s+=30;if(tag==='textarea')s+=35;if(e.isContentEditable||e.getAttribute?.('contenteditable')==='true')s+=35;if(String(e.getAttribute?.('role')||'').toLowerCase()==='textbox')s+=30;if(String(e.getAttribute?.('aria-multiline')||'').toLowerCase()==='true')s+=20;if(/message|chat|ask|question|prompt|메시지|질문|입력/.test(text))s+=30;if(/search|검색/.test(text))s-=80;return s;};
                const collect=root=>[...root.querySelectorAll?.('textarea,input,[contenteditable],[role="textbox"]')||[]].filter(editable);
                const ranked=nodes=>nodes.map((e,i)=>({e,s:score(e,i)})).sort((a,b)=>b.s-a.s);
                const shadowCandidates=()=>{const found=[],queue=[document],seen=new Set([document]);for(let qi=0;qi<queue.length&&qi<32;qi++){const root=queue[qi];for(const element of root.querySelectorAll?.('*')||[]){const shadow=element.shadowRoot;if(shadow&&!seen.has(shadow)){seen.add(shadow);queue.push(shadow);for(const e of collect(shadow))found.push(e);}}}return found;};
                const findComposer=()=>{const direct=ranked(collect(document));if(direct.length&&direct[0].s>=80)return direct[0].e;const shadow=ranked(shadowCandidates());if(shadow.length&&(!direct.length||shadow[0].s>direct[0].s))return shadow[0].e;return direct.length&&direct[0].s>0?direct[0].e:null;};
                const findOwningForm=composer=>nearestForm(composer);
                const dispatchEnter=composer=>{try{composer.focus?.();const options={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true,shiftKey:false};composer.dispatchEvent(new KeyboardEvent('keydown',options));composer.dispatchEvent(new KeyboardEvent('keypress',options));composer.dispatchEvent(new KeyboardEvent('keyup',options));return true;}catch(_){return false;}};
                """;
    }

    private static String genericEditorPrelude() {
        return """
                const canonical=v=>String(v??'').replace(/[\u200B-\u200D\uFEFF]/g,'').replace(/\u00a0/g,' ').replace(/\r\n?/g,'\n').trim();
                const raw=e=>('value'in e?e.value:(e.innerText||e.textContent||''));
                const sameText=(e,value)=>canonical(raw(e))===canonical(value);
                const emitInput=(e,inputType,data)=>{try{e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType,data}));}catch(_){e.dispatchEvent(new Event('input',{bubbles:true}));}try{e.dispatchEvent(new Event('change',{bubbles:true}));}catch(_){}};
                const writeExact=(e,value)=>{try{e.focus?.();}catch(_){}if('value'in e){const p=Object.getPrototypeOf(e),own=p?Object.getOwnPropertyDescriptor(p,'value'):null,base=typeof HTMLTextAreaElement!=='undefined'?Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value'):null,setter=own?.set||base?.set;if(setter)setter.call(e,value);else e.value=value;emitInput(e,'insertText',value);}else{try{e.textContent=value;}catch(_){}emitInput(e,'insertText',value);}};
                """;
    }

    private static String continuationEditorPrelude() {
        return """
                const canonical=v=>String(v??'').replace(/[\u200B-\u200D\uFEFF]/g,'').replace(/\u00a0/g,' ').replace(/\r\n?/g,'\n').trim();
                const raw=e=>('value'in e?e.value:(e.innerText||e.textContent||''));
                const sameText=(e,value)=>!!e&&e.isConnected&&canonical(raw(e))===canonical(value);
                const emptyText=e=>!!e&&e.isConnected&&canonical(raw(e))==='';
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

    private static String projectGuard(String projectUrl) {
        String project = q(SelfRunScript.projectId(projectUrl));
        String general = q(SelfRunScript.GENERAL_CHAT_SCOPE);
        return "if(location.protocol!=='https:'||!['chatgpt.com','www.chatgpt.com'].includes(location.hostname))return result('" + TARGET_ERROR + "','host mismatch');"
                + "if(/\\/(?:auth|login)(?:\\/|$)/.test(location.pathname))return result('" + AUTH_REQUIRED + "','authentication route active');"
                + ProjectUrlPolicy.webProjectIdentityPrelude()
                + "const __srParts=location.pathname.split('/').filter(Boolean);const __srAfter=k=>{const i=__srParts.indexOf(k);return i>=0&&i+1<__srParts.length?__srParts[i+1]:''};"
                + "const __srExpectedProject=" + project + ";const __srActualProject=__srCanonicalProjectId(__srAfter('g'));"
                + "if(__srExpectedProject===" + general + "){if(__srAfter('g'))return result('" + TARGET_ERROR + "','project route active for general chat');}else if(__srActualProject!==__srExpectedProject)return result('" + TARGET_ERROR + "','project route mismatch');";
    }

    private static String q(String value) {
        return SelfRunScript.quote(value == null ? "" : value);
    }
}
