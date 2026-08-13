#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

SCENARIOS = [
    "long_response",
    "dom_mutation",
    "watchdog",
    "long_stable",
    "pause_user_action",
    "pause_self",
    "pause_manual",
    "stale_callback",
    "renderer_recovery",
    "process",
]

def load_dir(path: Path) -> dict[str, dict]:
    result = {}
    for name in SCENARIOS:
        file = path / f"{name}.json"
        if not file.exists():
            raise SystemExit(f"missing result: {file}")
        result[name] = json.loads(file.read_text(encoding="utf-8"))
    return result

def metric(item: dict, key: str) -> int:
    return int(item.get(key, 0) or 0)

def pct(before: int, after: int) -> str:
    if before <= 0:
        return "n/a"
    return f"{(before-after)*100.0/before:.1f}%"

def require(condition: bool, failures: list[str], message: str) -> None:
    if not condition:
        failures.append(message)

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    baseline = load_dir(args.baseline)
    candidate = load_dir(args.candidate)
    failures: list[str] = []

    for name in SCENARIOS:
        require(bool(baseline[name].get("pass")), failures, f"baseline scenario failed: {name}")
        require(bool(candidate[name].get("pass")), failures, f"candidate scenario failed: {name}")

    for name in ("dom_mutation", "watchdog", "stale_callback"):
        b_lines = metric(baseline[name], "fileLogLines")
        c_lines = metric(candidate[name], "fileLogLines")
        b_batches = metric(baseline[name], "logWriteBatches")
        c_batches = metric(candidate[name], "logWriteBatches")
        require(c_lines < b_lines, failures, f"{name}: candidate log lines did not decrease ({b_lines}->{c_lines})")
        require(c_batches < b_batches, failures, f"{name}: candidate log batches did not decrease ({b_batches}->{c_batches})")
        require(c_lines * 2 <= b_lines, failures, f"{name}: candidate log-line reduction below 50% ({b_lines}->{c_lines})")

    require(
        metric(candidate["long_response"], "fileLogLines")
        <= metric(baseline["long_response"], "fileLogLines") + 2,
        failures,
        "long_response: candidate introduced material log growth",
    )

    require(metric(candidate["long_stable"], "stateWrites") == 0, failures,
            "long_stable: candidate produced state writes")
    require(metric(candidate["long_stable"], "historyWrites") == 0, failures,
            "long_stable: candidate produced history writes")

    for name in ("pause_user_action", "pause_self", "pause_manual"):
        require(
            metric(candidate[name], "stateWrites") <= metric(baseline[name], "stateWrites"),
            failures,
            f"{name}: candidate state writes exceed baseline",
        )
        require(
            metric(candidate[name], "historyWrites") <= metric(baseline[name], "historyWrites"),
            failures,
            f"{name}: candidate history writes exceed baseline",
        )

    require(bool(candidate["renderer_recovery"].get("rendererGone", 0)), failures,
            "renderer_recovery: no renderer-gone evidence")
    require(bool(candidate["renderer_recovery"].get("webViewLaunch", 0)), failures,
            "renderer_recovery: no replacement WebView launch evidence")
    require(bool(candidate["process"].get("freshPid")), failures,
            "process: fresh process PID not observed")
    require(bool(candidate["process"].get("primaryRecovered")), failures,
            "process: primary state did not recover")
    require(bool(candidate["process"].get("historyRecovered")), failures,
            "process: history state did not recover")

    rows = []
    for name in SCENARIOS:
        b = baseline[name]
        c = candidate[name]
        row = {
            "scenario": name,
            "baseline": {
                "pass": bool(b.get("pass")),
                "logLines": metric(b, "fileLogLines") if "fileLogLines" in b else metric(b, "logLines"),
                "logWriteBatches": metric(b, "logWriteBatches"),
                "stateWrites": metric(b, "stateWrites"),
                "historyWrites": metric(b, "historyWrites"),
            },
            "candidate": {
                "pass": bool(c.get("pass")),
                "logLines": metric(c, "fileLogLines") if "fileLogLines" in c else metric(c, "logLines"),
                "logWriteBatches": metric(c, "logWriteBatches"),
                "stateWrites": metric(c, "stateWrites"),
                "historyWrites": metric(c, "historyWrites"),
            },
        }
        row["reduction"] = {
            "logLines": pct(row["baseline"]["logLines"], row["candidate"]["logLines"]),
            "logWriteBatches": pct(row["baseline"]["logWriteBatches"], row["candidate"]["logWriteBatches"]),
            "stateWrites": pct(row["baseline"]["stateWrites"], row["candidate"]["stateWrites"]),
            "historyWrites": pct(row["baseline"]["historyWrites"], row["candidate"]["historyWrites"]),
        }
        rows.append(row)

    output = {
        "baseline": "v0.2.3-dev5@c3d7d800405608fa7bdee34b1f4a7d4a14795291",
        "candidate": "v0.2.3-dev6",
        "scenarios": rows,
        "failures": failures,
        "pass": not failures,
    }
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "ac06-matrix.json").write_text(
        json.dumps(output, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    md = [
        "# AC-06 runtime before/after matrix",
        "",
        "| Scenario | dev5 pass | dev6 pass | Log lines | Log batches | State writes | History writes |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for row in rows:
        b, c, r = row["baseline"], row["candidate"], row["reduction"]
        md.append(
            f"| {row['scenario']} | {b['pass']} | {c['pass']} | "
            f"{b['logLines']}→{c['logLines']} ({r['logLines']}) | "
            f"{b['logWriteBatches']}→{c['logWriteBatches']} ({r['logWriteBatches']}) | "
            f"{b['stateWrites']}→{c['stateWrites']} ({r['stateWrites']}) | "
            f"{b['historyWrites']}→{c['historyWrites']} ({r['historyWrites']}) |"
        )
    md += ["", f"Overall: {'PASS' if not failures else 'FAIL'}"]
    if failures:
        md += ["", "Failures:"] + [f"- {item}" for item in failures]
    (args.out / "ac06-matrix.md").write_text("\n".join(md) + "\n", encoding="utf-8")

    print("\n".join(md))
    if failures:
        raise SystemExit(1)

if __name__ == "__main__":
    main()
