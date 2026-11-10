#!/data/data/com.termux/files/usr/bin/bash
# Start the Python-first companion backend without the TUI microphone loop.
# AI: Codex is primary, OpenCode is the automatic fallback. Local commands,
# memory, search, pairing and Android action planning work without either CLI.
cd "$(dirname "$0")" || exit 1

export JARVIS_AI_PRIMARY="${JARVIS_AI_PRIMARY:-codex}"
export JARVIS_AI_FALLBACK="${JARVIS_AI_FALLBACK:-opencode}"
export JARVIS_AI_TIMEOUT="${JARVIS_AI_TIMEOUT:-60}"

for provider in "$JARVIS_AI_PRIMARY" "$JARVIS_AI_FALLBACK"; do
    case "$provider" in
        codex)
            command -v codex >/dev/null 2>&1 || echo "JARVIS: codex not found (fallback will be used)" >&2
            ;;
        opencode)
            command -v opencode >/dev/null 2>&1 || echo "JARVIS: opencode not found (primary must answer)" >&2
            ;;
    esac
done

exec python3 mobile_host.py
