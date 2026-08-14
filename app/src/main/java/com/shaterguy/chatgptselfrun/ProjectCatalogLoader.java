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
    private static final int REQUIRED_STABLE_PROBES = 3;

    private static final String PROBE_JS = "(function(){try{"
            + "const t=e=>((e.innerText||e.textContent||e.getAttribute('aria-label')||'')+'').trim().replace(/\\s+/g,' ');"
            + "const isProjectHref=e=>{try{if(!e||!e.getAttribute)return false;const h=e.getAttribute('href');if(!h)return false;const u=new URL(h,location.href);return /^\\/g\\/g-p-[A-Za-z0-9_-]+(?:\\/project)?\\/?$/.test(u.pathname);}catch(x){return false;}};"
            + "const controls=[...document.querySelectorAll('button,[role=button],a')];"
            + "const isProjectsControl=e=>/^(projects?|프로젝트)(?:\\s|$)/i.test(t(e))&&!isProjectHref(e);"
            + "const marker=controls.some(isProjectsControl);"
            + "if(!window.__selfrunProjectsOpenAttempted){const open=controls.find(isProjectsControl);"
            + "if(open){window.__selfrunProjectsOpenAttempted=true;open.click();return JSON.stringify({state:'OPENING',marker:true,entries:[]});}}"
            + "const more=controls.find(e=>/^(show more|view all|see all|더 보기|모두 보기)(?:\\s|$)/i.test(t(e)));if(more)more.click();"
            + "const out=[];const seen=new Set();for(const a of document.querySelectorAll('a[href]')){try{"
            + "const u=new URL(a.getAttribute('href'),location.href);if(u.protocol!=='https:'||!/^(www\\.)?chatgpt\\.com$/i.test(u.hostname))continue;"
            + "if(!/^\\/g\\/g-p-[A-Za-z0-9_-]+(?:\\/project)?\\/?$/.test(u.pathname))continue;"
            + "const key=u.pathname;if(seen.has(key))continue;seen.add(key);let name=t(a);if(!name)name='프로젝트';out.push({name:name,url:u.href});}catch(x){}}"
            + "for(const e of document.querySelectorAll('*')){try{const s=getComputedStyle(e);if((s.overflowY==='auto'||s.overflowY==='scroll')&&e.scrollHeight>e.clientHeight+8)e.scrollTop=e.scrollHeight;}catch(x){}}"
            + "return JSON.stringify({state:out.length?'FOUND':'EMPTY',marker:marker,entries:out});"
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

    private void scheduleProbe(long delay) {
        if (finished) return;
        handler.removeCallbacks(probeRunnable);
        handler.postDelayed(probeRunnable, delay);
    }

    private void probe() {
        if (finished || webView == null) return;
        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed > TIMEOUT_MS) { fail("REFRESH_TIMEOUT"); return; }
        String current = webView.getUrl();
        if (!ProjectCatalog.isTrustedChatgptPage(current)) { fail("UNTRUSTED_NAVIGATION"); return; }
        webView.evaluateJavascript(PROBE_JS, raw -> {
            if (finished) return;
            final ProjectCatalog.Probe result;
            try { result = ProjectCatalog.parseProbe(raw); }
            catch (Throwable error) { fail("PROJECT_RESULT_INVALID"); return; }
            if ("ERROR".equals(result.state)) { fail("DOM_PROBE_FAILED"); return; }
            if ("OPENING".equals(result.state)) {
                stableProbes = 0; lastFingerprint = ""; scheduleProbe(PROBE_MS); return;
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
