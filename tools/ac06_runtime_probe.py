#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

COUNTER_JAVA = r'''package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;

final class SelfRunAc06Counter {
    private static long stateWrites;
    private static long historyWrites;
    private static long logWriteBatches;
    private static long logLines;

    private SelfRunAc06Counter() {}

    static synchronized void reset() {
        stateWrites = 0L;
        historyWrites = 0L;
        logWriteBatches = 0L;
        logLines = 0L;
    }

    static synchronized void stateWrite() { stateWrites++; }
    static synchronized void historyWrite() { historyWrites++; }

    static synchronized void logWrite(long lines) {
        logWriteBatches++;
        if (lines > 0L) logLines += lines;
    }

    static synchronized JSONObject snapshot() {
        JSONObject result = new JSONObject();
        try {
            result.put("stateWrites", stateWrites);
            result.put("historyWrites", historyWrites);
            result.put("logWriteBatches", logWriteBatches);
            result.put("logLines", logLines);
        } catch (Exception ignored) {}
        return result;
    }
}
'''

ACTIVITY_JAVA = r'''package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class SelfRunRuntimeProbeActivity extends Activity {
    private static final String SERVICE_PREFIX = "com.shaterguy.chatgptselfrun.AC06_";
    private static final String CONVERSATION =
            "https://chatgpt.com/c/00000000-0000-4000-8000-000000000006";
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SelfRunStore store;
    private String scenario;
    private String runId;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        scenario = getIntent().getStringExtra("action");
        if (scenario == null || scenario.isEmpty()) scenario = "unknown";
        if ("process_verify".equals(scenario)) {
            verifyProcessBoundary();
            return;
        }
        if ("process_prepare".equals(scenario)) {
            prepareProcessBoundary();
            return;
        }
        if ("long_stable".equals(scenario)) {
            runLongStable();
            return;
        }
        runServiceScenario();
    }

    private void runLongStable() {
        store = prepareStore("long_stable");
        handler.postDelayed(() -> {
            SelfRunAc06Counter.reset();
            clearLogs();
            for (int i = 0; i < 1_000; i++) store.setStatus("stable");
            JSONObject result = baseResult("long_stable");
            put(result, "pass",
                    metric(result, "stateWrites") == 0L && metric(result, "historyWrites") == 0L);
            put(result, "rawOperations", 1_000);
            writeResult("long_stable", result);
            finish();
        }, 800L);
    }

    private void runServiceScenario() {
        store = prepareStore(scenario);
        startServiceAction("SYNTHETIC_START");
        handler.postDelayed(() -> {
            SelfRunAc06Counter.reset();
            clearLogs();
            switch (scenario) {
                case "long_response" -> {
                    startServiceAction("LONG_RESPONSE");
                    handler.postDelayed(() -> collectServiceResult("long_response", 240), 7_000L);
                }
                case "dom_mutation" -> {
                    startServiceAction("MUTATE");
                    handler.postDelayed(() -> collectServiceResult("dom_mutation", 40), 11_000L);
                }
                case "watchdog" -> {
                    startServiceAction("WATCHDOG");
                    handler.postDelayed(() -> collectServiceResult("watchdog", 20), 8_000L);
                }
                case "pause_user_action" -> {
                    store.setLastSignal("[SELF_RUN_USER_ACTION_REQUIRED " + runId + " AC06]");
                    SelfRunAc06Counter.reset();
                    clearLogs();
                    startServiceAction("PAUSE_USER_ACTION");
                    handler.postDelayed(() -> collectServiceResult("pause_user_action", 1), 1_500L);
                }
                case "pause_self" -> {
                    store.setLastSignal("[SELF_RUN_PAUSE " + runId + " REASON=AC06]");
                    SelfRunAc06Counter.reset();
                    clearLogs();
                    startServiceAction("PAUSE_SELF");
                    handler.postDelayed(() -> collectServiceResult("pause_self", 1), 1_500L);
                }
                case "pause_manual" -> {
                    SelfRunAc06Counter.reset();
                    clearLogs();
                    Intent pause = new Intent(this, SelfRunService.class)
                            .setAction(SelfRunService.ACTION_PAUSE);
                    startForegroundService(pause);
                    handler.postDelayed(() -> collectServiceResult("pause_manual", 1), 1_500L);
                }
                case "stale_callback" -> {
                    startServiceAction("STALE");
                    handler.postDelayed(() -> collectServiceResult("stale_callback", 100), 3_500L);
                }
                case "renderer_recovery" -> {
                    startServiceAction("CRASH_RENDERER");
                    handler.postDelayed(() -> collectServiceResult("renderer_recovery", 1), 7_000L);
                }
                default -> {
                    JSONObject result = baseResult(scenario);
                    put(result, "pass", false);
                    put(result, "error", "unknown_scenario");
                    writeResult(scenario, result);
                    finish();
                }
            }
        }, 3_000L);
    }

    private void prepareProcessBoundary() {
        store = prepareStore("process");
        startServiceAction("SYNTHETIC_START");
        handler.postDelayed(() -> {
            JSONObject result = baseResult("process_prepare");
            put(result, "pass", hasObserverAttached(readLogs()));
            put(result, "expectedRunId", store.runId());
            put(result, "expectedMode", store.mode());
            put(result, "expectedConversation", store.conversationUrl());
            put(result, "expectedPhase", store.phase());
            put(result, "expectedRole", store.role());
            put(result, "expectedPendingModel", store.pendingModel());
            put(result, "expectedPendingReasoning", store.pendingReasoning());
            put(result, "expectedLastSignal", store.lastSignal());
            put(result, "expectedAssistantBaseline", store.assistantBaselineKey());
            put(result, "expectedLastAssistant", store.lastAssistantKey());
            put(result, "expectedTurn", store.turn());
            put(result, "expectedActive", store.active());
            put(result, "expectedPaused", store.paused());
            writeResult("process_prepare", result);
        }, 3_000L);
    }

    private void verifyProcessBoundary() {
        try {
            JSONObject before = new JSONObject(readFile(new File(getFilesDir(), "ac06-process_prepare.json")));
            SelfRunStore recovered = new SelfRunStore(this);
            JSONObject history = new SelfRunHistoryStore(this).get(before.optString("expectedRunId"));
            boolean primary = before.optString("expectedRunId").equals(recovered.runId())
                    && before.optString("expectedMode").equals(recovered.mode())
                    && before.optString("expectedConversation").equals(recovered.conversationUrl())
                    && before.optString("expectedPhase").equals(recovered.phase())
                    && before.optString("expectedRole").equals(recovered.role())
                    && before.optString("expectedPendingModel").equals(recovered.pendingModel())
                    && before.optString("expectedPendingReasoning").equals(recovered.pendingReasoning())
                    && before.optString("expectedLastSignal").equals(recovered.lastSignal())
                    && before.optString("expectedAssistantBaseline").equals(recovered.assistantBaselineKey())
                    && before.optString("expectedLastAssistant").equals(recovered.lastAssistantKey())
                    && before.optInt("expectedTurn") == recovered.turn()
                    && before.optBoolean("expectedActive") == recovered.active()
                    && before.optBoolean("expectedPaused") == recovered.paused();
            boolean historyOk = history != null
                    && recovered.runId().equals(history.optString("runId"))
                    && recovered.conversationUrl().equals(history.optString("conversationUrl"))
                    && recovered.phase().equals(history.optString("phase"))
                    && recovered.role().equals(history.optString("role"))
                    && recovered.turn() == history.optInt("turn")
                    && recovered.active() == history.optBoolean("active")
                    && recovered.paused() == history.optBoolean("paused");
            int oldPid = before.optInt("pid", -1);
            int newPid = Process.myPid();
            JSONObject result = new JSONObject();
            put(result, "scenario", "process");
            put(result, "pass", oldPid > 0 && newPid > 0 && oldPid != newPid && primary && historyOk);
            put(result, "pid", newPid);
            put(result, "oldPid", oldPid);
            put(result, "freshPid", oldPid != newPid);
            put(result, "primaryRecovered", primary);
            put(result, "historyRecovered", historyOk);
            copyMetric(before, result, "stateWrites");
            copyMetric(before, result, "historyWrites");
            copyMetric(before, result, "logWriteBatches");
            copyMetric(before, result, "logLines");
            writeResult("process", result);
        } catch (Throwable error) {
            JSONObject result = new JSONObject();
            put(result, "scenario", "process");
            put(result, "pass", false);
            put(result, "error", error.getClass().getSimpleName());
            writeResult("process", result);
        }
        finish();
    }

    private SelfRunStore prepareStore(String suffix) {
        stopService(new Intent(this, SelfRunService.class));
        getSharedPreferences("selfrun", Context.MODE_PRIVATE).edit().clear().commit();
        getSharedPreferences("selfrun_history", Context.MODE_PRIVATE).edit().clear().commit();
        clearLogs();
        SelfRunAc06Counter.reset();

        runId = "SR-ac06-" + suffix;
        SelfRunStore value = new SelfRunStore(this);
        value.start(runId, SelfRunStore.MODE_CHAT,
                "https://chatgpt.com/g/g-p-ac06/project", "AC06 runtime probe");
        value.setConversationUrl(CONVERSATION);
        value.setRole("VERIFIER");
        value.setPendingModel("sol");
        value.setPendingReasoning("xhigh");
        value.setLastSignal("[SELF_RUN_NEXT " + runId + " ROLE=VERIFIER]");
        value.setAssistantBaselineKey("ac06-baseline");
        value.setLastAssistantKey("ac06-assistant");
        value.setTurn(4);
        value.setPhase(SelfRunStore.PHASE_WAIT_ASSISTANT);
        value.setStatus("stable");
        return value;
    }

    private void collectServiceResult(String name, int rawOperations) {
        List<String> lines = readLogs();
        JSONObject result = baseResult(name);
        put(result, "rawOperations", rawOperations);
        put(result, "fileLogLines", lines.size());
        put(result, "observerStateLogical", logicalCount(lines, "DOM_OBSERVER_STATE"));
        put(result, "watchdogHealthLogical", logicalCount(lines, "DOM_WATCHDOG_HEALTH"));
        put(result, "observerHealthLogical", logicalCount(lines, "DOM_OBSERVER_HEALTH_EVALUATE"));
        put(result, "staleLogical", logicalCount(lines, "STALE_CALLBACK"));
        put(result, "rendererGone", directCount(lines, "RENDERER_GONE"));
        put(result, "webViewLaunch", directCount(lines, "WEBVIEW_LAUNCH"));
        put(result, "observerAttached", directCount(lines, "DOM_OBSERVER_ATTACHED"));
        put(result, "pausedEvent", directCount(lines, "PAUSED"));

        boolean pass = switch (name) {
            case "long_response" -> logicalCount(lines, "DOM_OBSERVER_STATE") >= 1;
            case "dom_mutation" -> logicalCount(lines, "DOM_OBSERVER_STATE") >= 20;
            case "watchdog" -> logicalCount(lines, "DOM_WATCHDOG_HEALTH") >= 10
                    && logicalCount(lines, "DOM_OBSERVER_HEALTH_EVALUATE") >= 10;
            case "pause_user_action", "pause_self", "pause_manual" ->
                    store.paused() && SelfRunStore.PHASE_PAUSED.equals(store.phase())
                            && directCount(lines, "PAUSED") >= 1;
            case "stale_callback" -> logicalCount(lines, "STALE_CALLBACK") >= 100;
            case "renderer_recovery" -> directCount(lines, "RENDERER_GONE") >= 1
                    && directCount(lines, "WEBVIEW_LAUNCH") >= 1
                    && directCount(lines, "DOM_OBSERVER_ATTACHED") >= 1;
            default -> false;
        };
        put(result, "pass", pass);
        writeResult(name, result);
        finish();
    }

    private JSONObject baseResult(String name) {
        JSONObject result = SelfRunAc06Counter.snapshot();
        put(result, "scenario", name);
        put(result, "pid", Process.myPid());
        put(result, "runId", store == null ? "" : store.runId());
        put(result, "phase", store == null ? "" : store.phase());
        put(result, "active", store != null && store.active());
        put(result, "paused", store != null && store.paused());
        return result;
    }

    private void startServiceAction(String suffix) {
        Intent intent = new Intent(this, SelfRunService.class).setAction(SERVICE_PREFIX + suffix);
        startForegroundService(intent);
    }

    private List<String> readLogs() {
        return new SelfRunRunLog(this).readDebug(runId, 20_000);
    }

    private void clearLogs() {
        deleteRecursively(new File(getNoBackupFilesDir(), "selfrun-logs"));
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }

    private static boolean hasObserverAttached(List<String> lines) {
        return directCount(lines, "DOM_OBSERVER_ATTACHED") >= 1;
    }

    private static int directCount(List<String> lines, String event) {
        int count = 0;
        for (String raw : lines) {
            try {
                if (event.equals(new JSONObject(raw).optString("event"))) count++;
            } catch (Exception ignored) {}
        }
        return count;
    }

    private static long logicalCount(List<String> lines, String event) {
        long direct = 0L;
        long summaryMax = 0L;
        for (String raw : lines) {
            try {
                JSONObject item = new JSONObject(raw);
                if (event.equals(item.optString("event"))) direct++;
                if ("REPEAT_SUMMARY".equals(item.optString("event"))
                        && item.optString("detail").contains("source_event=" + event)) {
                    summaryMax = Math.max(summaryMax, item.optLong("repeat_count", 0L));
                }
            } catch (Exception ignored) {}
        }
        return Math.max(direct, summaryMax);
    }

    private void writeResult(String name, JSONObject result) {
        File file = new File(getFilesDir(), "ac06-" + name + ".json");
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write((result.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (Exception ignored) {}
    }

    private static String readFile(File file) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static long metric(JSONObject object, String key) { return object.optLong(key, 0L); }

    private static void copyMetric(JSONObject from, JSONObject to, String key) {
        put(to, key, from.optLong(key, 0L));
    }

    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (Exception ignored) {}
    }
}
'''

