package com.shaterguy.chatgptselfrun;

/**
 * V3-only ChatGPT composer transport.
 *
 * <p>The transport owns V3 prompt preparation/submission. It does not depend on the retired
 * continuation DOM adapter, calibrated element identities, layout visibility, or SEND/STOP button
 * state. A composer is discovered from editable capabilities, including open shadow roots, and the
 * owning form is the primary submission primitive.</p>
 */
final class SelfRun3ComposerTransport {
    static final String READY_TO_SUBMIT = "READY_TO_SUBMIT";
    static final String COMPOSER_WAITING = "COMPOSER_WAITING";
    static final String COMPOSER_INPUTTING = "COMPOSER_INPUTTING";
    static final String SUBMISSION_PENDING = "SUBMISSION_PENDING";
    static final String STOP = "STOP"; // retained only for status compatibility; never emitted here.
    static final String SEND_DISABLED = "SEND_DISABLED"; // retained only for status compatibility.
    static final String SEND_UNAVAILABLE = "SEND_UNAVAILABLE";
    static final String AUTH_REQUIRED = "AUTH_REQUIRED";
    static final String TARGET_ERROR = "TARGET_ERROR";

    private SelfRun3ComposerTransport() {}

    static String prepareInitial(String projectUrl, String prompt) {
        return prepare(projectGuard(projectUrl), prompt);
    }

    static String prepareContinuation(String conversationUrl, String prompt) {
        return prepare(conversationGuard(conversationUrl), prompt);
    }

    static String submitInitial(String projectUrl, String prompt) {
        return submit(projectGuard(projectUrl), prompt);
    }

    static String submitContinuation(String conversationUrl, String prompt) {
        return submit(conversationGuard(conversationUrl), prompt);
    }

    /** Used only by bounded reconciliation and shares the live transport's capability probe. */
    static String composerReadyExpression() {
        return "(()=>{" + locatorPrelude() + "return !!findComposer();})()";
    }

    private static String prepare(String guard, String prompt) {
        return "(()=>{const result=(status,detail='')=>JSON.stringify({status,detail});"
                + guard
                + "const expected=" + q(prompt) + ";"
                + locatorPrelude()
                + editorPrelude()
                + protocolIdleGuard()
                + "const composer=findComposer();"
                + "if(!composer)return result('" + COMPOSER_WAITING + "','editable composer not present yet');"
                + "if(sameText(composer,expected))return result('" + READY_TO_SUBMIT + "','exact prompt already prepared');"
                + "writeExact(composer,expected);"
                + "return result('" + COMPOSER_INPUTTING + "',sameText(composer,expected)"
                + "?'native editor mutation visible; model readback will be rechecked':'native editor mutation issued; model readback is pending');"
                + "})()";
    }

    private static String submit(String guard, String prompt) {
        return "(()=>{const result=(status,detail='')=>JSON.stringify({status,detail});"
                + guard
                + "const expected=" + q(prompt) + ";"
                + locatorPrelude()
                + editorPrelude()
                + protocolIdleGuard()
                + "const composer=findComposer();"
                + "if(!composer)return result('" + COMPOSER_WAITING + "','editable composer disappeared before submit');"
                + "if(!sameText(composer,expected))return result('" + COMPOSER_INPUTTING + "','exact prompt model readback changed before submit');"
                + "const form=findOwningForm(composer);"
                + "if(form&&typeof form.requestSubmit==='function'){"
                + "try{form.requestSubmit();return result('" + SUBMISSION_PENDING + "','dispatch=form_request_submit');}"
                + "catch(_){}}"
                + "if(dispatchEnter(composer))return result('" + SUBMISSION_PENDING + "','dispatch=editor_enter');"
                + "return result('" + SEND_UNAVAILABLE + "','no functional submit path');"
                + "})()";
    }

    private static String protocolIdleGuard() {
        return "const protocol=window.__selfRunTurnProtocol?.snapshot?.();"
                + "if(protocol&&(protocol.phase==='THINKING'||protocol.phase==='ANSWERING'))"
                + "return result('TURN_PROTOCOL_BUSY','previous response protocol is still active');";
    }

