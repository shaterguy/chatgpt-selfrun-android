#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"{label}: anchor not found in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def harden(root: Path) -> None:
    main = root / "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java"
    activity = root / "app/src/debug/java/com/shaterguy/chatgptselfrun/SelfRunRuntimeProbeActivity.java"

    replace_once(
        main,
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

    # Make process recovery exercise a critical snapshot immediately before the external ADB kill.
    replace_once(
        activity,
        'JSONObject result = baseResult("process_prepare");',
        'store.enterPausedState("AC06 critical process snapshot");\n            JSONObject result = baseResult("process_prepare");',
        "process critical snapshot",
    )


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("usage: ac06_probe_finalize.py <worktree> [<worktree> ...]")
    for raw in sys.argv[1:]:
        root = Path(raw).resolve()
        harden(root)
        print(f"AC06 probe hardened: {root}")


if __name__ == "__main__":
    main()
