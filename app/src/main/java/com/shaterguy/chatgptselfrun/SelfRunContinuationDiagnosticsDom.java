package com.shaterguy.chatgptselfrun;

/** Passive, privacy-safe composer diagnostics used only after VirtualDisplay reattachment. */
final class SelfRunContinuationDiagnosticsDom {
    private SelfRunContinuationDiagnosticsDom() {}

    static String snapshot() {
        return "(()=>{try{"
                + "const specific=['textarea#prompt-textarea','textarea[data-testid=\"prompt-textarea\"]','div#prompt-textarea[contenteditable=\"true\"]','main form [contenteditable=\"true\"][data-lexical-editor=\"true\"]','main form [contenteditable=\"true\"]'];"
                + "const exact=[...new Set(specific.flatMap(s=>[...document.querySelectorAll(s)]))];"
                + "const broad=[...document.querySelectorAll('main textarea,main [contenteditable=\"true\"],form textarea,form [contenteditable=\"true\"]')];"
                + "const nodes=[...new Set([...exact,...broad])];"
                + "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;"
                + "const sig=e=>{if(!e)return'none';let s=null,r=null;try{s=getComputedStyle(e);}catch(_){}try{r=e.getBoundingClientRect();}catch(_){}return['tag='+String(e.tagName||'').toLowerCase(),'conn='+(e.isConnected?1:0),'off='+(e.offsetParent!==null?1:0),'rect='+(r&&r.width>0&&r.height>0?1:0),'disp='+(s&&s.display==='none'?0:1),'vis='+(s&&s.visibility==='hidden'?0:1),'ce='+(e.isContentEditable?1:0),'attr='+(e.getAttribute?.('contenteditable')==='true'?1:0),'aria='+(e.getAttribute?.('aria-disabled')==='true'?1:0),'disabled='+(e.disabled?1:0),'ro='+(e.readOnly?1:0),'form='+(e.closest?.('form')?1:0),'main='+(e.closest?.('main')?1:0)].join(',');};"
                + "return['doc='+(document.visibilityState||'na'),'focus='+(document.hasFocus?.()?1:0),'width='+(window.innerWidth||0),'height='+(window.innerHeight||0),'specific='+exact.length,'broad='+broad.length,'connected='+nodes.filter(e=>e&&e.isConnected).length,'visible='+nodes.filter(visible).length,'candidate='+sig(nodes[0])].join(';');"
                + "}catch(_){return'diag=error';}})()";
    }
}
