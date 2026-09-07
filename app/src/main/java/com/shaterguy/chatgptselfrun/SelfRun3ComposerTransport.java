package com.shaterguy.chatgptselfrun;

/**
 * V3-only ChatGPT composer transport.
 *
 * <p>This transport discovers the current composer by editable capabilities and submits
 * through the owning form when available. It is independent from the retired continuation
 * execution authority.</p>
 */
final class SelfRun3ComposerTransport {
    static final String READY_TO_SUBMIT = "READY_TO_SUBMIT";
    static final String COMPOSER_WAITING = "COMPOSER_WAITING";
    static final String COMPOSER_INPUTTING = "COMPOSER_INPUTTING";
    static final String SUBMISSION_PENDING = "SUBMISSION_PENDING";
    static final String STOP = "STOP";
    static final String SEND_DISABLED = "SEND_DISABLED";
    static final String SEND_UNAVAILABLE = "SEND_UNAVAILABLE";
    static final String AUTH_REQUIRED = "AUTH_REQUIRED";
    static final String TARGET_ERROR = "TARGET_ERROR";

    private SelfRun3ComposerTransport() {}

    static String prepareInitial(String projectUrl, String prompt) {
        return prepare(projectGuard(projectUrl, true), prompt);
    }

    static String prepareContinuation(String conversationUrl, String prompt) {
        return prepare(conversationGuard(conversationUrl), prompt);
    }

    static String submitInitial(String projectUrl, String prompt) {
        return submit(projectGuard(projectUrl, true), prompt);
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
                + "return sameText(composer,expected)"
                + "?result('" + READY_TO_SUBMIT + "','exact prompt prepared')"
                + ":result('" + COMPOSER_INPUTTING + "','editor accepted mutation but exact readback is pending');"
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
                + "if(!sameText(composer,expected))return result('" + COMPOSER_INPUTTING + "','exact prompt readback changed before submit');"
                + "const form=findOwningForm(composer);"
                + "if(form&&typeof form.requestSubmit==='function'){"
                + "try{form.requestSubmit();return result('" + SUBMISSION_PENDING + "','dispatch=form_request_submit');}"
                + "catch(_){}}"
                + "const send=findSendControl(composer,form);"
                + "if(send){"
                + "if(send.disabled||send.getAttribute?.('aria-disabled')==='true')"
                + "return result('" + SEND_DISABLED + "','send control is disabled');"
                + "try{send.focus?.();send.click();return result('" + SUBMISSION_PENDING + "','dispatch=semantic_send_control');}"
                + "catch(_){return result('" + SEND_UNAVAILABLE + "','send control click failed');}}"
                + "return result('" + SEND_UNAVAILABLE + "','no submit form or semantic send control');"
                + "})()";
    }

