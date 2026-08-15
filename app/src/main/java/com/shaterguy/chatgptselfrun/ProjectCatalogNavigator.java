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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves ChatGPT Project links by entering each visible project and reading WebView.getUrl(). */
final class ProjectCatalogNavigator {
    interface Callback {
        void onSuccess(List<ProjectCatalog.Entry> entries);
        void onFailure(String code);
    }

    private enum Phase { DISCOVER, WAIT_PROJECT, RETURNING }

    private static final String HOME_URL = "https://chatgpt.com/";
    private static final long DISCOVERY_POLL_MS = 400L;
    private static final long NAVIGATION_POLL_MS = 150L;
    private static final long PROJECT_TIMEOUT_MS = 10_000L;
    private static final long RETURN_TIMEOUT_MS = 8_000L;
    private static final long EMPTY_TIMEOUT_MS = 10_000L;
    private static final int MAX_ROW_RETRIES = 2;
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36 SelfRunDrive/"
                    + BuildConfig.VERSION_NAME;

    private static final String RESET_JS = """
            (function(){try{
              delete window.__selfrunCandidateMap;
              delete window.__selfrunProjectOpenTried;
              delete window.__selfrunBeforeProjectItems;
              delete window.__selfrunSidebarOpenTried;
            }catch(_){}
            return true;
            })();
            """;

