package com.shaterguy.chatgptselfrun;

/** Applies one Work preference per evaluation so menu state changes are always read back separately. */
final class WorkPreferenceDom {
    private WorkPreferenceDom() {}

    static String modelForProject(String projectUrl, String model) {
        return model(projectGuard(SelfRunScript.projectId(projectUrl)), model);
    }

    static String reasoningForProject(String projectUrl, String reasoning) {
        return reasoning(projectGuard(SelfRunScript.projectId(projectUrl)), reasoning);
    }

    static String modelForConversation(String conversationUrl, String model) {
        return model(conversationGuard(SelfRunScript.conversationId(conversationUrl)), model);
    }

    static String reasoningForConversation(String conversationUrl, String reasoning) {
        return reasoning(conversationGuard(SelfRunScript.conversationId(conversationUrl)), reasoning);
    }

    private static String model(String guard, String wanted) {
        return "(() =>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,diagnostics,url:location.href});"
                + guard + helpers()
                + "const wanted=" + q(wanted) + ";const modelOf=s=>{const m=text(s).match(/(?:^|\\s)(sol|terra|luna)(?:\\s|$)/);return m?m[1]:''};"
                + "const options=[...document.querySelectorAll('[role=\"menuitemradio\"],[role=\"radio\"],[role=\"option\"],[role=\"menuitem\"]')].filter(visible).filter(e=>!!modelOf(label(e)));const option=options.find(e=>modelOf(label(e))===wanted);"
                + "const level=[...document.querySelectorAll('[role=\"menuitem\"]')].filter(visible).find(e=>/^(model|모델)(\\s|$)/.test(label(e)));const trigger=[...document.querySelectorAll('button[aria-haspopup=\"menu\"],[role=\"button\"][aria-haspopup=\"menu\"]')].filter(visible).filter(near).find(e=>!!modelOf(label(e)));const current=trigger?modelOf(label(trigger)):'';"
                + "const diagnostics={requested:wanted,current,optionFound:!!option,levelFound:!!level,triggerFound:!!trigger};if(option){if(selected(option))return result('READY','모델 적용 확인',diagnostics);option.click();return result('UI_WAIT','모델 선택 반영 대기',diagnostics);}if(trigger&&current===wanted)return result('READY','모델 적용 확인',diagnostics);if(level){level.click();return result('UI_WAIT','모델 메뉴 열기',diagnostics);}if(trigger){trigger.click();return result('UI_WAIT','Work 설정 열기',diagnostics);}return result('UI_WAIT','모델 선택 요소 대기',diagnostics);})()";
    }

    private static String reasoning(String guard, String wanted) {
        return "(() =>{const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,diagnostics,url:location.href});"
                + guard + helpers()
                + "const wanted=" + q(wanted) + ";const effort=s=>{const v=text(s);if(v.includes('ultra')||v.includes('울트라'))return'ultra';if(v.includes('xhigh')||v.includes('extra high')||v.includes('very high')||v.includes('매우 높음'))return'xhigh';if(v.includes('maximum')||v.includes('max')||v.includes('최대'))return'max';if(v.includes('high')||v.includes('높음'))return'high';return''};"
                + "const options=[...document.querySelectorAll('[role=\"menuitemradio\"],[role=\"radio\"],[role=\"option\"],[role=\"menuitem\"]')].filter(visible).filter(e=>!!effort(label(e)));const option=options.find(e=>effort(label(e))===wanted);"
                + "const level=[...document.querySelectorAll('[role=\"menuitem\"]')].filter(visible).find(e=>/^(reasoning (level|effort)|추론 (수준|강도|정도))(\\s|$)/.test(label(e)));const trigger=[...document.querySelectorAll('button[aria-haspopup=\"menu\"],[role=\"button\"][aria-haspopup=\"menu\"]')].filter(visible).filter(near).find(e=>!!effort(label(e)));const current=trigger?effort(label(trigger)):'';"
                + "const diagnostics={requested:wanted,current,optionFound:!!option,levelFound:!!level,triggerFound:!!trigger};if(option){if(selected(option))return result('READY','추론 적용 확인',diagnostics);option.click();return result('UI_WAIT','추론 선택 반영 대기',diagnostics);}if(trigger&&current===wanted)return result('READY','추론 적용 확인',diagnostics);if(level){level.click();return result('UI_WAIT','추론 메뉴 열기',diagnostics);}if(trigger){trigger.click();return result('UI_WAIT','추론 설정 열기',diagnostics);}return result('UI_WAIT','추론 선택 요소 대기',diagnostics);})()";
    }

    private static String helpers() {
        return "const text=s=>String(s??'').replace(/\\s+/g,' ').trim().toLowerCase();const label=e=>text(e?.innerText||'')||text(e?.getAttribute?.('aria-label')||'');const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;"
                + "const input=document.querySelector('#prompt-textarea')||[...document.querySelectorAll('textarea,[contenteditable=\"true\"]')].filter(visible).sort((a,b)=>b.getBoundingClientRect().bottom-a.getBoundingClientRect().bottom)[0]||null;const form=input?.closest?.('form')||null;const near=e=>{if(!e||!input)return false;if(form)return form.contains(e);const a=e.getBoundingClientRect(),b=input.getBoundingClientRect();return a.bottom>=b.top-240&&a.top<=b.bottom+240&&a.right>=b.left-320&&a.left<=b.right+320};"
                + "const selected=e=>!!e&&(e.getAttribute('aria-checked')==='true'||e.getAttribute('aria-pressed')==='true'||e.getAttribute('aria-selected')==='true'||/^(checked|selected|active|on)$/.test(text(e.dataset?.state||'')));";
    }

    private static String projectGuard(String projectId) {
        return "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','호스트 불일치');const p=location.pathname.split('/').filter(Boolean);const after=k=>{const i=p.indexOf(k);return i>=0&&i+1<p.length?p[i+1]:''};if(after('g')!==" + q(projectId) + ")return result('TARGET_ERROR','프로젝트 불일치');";
    }

    private static String conversationGuard(String conversationId) {
        return "if(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com')return result('TARGET_ERROR','호스트 불일치');const p=location.pathname.split('/').filter(Boolean);const after=k=>{const i=p.indexOf(k);return i>=0&&i+1<p.length?p[i+1]:''};if(after('c')!==" + q(conversationId) + ")return result('TARGET_ERROR','canonical conversation 이탈');";
    }

    private static String q(String value) { return SelfRunScript.quote(value); }
}