    private static String protocolIdleGuard() {
        return "const protocol=window.__selfRunTurnProtocol?.snapshot?.();"
                + "if(protocol&&(protocol.phase==='THINKING'||protocol.phase==='ANSWERING'))"
                + "return result('" + STOP + "','previous response protocol is still active');";
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
                  if(tag==='input')return ['text','search'].includes(inputKind(e));
                  return e.isContentEditable||e.getAttribute?.('contenteditable')==='true';
                };
                const semantic=e=>safeText([
                  e?.id,e?.getAttribute?.('data-testid'),e?.getAttribute?.('aria-label'),
                  e?.getAttribute?.('placeholder'),e?.getAttribute?.('role'),
                  e?.getAttribute?.('data-lexical-editor')
                ].join(' '));
                const score=(e,index)=>{
                  let s=index/10000;const text=semantic(e),tag=String(e.tagName||'').toLowerCase();
                  if(e===document.activeElement)s+=80;
                  if(e.id==='prompt-textarea')s+=70;
                  if(String(e.getAttribute?.('data-testid')||'').toLowerCase()==='prompt-textarea')s+=65;
                  if(tag==='textarea')s+=35;
                  if(e.isContentEditable||e.getAttribute?.('contenteditable')==='true')s+=35;
                  if(String(e.getAttribute?.('role')||'').toLowerCase()==='textbox')s+=25;
                  if(e.closest?.('form'))s+=25;
                  if(e.closest?.('main'))s+=15;
                  if(/prompt|message|chat|ask|question|메시지|질문|입력/.test(text))s+=35;
                  return s;
                };
                const findComposer=()=>{
                  const nodes=[...document.querySelectorAll('textarea,input,[contenteditable],[role="textbox"]')]
                    .filter(editable);
                  if(!nodes.length)return null;
                  return nodes.map((e,i)=>({e,s:score(e,i)})).sort((a,b)=>b.s-a.s)[0].e;
                };
                const findOwningForm=composer=>{
                  const direct=composer?.closest?.('form');if(direct)return direct;
                  let p=composer?.parentElement,depth=0;
                  while(p&&depth++<4){const f=p.querySelector?.(':scope > form,form');if(f&&f.contains(composer))return f;p=p.parentElement;}
                  return null;
                };
                const controlText=e=>safeText([
                  e?.getAttribute?.('data-testid'),e?.getAttribute?.('aria-label'),
                  e?.getAttribute?.('title'),e?.innerText,e?.textContent
                ].join(' '));
                const sendScore=e=>{
                  if(!e||!e.isConnected||hiddenByContract(e))return -1;
                  const t=controlText(e);let s=0;
                  if(String(e.getAttribute?.('type')||'').toLowerCase()==='submit')s+=40;
                  if(/send-button|composer-submit-button/.test(String(e.getAttribute?.('data-testid')||'').toLowerCase()))s+=70;
                  if(/\\b(send|submit)\\b|보내기/.test(t))s+=60;
                  if(/\\bstop\\b|중지|정지|voice|microphone|mic|음성|마이크/.test(t))s-=100;
                  return s;
                };
                const findSendControl=(composer,form)=>{
                  const roots=[];if(form)roots.push(form);
                  let p=composer?.parentElement,depth=0;while(p&&depth++<3){roots.push(p);p=p.parentElement;}
                  const all=[];for(const root of roots)for(const e of root.querySelectorAll?.('button,[role="button"]')||[])if(!all.includes(e))all.push(e);
                  const ranked=all.map(e=>({e,s:sendScore(e)})).filter(x=>x.s>0).sort((a,b)=>b.s-a.s);
                  return ranked.length?ranked[0].e:null;
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
                const setField=(e,value)=>{
                  const proto=Object.getPrototypeOf(e);
                  const own=proto?Object.getOwnPropertyDescriptor(proto,'value'):null;
                  const textarea=typeof HTMLTextAreaElement!=='undefined'
                    ?Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value'):null;
                  const input=typeof HTMLInputElement!=='undefined'
                    ?Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value'):null;
                  const setter=own?.set||textarea?.set||input?.set;
                  if(setter)setter.call(e,value);else e.value=value;
                  try{e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:value}));}
                  catch(_){e.dispatchEvent(new Event('input',{bubbles:true}));}
                  e.dispatchEvent(new Event('change',{bubbles:true}));
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
                  const doc=e.ownerDocument||document;e.focus?.();selectContents(e);
                  let nativeChanged=false;
                  try{nativeChanged=!!doc.execCommand?.('insertText',false,value);}catch(_){}
                  if(!sameText(e,value)){
                    while(e.firstChild)e.removeChild(e.firstChild);
                    const p=doc.createElement('p');
                    if(value)p.textContent=value;else p.appendChild(doc.createElement('br'));
                    e.appendChild(p);
                    try{e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:value}));}
                    catch(_){e.dispatchEvent(new Event('input',{bubbles:true}));}
                  }else if(!nativeChanged){
                    try{e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:value}));}
                    catch(_){e.dispatchEvent(new Event('input',{bubbles:true}));}
                  }
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

    private static String projectGuard(String projectUrl, boolean requireNewConversation) {
        String project = q(SelfRunScript.projectId(projectUrl));
        String general = q(SelfRunScript.GENERAL_CHAT_SCOPE);
        String noConversation = requireNewConversation
                ? "if(__srAfter('c'))return result('" + TARGET_ERROR + "','unexpected existing conversation');"
                : "";
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
                + "return result('" + TARGET_ERROR + "','project route mismatch');"
                + noConversation;
    }

    private static String q(String value) {
        return SelfRunScript.quote(value == null ? "" : value);
    }
}
