#!/data/data/com.termux/files/usr/bin/bash
export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:/system/bin"
export TMPDIR="$PREFIX/tmp"

# The shared service owns its own session and must outlive SelfRun.
AUTH_START="$HOME/bin/termux-google-auth-start.sh"
if [ ! -x "$AUTH_START" ] || ! "$AUTH_START"; then
  echo "SELFRUN_SHARED_AUTH_SERVICE_UNAVAILABLE" >&2
  exit 1
fi

termux-wake-lock >/dev/null 2>&1 || true

if "$PREFIX/bin/tmux" has-session -t selfrun-server 2>/dev/null; then
  exit 0
fi

"$PREFIX/bin/tmux" new-session -d -s selfrun-server "$HOME/bin/selfrun-server-supervisor.sh"
