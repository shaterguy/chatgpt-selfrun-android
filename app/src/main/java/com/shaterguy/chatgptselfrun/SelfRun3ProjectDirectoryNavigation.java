package com.shaterguy.chatgptselfrun;

import java.net.URI;

/** Project entry policy for SelfRun 3: enter through ChatGPT's projects directory and click the real row. */
final class SelfRun3ProjectDirectoryNavigation {
    static final String DIRECTORY_URL = "https://chatgpt.com/projects";

    private SelfRun3ProjectDirectoryNavigation() {}

    static boolean isProjectTarget(String targetUrl) {
        return ProjectUrlPolicy.parseProject(targetUrl) != null;
    }

    static String entryUrl(String targetUrl) {
        return isProjectTarget(targetUrl) ? DIRECTORY_URL : targetUrl;
    }

    static boolean isDirectoryPage(String actualUrl) {
        if (actualUrl == null || actualUrl.isEmpty()) return false;
        try {
            URI uri = new URI(actualUrl);
            String host = uri.getHost();
            String path = uri.getPath();
            return "https".equals(uri.getScheme())
                    && ("chatgpt.com".equals(host) || "www.chatgpt.com".equals(host))
                    && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && ("/projects".equals(path) || "/projects/".equals(path));
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean needsDirectoryStep(String targetUrl, String actualUrl) {
        return isProjectTarget(targetUrl) && !ProjectUrlPolicy.sameProject(targetUrl, actualUrl);
    }

    static boolean isWrongProjectRoute(String targetUrl, String actualUrl) {
        ProjectUrlPolicy.ProjectRef expected = ProjectUrlPolicy.parseProject(targetUrl);
        ProjectUrlPolicy.ProjectRef actual = ProjectUrlPolicy.parseProject(actualUrl);
        return expected != null && actual != null && !expected.projectId.equals(actual.projectId);
    }

    static String build(String projectDisplayName, int candidateIndex) {
        String expected = jsQuote(ProjectCatalog.normalizeDisplayName(projectDisplayName));
        int ordinal = Math.max(0, candidateIndex);
        return "(()=>{" +
                "const result=(status,detail='',diagnostics={})=>JSON.stringify({status,detail,url:location.href,diagnostics});" +
                "const clean=s=>String(s??'').replace(/\\s+/g,' ').trim();" +
                "const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;" +
                "if(location.protocol!=='https:'||(location.hostname!=='chatgpt.com'&&location.hostname!=='www.chatgpt.com'))" +
                "return result('TARGET_CONTEXT_MISMATCH','ChatGPT 호스트가 아닙니다.');" +
                "const authPath=/^\\/auth\\/(?:login|signup|signin)(?:\\/|$)/i.test(location.pathname);" +
                "const authControls=[...document.querySelectorAll('a[href],button,[role=\"button\"]')].filter(visible);" +
                "const authControl=authControls.find(e=>{const label=clean(e.innerText||e.textContent||e.getAttribute?.('aria-label')).toLowerCase();const href=String(e.getAttribute?.('href')||'').toLowerCase();return /^(?:log in|sign in|sign up|create account|로그인|회원가입|가입)$/.test(label)||/(?:^|\\/)(?:auth\\/)?(?:login|signup|signin)(?:[\\/?#]|$)/.test(href);});" +
                "if(authPath)return result('AUTH_REQUIRED','ChatGPT 로그인이 필요합니다.',{authPath:true});" +
                "if(location.pathname!=='/projects')return result('NAVIGATE_DIRECTORY','프로젝트 목록 화면으로 이동합니다.',{path:location.pathname});" +
                "const expected=clean(" + expected + ");" +
                "const rows=[...document.querySelectorAll('[role=\"row\"][data-page-table-selectable-row=\"true\"]')].filter(visible);" +
                "if(!rows.length&&authControl)return result('AUTH_REQUIRED','ChatGPT 로그인이 필요합니다.',{authPath:false,rows:0});" +
                "const matches=[];" +
                "for(let i=0;i<rows.length;i++){const row=rows[i];const labels=[...row.querySelectorAll('button[aria-label]')].map(b=>clean(b.getAttribute('aria-label'))).filter(Boolean);const label=labels.find(v=>v===expected||v.startsWith(expected+' '));if(label)matches.push({row,index:i,label});}" +
                "if(!matches.length)return result('RETRY','프로젝트 목록에서 대상 대기',{expected,rows:rows.length,matchingRows:0,readyState:document.readyState});" +
                "const ordinal=" + ordinal + ";" +
                "if(ordinal>=matches.length)return result('PROJECT_NOT_FOUND','동일 이름 후보를 모두 확인했지만 대상 프로젝트를 찾지 못했습니다.',{expected,rows:rows.length,matchingRows:matches.length,ordinal});" +
                "const pick=matches[ordinal];" +
                "try{pick.row.scrollIntoView({block:'center',inline:'nearest'});}catch(_){}" +
                "pick.row.click();" +
                "return result('PROJECT_ROW_CLICKED','대상 프로젝트 행을 클릭했습니다.',{expected,rows:rows.length,matchingRows:matches.length,ordinal,rowIndex:pick.index,label:pick.label});" +
                "})()";
    }

    private static String jsQuote(String value) {
        String safe = value == null ? "" : value;
        StringBuilder out = new StringBuilder(safe.length() + 16).append('"');
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
