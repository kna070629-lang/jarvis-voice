#!/data/data/com.termux/files/usr/bin/bash
# Start the Python-first companion backend without the TUI microphone loop.
cd "$(dirname "$0")" || exit 1
exec python3 mobile_host.py
