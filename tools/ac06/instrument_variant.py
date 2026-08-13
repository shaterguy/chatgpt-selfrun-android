#!/usr/bin/env python3
import argparse
import shutil
from pathlib import Path

def replace_once(path: Path, old: str, new: str):
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"anchor not found in {path}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", required=True)
    parser.add_argument("--worktree", required=True)
    args = parser.parse_args()

    source_root = Path(args.source_root)
    worktree = Path(args.worktree)
    pkg = worktree / "app/src/debug/java/com/shaterguy/chatgptselfrun"
    pkg.mkdir(parents=True, exist_ok=True)
    debug_src = source_root / "tools/ac06/debug/java/com/shaterguy/chatgptselfrun"
    for name in (
            "SelfRunAc06Counter.java",
            "SelfRunAc06Bridge.java",
            "SelfRunAc06ProbeActivity.java",
            "SelfRunAc06Support.java",
            "SelfRunAc06MatrixRunner.java",
            "SelfRunAc06RecoveryRunner.java",
    ):
        shutil.copy2(debug_src / name, pkg / name)
    manifest_dir = worktree / "app/src/debug"
    manifest_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source_root / "tools/ac06/debug/AndroidManifest.xml", manifest_dir / "AndroidManifest.xml")

    java = worktree / "app/src/main/java/com/shaterguy/chatgptselfrun"

    runlog = java / "SelfRunRunLog.java"
    replace_once(
        runlog,
        'if (store == null || store.runId().isEmpty()) return;',
        'if (store == null || store.runId().isEmpty()) return;\n        SelfRunAc06Counter.logCall();'
    )
    text = runlog.read_text(encoding="utf-8")
    if "private void appendBatch(String runId, List<String> lines) throws Exception {" in text:
        replace_once(
            runlog,
            'private void appendBatch(String runId, List<String> lines) throws Exception {\n        if (lines == null || lines.isEmpty()) return;',
            'private void appendBatch(String runId, List<String> lines) throws Exception {\n        if (lines == null || lines.isEmpty()) return;\n        SelfRunAc06Counter.logPhysicalWrite(lines.size());'
        )
    elif "private void append(String runId, String line) throws Exception {" in text:
        replace_once(
            runlog,
            'private void append(String runId, String line) throws Exception {\n        File file',
            'private void append(String runId, String line) throws Exception {\n        SelfRunAc06Counter.logPhysicalWrite(1);\n        File file'
        )
    else:
        raise SystemExit("unknown SelfRunRunLog append shape")

    history = java / "SelfRunHistoryStore.java"
    htext = history.read_text(encoding="utf-8")
    dev6_history = 'prefs.edit().putString(KEY_BACKUP, previous).putString(KEY_PRIMARY, next.toString()).apply();'
    dev5_history = 'return prefs.edit().putString(KEY_BACKUP, previous).putString(KEY_PRIMARY, next.toString()).commit();'
    if dev6_history in htext:
        replace_once(history, dev6_history,
                'SelfRunAc06Counter.historyWrite();\n            ' + dev6_history)
    elif dev5_history in htext:
        replace_once(history, dev5_history,
                'SelfRunAc06Counter.historyWrite();\n        ' + dev5_history)
    else:
        raise SystemExit("unknown SelfRunHistoryStore write shape")

    store = java / "SelfRunStore.java"
    stext = store.read_text(encoding="utf-8")
    if "private void applyEditor(SharedPreferences.Editor editor)" in stext:
        replace_once(
            store,
            'private void applyEditor(SharedPreferences.Editor editor) {\n        editor.apply();',
            'private void applyEditor(SharedPreferences.Editor editor) {\n        editor.apply();\n        SelfRunAc06Counter.stateWrite();'
        )
    else:
        count = stext.count(".apply();")
        if count < 5:
            raise SystemExit(f"unexpected dev5 apply count: {count}")
        stext = stext.replace(".apply();", ".apply(); SelfRunAc06Counter.stateWrite();")
        store.write_text(stext, encoding="utf-8")

    service = java / "SelfRunService.java"
    replace_once(service, 'super.onCreate();',
            'super.onCreate();\n        SelfRunAc06Bridge.service = this;')

if __name__ == "__main__":
    main()
