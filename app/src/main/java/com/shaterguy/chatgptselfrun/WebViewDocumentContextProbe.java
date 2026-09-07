package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.webkit.WebView;

import org.json.JSONTokener;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Passive URL/document identity probe. Never reads message or input contents. */
final class WebViewDocumentContextProbe {
    static final String EVENT = "WEBVIEW_CONTEXT";

    private WebViewDocumentContextProbe() {}

    static void record(Context context, WebView view, String stage, String eventUrl) {
        if (context == null || view == null) return;
        Context app = context.getApplicationContext();
        SelfRunStore store = new SelfRunStore(app);
        String runId = store.runId();
        if (runId.isEmpty()) return;
        SelfRunRunLog log = new SelfRunRunLog(app);
        String prefix = "st=" + safeStage(stage)
                + ";wv=" + Integer.toHexString(System.identityHashCode(view))
                + ";nr=" + routeSignature(view.getUrl())
                + ";er=" + routeSignature(eventUrl) + ";";
        try {
            view.evaluateJavascript(script(), raw -> {
                if (!runId.equals(store.runId())) return;
                log.record(store, EVENT, prefix + decode(raw));
            });
        } catch (Throwable error) {
            log.record(store, EVENT, prefix + "e=" + error.getClass().getSimpleName());
        }
    }

    static String routeSignature(String value) {
        try {
            String path = URI.create(value == null ? "" : value).getPath();
            if (path == null) path = "";
            String[] raw = path.split("/");
            List<String> segments = new ArrayList<>();
            for (String item : raw) if (item != null && !item.isEmpty()) segments.add(item);
            if (segments.isEmpty()) return "root:0";
            if ("c".equals(segments.get(0))) return "c:" + segments.size();
            if (segments.contains("g")) return "g:" + segments.size();
            return "o:" + segments.size();
        } catch (Throwable ignored) {
            return "e:0";
        }
    }

    static String script() {
        return """
                (()=>{try{
                const rs=p=>{const a=String(p||'').split('/').filter(Boolean);if(a.length===0)return'root:0';if(a[0]==='c')return'c:'+a.length;if(a.includes('g'))return'g:'+a.length;return'o:'+a.length;};
                const du=()=>{try{return new URL(document.documentURI).pathname;}catch(_){return'';}};
                const n=s=>{try{return document.querySelectorAll(s).length;}catch(_){return-1;}};
                return ['jr='+rs(location.pathname),'dr='+rs(du()),'rd='+document.readyState,'vis='+(document.visibilityState||'na'),'f='+(document.hasFocus?.()?1:0),'to='+Math.round(performance.timeOrigin||0),'hl='+(history.length||0),'hs='+(history.state==null?0:1),'pt='+n('#prompt-textarea'),'fm='+n('form[data-type="unified-composer"]'),'tb='+n('[role="textbox"]'),'ce='+n('[contenteditable]'),'pm='+n('.ProseMirror'),'if='+n('iframe')].join(';');
                }catch(_){return'e=js';}})()
                """;
    }

    private static String safeStage(String value) {
        if ("ps".equals(value) || "pf".equals(value) || "hist".equals(value)) return value;
        return "o";
    }

    private static String decode(String raw) {
        if (raw == null || raw.isEmpty()) return "e=empty";
        try {
            Object value = new JSONTokener(raw).nextValue();
            return value instanceof String ? (String) value : "e=non_string";
        } catch (Throwable ignored) {
            return "e=decode";
        }
    }
}
