#!/usr/bin/env python3
import json
import sys
from pathlib import Path

def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))

def scenarios(doc):
    return {row["name"]: row for row in doc["matrix"]["scenarios"]}

def counters(row):
    return row.get("counters", {})

def metric(row, key):
    return int(counters(row).get(key, 0))

def check(condition, message, failures):
    if not condition:
        failures.append(message)

def sum_counter(*rows):
    keys = ("log_calls", "log_write_batches", "log_lines_written",
            "state_write_transactions", "history_write_transactions")
    return {key: sum(metric(row, key) for row in rows) for key in keys}

def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: compare_matrix.py DEV5.json DEV6.json")
    dev5 = load(sys.argv[1])
    dev6 = load(sys.argv[2])
    a = scenarios(dev5)
    b = scenarios(dev6)
    failures = []

    expected = {
        "long_response", "observer_state", "watchdog", "stale_callback",
        "stable_no_change", "pause_user_action", "pause_protocol_pause",
        "pause_manual_pause", "actual_dom_mutation", "service_long_response"
    }
    check(expected.issubset(a), f"dev5 matrix missing {sorted(expected-set(a))}", failures)
    check(expected.issubset(b), f"dev6 matrix missing {sorted(expected-set(b))}", failures)

    for name in ("long_response", "observer_state", "watchdog", "stale_callback"):
        if name in a and name in b:
            check(metric(b[name], "log_lines_written") < metric(a[name], "log_lines_written"),
                  f"{name}: dev6 log lines not reduced", failures)
            check(metric(b[name], "log_write_batches") < metric(a[name], "log_write_batches"),
                  f"{name}: dev6 log write batches not reduced", failures)
            check(bool(a[name].get("state_valid")) and bool(b[name].get("state_valid")),
                  f"{name}: functional state invalid", failures)

    for name in ("pause_user_action", "pause_protocol_pause", "pause_manual_pause"):
        if name in a and name in b:
            check(metric(b[name], "state_write_transactions") <= metric(a[name], "state_write_transactions"),
                  f"{name}: dev6 state writes regressed", failures)
            check(metric(b[name], "history_write_transactions") <= metric(a[name], "history_write_transactions"),
                  f"{name}: dev6 history writes regressed", failures)
            check(bool(a[name].get("state_valid")) and bool(b[name].get("state_valid")),
                  f"{name}: pause/resume state invalid", failures)

    for doc, label in ((dev5, "dev5"), (dev6, "dev6")):
        stable = scenarios(doc).get("stable_no_change", {})
        check(metric(stable, "state_write_transactions") == 0,
              f"{label}: stable same-state caused primary state writes", failures)
        check(metric(stable, "history_write_transactions") == 0,
              f"{label}: stable same-state caused history writes", failures)
        dom = scenarios(doc).get("actual_dom_mutation", {})
        check(bool(dom.get("state_valid")), f"{label}: actual DOM mutation observer failed", failures)
        check(1 <= int(dom.get("dom_state_events", 0)) <= 4,
              f"{label}: actual DOM mutation was not coalesced", failures)
        service_dom = scenarios(doc).get("service_long_response", {})
        check(bool(service_dom.get("state_valid")), f"{label}: service long-response state invalid", failures)
        check(1 <= int(service_dom.get("service_dom_state_events", 0)) <= 4,
              f"{label}: service long-response DOM events were not coalesced", failures)
        verify = doc["process_verify"]
        check(bool(verify.get("recovered")), f"{label}: process state recovery failed", failures)
        check(bool(verify.get("history_recovered")), f"{label}: process history recovery failed", failures)
        check(bool(verify.get("service_started")), f"{label}: service did not restart in fresh process", failures)
        check(bool(verify.get("service_recovered")), f"{label}: service did not read recovered state", failures)
        renderer = doc["renderer"]
        check(bool(renderer.get("renderer_recovered")), f"{label}: renderer recovery failed", failures)
        check(bool(renderer.get("renderer_gone_logged")), f"{label}: RENDERER_GONE not logged", failures)

    rows = []
    for name in (
        "long_response", "observer_state", "watchdog", "stale_callback",
        "stable_no_change", "pause_user_action", "pause_protocol_pause",
        "pause_manual_pause", "actual_dom_mutation", "service_long_response"
    ):
        if name not in a or name not in b:
            continue
        rows.append((name, counters(a[name]), counters(b[name])))

    process5 = sum_counter(dev5["process_seed"], dev5["process_verify"])
    process6 = sum_counter(dev6["process_seed"], dev6["process_verify"])
    rows.append(("process_recovery", process5, process6))
    rows.append(("renderer_recovery", counters(dev5["renderer"]), counters(dev6["renderer"])))

    print("# AC-06 before/after runtime evidence")
    print()
    print(f"- dev5 variant: `{dev5['variant']}`")
    print(f"- dev6 variant: `{dev6['variant']}`")
    print("- Counts below are application persistence/log write transactions observed in debug-only instrumented builds; the production release source is not patched by the probe.")
    print()
    print("| scenario | dev5 log calls | dev5 log batches | dev5 log lines | dev5 state writes | dev5 history writes | dev6 log calls | dev6 log batches | dev6 log lines | dev6 state writes | dev6 history writes |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for name, c5, c6 in rows:
        print(
            f"| {name} | {c5.get('log_calls',0)} | {c5.get('log_write_batches',0)} | "
            f"{c5.get('log_lines_written',0)} | {c5.get('state_write_transactions',0)} | "
            f"{c5.get('history_write_transactions',0)} | {c6.get('log_calls',0)} | "
            f"{c6.get('log_write_batches',0)} | {c6.get('log_lines_written',0)} | "
            f"{c6.get('state_write_transactions',0)} | {c6.get('history_write_transactions',0)} |"
        )
    print()
    for label, doc in (("dev5", dev5), ("dev6", dev6)):
        p = doc["process_verify"]
        r = doc["renderer"]
        d = scenarios(doc)["actual_dom_mutation"]
        print(
            f"- {label}: fresh-process recovered={p.get('recovered')} "
            f"history_recovered={p.get('history_recovered')} "
            f"service_recovered={p.get('service_recovered')} "
            f"renderer_recovered={r.get('renderer_recovered')} "
            f"renderer_gone_events={r.get('renderer_gone_events')} "
            f"actual_dom_state_events={d.get('dom_state_events')}"
        )
    print()
    if failures:
        print("## FAIL")
        for failure in failures:
            print(f"- {failure}")
        raise SystemExit(1)
    print("## PASS")
    print("- Required reduction and recovery assertions passed for both baseline and dev6.")

if __name__ == "__main__":
    main()
