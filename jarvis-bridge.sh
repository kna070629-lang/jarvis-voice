#!/data/data/com.termux/files/usr/bin/bash
# Backward-compatible alias for the standalone companion backend.
cd "$(dirname "$0")" || exit 1
exec ./jarvis-mobile.sh "$@"
