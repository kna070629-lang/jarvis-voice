#!/data/data/com.termux/files/usr/bin/bash
# jarvis.sh — Launcher script for Jarvis Voice Assistant

cd "$(dirname "$0")" || exit 1

# Set environment
export JARVIS_STT_ENGINE="${JARVIS_STT_ENGINE:-termux-api}"
export JARVIS_TTS_ENGINE="${JARVIS_TTS_ENGINE:-termux-tts}"

# The gateway auth token lives in an UNTRACKED local file, not in the
# versioned jarvis.conf. This repository is PUBLIC, so anything committed
# here is readable by anyone. `_cfg()` in tui.py resolves env vars before
# the conf file, so exporting JARVIS_AUTH_TOKEN wins.
#
# The file is created on first run from the legacy jarvis.conf if it still
# carries a token, so an existing setup keeps working after the upgrade.
if [ -f ".jarvis.local.conf" ]; then
  # shellcheck disable=SC1091
  . ".jarvis.local.conf"
fi
if [ -z "${JARVIS_AUTH_TOKEN:-}" ] && [ -f "jarvis.conf" ]; then
  _tok=$(grep -E '^AUTH_TOKEN[[:space:]]*=[[:space:]]*.+' jarvis.conf 2>/dev/null | head -1 | cut -d= -f2- | tr -d '[:space:]')
  if [ -n "$_tok" ]; then
    export JARVIS_AUTH_TOKEN="$_tok"
    unset _tok
  fi
fi

# Run with Python
exec python3 tui.py "$@"
