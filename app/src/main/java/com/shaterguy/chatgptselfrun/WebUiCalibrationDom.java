package com.shaterguy.chatgptselfrun;

/** JavaScript recorder and runtime matcher for user-calibrated ChatGPT UI elements. */
final class WebUiCalibrationDom {
    private static final String CAPTURE_KEY = "selfrun-drive:ui-calibration:capture";

    private WebUiCalibrationDom() {}

    static String install(String purpose) {
        return "(()=>{"
                + "if(location.protocol!=='https:'||(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com'))return JSON.stringify({status:'TARGET_ERROR'});"
                + "const purpose=" + SelfRunScript.quote(purpose) + ",captureKey=" + SelfRunScript.quote(CAPTURE_KEY) + ";"
                + "let existing='';try{existing=sessionStorage.getItem(captureKey)||'';}catch(_){}if(existing){try{const x=JSON.parse(existing);if(x&&x.ready&&x.purpose===purpose)return JSON.stringify({status:'READY_EXISTING'});}catch(_){}}"
                + "const norm=s=>String(s??'').replace(/\\s+/g,' ').trim().slice(0,120);"
                + "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;"
                + "const actionable=t=>t?.closest?.('button,a,input,textarea,[contenteditable=\"true\"],[role=\"button\"],[role=\"radio\"],[role=\"tab\"],[role=\"menuitem\"],[role=\"menuitemradio\"],[role=\"option\"]')||t;"
                + "const desc=e=>{if(!e)return null;const p=e.parentElement;return {tag:(e.tagName||'').toLowerCase(),id:norm(e.id),role:norm(e.getAttribute?.('role')),testid:norm(e.dataset?.testid),aria:norm(e.getAttribute?.('aria-label')),name:norm(e.getAttribute?.('name')),type:norm(e.getAttribute?.('type')),text:norm(e.innerText||e.textContent),href:norm(e.getAttribute?.('href')),parentRole:norm(p?.getAttribute?.('role')),parentTestid:norm(p?.dataset?.testid),parentAria:norm(p?.getAttribute?.('aria-label'))};};"
                + "const viewport=()=>({innerWidth:window.innerWidth||0,innerHeight:window.innerHeight||0,devicePixelRatio:window.devicePixelRatio||1,screenWidth:window.screen?.width||0,screenHeight:window.screen?.height||0});"
                + "const isComposer=e=>!!e&&(e.id==='prompt-textarea'||e.matches?.('textarea,[contenteditable=\"true\"]'))&&!!(e.closest?.('form')||e.closest?.('main'));"
                + "const isSubmitPurpose=purpose==='PROJECT_NEW_CHAT'||purpose==='GENERAL_NEW_CHAT';const isProject=()=>location.pathname.split('/').filter(Boolean).includes('g');"
                + "const contextOk=()=>purpose==='PROJECT_NEW_CHAT'?isProject():(purpose==='GENERAL_NEW_CHAT'?!isProject():true);"
                + "const write=o=>{try{sessionStorage.setItem(captureKey,JSON.stringify(o));}catch(_){}};"
                + "if(window.__selfRunUiCalibration&&window.__selfRunUiCalibration.purpose===purpose)return JSON.stringify({status:'ARMED'});"
                + "try{sessionStorage.removeItem(captureKey);}catch(_){}const state={purpose,entry:null,composer:null,send:null};window.__selfRunUiCalibration=state;"
                + "const candidate=e=>{if(!contextOk()||!e||!visible(e))return;write({ready:false,purpose,target:desc(e),viewport:viewport(),capturedAt:Date.now()});};"
                + "const finishSubmit=()=>{if(!contextOk()||!state.composer||!state.send)return;write({ready:true,purpose,entry:state.entry,composer:state.composer,send:state.send,viewport:viewport(),capturedAt:Date.now()});};"
                + "document.addEventListener('input',ev=>{if(window.__selfRunUiCalibration!==state||!isSubmitPurpose)return;const e=actionable(ev.target);if(isComposer(e)){state.composer=desc(e);finishSubmit();}},true);"
                + "document.addEventListener('click',ev=>{if(window.__selfRunUiCalibration!==state)return;const e=actionable(ev.target);if(!e||!visible(e))return;if(!isSubmitPurpose){candidate(e);return;}if(isComposer(e)){state.composer=desc(e);return;}if(state.composer){const form=e.closest?.('form');const composerForm=document.querySelector('#prompt-textarea')?.closest?.('form');if(e.tagName==='BUTTON'||e.getAttribute?.('role')==='button'||(form&&composerForm&&form===composerForm)){state.send=desc(e);finishSubmit();return;}}if(!state.composer&&!state.entry)state.entry=desc(e);},true);"
                + "document.addEventListener('submit',ev=>{if(window.__selfRunUiCalibration!==state||!isSubmitPurpose)return;const form=ev.target;if(!state.composer){const c=form?.querySelector?.('#prompt-textarea,textarea,[contenteditable=\"true\"]');if(c)state.composer=desc(c);}const s=ev.submitter||form?.querySelector?.('button[type=\"submit\"],button[data-testid*=\"send\"],button[data-testid*=\"submit\"]');if(s)state.send=desc(s);finishSubmit();},true);"
                + "return JSON.stringify({status:'ARMED'});})()";
    }

