#!/data/data/com.termux/files/usr/bin/bash
export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:/system/bin"
export TMPDIR="$PREFIX/tmp"

ROOT="$HOME/work/selfrun-termux-server/termux-server"
DATA="$HOME/.selfrun-server"
LOG="$DATA/server.log"

mkdir -p "$DATA" "$TMPDIR"
termux-wake-lock >/dev/null 2>&1 || true

while true; do
  echo "$(date -Iseconds) starting SelfRun Termux Server" >> "$LOG"
  cd "$ROOT" || exit 1
  "$PREFIX/bin/node" src/server.mjs >> "$LOG" 2>&1
  code=$?
  echo "$(date -Iseconds) SelfRun server exited code=$code; restarting in 3s" >> "$LOG"
  sleep 3
done
