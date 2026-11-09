#!/data/data/com.termux/files/usr/bin/bash
# Start the Python/OpenClaw companion backend without the TUI's microphone loop.
cd "$(dirname "$0")" || exit 1
TOKEN=$(python3 mobile_bridge.py --show-token) || exit 1
printf 'Pairing token for the JARVIS Android app: %s\n' "$TOKEN"
exec python3 mobile_host.py
