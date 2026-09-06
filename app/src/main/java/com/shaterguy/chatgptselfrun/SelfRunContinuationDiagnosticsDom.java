package com.shaterguy.chatgptselfrun;

/** Passive, privacy-safe composer diagnostics used only after VirtualDisplay reattachment. */
final class SelfRunContinuationDiagnosticsDom {
    private SelfRunContinuationDiagnosticsDom() {}

    static String snapshot() {
        return "(()=>{try{"
                + "const specific=['#prompt-textarea','[data-testid=\"prompt-textarea\"]','textarea#prompt-textarea','main form textarea','main form [role=\"textbox\"][contenteditable]','main form .ProseMirror[contenteditable]','main form [data-lexical-editor=\"true\"][contenteditable]'];"
                + "const exact=[...new Set(specific.flatMap(s=>[...document.querySelectorAll(s)]))];"
                + "const broad=[...document.querySelectorAll('main textarea,main [contenteditable],main [role=\"textbox\"],form textarea,form [contenteditable],form [role=\"textbox\"]')];"
                + "const nodes=[...new Set([...exact,...broad])];"
                + "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;"
                + "const editable=e=>{if(!e)return false;const v=String(e.getAttribute?.('contenteditable')||'').trim().toLowerCase();return !!e.isContentEditable||(e.hasAttribute?.('contenteditable')&&v!=='false');};"
                + "const semantic=e=>{if(!e)return false;const specific=e.id==='prompt-textarea'||e.dataset?.testid==='prompt-textarea'||e.matches?.('textarea');const structured=editable(e)&&e.matches?.('[role=\"textbox\"],.ProseMirror,[data-lexical-editor=\"true\"],[contenteditable]');return !!(specific||structured);};"
                + "const ce=e=>{const v=String(e?.getAttribute?.('contenteditable')||'').trim().toLowerCase();return !v?'none':(v==='true'||v==='false'||v==='plaintext-only'?v:'other');};"
                + "const sig=e=>{if(!e)return'none';let s=null,r=null;try{s=getComputedStyle(e);}catch(_){}try{r=e.getBoundingClientRect();}catch(_){}return['tag='+String(e.tagName||'').toLowerCase(),'conn='+(e.isConnected?1:0),'off='+(e.offsetParent!==null?1:0),'rect='+(r&&r.width>0&&r.height>0?1:0),'disp='+(s&&s.display==='none'?0:1),'vis='+(s&&s.visibility==='hidden'?0:1),'ce='+(e.isContentEditable?1:0),'ceattr='+ce(e),'role='+(e.getAttribute?.('role')==='textbox'?'textbox':'other'),'semantic='+(semantic(e)?1:0),'aria='+(e.getAttribute?.('aria-disabled')==='true'?1:0),'disabled='+(e.disabled?1:0),'ro='+(e.readOnly?1:0),'form='+(e.closest?.('form')?1:0),'main='+(e.closest?.('main')?1:0)].join(',');};"
                + "const semanticNodes=nodes.filter(semantic);const candidate=semanticNodes.find(visible)||semanticNodes[0]||nodes[0];"
                + "return['doc='+(document.visibilityState||'na'),'focus='+(document.hasFocus?.()?1:0),'width='+(window.innerWidth||0),'height='+(window.innerHeight||0),'specific='+exact.length,'broad='+broad.length,'connected='+nodes.filter(e=>e&&e.isConnected).length,'visible='+nodes.filter(visible).length,'semantic='+semanticNodes.length,'candidate='+sig(candidate)].join(';');"
                + "}catch(_){return'diag=error';}})()";
    }
}
