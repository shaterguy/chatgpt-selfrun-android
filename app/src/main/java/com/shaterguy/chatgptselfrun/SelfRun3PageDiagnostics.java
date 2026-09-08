package com.shaterguy.chatgptselfrun;

import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;

import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only, bounded diagnostics. Never records page text, URLs, error messages or stacks. */
final class SelfRun3PageDiagnostics extends WebChromeClient {
    private static final int MAX_ERROR_CLASSES = 12;
    private static final Pattern REACT_CODE = Pattern.compile("Minified React error #([0-9]{1,4})");
    private static final String[] COUNTS = {
            "bodyChildren", "mainCount", "forms", "rawEditors", "textInputs", "shadowEditors",
            "frameEditors", "shadowRoots", "frames", "unreadableFrames", "scannedElements",
            "scanCapped", "bodyTextLength", "width", "height", "visualWidth", "visualHeight"
    };
    // The existing diagnostic log permits only 240 characters per detail record.
    private static final String[] LABELS = {
            "b", "m", "f", "ed", "in", "se", "fe", "sr", "fr", "xf", "n", "cap", "txt", "w", "h", "vw", "vh"
    };
    private final Consumer<String> record;
    private final Set<String> reported = new LinkedHashSet<>();

    SelfRun3PageDiagnostics(Consumer<String> record) { this.record = record; }

    @Override public boolean onConsoleMessage(ConsoleMessage message) {
        if (message != null && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                && reported.size() < MAX_ERROR_CLASSES) {
            String detail = classifyError(message.message());
            if (reported.add(detail)) {
                try { record.accept(detail); } catch (RuntimeException ignored) { }
            }
        }
        return false;
    }

    static String classifyError(String raw) {
        String text = raw == null ? "" : raw.substring(0, Math.min(8192, raw.length()));
        String kind = "UNCLASSIFIED";
        for (String candidate : new String[]{"NotFoundError", "HierarchyRequestError", "InvalidStateError",
                "AbortError", "SecurityError", "TypeError", "ReferenceError", "SyntaxError", "RangeError"}) {
            if (text.contains(candidate)) { kind = candidate.toUpperCase(java.util.Locale.ROOT); break; }
        }
        String operation = text.contains("removeChild") ? "REMOVE_CHILD"
                : text.contains("insertBefore") ? "INSERT_BEFORE"
                : text.contains("replaceChild") ? "REPLACE_CHILD" : "NONE";
        Matcher match = REACT_CODE.matcher(text);
        String react = match.find() ? match.group(1) : "0";
        return "kind=" + kind + ";operation=" + operation + ";reactCode=" + react;
    }

    /** Invoked once on missing continuation composer and once on its first timeout, never by a timer. */
    static String pageSnapshotScript() {
        return """
                (()=>{
                  const out={bodyChildren:document.body?.childElementCount||0,
                    mainCount:document.querySelectorAll('main').length,
                    forms:document.querySelectorAll('form').length,
                    rawEditors:0,textInputs:0,shadowEditors:0,frameEditors:0,
                    shadowRoots:0,frames:0,unreadableFrames:0,scannedElements:0,scanCapped:0,
                    bodyTextLength:Math.min(999999,String(document.body?.textContent||'').length),
                    width:Math.round(innerWidth),height:Math.round(innerHeight),
                    visualWidth:Math.round(window.visualViewport?.width||0),
                    visualHeight:Math.round(window.visualViewport?.height||0),titleClass:'OTHER'};
                  const title=String(document.title||'').toLowerCase();
                  if(title.includes('application error')||title.includes('client-side exception'))out.titleClass='APP_ERROR';
                  else if(title.includes('just a moment')||title.includes('attention required'))out.titleClass='CHALLENGE';
                  else if(title.includes('log in')||title.includes('sign in'))out.titleClass='AUTH';
                  const roots=[{root:document,kind:'main'}],seen=new Set([document]);
                  for(let i=0;i<roots.length&&i<24;i++){
                    const {root,kind}=roots[i];
                    const count=root.querySelectorAll('textarea,[contenteditable],[role="textbox"]').length;
                    if(kind==='main')out.rawEditors+=count;
                    else if(kind==='shadow')out.shadowEditors+=count;
                    else out.frameEditors+=count;
                    out.textInputs+=root.querySelectorAll('input:not([type]),input[type="text"]').length;
                    const elements=root.querySelectorAll('*');
                    for(const e of elements){
                      if(out.scannedElements++>=5000){out.scanCapped=1;break;}
                      if(e.shadowRoot&&!seen.has(e.shadowRoot)){
                        seen.add(e.shadowRoot);out.shadowRoots++;
                        if(roots.length<24)roots.push({root:e.shadowRoot,kind:kind==='frame'?'frame':'shadow'});
                        else out.scanCapped=1;
                      }
                      if(e.tagName==='IFRAME'){
                        out.frames++;
                        if(out.frames>8){out.scanCapped=1;continue;}
                        try{
                          const doc=e.contentDocument;
                          if(doc&&!seen.has(doc)){
                            seen.add(doc);
                            if(roots.length<24)roots.push({root:doc,kind:'frame'});else out.scanCapped=1;
                          }else if(!doc)out.unreadableFrames++;
                        }catch(_){out.unreadableFrames++;}
                      }
                    }
                    if(out.scannedElements>=5000){out.scanCapped=1;break;}
                  }
                  return JSON.stringify(out);
                })()
                """;
    }

    static String compactSnapshot(JSONObject data) {
        if (data == null) return "snapshot=UNAVAILABLE";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < COUNTS.length; i++) {
            if (out.length() > 0) out.append(';');
            out.append(LABELS[i]).append('=').append(Math.max(0L, Math.min(999999L, data.optLong(COUNTS[i], 0L))));
        }
        String titleClass = data.optString("titleClass");
        if (!Set.of("APP_ERROR", "CHALLENGE", "AUTH").contains(titleClass)) titleClass = "OTHER";
        return out.append(";title=").append(titleClass).toString();
    }
}
