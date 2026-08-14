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

    private static final String PROBE_JS = "(function(){try{"
            + "const clean=s=>String(s??'').trim().replace(/\\s+/g,' ');const text=e=>clean(e?.innerText||e?.textContent||'');const aria=e=>clean(e?.getAttribute?.('aria-label')||'');const desc=e=>clean((aria(e)+' '+text(e)).trim());"
            + "const visible=e=>!!e&&e.isConnected&&e.getClientRects&&e.getClientRects().length>0;"
            + "const isProjectHref=e=>{try{if(!e||!e.getAttribute)return false;const h=e.getAttribute('href');if(!h)return false;const u=new URL(h,location.href);return /^\\/g\\/g-p-[A-Za-z0-9_-]+(?:\\/project)?\\/?$/.test(u.pathname);}catch(x){return false;}};"
            + "const controls=[...document.querySelectorAll('button,[role=button],[role=menuitem],[role=treeitem],a')].filter(visible);"
            + "const isProjectsControl=e=>/^(projects?|프로젝트)(?:\\s|$)/i.test(desc(e))&&!isProjectHref(e);"
            + "const projectControl=controls.find(isProjectsControl)||null;const marker=!!projectControl;"
            + "const collect=()=>{const out=[],seen=new Set();for(const a of document.querySelectorAll('a[href]')){try{const u=new URL(a.getAttribute('href'),location.href);if(u.protocol!=='https:'||!/^(www\\.)?chatgpt\\.com$/i.test(u.hostname))continue;if(!/^\\/g\\/g-p-[A-Za-z0-9_-]+(?:\\/project)?\\/?$/.test(u.pathname))continue;const key=u.pathname.replace(/\\/$/,'');if(seen.has(key))continue;seen.add(key);let name=text(a)||aria(a);if(!name)name='프로젝트';out.push({name:name,url:u.href});}catch(x){}}return out;};"
            + "let out=collect();if(out.length)return JSON.stringify({state:'FOUND',marker:marker,entries:out});"
            + "if(projectControl&&!window.__selfrunProjectsOpenAttempted){window.__selfrunProjectsOpenAttempted=true;projectControl.focus?.();projectControl.click();return JSON.stringify({state:'OPENING',marker:true,entries:[]});}"
            + "const more=controls.find(e=>/^(show more|view all|see all|더 보기|모두 보기)(?:\\s|$)/i.test(desc(e)));"
            + "if(projectControl&&more&&!window.__selfrunProjectsMoreAttempted){window.__selfrunProjectsMoreAttempted=true;more.focus?.();more.click();return JSON.stringify({state:'OPENING',marker:true,entries:[]});}"
            + "for(const e of document.querySelectorAll('*')){try{const s=getComputedStyle(e);if((s.overflowY==='auto'||s.overflowY==='scroll')&&e.scrollHeight>e.clientHeight+8)e.scrollTop=e.scrollHeight;}catch(x){}}"
            + "out=collect();if(out.length)return JSON.stringify({state:'FOUND',marker:marker,entries:out});"
            + "if(!projectControl){const candidates=[];const add=e=>{if(e&&visible(e)&&!candidates.includes(e))candidates.push(e);};"
            + "for(const sel of ['[data-testid=\"open-sidebar-button\"]','button[aria-label*=\"sidebar\" i]','[role=button][aria-label*=\"sidebar\" i]','button[aria-label*=\"navigation\" i]','[role=button][aria-label*=\"navigation\" i]']){try{document.querySelectorAll(sel).forEach(add);}catch(x){}}"
            + "controls.forEach(add);const isNavOpener=e=>{const v=desc(e).toLowerCase(),id=String(e?.dataset?.testid||'').toLowerCase(),expanded=e?.getAttribute?.('aria-expanded');if(id==='open-sidebar-button')return true;if(expanded==='true'&&!/(open|show|expand|열기|펼치)/i.test(v))return false;return /(?:open|show|expand).*(?:sidebar|navigation)|(?:sidebar|navigation).*(?:open|show|expand)|사이드바.*(?:열기|펼치기)|(?:열기|펼치기).*사이드바|(?:메뉴|탐색).*(?:열기|펼치기)|(?:열기|펼치기).*(?:메뉴|탐색)/i.test(v);};"
            + "const nav=candidates.find(isNavOpener)||null;if(nav&&!window.__selfrunSidebarOpenAttempted){window.__selfrunSidebarOpenAttempted=true;nav.focus?.();nav.click();return JSON.stringify({state:'OPENING',marker:false,entries:[]});}}"
            + "return JSON.stringify({state:'EMPTY',marker:marker,entries:[]});"
            + "}catch(e){return JSON.stringify({state:'ERROR',marker:false,entries:[]});}})();";

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable probeRunnable = this::probe;
    private HeadlessWebViewHost host;
    private WebView webView;
    private Callback callback;
    private long startedAt;
    private int stableProbes;
    private String lastFingerprint = "";
    private boolean projectsControlSeen;
    private boolean navigationOpenAttempted;
    private boolean finished;

    ProjectCatalogLoader(Context context) { this.context = context; }

    void start(Callback callback) {
        if (this.callback != null) throw new IllegalStateException("project loader already started");
        this.callback = callback;
        this.startedAt = System.currentTimeMillis();
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
        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed > TIMEOUT_MS) {
            fail(projectsControlSeen ? "REFRESH_TIMEOUT" : "PROJECTS_CONTROL_NOT_FOUND");
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
            long nowElapsed = System.currentTimeMillis() - startedAt;
            if (result.entries.isEmpty() && result.markerSeen
                    && nowElapsed >= EMPTY_SETTLE_MS && stableProbes >= REQUIRED_STABLE_PROBES) {
                succeed(result.entries); return;
            }
            if (result.entries.isEmpty() && !result.markerSeen && nowElapsed >= CONTROL_DISCOVERY_MS) {
                fail(navigationOpenAttempted
                        ? "PROJECTS_CONTROL_NOT_FOUND_AFTER_NAVIGATION"
                        : "PROJECTS_CONTROL_NOT_FOUND");
                return;
            }
            scheduleProbe(PROBE_MS);
        });
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