    private static final String SCAN_TEMPLATE = """
            (function(visited){try{
              const done=new Set(Array.isArray(visited)?visited:[]);
              const clean=v=>String(v??'').trim().replace(/\\s+/g,' ');
              const visible=e=>!!e&&e.isConnected&&e.getClientRects&&e.getClientRects().length>0
                &&getComputedStyle(e).display!=='none'&&getComputedStyle(e).visibility!=='hidden';
              const text=e=>clean(e?.innerText||e?.textContent||'');
              const desc=e=>clean((e?.getAttribute?.('aria-label')||'')+' '+text(e));
              const vals=e=>{const a=[];if(!e?.getAttribute)return a;
                for(const k of ['href','data-href','data-url','data-path','data-to']){
                  const v=e.getAttribute(k);if(v)a.push(v);
                }return a;};
              const project=raw=>{try{
                const u=new URL(String(raw),location.href);
                if(u.protocol!=='https:'||!/^(www\\.)?chatgpt\\.com$/i.test(u.hostname))return '';
                const p=u.pathname.split('/').filter(Boolean);
                if(p.length<2||p[0]!=='g'||!/^g-p-[A-Za-z0-9_-]+$/.test(p[1]))return '';
                const t=p.slice(2);
                if(!(t.length===0||(t.length===1&&t[0]==='project')))return '';
                return 'https://chatgpt.com/g/'+p[1]+'/project';
              }catch(_){return '';}};
              const projectContext=raw=>{try{
                const u=new URL(String(raw),location.href);
                if(u.protocol!=='https:'||!/^(www\\.)?chatgpt\\.com$/i.test(u.hostname))return '';
                const p=u.pathname.split('/').filter(Boolean);
                if(p.length<2||p[0]!=='g'||!/^g-p-[A-Za-z0-9_-]+$/.test(p[1]))return '';
                const t=p.slice(2);
                return (t.length===0||(t.length===1&&t[0]==='project')
                  ||(t.length===2&&t[0]==='c'&&!!t[1]))
                  ?'https://chatgpt.com/g/'+p[1]+'/project':'';
              }catch(_){return '';}};

              const controls=[...document.querySelectorAll(
                'button,a,[role=button],[role=link],[role=menuitem],[role=treeitem],[role=option],[tabindex]'
              )].filter(visible);
              const pc=controls.find(e=>/^(projects?|프로젝트)(?:\\s|$)/i.test(desc(e))
                &&!vals(e).some(v=>!!projectContext(v)))||null;
              if(!pc){
                const nav=controls.find(e=>{
                  const d=desc(e),id=clean(e?.dataset?.testid||'');
                  return id==='open-sidebar-button'
                    ||/(?:open|show|expand).*(?:sidebar|navigation)|(?:sidebar|navigation).*(?:open|show|expand)|사이드바.*(?:열기|펼치)|(?:열기|펼치).*(?:사이드바|메뉴|탐색)/i.test(d);
                })||null;
                if(nav&&!window.__selfrunSidebarOpenTried){
                  window.__selfrunSidebarOpenTried=true;nav.click();
                  return JSON.stringify({state:'OPENING',marker:false});
                }
                return JSON.stringify({state:'EMPTY',marker:false});
              }

              const generic=/^(?:projects?|프로젝트|new project|create project|새 프로젝트|프로젝트 만들기|search|검색|close|닫기|more|more options?|options?|menu|메뉴|더 보기|settings?|설정|project settings?|프로젝트 설정|view all|모두 보기)$/i;
              const controlled=()=>{
                for(const k of ['aria-controls','aria-owns']){
                  for(const id of clean(pc.getAttribute?.(k)||'').split(/\\s+/).filter(Boolean)){
                    const x=document.getElementById(id);if(x&&visible(x))return x;
                  }
                }return null;
              };
              const portals=()=>[...document.querySelectorAll(
                '[role=dialog],[role=menu],[role=listbox],[data-radix-popper-content-wrapper]'
              )].filter(visible);
              const selector='a[href],button,[role=button],[role=link],[role=menuitem],[role=treeitem],[role=option],[tabindex],[data-href],[data-url],[data-path],[data-to]';
              const rows=(scope,newOnly)=>{
                const out=[],roots=new Set(),names=new Map();
                for(const e of scope.querySelectorAll?.(selector)||[]){
                  if(!visible(e)||e===pc||pc.contains(e))continue;
                  if(newOnly&&window.__selfrunBeforeProjectItems?.has?.(e))continue;
                  let root=e.closest?.('li,[role=listitem],[role=menuitem],[role=treeitem],[role=option]')||e;
                  if(!scope.contains(root))root=e;if(roots.has(root))continue;
                  const all=[...vals(e),...(root===e?[]:vals(root))];
                  const direct=all.map(project).find(Boolean)||'';
                  const ctx=all.map(projectContext).find(Boolean)||'';
                  if(ctx&&!direct)continue;
                  if(all.length&&!direct&&all.some(v=>{try{
                    const u=new URL(String(v),location.href);return /^https?:$/.test(u.protocol);
                  }catch(_){return false;}}))continue;
                  let name=clean(e.getAttribute?.('aria-label')||e.getAttribute?.('title')||'');
                  if(!name||generic.test(name))name=text(e);
                  if((!name||generic.test(name))&&root!==e)name=text(root).split(/\\r?\\n/).map(clean).find(x=>x&&!generic.test(x))||'';
                  if(!name||generic.test(name)||name.length>300)continue;
                  roots.add(root);
                  const n=name.toLowerCase(),occ=(names.get(n)||0)+1;names.set(n,occ);
                  const stable=clean(root.dataset?.testid||root.dataset?.id||root.getAttribute?.('aria-posinset')||root.dataset?.index||root.id||'');
                  const role=clean(root.getAttribute?.('role')||e.getAttribute?.('role')||e.tagName||'');
                  const r=root.getBoundingClientRect(),scroll=scope===document?(document.scrollingElement?.scrollTop||0):(scope.scrollTop||0);
                  const top=Math.round((r.top+scroll)/8)*8;
                  const key=[n,direct,stable,role,String(top),String(occ)].join('|');
                  out.push({key,name,direct,target:e,root});
                }return out;
              };

              let scope=controlled(),items=scope?rows(scope,false):[];
              if(!items.length){
                const expanded=pc.getAttribute?.('aria-expanded')==='true';
                if(!window.__selfrunProjectOpenTried&&!expanded){
                  window.__selfrunBeforeProjectItems=new WeakSet([...document.querySelectorAll(selector)].filter(visible));
                  window.__selfrunProjectOpenTried=true;pc.click();
                  return JSON.stringify({state:'OPENING',marker:true});
                }
                const newOnly=!!window.__selfrunBeforeProjectItems;
                for(const p of portals()){items=rows(p,newOnly);if(items.length){scope=p;break;}}
                if(!items.length){scope=document;items=rows(document,newOnly);}
              }
              if(!items.length){
                if(!window.__selfrunProjectOpenTried&&pc.getAttribute?.('aria-expanded')!=='true'){
                  window.__selfrunBeforeProjectItems=new WeakSet([...document.querySelectorAll(selector)].filter(visible));
                  window.__selfrunProjectOpenTried=true;pc.click();
                  return JSON.stringify({state:'OPENING',marker:true});
                }
                return JSON.stringify({state:'EMPTY',marker:true});
              }

              window.__selfrunCandidateMap=new Map(items.map(x=>[x.key,x.target]));
              for(const x of items)if(!done.has(x.key))
                return JSON.stringify({state:'READY',marker:true,candidate:{key:x.key,name:x.name,directUrl:x.direct}});

              let scroller=null,node=items[0]?.root;
              while(node&&node!==document.body){
                const s=getComputedStyle(node);
                if((s.overflowY==='auto'||s.overflowY==='scroll')&&node.scrollHeight>node.clientHeight+8){scroller=node;break;}
                if(node===scope)break;node=node.parentElement;
              }
              if(!scroller&&scope!==document&&scope.scrollHeight>scope.clientHeight+8)scroller=scope;
              if(scroller){
                const max=scroller.scrollHeight-scroller.clientHeight,before=scroller.scrollTop;
                if(before<max-8){
                  scroller.scrollTop=Math.min(max,before+Math.max(200,Math.floor(scroller.clientHeight*.75)));
                  return JSON.stringify({state:'SCROLLED',marker:true});
                }
              }
              return JSON.stringify({state:'COMPLETE',marker:true});
            }catch(e){return JSON.stringify({state:'ERROR',marker:false});}})(__VISITED__);
            """;

