#!/data/data/com.termux/files/usr/bin/bash
# jarvis.sh — Launcher script for Jarvis Voice Assistant

cd "$(dirname "$0")" || exit 1

# Set environment
export JARVIS_STT_ENGINE="${JARVIS_STT_ENGINE:-termux-api}"
export JARVIS_TTS_ENGINE="${JARVIS_TTS_ENGINE:-termux-tts}"

# Run with Python
exec python3 tui.py "$@"