    private static String locatorPrelude() {
        return """
                const safeText=v=>String(v??'').replace(/\\s+/g,' ').trim().toLowerCase();
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
                const semantic=e=>safeText([
                  e?.getAttribute?.('aria-label'),e?.getAttribute?.('placeholder'),
                  e?.getAttribute?.('role'),e?.getAttribute?.('aria-multiline'),
                  e?.getAttribute?.('contenteditable')
                ].join(' '));
                const nearestForm=e=>{
                  let node=e,depth=0;
                  while(node&&depth++<16){
                    if(String(node.tagName||'').toLowerCase()==='form')return node;
                    if(node.parentElement){node=node.parentElement;continue;}
                    const root=node.getRootNode?.();node=root&&root.host?root.host:null;
                  }
                  return null;
                };
                const inMain=e=>{
                  let node=e,depth=0;
                  while(node&&depth++<16){
                    if(String(node.tagName||'').toLowerCase()==='main')return true;
                    if(node.parentElement){node=node.parentElement;continue;}
                    const root=node.getRootNode?.();node=root&&root.host?root.host:null;
                  }
                  return false;
                };
                const score=(e,index)=>{
                  let s=index/10000;const text=semantic(e),tag=String(e.tagName||'').toLowerCase();
                  if(nearestForm(e))s+=70;
                  if(inMain(e))s+=30;
                  if(tag==='textarea')s+=35;
                  if(e.isContentEditable||e.getAttribute?.('contenteditable')==='true')s+=35;
                  if(String(e.getAttribute?.('role')||'').toLowerCase()==='textbox')s+=30;
                  if(String(e.getAttribute?.('aria-multiline')||'').toLowerCase()==='true')s+=20;
                  if(/message|chat|ask|question|prompt|메시지|질문|입력/.test(text))s+=30;
                  if(/search|검색/.test(text))s-=80;
                  return s;
                };
                const collect=root=>[...root.querySelectorAll?.('textarea,input,[contenteditable],[role="textbox"]')||[]].filter(editable);
                const ranked=nodes=>nodes.map((e,i)=>({e,s:score(e,i)})).sort((a,b)=>b.s-a.s);
                const shadowCandidates=()=>{
                  const found=[],queue=[document],seen=new Set([document]);
                  for(let qi=0;qi<queue.length&&qi<32;qi++){
                    const root=queue[qi];
                    for(const element of root.querySelectorAll?.('*')||[]){
                      const shadow=element.shadowRoot;
                      if(shadow&&!seen.has(shadow)){seen.add(shadow);queue.push(shadow);for(const e of collect(shadow))found.push(e);}
                    }
                  }
                  return found;
                };
                const findComposer=()=>{
                  const direct=ranked(collect(document));
                  if(direct.length&&direct[0].s>=80)return direct[0].e;
                  const shadow=ranked(shadowCandidates());
                  if(shadow.length&&(!direct.length||shadow[0].s>direct[0].s))return shadow[0].e;
                  return direct.length&&direct[0].s>0?direct[0].e:null;
                };
                const findOwningForm=composer=>nearestForm(composer);
                const dispatchEnter=composer=>{
                  try{
                    composer.focus?.();
                    const options={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true,shiftKey:false};
                    composer.dispatchEvent(new KeyboardEvent('keydown',options));
                    composer.dispatchEvent(new KeyboardEvent('keypress',options));
                    composer.dispatchEvent(new KeyboardEvent('keyup',options));
                    return true;
                  }catch(_){return false;}
                };
                """;
    }