    static String read(String purpose) {
        return "(()=>{try{const raw=sessionStorage.getItem(" + SelfRunScript.quote(CAPTURE_KEY)
                + ")||'';if(!raw)return '';const x=JSON.parse(raw);return x&&x.purpose==="
                + SelfRunScript.quote(purpose) + "?raw:'';}catch(e){return '';}})()";
    }

    static String finalizeSimple(String purpose) {
        return "(()=>{try{const k=" + SelfRunScript.quote(CAPTURE_KEY) + ",raw=sessionStorage.getItem(k)||'';if(!raw)return 'EMPTY';const x=JSON.parse(raw);if(x.purpose!=="
                + SelfRunScript.quote(purpose) + "||!x.target)return 'MISMATCH';x.ready=true;x.confirmedAt=Date.now();sessionStorage.setItem(k,JSON.stringify(x));return 'OK';}catch(e){return 'ERROR';}})()";
    }

    static String clearCapture() {
        return "(()=>{try{sessionStorage.removeItem(" + SelfRunScript.quote(CAPTURE_KEY)
                + ");window.__selfRunUiCalibration=null;return 'OK';}catch(e){return 'ERROR';}})()";
    }

    /**
     * Defines __srFind(key), which scores stable element attributes captured by the user.
     * Text is deliberately low-weight; id/testid/aria/role and parent structure dominate.
     */
    static String runtimePrelude() {
        return "const __srNorm=s=>String(s??'').replace(/\\s+/g,' ').trim().toLowerCase().slice(0,120);"
                + "let __srProfile={};try{__srProfile=JSON.parse(localStorage.getItem('"
                + WebUiCalibrationStore.STORAGE_KEY + "')||'{}')||{};}catch(_){}const __srTargets=__srProfile.targets||{};"
                + "const __srVisible=e=>!!e&&e.isConnected&&e.offsetParent!==null;const __srLabel=e=>__srNorm(e?.innerText||e?.textContent)||__srNorm(e?.getAttribute?.('aria-label'));"
                + "const __srScore=(e,d)=>{if(!e||!d)return-999;let s=0;const p=e.parentElement,tag=(e.tagName||'').toLowerCase(),id=__srNorm(e.id),role=__srNorm(e.getAttribute?.('role')),testid=__srNorm(e.dataset?.testid),aria=__srNorm(e.getAttribute?.('aria-label')),name=__srNorm(e.getAttribute?.('name')),type=__srNorm(e.getAttribute?.('type')),text=__srLabel(e),href=__srNorm(e.getAttribute?.('href')),pr=__srNorm(p?.getAttribute?.('role')),pt=__srNorm(p?.dataset?.testid),pa=__srNorm(p?.getAttribute?.('aria-label'));if(d.id){if(id===__srNorm(d.id))s+=14;else s-=2;}if(d.testid){if(testid===__srNorm(d.testid))s+=14;else s-=2;}if(d.aria){if(aria===__srNorm(d.aria))s+=8;else if(aria&&(__srNorm(d.aria).includes(aria)||aria.includes(__srNorm(d.aria))))s+=3;}if(d.role&&role===__srNorm(d.role))s+=4;if(d.tag&&tag===__srNorm(d.tag))s+=3;if(d.name&&name===__srNorm(d.name))s+=3;if(d.type&&type===__srNorm(d.type))s+=2;if(d.href&&href===__srNorm(d.href))s+=4;if(d.text){const x=__srNorm(d.text);if(text===x)s+=4;else if(text&&x&&(text.includes(x)||x.includes(text)))s+=1;}if(d.parentRole&&pr===__srNorm(d.parentRole))s+=2;if(d.parentTestid&&pt===__srNorm(d.parentTestid))s+=4;if(d.parentAria&&pa===__srNorm(d.parentAria))s+=2;return s;};"
                + "const __srFind=k=>{const d=__srTargets[k];if(!d)return null;const nodes=[...document.querySelectorAll('button,a,input,textarea,[contenteditable=\"true\"],[role=\"button\"],[role=\"radio\"],[role=\"tab\"],[role=\"menuitem\"],[role=\"menuitemradio\"],[role=\"option\"]')].filter(__srVisible);let best=null,score=-999;for(const e of nodes){const n=__srScore(e,d);if(n>score){score=n;best=e;}}return score>=6?best:null;};";
    }
}
