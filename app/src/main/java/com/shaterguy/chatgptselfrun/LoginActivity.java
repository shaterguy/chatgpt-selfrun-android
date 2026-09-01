package com.shaterguy.chatgptselfrun;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

public final class LoginActivity extends Activity {
    private static final String OBSERVE_PROJECT_SCRIPT =
            "(()=>{" +
            "if(location.protocol!=='https:'||location.hostname!=='chatgpt.com'||location.port!=='')return '';" +
            "const p=location.pathname.split('/');" +
            "const id=p.length>2&&p[1]==='g'?p[2]:'';" +
            "if(!/^g-p-[A-Za-z0-9_-]+$/.test(id))return JSON.stringify({href:location.href,name:''});" +
            "const canonical='/g/'+id+'/project';" +
            "const clean=v=>String(v||'').replace(/\\s+/g,' ').trim().slice(0,120);" +
            "let name='';" +
            "for(const a of document.querySelectorAll('a[href]')){try{" +
            "const u=new URL(a.getAttribute('href'),location.origin);" +
            "if(u.origin===location.origin&&u.pathname===canonical){const t=clean(a.innerText||a.textContent);if(t){name=t;break;}}" +
            "}catch(e){}}" +
            "const atRoot=location.pathname===canonical||location.pathname===canonical+'/';" +
            "if(!name&&atRoot){const h=document.querySelector('main h1,[role=\"main\"] h1,h1');name=clean(h&&(h.innerText||h.textContent));}" +
            "if(!name&&atRoot){let t=clean(document.title);for(const s of [' | ChatGPT',' - ChatGPT',' · ChatGPT',' — ChatGPT',' – ChatGPT'])if(t.endsWith(s)){t=clean(t.slice(0,-s.length));break;}if(t&&t.toLowerCase()!=='chatgpt')name=t;}" +
            "return JSON.stringify({href:location.href,name:name});" +
            "})()";

    private WebView webView;
    private OnBackInvokedCallback backCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProjectCatalog catalog;
    private TextView status;
    private boolean resumed;
    private int observerGeneration;
    private final Runnable observeRunnable = this::observeVisitedProject;

    @Override
    @SuppressWarnings("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.isMedium(this) ? Ui.dp(this, 20) : Ui.dp(this, 10);
        root.setPadding(side, Ui.dp(this, 6), side, 0);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.addView(Ui.topBar(this, "ChatGPT 세션 · 프로젝트", "프로젝트를 직접 열어 등록합니다",
                Ui.textButton(this, "닫기", v -> finish())));
        status = Ui.muted(this, "프로젝트 방문 대기");
        status.setTextIsSelectable(false);
        heading.addView(status);
        heading.addView(Ui.actionStrip(this,
                Ui.textButton(this, "뒤로", v -> navigateBack()),
                Ui.textButton(this, "새로고침", v -> webView.reload()),
                Ui.outlinedButton(this, "ChatGPT 홈", v -> webView.loadUrl("https://chatgpt.com/"))));
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headingParams.bottomMargin = Ui.dp(this, 6);
        root.addView(heading, headingParams);

        webView = new WebView(this);
        catalog = new ProjectCatalog(this);
        WebViewConfig.applyLogin(webView);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onReceivedTitle(WebView view, String title) { scheduleObservation(80L); }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) { scheduleObservation(100L); }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) { scheduleObservation(100L); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                scheduleObservation(350L);
                return false;
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        Ui.setContent(this, root);
        webView.loadUrl("https://chatgpt.com/");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = this::navigateBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    @Override
    @SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        navigateBack();
    }

    private void navigateBack() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        observerGeneration++;
        scheduleObservation(150L);
    }

    @Override
    protected void onPause() {
        resumed = false;
        observerGeneration++;
        handler.removeCallbacks(observeRunnable);
        super.onPause();
    }

    private void scheduleObservation(long delayMs) {
        if (!resumed || webView == null) return;
        handler.removeCallbacks(observeRunnable);
        handler.postDelayed(observeRunnable, delayMs);
    }

    private void observeVisitedProject() {
        if (!resumed || isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())
                || webView == null) return;
        String current = webView.getUrl();
        if (!ProjectUrlPolicy.isTrustedChatgptPage(current)) return;
        final int epoch = observerGeneration;
        final WebView observed = webView;
        observed.evaluateJavascript(OBSERVE_PROJECT_SCRIPT, raw -> {
            if (!resumed || epoch != observerGeneration || observed != webView || isFinishing()
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) return;
            ProjectVisit visit = jsonProjectVisit(raw);
            ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(visit.href);
            if (ref != null && catalog.addVisitedProject(ref.canonicalUrl, visit.name)) {
                String suffix = visit.name.isEmpty() ? " 등록됨 · 이름 확인 중" : " 등록/업데이트됨";
                status.setText(catalog.displayName(ref) + suffix);
            }
            if (resumed) scheduleObservation(500L);
        });
    }

    private static ProjectVisit jsonProjectVisit(String raw) {
        try {
            Object outer = new org.json.JSONTokener(raw == null ? "" : raw).nextValue();
            org.json.JSONObject result = new org.json.JSONObject(
                    outer instanceof String ? (String) outer : String.valueOf(outer));
            return new ProjectVisit(result.optString("href", ""), result.optString("name", ""));
        } catch (Throwable ignored) {
            return new ProjectVisit("", "");
        }
    }

    private static final class ProjectVisit {
        final String href;
        final String name;
        ProjectVisit(String href, String name) {
            this.href = href == null ? "" : href;
            this.name = ProjectCatalog.normalizeDisplayName(name);
        }
    }

    @Override
    protected void onDestroy() {
        resumed = false;
        observerGeneration++;
        handler.removeCallbacks(observeRunnable);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
        if (webView != null) {
            CookieManager.getInstance().flush();
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
