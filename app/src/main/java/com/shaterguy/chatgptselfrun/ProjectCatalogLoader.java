package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.List;

/** One-shot authenticated WebView discovery of project names and canonical project links. */
final class ProjectCatalogLoader {
    interface Callback {
        void onSuccess(List<ProjectCatalog.Entry> entries);
        void onFailure(String code);
    }

    private static final String HOME_URL = "https://chatgpt.com/";
    private static final long TIMEOUT_MS = 25_000L;
    private static final long PROBE_MS = 700L;
    private static final long EMPTY_SETTLE_MS = 7_000L;
    private static final long CONTROL_DISCOVERY_MS = 12_000L;
    private static final int REQUIRED_STABLE_PROBES = 3;
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36 SelfRunDrive/1.2.0-dev3";

    private static final String PROBE_JS = """
            (function(){try{
              const clean=s=>String(s??'').trim().replace(/\\s+/g,' ');
              const text=e=>clean(e?.innerText||e?.textContent||'');
              const aria=e=>clean(e?.getAttribute?.('aria-label')||'');
              const desc=e=>clean((aria(e)+' '+text(e)).trim());
              const visible=e=>!!e&&e.isConnected&&e.getClientRects&&e.getClientRects().length>0;
              const projectUrl=raw=>{try{
                if(!raw)return '';
                const u=new URL(String(raw),location.href);
                if(u.protocol!=='https:'||!/^(www\\.)?chatgpt\\.com$/i.test(u.hostname))return '';
                const parts=u.pathname.split('/').filter(Boolean);
                if(parts.length<2||parts[0]!=='g'||!/^g-p-[A-Za-z0-9_-]+$/.test(parts[1]))return '';
                const tail=parts.slice(2);
                const valid=tail.length===0||(tail.length===1&&tail[0]==='project')||(tail.length===2&&tail[0]==='c'&&!!tail[1]);
                return valid?'https://chatgpt.com/g/'+parts[1]+'/project':'';
              }catch(_){return '';}};
              const candidateValues=e=>{const values=[];if(!e?.getAttribute)return values;
                for(const key of ['href','data-href','data-url','data-path','data-to']){const value=e.getAttribute(key);if(value)values.push(value);}
                for(const attr of Array.from(e.attributes||[])){const value=String(attr.value||'');if(value.includes('g-p-')&&!values.includes(value))values.push(value);}
                return values;
              };
              const isProjectLink=e=>candidateValues(e).some(v=>!!projectUrl(v));
              const controls=[...document.querySelectorAll('button,[role=button],[role=menuitem],[role=treeitem],[role=link],a')].filter(visible);
              const isProjectsControl=e=>/^(projects?|프로젝트)(?:\\s|$)/i.test(desc(e))&&!isProjectLink(e);
              const projectControl=controls.find(isProjectsControl)||null;
              const marker=!!projectControl;
              const labelFor=e=>{const own=clean(text(e)||aria(e));if(own&&own.length<=300)return own;
                const parent=e?.closest?.('li,[role=listitem],[role=menuitem],[role=treeitem]');const outer=clean(text(parent)||aria(parent));
                return outer&&outer.length<=300?outer:'프로젝트';};
              const collect=()=>{const out=[],seen=new Set();const add=(e,raw)=>{const url=projectUrl(raw);if(!url||seen.has(url))return;seen.add(url);out.push({name:labelFor(e),url});};
                for(const e of document.querySelectorAll('a[href],[role=link],[data-href],[data-url],[data-path],[data-to]'))for(const raw of candidateValues(e))add(e,raw);
                const nodes=document.querySelectorAll('*');const limit=Math.min(nodes.length,12000);for(let i=0;i<limit;i++){const e=nodes[i];for(const attr of Array.from(e.attributes||[])){const value=String(attr.value||'');if(value.includes('g-p-'))add(e,value);}}
                return out;};
              let out=collect();if(out.length)return JSON.stringify({state:'FOUND',marker,entries:out});
              if(projectControl&&!window.__selfrunProjectsOpenAttempted){window.__selfrunProjectsOpenAttempted=true;projectControl.focus?.();projectControl.click();return JSON.stringify({state:'OPENING',marker:true,entries:[]});}
              const more=controls.find(e=>/^(show more|view all|see all|더 보기|모두 보기)(?:\\s|$)/i.test(desc(e)));
              if(projectControl&&more&&!window.__selfrunProjectsMoreAttempted){window.__selfrunProjectsMoreAttempted=true;more.focus?.();more.click();return JSON.stringify({state:'OPENING',marker:true,entries:[]});}
              for(const e of document.querySelectorAll('*')){try{const s=getComputedStyle(e);if((s.overflowY==='auto'||s.overflowY==='scroll')&&e.scrollHeight>e.clientHeight+8)e.scrollTop=e.scrollHeight;}catch(_){}}
              out=collect();if(out.length)return JSON.stringify({state:'FOUND',marker,entries:out});
              if(!projectControl){const candidates=[];const add=e=>{if(e&&visible(e)&&!candidates.includes(e))candidates.push(e);};
                for(const sel of ['[data-testid="open-sidebar-button"]','button[aria-label*="sidebar" i]','[role=button][aria-label*="sidebar" i]','button[aria-label*="navigation" i]','[role=button][aria-label*="navigation" i]']){try{document.querySelectorAll(sel).forEach(add);}catch(_){}}
                controls.forEach(add);const isNavOpener=e=>{const v=desc(e).toLowerCase(),id=String(e?.dataset?.testid||'').toLowerCase(),expanded=e?.getAttribute?.('aria-expanded');if(id==='open-sidebar-button')return true;if(expanded==='true'&&!/(open|show|expand|열기|펼치)/i.test(v))return false;return /(?:open|show|expand).*(?:sidebar|navigation)|(?:sidebar|navigation).*(?:open|show|expand)|사이드바.*(?:열기|펼치기)|(?:열기|펼치기).*사이드바|(?:메뉴|탐색).*(?:열기|펼치기)|(?:열기|펼치기).*(?:메뉴|탐색)/i.test(v);};
                const nav=candidates.find(isNavOpener)||null;if(nav&&!window.__selfrunSidebarOpenAttempted){window.__selfrunSidebarOpenAttempted=true;nav.focus?.();nav.click();return JSON.stringify({state:'OPENING',marker:false,entries:[]});}}
              return JSON.stringify({state:'EMPTY',marker,entries:[]});
            }catch(e){return JSON.stringify({state:'ERROR',marker:false,entries:[]});}})();
            """;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable probeRunnable = this::probe;
    private HeadlessWebViewHost host;
    private WebView webView;
    private Callback callback;
    private long passStartedAt;
    private int stableProbes;
    private String lastFingerprint = "";
    private boolean projectsControlSeen;
    private boolean navigationOpenAttempted;
    private boolean desktopFallbackAttempted;
    private boolean finished;