    private static final String CLICK_TEMPLATE = """
            (function(key){try{
              const e=window.__selfrunCandidateMap?.get?.(key);
              if(!e||!e.isConnected||!e.getClientRects||e.getClientRects().length===0)
                return JSON.stringify({clicked:false});
              e.focus?.();e.click();return JSON.stringify({clicked:true});
            }catch(_){return JSON.stringify({clicked:false});}})(__KEY__);
            """;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stepRunnable = this::step;
    private final Set<String> visitedKeys = new LinkedHashSet<>();
    private final Set<String> collectedUrls = new LinkedHashSet<>();
    private final List<ProjectCatalog.Entry> collected = new ArrayList<>();

    private HeadlessWebViewHost host;
    private WebView webView;
    private Callback callback;
    private Phase phase = Phase.DISCOVER;
    private Candidate candidate;
    private String beforeUrl = "";
    private long phaseStartedAt;
    private long emptyStartedAt;
    private int rowRetryCount;
    private boolean desktopFallback;
    private boolean recoveredHome;
    private boolean returningHomeFallback;
    private boolean finished;

    ProjectCatalogNavigator(Context context) { this.context = context; }

    void start(Callback callback) {
        if (this.callback != null) throw new IllegalStateException("project navigator already started");
        this.callback = callback;
        phaseStartedAt = System.currentTimeMillis();
        try {
            host = HeadlessWebViewHost.create(context);
            webView = host.webView();
            WebViewConfig.applyAutomation(webView);
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                    if (!ProjectCatalog.isTrustedChatgptPage(url)) fail("UNTRUSTED_NAVIGATION");
                }
                @Override public void onPageFinished(WebView view, String url) {
                    if (!ProjectCatalog.isTrustedChatgptPage(url)) { fail("UNTRUSTED_NAVIGATION"); return; }
                    schedule(200L);
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (!request.isForMainFrame()) return false;
                    if (!ProjectCatalog.isTrustedChatgptPage(String.valueOf(request.getUrl()))) {
                        fail(phase == Phase.WAIT_PROJECT ? "UNTRUSTED_PROJECT_ROW_NAVIGATION" : "UNTRUSTED_NAVIGATION");
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
            webView.loadUrl(HOME_URL);
        } catch (Throwable error) {
            fail("WEBVIEW_START_FAILED");
        }
    }

    void cancel() { finish(null, "CANCELLED"); }

    static String scanScriptForTesting(Set<String> visited) { return scanScript(visited == null ? new LinkedHashSet<>() : visited); }
    static String clickScriptForTesting(String key) { return clickScript(key); }

    private static String scanScript(Set<String> visited) {
        JSONArray array = new JSONArray();
        for (String key : visited) array.put(key);
        return SCAN_TEMPLATE.replace("__VISITED__", array.toString());
    }
    private static String clickScript(String key) {
        return CLICK_TEMPLATE.replace("__KEY__", JSONObject.quote(key == null ? "" : key));
    }

    private void schedule(long delay) {
        if (finished) return;
        handler.removeCallbacks(stepRunnable);
        handler.postDelayed(stepRunnable, delay);
    }

    private void step() {
        if (finished || webView == null) return;
        if (phase == Phase.DISCOVER) discover();
        else if (phase == Phase.WAIT_PROJECT) waitProject();
        else waitReturn();
    }

    private void discover() {
        String current = webView.getUrl();
        if (!ProjectCatalog.isTrustedChatgptPage(current)) { fail("UNTRUSTED_NAVIGATION"); return; }
        if (!ProjectCatalog.canonicalProjectUrl(current).isEmpty()) { beginReturn(); return; }

        webView.evaluateJavascript(scanScript(visitedKeys), raw -> {
            if (finished || phase != Phase.DISCOVER) return;
            final Scan scan;
            try { scan = parseScan(raw); }
            catch (Throwable error) { fail("PROJECT_RESULT_INVALID"); return; }
            switch (scan.state) {
                case "OPENING":
                case "SCROLLED":
                    emptyStartedAt = 0L; schedule(DISCOVERY_POLL_MS); return;
                case "READY":
                    emptyStartedAt = 0L; click(scan.candidate); return;
                case "COMPLETE":
                    if (collected.isEmpty() && !desktopFallback) recoverOrDesktop("PROJECT_LIST_UNRESOLVED");
                    else succeed();
                    return;
                case "EMPTY":
                    long now=System.currentTimeMillis();
                    if(emptyStartedAt==0L)emptyStartedAt=now;
                    if(now-emptyStartedAt<EMPTY_TIMEOUT_MS){schedule(DISCOVERY_POLL_MS);return;}
                    recoverOrDesktop(scan.marker ? "PROJECT_LIST_UNRESOLVED" : "PROJECTS_CONTROL_NOT_FOUND");
                    return;
                default:
                    fail("DOM_PROBE_FAILED");
            }
        });
    }

    private void click(Candidate next) {
        if (next == null) { fail("PROJECT_RESULT_INVALID"); return; }
        candidate = next;
        beforeUrl = webView.getUrl();
        phase = Phase.WAIT_PROJECT;
        phaseStartedAt = System.currentTimeMillis();
        webView.evaluateJavascript(clickScript(next.key), raw -> {
            if (finished || phase != Phase.WAIT_PROJECT || candidate != next) return;
            try {
                if (!decode(raw).optBoolean("clicked", false)) {
                    candidate=null;phase=Phase.DISCOVER;schedule(DISCOVERY_POLL_MS);return;
                }
            } catch (Throwable error) {
                fail("PROJECT_CLICK_RESULT_INVALID");return;
            }
            schedule(NAVIGATION_POLL_MS);
        });
    }

    private void waitProject() {
        String current = webView.getUrl();
        if (!ProjectCatalog.isTrustedChatgptPage(current)) { fail("UNTRUSTED_PROJECT_ROW_NAVIGATION"); return; }
        String canonical = ProjectCatalog.canonicalProjectUrl(current);
        if (!canonical.isEmpty()) {
            if (!candidate.directUrl.isEmpty() && !candidate.directUrl.equals(canonical)) {
                fail("PROJECT_ROW_URL_MISMATCH"); return;
            }
            visitedKeys.add(candidate.key);
            rowRetryCount=0;
            if (collectedUrls.add(canonical)) collected.add(new ProjectCatalog.Entry(candidate.name,canonical));
            recoveredHome=false;
            beginReturn();
            return;
        }
        if (System.currentTimeMillis()-phaseStartedAt>=PROJECT_TIMEOUT_MS) {
            rowRetryCount++;
            if (rowRetryCount>=MAX_ROW_RETRIES) { fail("PROJECT_ROW_NAVIGATION_TIMEOUT"); return; }
            if (current != null && current.equals(beforeUrl)) {
                candidate=null;phase=Phase.DISCOVER;emptyStartedAt=0L;schedule(DISCOVERY_POLL_MS);
            } else beginReturn();
            return;
        }
        schedule(NAVIGATION_POLL_MS);
    }

    private void beginReturn() {
        phase=Phase.RETURNING;
        phaseStartedAt=System.currentTimeMillis();
        returningHomeFallback=false;
        try {
            if(webView.canGoBack())webView.goBack(); else webView.loadUrl(HOME_URL);
            schedule(200L);
        } catch(Throwable error) { fail("PROJECT_LIST_RETURN_FAILED"); }
    }

    private void waitReturn() {
        String current=webView.getUrl();
        if(!ProjectCatalog.isTrustedChatgptPage(current)){fail("UNTRUSTED_NAVIGATION");return;}
        if(ProjectCatalog.canonicalProjectUrl(current).isEmpty()){resetAndResume();return;}
        if(System.currentTimeMillis()-phaseStartedAt<RETURN_TIMEOUT_MS){schedule(200L);return;}
        if(!returningHomeFallback){
            returningHomeFallback=true;phaseStartedAt=System.currentTimeMillis();
            try{webView.stopLoading();webView.loadUrl(HOME_URL);schedule(200L);}
            catch(Throwable error){fail("PROJECT_LIST_RETURN_FAILED");}
            return;
        }
        fail("PROJECT_LIST_RETURN_FAILED");
    }

    private void resetAndResume() {
        webView.evaluateJavascript(RESET_JS, ignored -> {
            if(finished)return;
            candidate=null;beforeUrl="";phase=Phase.DISCOVER;emptyStartedAt=0L;
            phaseStartedAt=System.currentTimeMillis();schedule(DISCOVERY_POLL_MS);
        });
    }

    private void recoverOrDesktop(String code) {
        if(!collected.isEmpty()&&!recoveredHome){
            recoveredHome=true;reload(false);return;
        }
        if(!desktopFallback){
            desktopFallback=true;visitedKeys.clear();rowRetryCount=0;
            try{webView.getSettings().setUserAgentString(DESKTOP_USER_AGENT);webView.clearHistory();}
            catch(Throwable ignored){}
            reload(true);return;
        }
        fail(code+"_AFTER_DESKTOP_FALLBACK");
    }

    private void reload(boolean clearRecoveredHome) {
        handler.removeCallbacks(stepRunnable);
        candidate=null;beforeUrl="";phase=Phase.DISCOVER;emptyStartedAt=0L;
        if(clearRecoveredHome)recoveredHome=false;
        try{webView.stopLoading();webView.loadUrl(HOME_URL);}
        catch(Throwable error){fail("PROJECT_HOME_RECOVERY_FAILED");}
    }

    private void succeed(){finish(new ArrayList<>(collected),null);}
    private void fail(String code){finish(null,code);}

    private void finish(List<ProjectCatalog.Entry> entries,String error){
        if(finished)return;finished=true;handler.removeCallbacks(stepRunnable);
        Callback cb=callback;callback=null;
        if(host!=null)host.destroy();host=null;webView=null;candidate=null;
        if(cb==null)return;if(error==null)cb.onSuccess(entries);else cb.onFailure(error);
    }

    private static Scan parseScan(String raw)throws JSONException{
        JSONObject o=decode(raw);String state=o.optString("state","");
        if(!("OPENING".equals(state)||"SCROLLED".equals(state)||"READY".equals(state)
                ||"COMPLETE".equals(state)||"EMPTY".equals(state)||"ERROR".equals(state)))
            throw new JSONException("unexpected project crawl state");
        Candidate c=null;
        if("READY".equals(state)){
            JSONObject x=o.optJSONObject("candidate");if(x==null)throw new JSONException("missing candidate");
            String key=x.optString("key","").trim(),name=x.optString("name","").trim().replaceAll("\\s+"," ");
            String direct=x.optString("directUrl","").trim();
            if(key.isEmpty()||key.length()>2048)throw new JSONException("bad candidate key");
            if(name.isEmpty())name="프로젝트";if(name.length()>ProjectCatalog.MAX_NAME_CHARS)name=name.substring(0,ProjectCatalog.MAX_NAME_CHARS);
            if(!direct.isEmpty()){direct=ProjectCatalog.canonicalProjectUrl(direct);if(direct.isEmpty())throw new JSONException("bad candidate url");}
            c=new Candidate(key,name,direct);
        }
        return new Scan(state,o.optBoolean("marker",false),c);
    }

    private static JSONObject decode(String raw)throws JSONException{
        if(raw==null||raw.length()>2_000_000)throw new JSONException("missing result");
        Object x=new JSONTokener(raw).nextValue();
        if(x instanceof String)x=new JSONTokener((String)x).nextValue();
        if(!(x instanceof JSONObject))throw new JSONException("not object");
        return (JSONObject)x;
    }

    private static final class Candidate{
        final String key,name,directUrl;
        Candidate(String key,String name,String directUrl){this.key=key;this.name=name;this.directUrl=directUrl;}
    }
    private static final class Scan{
        final String state;final boolean marker;final Candidate candidate;
        Scan(String state,boolean marker,Candidate candidate){this.state=state;this.marker=marker;this.candidate=candidate;}
    }
}
