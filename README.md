# Jarvis Voice TUI

**Always-on voice assistant** for Android (Termux) powered by OpenClaw. Listens for your voice, processes commands via AI, and speaks replies out loud.

---

## Features

- 🎤 **Continuous voice monitoring** — WebRTC VAD detects speech activity without keeping STT open
- 👂 **Wake flow** — say `Hey Jarvis`, then ask follow-up questions naturally
- 🤖 **OpenClaw AI** — sends transcribed commands to an OpenClaw sub-session for rich responses
- 🔊 **On-device TTS** — reads responses aloud via `termux-tts-speak` (no API keys)
- 😊 **Animated emoji face** — shows current state (idle / listening / thinking / speaking)
- 🌐 **No cloud dependencies** — runs entirely on-device (except OpenClaw gateway)
- 🔓 **Open source** — MIT licensed, self-hosted

---

## Requirements

- **Android** with [Termux](https://termux.com/) installed
- **Python 3.13+** (comes with Termux)
- **termux-api** package (`pkg install termux-api`)
- **OpenClaw** gateway running on the device (or accessible elsewhere)
- **Internet** — for OpenClaw AI responses (or use the local fallback)

### Termux packages needed

```bash
pkg install termux-api python ffmpeg sox
```

### Python dependencies

```bash
pip install -r requirements.txt
```

That's it. No PyTorch, no cloud API keys, no compilation.

---

## Quick Start

```bash
# Clone (or open the repo directory)
cd ~/jarvis-voice

# Install Python dependencies
pip install -r requirements.txt

# Run the mic test first to verify audio works
python3 tui.py --test-mic

# Configure (optional — defaults work for local OpenClaw)
export JARVIS_GATEWAY_URL="http://localhost:18789"
export JARVIS_AUTH_TOKEN="your-gateway-token"
export JARVIS_OPENCLAW_SESSION_ID="jarvis-tui"

# Run!
python3 tui.py
```

---

## How it works

```
[Mic] ──► [WebRTC VAD always-on loop]
              short chunks → 16k mono PCM → 30ms frames
              Energy fallback (RMS > 800)
              Speech activity wakes STT
                    │
                    ▼
       [termux-speech-to-text: capture the question]
                    │
                    ▼
          [OpenClaw sub-session: jarvis-tui]
                    │
                    ▼
        [termux-tts-speak + animated emoji face]
```

1. **VAD loop** — always-on, records short chunks, converts them to raw 16 kHz mono PCM, and checks valid 30 ms WebRTC VAD frames
2. **Wake** — saying `Hey Jarvis` creates speech activity, Jarvis wakes, and then starts Android STT
3. **Conversation** — ask the question after Jarvis says `Yes?`; follow-up replies do not need `Hey Jarvis`
4. **AI response** — sends each turn to the same OpenClaw sub-session, with a local fallback if the gateway is unavailable
5. **Idle timeout** — after 15 seconds of silence, Jarvis returns to waiting for `Hey Jarvis`
6. **TTS + face** — speaks via `termux-tts-speak` and animates the emoji state in the terminal

---

## VAD Tuning

The VAD is configured for natural conversation pauses:

| Parameter | Value | Meaning |
|-----------|-------|---------|
| Energy threshold | 800 RMS | Sensitivity for quiet speech |
| Silence threshold | 5 frames (~150ms of VAD frames) | Speech-end confirmation before wake check |
| Min speech frames | 4 frames (120ms) | Minimum speech to count as speaking |

### Wake phrase behavior

WebRTC VAD detects speech activity, not words. Jarvis therefore uses VAD to decide when to wake, then starts Android STT for the question. This avoids needing to say `Hey Jarvis` twice.

You can tune this with:

```bash
export JARVIS_WAKE_WORD="hey jarvis"
export JARVIS_REQUIRE_WAKE_WORD=0
export JARVIS_QUESTION_TIMEOUT=45
export JARVIS_CONVERSATION_IDLE_TIMEOUT=15
export JARVIS_STT_CONTINUATION_TIMEOUT=2.5
export JARVIS_STT_MAX_PARTS=2
export JARVIS_TTS_SETTLE_SECONDS=0.8
export JARVIS_TTS_WORDS_PER_MINUTE=155
export JARVIS_TTS_MAX_WAIT_SECONDS=18
export JARVIS_OPENCLAW_SESSION_ID="jarvis-tui"
export JARVIS_OPENCLAW_TIMEOUT=90
```

Set `JARVIS_REQUIRE_WAKE_WORD=1` if you want Android STT to confirm the wake phrase, but that mode may require saying `Hey Jarvis` again because STT starts after VAD wakes.

---

## Key files

| File | Purpose |
|------|---------|
| `tui.py` | Main entry point — VAD loop + TUI + gateway integration |
| `wakeword.py` | WebRTC VAD engine + energy fallback |
| `stt.py` | STT engines (termux-api, OpenAI Whisper API) |
| `tts.py` | TTS engines (termux-tts-speak, ElevenLabs, OpenAI TTS) |
| `face.py` | Animated emoji face engine |
| `config.py` | Environment variable configuration |
| `requirements.txt` | Python dependencies |

---

## Speech-to-Text

**Local option:** `whisper-cli` with a Whisper model. When no local model is configured, Jarvis opens Android's `termux-speech-to-text` recognizer. The Android path requires the Termux:API package and the matching Android app, with microphone permission granted.

> **Note:** `faster-whisper` is not available on Python 3.13 / Android ARM64 — `ctranslate2` has no compatible wheel.

---

## Text-to-Speech

**Primary:** `termux-tts-speak` — on-device Android neural TTS, no API key needed.
Check available voices: `termux-tts-engines`

The current TUI uses `termux-tts-speak`. The separate `tts.py` module also contains API-backed engines, but those are not wired into the TUI.

---

## OpenClaw Integration

Connects to OpenClaw through `openclaw agent` using a stable session id, so every voice turn is appended to the same OpenClaw chat context:

```bash
export JARVIS_OPENCLAW_SESSION_ID="jarvis-tui"
export JARVIS_OPENCLAW_TIMEOUT=90
```

### Local fallback

If OpenClaw is unreachable, a rule-based response generator handles basic commands:
`hello`, `how are you`, `your name`, `time`, `date`, `weather`, `joke`, `thank`, `bye`

---

## Native Android Companion

The Kotlin companion lives under `android/` and uses the existing Python/OpenClaw backend; it does not replace the terminal assistant.

### Build

The Android module targets Android 14 (API 34), compiles against API 35, and supports Android 8.0+ (API 26). Build it on a machine with JDK 17+, Android SDK platform 35, and Android Build Tools installed:

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

The current workspace has Java 21 but no Android SDK or `adb`, so APK generation and device installation have not been verified here. The project includes a Gradle wrapper and pins the Gradle distribution checksum.

### Connect to the existing Termux backend

Run `./jarvis-mobile.sh` in Termux to start the companion backend without activating the TUI microphone loop. It prints the pairing token and binds an authenticated HTTP bridge to `127.0.0.1:8765`; the bridge exposes only `/health` and the structured `chat` action. It does not execute shell commands from AI output. The random bearer token is saved at `~/.config/jarvis/mobile.token` with owner-only permissions. If you instead run the regular `jarvis.sh` TUI, the bridge is started there too; retrieve the token with:

```bash
python3 mobile_bridge.py --show-token
```

Use one backend launcher at a time; both use port 8765.

Install the debug APK, paste the token into the app, and select **Save token and connect**. The app stores it encrypted with Android Keystore. Keep the token private. The Android app only connects to loopback and requires either the companion backend or the TUI to be running.

The companion uses Android `SpeechRecognizer` and `TextToSpeech`. Speech recognition availability and whether the configured Android recognition provider processes audio locally depend on the device's selected provider. The app requests microphone access at first use and stops listening when it leaves the foreground. Conversation mode is opt-in and reopens a recognition session after a spoken reply; it is not a wake-word or background-listening feature.

The installed-app picker uses Android package visibility for launchable activities. Short voice commands such as “open Spotify” use fixed Android intents/package lookup. WhatsApp drafts require a number and message, show a confirmation dialog, and open a prefilled WhatsApp conversation; Jarvis never presses Send. Phone opens the dialer without placing a call.

Accessibility and notification access are optional special settings. Accessibility navigation runs only after an in-app action, taps one exact visible label, and ignores password nodes. Notification text is kept in memory (up to 20 items); sending a preview to OpenClaw requires a separate confirmation. Neither capability is activated automatically.

### Verification status

- **Implemented:** Python bridge, native UI/voice code, local app launching, optional notification/accessibility services, confirmation flow.
- **Build verified:** Python bridge unit tests and static checks can run in this repository; Android build depends on an Android SDK that is not present in this environment.
- **Installed on device:** Not verified.
- **Physically tested:** Not verified on the Vivo I2410.

---

## Running as a Service

```bash
# Background (survives terminal close)
nohup python3 tui.py > jarvis.log 2>&1 &

# Watch log
tail -f jarvis.log

# Stop
pkill -f tui.py
```

---

## Troubleshooting

**`termux-api not found`** → `pkg install termux-api`

**Mic test fails** → Grant microphone permission to Termux in Android Settings → Apps → Termux → Permissions

**VAD not triggering** → Run with debug output enabled in tui.py (per-chunk RMS logging is already on every 5th iteration)

**Gateway connection refused** → `openclaw gateway start`

**webrtcvad import error** → The package is pre-patched to remove a `pkg_resources` dependency. Reinstall if needed.

---

## Known Limitations

| Issue | Cause | Workaround |
|-------|-------|------------|
| Wake is VAD-based by default | WebRTC VAD detects voice, not words | Use a quiet environment, or set `JARVIS_REQUIRE_WAKE_WORD=1` for stricter STT wake confirmation |
| No `faster-whisper` | No ctranslate2 cp313 wheel | Use termux-speech-to-text |
| No `torch` | download.pytorch.org blocked | WebRTC VAD instead |
| No `numpy` | No cp313 Android ARM64 wheel | Pure Python RMS fallback |

---

## Project Status

**Implemented (device behavior still needs validation):**
- WebRTC VAD monitoring with an energy fallback
- Local Whisper transcription, or Android `termux-speech-to-text` when no Whisper model is configured
- `termux-tts-speak` responses
- OpenClaw integration and local fallback responses
- Terminal face display and `--test-mic` diagnostic

**Needs Android device validation:**
- VAD threshold tuning and end-of-utterance behavior
- Termux:API recognizer, microphone permission, and TTS behavior across Android versions

**Blocked:**
- ❌ `faster-whisper` / ctranslate2 — no cp313 wheel
- ❌ Silero VAD — no torch
- ❌ True wake-word phrase detection

---

## License

MIT
