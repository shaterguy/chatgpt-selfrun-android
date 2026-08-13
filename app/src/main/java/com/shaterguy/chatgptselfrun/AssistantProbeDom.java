package com.shaterguy.chatgptselfrun;

/** Full semantic assistant probe used by observer, quiet, watchdog, timeout, resume and reload paths. */
final class AssistantProbeDom {
    private AssistantProbeDom() {}

    static String observeAssistant(String conversationUrl, String baselineKey) {
        String conversation = q(SelfRunScript.conversationId(conversationUrl));
        return "(() =>{const result=(status,text='',extra={})=>JSON.stringify({status,text,url:location.href,...extra});"
                + "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','호스트 불일치');"
                + "const p=location.pathname.split('/').filter(Boolean);const after=k=>{const i=p.indexOf(k);return i>=0&&i+1<p.length?p[i+1]:''};"
                + "if(after('c')!==" + conversation + ")return result('TARGET_ERROR','canonical conversation 이탈');"
                + "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;"
                + "const auth=[...document.querySelectorAll('[data-testid*=login],a[href*=\"/auth/login\"],button')].filter(visible).some(e=>/^(log in|sign up|로그인|가입)$/i.test(String(e.innerText||e.getAttribute('aria-label')||'').trim()));"
                + "if(auth)return result('AUTH_REQUIRED','ChatGPT 로그인이 필요합니다.');"
                + "const roleOf=e=>e.getAttribute('data-message-author-role')||e.getAttribute('data-turn')||e.querySelector('[data-message-author-role]')?.getAttribute('data-message-author-role')||'';"
                + "const turns=[...document.querySelectorAll('article,[data-message-author-role]')].filter((e,i,a)=>!a.some((parent,j)=>j<i&&parent.contains(e)));"
                + "let userIndex=-1;for(let i=0;i<turns.length;i++){if(roleOf(turns[i])==='user')userIndex=i;}"
                + "if(userIndex<0)return result('WAIT','최근 사용자 턴 대기');"
                + "let assistant=null,assistantIndex=-1;for(let i=userIndex+1;i<turns.length;i++){const role=roleOf(turns[i]);if(role==='user')break;if(role==='assistant'){assistant=turns[i];assistantIndex=i;break;}}"
                + "const stopping=[...document.querySelectorAll('button')].filter(visible).some(b=>b.dataset?.testid==='stop-button'||/stop generating|응답 중지|생성 중지/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||'')));"
                + "if(!assistant)return result(stopping?'GENERATING':'WAIT',stopping?'어시스턴트 응답 생성 중':'새 assistant 응답 대기');"
                + "const assistantText=String(assistant.innerText||assistant.textContent||'').trim();"
                + "const explicitStreaming=assistant.getAttribute('aria-busy')==='true'||assistant.getAttribute('data-is-streaming')==='true'||!!assistant.querySelector('[aria-busy=\"true\"],[data-is-streaming=\"true\"]');"
                + "const assistantIdentity=assistant.getAttribute('data-message-id')||assistant.dataset?.messageId||assistant.id||'index';const assistantKey=assistantIdentity+':'+assistantIndex;"
                + "if(assistantKey===" + q(baselineKey) + ")return result('STALE','',{assistantKey});"
                + "if(stopping||explicitStreaming)return result('GENERATING',assistantText,{assistantKey});"
                + "return assistantText?result('COMPLETE',assistantText,{assistantKey}):result('WAIT','',{assistantKey});})()";
    }

    private static String q(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