DEBUG_MANIFEST = r'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity android:name=".SelfRunRuntimeProbeActivity" android:exported="true" />
    </application>
</manifest>
'''

AC06_SERVICE_CONSTANTS = r'''
    private static final String AC06_PREFIX = "com.shaterguy.chatgptselfrun.AC06_";
    private static final String AC06_BASE_URL = "https://chatgpt.com/ac06";
    private static final String AC06_HTML = """
            <!doctype html><html><head><meta charset="utf-8"></head><body>
            <main>
              <form>
                <textarea id="prompt-textarea"></textarea>
                <button id="toggle" type="button" data-testid="send-button" aria-label="Send">Send</button>
              </form>
              <article data-message-author-role="user">ac06 user</article>
              <article id="assistant" data-message-author-role="assistant" data-message-id="ac06-assistant"
                       data-is-streaming="true"><span id="assistant-text">seed</span></article>
            </main>
            </body></html>
            """;
'''

AC06_SERVICE_METHODS = r'''
    private int handleAc06Action(String action, int startId) {
        if ((AC06_PREFIX + "SYNTHETIC_START").equals(action)) {
            ac06SyntheticMode = true;
            startForegroundCompat();
            updateWakeLockForState("ac06_start");
            launchWebView(AC06_BASE_URL);
            return START_STICKY;
        }
        if (!ac06SyntheticMode || webView == null) return START_STICKY;

        if ((AC06_PREFIX + "LONG_RESPONSE").equals(action)) {
            webView.evaluateJavascript("""
                    (() => {
                      const a=document.getElementById('assistant');
                      const t=document.getElementById('assistant-text');
                      if(!a||!t) return 'missing';
                      a.setAttribute('data-is-streaming','true');
                      let n=0;
                      const timer=setInterval(() => {
                        t.firstChild ? (t.firstChild.nodeValue='token-'+n) : (t.textContent='token-'+n);
                        n++;
                        if(n>=240){ clearInterval(timer); a.setAttribute('data-is-streaming','false'); }
                      },20);
                      return 'started';
                    })()
                    """, null);
            handler.postDelayed(() -> runLog.record(store, "AC06_MARKER", "long_response_complete"), 5_500L);
            return START_STICKY;
        }
        if ((AC06_PREFIX + "MUTATE").equals(action)) {
            webView.evaluateJavascript("""
                    (() => {
                      const b=document.getElementById('toggle');
                      if(!b) return 'missing';
                      let n=0;
                      const timer=setInterval(() => {
                        b.setAttribute('aria-pressed', (n % 2) ? 'false' : 'true');
                        n++;
                        if(n>=40) clearInterval(timer);
                      },220);
                      return 'started';
                    })()
                    """, null);
            handler.postDelayed(() -> runLog.record(store, "AC06_MARKER", "mutation_complete"), 9_500L);
            return START_STICKY;
        }
        if ((AC06_PREFIX + "WATCHDOG").equals(action)) {
            for (int i = 0; i < 20; i++) handler.postDelayed(this::runDomWatchdog, i * 300L);
            handler.postDelayed(() -> runLog.record(store, "AC06_MARKER", "watchdog_complete"), 6_500L);
            return START_STICKY;
        }
        if ((AC06_PREFIX + "PAUSE_USER_ACTION").equals(action)) {
            enterPreservedPause("USER_ACTION", "AC06 user action pause");
            return START_STICKY;
        }
        if ((AC06_PREFIX + "PAUSE_SELF").equals(action)) {
            enterPreservedPause("PAUSE", "AC06 self pause");
            return START_STICKY;
        }
        if ((AC06_PREFIX + "STALE").equals(action)) {
            for (int i = 0; i < 100; i++) {
                handler.postDelayed(() -> runLog.record(store, "STALE_CALLBACK", "source=ac06_service"), i * 20L);
            }
            handler.postDelayed(() -> runLog.record(store, "AC06_MARKER", "stale_complete"), 2_500L);
            return START_STICKY;
        }
        if ((AC06_PREFIX + "CRASH_RENDERER").equals(action)) {
            webView.loadUrl("chrome://crash");
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }
'''

def require_replace(text: str, old: str, new: str, label: str, count: int = 1) -> str:
    actual = text.count(old)
    if actual < count:
        raise RuntimeError(f"{label}: expected at least {count} occurrence(s), found {actual}")
    return text.replace(old, new, count)

def patch_store(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if "SelfRunAc06Counter.stateWrite();" in text:
        return
    if ".apply();" not in text:
        raise RuntimeError("SelfRunStore: no SharedPreferences apply calls")
    path.write_text(text.replace(".apply();", ".apply(); SelfRunAc06Counter.stateWrite();"), encoding="utf-8")

def patch_history(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if "SelfRunAc06Counter.historyWrite();" in text:
        return
    baseline = "return prefs.edit().putString(KEY_BACKUP, previous).putString(KEY_PRIMARY, next.toString()).commit();"
    current = "prefs.edit().putString(KEY_BACKUP, previous).putString(KEY_PRIMARY, next.toString()).apply();"
    if baseline in text:
        text = text.replace(
            baseline,
            "boolean ac06Written = prefs.edit().putString(KEY_BACKUP, previous)"
            ".putString(KEY_PRIMARY, next.toString()).commit();\n"
            "            SelfRunAc06Counter.historyWrite();\n"
            "            return ac06Written;",
            1,
        )
    elif current in text:
        text = text.replace(current, current + " SelfRunAc06Counter.historyWrite();", 1)
    else:
        raise RuntimeError("SelfRunHistoryStore: persistence anchor not found")
    path.write_text(text, encoding="utf-8")

def patch_runlog(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if "SelfRunAc06Counter.logWrite(" in text:
        return
    baseline = "append(store.runId(), item.toString());"
    current = "appendBatch(entry.getKey(), entry.getValue());"
    if baseline in text:
        text = text.replace(baseline, baseline + "\n            SelfRunAc06Counter.logWrite(1L);", 1)
    elif current in text:
        text = text.replace(current, current + "\n                    SelfRunAc06Counter.logWrite(entry.getValue().size());", 1)
    else:
        raise RuntimeError("SelfRunRunLog: append anchor not found")
    path.write_text(text, encoding="utf-8")

def patch_service(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if "handleAc06Action" in text:
        return

    action_anchor = '    static final String ACTION_RESUME = "com.shaterguy.chatgptselfrun.RESUME";\n'
    text = require_replace(text, action_anchor, action_anchor + AC06_SERVICE_CONSTANTS, "SelfRunService constants")

    field_anchor = '    private String pendingEvaluationTrigger = "startup";\n'
    text = require_replace(text, field_anchor, field_anchor + "    private boolean ac06SyntheticMode;\n", "SelfRunService field")

    dispatch_anchor = "        String action = intent == null ? ACTION_RUN : intent.getAction();\n"
    text = require_replace(
        text, dispatch_anchor,
        dispatch_anchor + "        if (action != null && action.startsWith(AC06_PREFIX)) {\n"
        + "            return handleAc06Action(action, startId);\n        }\n",
        "SelfRunService dispatch")

    runstep_anchor = "    private void runStep() {\n        if (!canRun()"
    text = require_replace(text, runstep_anchor,
        "    private void runStep() {\n        if (ac06SyntheticMode) return;\n        if (!canRun()",
        "SelfRunService runStep")

    route_anchor = "    private boolean routeAcceptable(String actual) {\n        if (actual == null || actual.isEmpty()) return false;\n"
    text = require_replace(text, route_anchor,
        "    private boolean routeAcceptable(String actual) {\n"
        "        if (ac06SyntheticMode && actual != null && actual.startsWith(AC06_BASE_URL)) return true;\n"
        "        if (actual == null || actual.isEmpty()) return false;\n",
        "SelfRunService route")

    load_anchor = "            active.loadUrl(target);\n"
    text = require_replace(text, load_anchor,
        "            if (ac06SyntheticMode) {\n"
        "                active.loadDataWithBaseURL(AC06_BASE_URL, AC06_HTML, \"text/html\", \"UTF-8\", AC06_BASE_URL);\n"
        "            } else {\n                active.loadUrl(target);\n            }\n",
        "SelfRunService load")

    method_anchor = "    private boolean isRateLimited() {\n"
    text = require_replace(text, method_anchor, AC06_SERVICE_METHODS + "\n" + method_anchor,
        "SelfRunService methods")
    path.write_text(text, encoding="utf-8")

def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: ac06_runtime_probe.py <worktree>")
    root = Path(sys.argv[1]).resolve()
    app = root / "app"
    debug_java = app / "src/debug/java/com/shaterguy/chatgptselfrun"
    debug_java.mkdir(parents=True, exist_ok=True)
    (debug_java / "SelfRunAc06Counter.java").write_text(COUNTER_JAVA, encoding="utf-8")
    (debug_java / "SelfRunRuntimeProbeActivity.java").write_text(ACTIVITY_JAVA, encoding="utf-8")
    debug_manifest = app / "src/debug/AndroidManifest.xml"
    debug_manifest.parent.mkdir(parents=True, exist_ok=True)
    debug_manifest.write_text(DEBUG_MANIFEST, encoding="utf-8")

    main_java = app / "src/main/java/com/shaterguy/chatgptselfrun"
    patch_store(main_java / "SelfRunStore.java")
    patch_history(main_java / "SelfRunHistoryStore.java")
    patch_runlog(main_java / "SelfRunRunLog.java")
    patch_service(main_java / "SelfRunService.java")
    print(f"AC06 probe injected: {root}")

if __name__ == "__main__":
    main()
