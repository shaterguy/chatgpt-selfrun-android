#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"{label}: anchor not found in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_method(path: Path, method_name: str, next_method_name: str, new_method: str) -> None:
    text = path.read_text(encoding="utf-8")
    start_token = f"    private void {method_name}("
    end_token = f"    private void {next_method_name}("
    start = text.find(start_token)
    if start < 0:
        raise RuntimeError(f"{method_name}: start anchor not found in {path}")
    end = text.find(end_token, start)
    if end < 0:
        raise RuntimeError(f"{method_name}: next-method anchor not found in {path}")
    path.write_text(text[:start] + new_method.rstrip() + "\n\n" + text[end:], encoding="utf-8")


def harden(root: Path) -> None:
    service = root / "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java"
    activity = root / "app/src/debug/java/com/shaterguy/chatgptselfrun/SelfRunRuntimeProbeActivity.java"

    replace_once(
        service,
        'handler.postDelayed(() -> runLog.record(store, "AC06_MARKER", "long_response_complete"), 5_500L);',
        '''handler.postDelayed(() -> {
                WebView ac06Active = webView;
                if (ac06Active == null) {
                    runLog.record(store, "AC06_RESPONSE_INCOMPLETE", "webview_missing");
                    return;
                }
                ac06Active.evaluateJavascript(
                        "(() => { const a=document.getElementById('assistant'); const t=document.getElementById('assistant-text'); return (a && t && a.getAttribute('data-is-streaming') === 'false' && String(t.textContent || '').includes('token-239')) ? 'COMPLETE' : 'INCOMPLETE'; })()",
                        raw -> runLog.record(store,
                                "\\\"COMPLETE\\\"".equals(String.valueOf(raw))
                                        ? "AC06_RESPONSE_COMPLETE" : "AC06_RESPONSE_INCOMPLETE",
                                "verified"));
            }, 6_000L);''',
        "long response completion probe",
    )

    replace_once(
        activity,
        'handler.postDelayed(() -> collectServiceResult("long_response", 240), 7_000L);',
        'handler.postDelayed(() -> collectServiceResult("long_response", 240), 8_000L);',
        "long response collection delay",
    )
    replace_once(
        activity,
        'case "long_response" -> logicalCount(lines, "DOM_OBSERVER_STATE") >= 1;',
        'case "long_response" -> directCount(lines, "AC06_RESPONSE_COMPLETE") >= 1;',
        "long response pass condition",
    )

    process_method = r'''    private void prepareProcessBoundary() {
        store = prepareStore("process");
        startServiceAction("SYNTHETIC_START");
        handler.postDelayed(() -> {
            store.setPaused(true);
            store.setPhase(SelfRunStore.PHASE_PAUSED);
            store.setStatus("AC06 process recovery snapshot");
            store.syncHistory();
            handler.postDelayed(() -> {
                JSONObject result = baseResult("process_prepare");
                put(result, "pass", hasObserverAttached(readLogs()) && store.paused()
                        && SelfRunStore.PHASE_PAUSED.equals(store.phase()));
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
            }, 500L);
        }, 3_000L);
    }'''
    replace_method(activity, "prepareProcessBoundary", "verifyProcessBoundary", process_method)


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("usage: ac06_probe_finalize.py <worktree> [<worktree> ...]")
    for raw in sys.argv[1:]:
        root = Path(raw).resolve()
        harden(root)
        print(f"AC06 probe hardened: {root}")


if __name__ == "__main__":
    main()
