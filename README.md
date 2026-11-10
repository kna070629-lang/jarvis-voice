# JARVIS Voice Assistant

An existing Python-first Termux assistant with an authenticated Android companion. Local commands and saved memory run on the device. OpenRouter is an optional cloud provider for natural conversation and reasoning; requests are restricted to explicitly listed zero-priced `:free` model variants, with provider fallback disabled.

## Runtime

```text
Android SpeechRecognizer / Android TextToSpeech
                 │ authenticated loopback HTTP
                 ▼
127.0.0.1:8765 — mobile_bridge.py
                 │
                 ▼
jarvis_engine.py — local routing, session context, SQLite memory
        ├── local commands and Android intent delegation
        ├── live Wikipedia / Open-Meteo providers (when online)
        └── OpenRouter free-only chat (when configured and online)
```

OpenClaw is not required by either launcher. Its existing installation, files, and legacy configuration are retained as rollback material and are not used by the Python-first runtime.

## Start the Android backend

In Termux, from this repository:

```bash
cd ~/jarvis-workspace
./jarvis-mobile.sh
```

This starts the Python engine, validates/opens the local SQLite stores, and binds the authenticated bridge to `127.0.0.1:8765`. It does not start the TUI microphone loop. Stop with Ctrl+C. For the separate terminal voice interface, use `./jarvis.sh`; it uses the same Python engine and existing TUI/STT/TTS integration.

Pairing is a one-time local action. With the backend running, run `python3 mobile_bridge.py --show-token` in a separate Termux session and enter the displayed token in **Controls → Secure Python Connection** in Android. Keep it private; do not put it in screenshots, shared logs, source files, or cloud configuration. The app stores it using Android Keystore. The launcher itself never prints the token.

## OpenRouter setup

Cloud chat is disabled until a real OpenRouter key is configured. Enter it through hidden terminal input; do not paste it in a command or source file:

```bash
cd ~/jarvis-workspace
python3 jarvis_setup.py configure-openrouter
```

The key is stored in `~/.config/jarvis/secrets.json` with owner-only directory/file permissions. `OPENROUTER_API_KEY` may be supplied by a private process environment instead. JARVIS never prints the key. It checks the selected model against OpenRouter's live model catalog and requires an explicit `:free` identifier with both input and output prices equal to zero immediately before every chat request. The default is `google/gemma-4-31b-it:free`; set `OPENROUTER_MODEL` to another verified free model identifier to change it. There is no paid model or provider fallback. If verification fails, no chat request is sent.

To run without any cloud AI request:

```bash
JARVIS_MODE=offline ./jarvis-mobile.sh
```

Local time/date, explicit memory commands, Android app actions, and supported phone integrations remain local. Live search and weather report unavailable in offline mode. A missing or unreachable OpenRouter provider is reported honestly; JARVIS does not substitute canned answers.

## Memory and live search

Long-term facts explicitly saved with `Remember this: ...` are stored in `~/.local/share/jarvis/memory.sqlite3`. Memory commands include `What do you remember about me?`, `Forget memory 3`, and `Clear my memory`. Clearing requires the follow-up `confirm clear my memory`; say `cancel memory` to leave data unchanged. Conversation context is separate in `conversations.sqlite3`, bounded to recent turns, and scoped by assistant session. Secret-like content is excluded from both stores.

Explicit requests such as “search for …” use the separate no-key Wikipedia API provider and return source URLs. “What is the weather in …” uses Open-Meteo geocoding/current conditions. These services need internet; JARVIS distinguishes current provider results from AI explanation and reports failed searches instead of claiming they succeeded.

## Android controls and privacy

The Android app keeps its existing package `dev.jarvis.companion`, speech recognizer, Android TTS, app discovery, authenticated pairing, notification listener, and optional Accessibility service. Microphone use starts only after the user taps the microphone. The current UI animation reflects actual listening/thinking/speaking/error/offline app state; speaking motion is state-driven because Android TTS audio amplitude is not exposed by the existing integration.

App launch uses Android intents. Calls open the system dialer only after an in-app confirmation; the user must place the call there. SMS and WhatsApp actions display the recipient and draft, require confirmation, then open the native composer; JARVIS does not press Send. Notification access and Accessibility remain optional settings that the user must enable manually. Notification text is sent for summarization only after a separate preview/confirmation. Accessibility actions are limited to explicit navigation or a confirmed visible-label tap.

The bridge only binds loopback and requires a private bearer token for commands. It accepts a bounded structured chat request; model output is never interpreted as shell or device commands. Health status is returned only to an authenticated health request and contains provider state, not secrets.

## Tests and Android build

Run the Python checks from the repository:

```bash
python3 -m py_compile jarvis*.py mobile_bridge.py mobile_host.py tui.py
python3 -m pytest -q
bash -n jarvis.sh jarvis-mobile.sh jarvis-bridge.sh
git diff --check
```

The Android project is under `android/` and uses the existing Gradle wrapper, AGP 8.11.1, Gradle 8.13, and package `dev.jarvis.companion`. The current Termux/Ubuntu environment has no Android SDK, so a local APK build has not been verified here. `.github/workflows/android-debug.yml` provides a manual GitHub Actions debug build and uploads `android/app/build/outputs/apk/debug/app-debug.apk`. See [JARVIS_APK_BUILD.md](JARVIS_APK_BUILD.md) for the exact build and install steps and current verification status.

See [JARVIS_SETUP.md](JARVIS_SETUP.md), [JARVIS_TEST_REPORT.md](JARVIS_TEST_REPORT.md), and [JARVIS_COMPLETION_STATUS.md](JARVIS_COMPLETION_STATUS.md) for setup, evidence, limits, and the current checkpoint.
