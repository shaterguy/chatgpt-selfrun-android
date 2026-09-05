package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.webkit.ServiceWorkerClientCompat;
import androidx.webkit.ServiceWorkerControllerCompat;
import androidx.webkit.WebViewFeature;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Work/HYBRID native request observer. It never intercepts, proxies, or reconstructs traffic. */
final class WorkProtocolNativeObserver {
    static final String SOURCE_WEBVIEW = "native_webview";
    static final String SOURCE_SERVICE_WORKER = "native_service_worker";
    static final String ROUTE_CANONICAL_CONVERSATION = "canonical_conversation";
    private static final Set<String> CHATGPT_HOSTS = Set.of("chatgpt.com", "www.chatgpt.com");
    private static volatile boolean serviceWorkerClientInstalled;
    private static String environmentRunId = "";

    private WorkProtocolNativeObserver() {}

    static void installProcess(Context context) {
        if (context == null || serviceWorkerClientInstalled) return;
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) {
            return;
        }
        synchronized (WorkProtocolNativeObserver.class) {
            if (serviceWorkerClientInstalled) return;
            Context app = context.getApplicationContext();
            try {
                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
                        new ServiceWorkerClientCompat() {
                            @Override public WebResourceResponse shouldInterceptRequest(
                                    WebResourceRequest request) {
                                observeCanonical(app, null, request, SOURCE_SERVICE_WORKER);
                                return null;
                            }
                        });
                serviceWorkerClientInstalled = true;
            } catch (Throwable ignored) {
                // Optional Work observation failure must never disable the response protocol or normal Chat.
            }
        }
    }

    static WebResourceResponse observeWebViewRequest(
            Context context, WebView view, WebResourceRequest request) {
        observeCanonical(context, view, request, SOURCE_WEBVIEW);
        return null;
    }

    static void recordEnvironmentIfWork(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        SelfRunStore store = new SelfRunStore(app);
        if (store.active() && observableMode(store.mode())) recordEnvironment(app, store);
    }

    static boolean isCanonical(String method, String rawUrl) {
        if (!"POST".equalsIgnoreCase(String.valueOf(method))) return false;
        try {
            URI uri = URI.create(String.valueOf(rawUrl));
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if (host == null || !CHATGPT_HOSTS.contains(host.toLowerCase(Locale.ROOT))) return false;
            String path = uri.getPath();
            if (path == null || path.isEmpty()) return false;
            while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return "/backend-api/f/conversation".equals(path);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean observablePhase(String phase) {
        return SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)
                || SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)
                || SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase);
    }

    static boolean observableMode(String mode) {
        return SelfRunStore.MODE_WORK.equals(mode)
                || HybridRunProfileStore.MODE_HYBRID.equals(mode);
    }

    private static boolean eligible(SelfRunStore store) {
        return store != null && store.active() && observableMode(store.mode())
                && observablePhase(store.phase());
    }

    private static void observeCanonical(
            Context context, WebView callbackView, WebResourceRequest request, String source) {
        if (context == null || request == null || request.getUrl() == null) return;
        if (!isCanonical(request.getMethod(), request.getUrl().toString())) return;
        Context app = context.getApplicationContext();
        SelfRunStore store = new SelfRunStore(app);
        if (!eligible(store)) return;
        String runId = store.runId();
        if (runId == null || runId.isEmpty()) return;
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            SelfRunStore current = new SelfRunStore(app);
            if (!runId.equals(current.runId()) || !eligible(current)) return;
            recordEnvironment(app, current);
            if (HybridRunProfileStore.MODE_HYBRID.equals(current.mode())) {
                new SelfRunRunLog(app).record(current, "HYBRID_REQUEST_TRANSPORT",
                        "source=" + source + ";transport=" + source
                                + ";route=" + ROUTE_CANONICAL_CONVERSATION
                                + ";outcome=canonical_request;body=unavailable");
                return;
            }
            WorkProtocolCoverageTracker.observeNativeRequest(app, current, source);
            new SelfRunRunLog(app).record(current, "WORK_PROTOCOL_TRANSPORT",
                    "source=" + source + ";transport=" + source
                            + ";route=" + ROUTE_CANONICAL_CONVERSATION
                            + ";outcome=canonical_request");
            WebView active = HeadlessWebViewHost.activeWebView();
            WebView target = callbackView != null ? callbackView : active;
            if (target == null || target != active) return;
            String script = "(()=>{const capture=window.__selfRunWorkProtocolTransportCapture;"
                    + "if(!capture||typeof capture.observeNativeCanonical!=='function')return false;"
                    + "return !!capture.observeNativeCanonical("
                    + SelfRunScript.quote(source) + "," + SelfRunScript.quote(runId) + ");})()";
            try { target.evaluateJavascript(script, null); } catch (Throwable ignored) {}
        });
    }

    private static synchronized void recordEnvironment(Context context, SelfRunStore store) {
        String runId = store.runId();
        if (runId.equals(environmentRunId)) return;
        environmentRunId = runId;
        PackageInfo info = null;
        try { info = WebView.getCurrentWebViewPackage(); } catch (Throwable ignored) {}
        String packageName = info == null ? "unavailable" : safe(info.packageName);
        String version = info == null ? "unavailable" : safe(info.versionName);
        String detail = "apiLevel=" + Build.VERSION.SDK_INT
                + ";webViewPackage=" + packageName
                + ";webViewVersion=" + version
                + ";documentStart=" + supported(WebViewFeature.DOCUMENT_START_SCRIPT)
                + ";webMessageListener=" + supported(WebViewFeature.WEB_MESSAGE_LISTENER)
                + ";serviceWorker=" + supported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)
                + ";serviceWorkerIntercept=" + supported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
                + ";serviceWorkerClientInstalled=" + serviceWorkerClientInstalled;
        new SelfRunRunLog(context).record(store,
                HybridRunProfileStore.MODE_HYBRID.equals(store.mode())
                        ? "HYBRID_REQUEST_ENV" : "WORK_PROTOCOL_ENV", detail);
    }

    private static boolean supported(String feature) {
        try { return WebViewFeature.isFeatureSupported(feature); } catch (Throwable ignored) { return false; }
    }

    private static String safe(String value) {
        if (value == null) return "";
        String safe = value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }
}