    ProjectCatalogLoader(Context context) { this.context = context; }

    void start(Callback callback) {
        if (this.callback != null) throw new IllegalStateException("project loader already started");
        this.callback = callback;
        this.passStartedAt = System.currentTimeMillis();
        try {
            host = HeadlessWebViewHost.create(context);
            webView = host.webView();
            WebViewConfig.applyAutomation(webView);
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    if (!ProjectCatalog.isTrustedChatgptPage(url)) fail("UNTRUSTED_NAVIGATION");
                }
                @Override public void onPageFinished(WebView view, String url) {
                    if (!ProjectCatalog.isTrustedChatgptPage(url)) { fail("UNTRUSTED_NAVIGATION"); return; }
                    scheduleProbe(500L);
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (!request.isForMainFrame()) return false;
                    if (!ProjectCatalog.isTrustedChatgptPage(String.valueOf(request.getUrl()))) {
                        fail("UNTRUSTED_NAVIGATION");
                        return true;
                    }
                    return false;
                }
                @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame()) fail("PAGE_LOAD_FAILED");
                }
                @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                    handler.cancel(); fail("SSL_ERROR");
                }
                @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    fail("RENDERER_GONE"); return true;
                }
            });
            if (!ProjectCatalog.isTrustedChatgptPage(HOME_URL)) throw new IllegalStateException("invalid home URL");
            webView.loadUrl(HOME_URL);
        } catch (Throwable error) {
            fail("WEBVIEW_START_FAILED");
        }
    }

    void cancel() { finish(null, "CANCELLED"); }

    static String probeScriptForTesting() { return PROBE_JS; }

    private void scheduleProbe(long delay) {
        if (finished) return;
        handler.removeCallbacks(probeRunnable);
        handler.postDelayed(probeRunnable, delay);
    }

    private void probe() {
        if (finished || webView == null) return;
        long elapsed = System.currentTimeMillis() - passStartedAt;
        if (elapsed > TIMEOUT_MS) {
            fallbackOrFail(projectsControlSeen ? "PROJECT_LIST_UNRESOLVED" : "PROJECTS_CONTROL_NOT_FOUND");
            return;
        }
        String current = webView.getUrl();
        if (!ProjectCatalog.isTrustedChatgptPage(current)) { fail("UNTRUSTED_NAVIGATION"); return; }
        webView.evaluateJavascript(PROBE_JS, raw -> {
            if (finished) return;
            final ProjectCatalog.Probe result;
            try { result = ProjectCatalog.parseProbe(raw); }
            catch (Throwable error) { fail("PROJECT_RESULT_INVALID"); return; }
            if ("ERROR".equals(result.state)) { fail("DOM_PROBE_FAILED"); return; }
            projectsControlSeen |= result.markerSeen;
            if ("OPENING".equals(result.state)) {
                if (!result.markerSeen) navigationOpenAttempted = true;
                stableProbes = 0;
                lastFingerprint = "";
                scheduleProbe(PROBE_MS);
                return;
            }
            StringBuilder fingerprint = new StringBuilder();
            for (ProjectCatalog.Entry entry : result.entries) fingerprint.append(entry.url).append('\n');
            String value = fingerprint.toString();
            if (value.equals(lastFingerprint)) stableProbes++; else { lastFingerprint = value; stableProbes = 0; }
            if (!result.entries.isEmpty() && stableProbes >= REQUIRED_STABLE_PROBES) {
                succeed(result.entries); return;
            }
            long nowElapsed = System.currentTimeMillis() - passStartedAt;
            if (result.entries.isEmpty() && result.markerSeen
                    && nowElapsed >= EMPTY_SETTLE_MS && stableProbes >= REQUIRED_STABLE_PROBES) {
                fallbackOrFail("PROJECT_LIST_UNRESOLVED");
                return;
            }
            if (result.entries.isEmpty() && !result.markerSeen && nowElapsed >= CONTROL_DISCOVERY_MS) {
                fallbackOrFail(navigationOpenAttempted
                        ? "PROJECTS_CONTROL_NOT_FOUND_AFTER_NAVIGATION"
                        : "PROJECTS_CONTROL_NOT_FOUND");
                return;
            }
            scheduleProbe(PROBE_MS);
        });
    }

    private void fallbackOrFail(String code) {
        if (finished) return;
        if (!desktopFallbackAttempted && webView != null) {
            desktopFallbackAttempted = true;
            resetDiscoveryPass();
            try {
                webView.getSettings().setUserAgentString(DESKTOP_USER_AGENT);
                webView.stopLoading();
                webView.loadUrl(HOME_URL);
            } catch (Throwable error) {
                fail("DESKTOP_FALLBACK_FAILED");
            }
            return;
        }
        fail(code + "_AFTER_DESKTOP_FALLBACK");
    }

    private void resetDiscoveryPass() {
        handler.removeCallbacks(probeRunnable);
        passStartedAt = System.currentTimeMillis();
        stableProbes = 0;
        lastFingerprint = "";
        projectsControlSeen = false;
        navigationOpenAttempted = false;
    }

    private void succeed(List<ProjectCatalog.Entry> entries) { finish(entries, null); }
    private void fail(String code) { finish(null, code); }

    private void finish(List<ProjectCatalog.Entry> entries, String error) {
        if (finished) return;
        finished = true;
        handler.removeCallbacks(probeRunnable);
        Callback cb = callback;
        callback = null;
        if (host != null) host.destroy();
        host = null; webView = null;
        if (cb == null) return;
        if (error == null) cb.onSuccess(entries); else cb.onFailure(error);
    }
}