    private static String editorPrelude() {
        return """
                const canonical=v=>String(v??'')
                  .replace(/[\\u200B-\\u200D\\uFEFF]/g,'')
                  .replace(/\\u00a0/g,' ')
                  .replace(/\\r\\n?/g,'\\n')
                  .trim();
                const raw=e=>('value'in e?e.value:(e.innerText||e.textContent||''));
                const sameText=(e,value)=>canonical(raw(e))===canonical(value);
                const emitInput=(e,inputType,data)=>{
                  try{e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType,data}));}
                  catch(_){e.dispatchEvent(new Event('input',{bubbles:true}));}
                  try{e.dispatchEvent(new Event('change',{bubbles:true}));}catch(_){}
                };
                const emitBeforeInput=(e,inputType,data)=>{
                  try{return e.dispatchEvent(new InputEvent('beforeinput',{
                    bubbles:true,cancelable:true,inputType,data
                  }));}catch(_){return true;}
                };
                const setField=(e,value)=>{
                  const proto=Object.getPrototypeOf(e);
                  const own=proto?Object.getOwnPropertyDescriptor(proto,'value'):null;
                  const textarea=typeof HTMLTextAreaElement!=='undefined'
                    ?Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value'):null;
                  const input=typeof HTMLInputElement!=='undefined'
                    ?Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value'):null;
                  const setter=own?.set||textarea?.set||input?.set;
                  e.focus?.();
                  emitBeforeInput(e,'insertText',value);
                  if(!sameText(e,value)){
                    if(setter)setter.call(e,value);else e.value=value;
                  }
                  emitInput(e,'insertText',value);
                };
                const selectContents=e=>{
                  try{
                    const doc=e.ownerDocument||document,win=doc.defaultView||window;
                    const sel=win.getSelection?.(),range=doc.createRange?.();
                    if(!sel||!range)return false;
                    range.selectNodeContents(e);sel.removeAllRanges();sel.addRange(range);return true;
                  }catch(_){return false;}
                };
                const setRich=(e,value)=>{
                  const doc=e.ownerDocument||document;
                  try{e.focus?.();}catch(_){}
                  selectContents(e);
                  emitBeforeInput(e,'insertText',value);
                  if(!sameText(e,value)&&e.isConnected){
                    selectContents(e);
                    try{doc.execCommand?.('insertText',false,value);}catch(_){}
                  }
                  if(e.isConnected)emitInput(e,'insertText',value);
                };
                const writeExact=(e,value)=>{
                  try{e.focus?.();}catch(_){}
                  if('value'in e)setField(e,value);else setRich(e,value);
                };
                """;
    }

    private static String conversationGuard(String conversationUrl) {
        String conversation = q(SelfRunScript.conversationId(conversationUrl));
        return "if(location.protocol!=='https:'||!['chatgpt.com','www.chatgpt.com'].includes(location.hostname))"
                + "return result('" + TARGET_ERROR + "','host mismatch');"
                + "if(/\\/(?:auth|login)(?:\\/|$)/.test(location.pathname))"
                + "return result('" + AUTH_REQUIRED + "','authentication route active');"
                + "const __srParts=location.pathname.split('/').filter(Boolean);"
                + "const __srAfter=k=>{const i=__srParts.indexOf(k);return i>=0&&i+1<__srParts.length?__srParts[i+1]:''};"
                + "if(__srAfter('c')!==" + conversation + ")return result('" + TARGET_ERROR + "','conversation route mismatch');";
    }

    private static String projectGuard(String projectUrl) {
        String project = q(SelfRunScript.projectId(projectUrl));
        String general = q(SelfRunScript.GENERAL_CHAT_SCOPE);
        return "if(location.protocol!=='https:'||!['chatgpt.com','www.chatgpt.com'].includes(location.hostname))"
                + "return result('" + TARGET_ERROR + "','host mismatch');"
                + "if(/\\/(?:auth|login)(?:\\/|$)/.test(location.pathname))"
                + "return result('" + AUTH_REQUIRED + "','authentication route active');"
                + ProjectUrlPolicy.webProjectIdentityPrelude()
                + "const __srParts=location.pathname.split('/').filter(Boolean);"
                + "const __srAfter=k=>{const i=__srParts.indexOf(k);return i>=0&&i+1<__srParts.length?__srParts[i+1]:''};"
                + "const __srExpectedProject=" + project + ";"
                + "const __srActualProject=__srCanonicalProjectId(__srAfter('g'));"
                + "if(__srExpectedProject===" + general + "){"
                + "if(__srAfter('g'))return result('" + TARGET_ERROR + "','project route active for general chat');"
                + "}else if(__srActualProject!==__srExpectedProject)"
                + "return result('" + TARGET_ERROR + "','project route mismatch');";
    }

    private static String q(String value) {
        return SelfRunScript.quote(value == null ? "" : value);
    }
}
