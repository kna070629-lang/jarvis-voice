# JARVIS Voice Assistant

An existing Python-first Termux assistant with an authenticated Android companion. Local commands, structured Android actions, and saved memory run on the device. Natural conversation uses the local agent CLIs as text-only providers: **Codex is primary**, **OpenCode is the automatic fallback**. The provider subprocesses run in an empty temporary home with tools denied, read-only where supported, a timeout, and an output-size cap.

## Runtime

```text
Android SpeechRecognizer / Android TextToSpeech
                 │ authenticated loopback HTTP
                 ▼
127.0.0.1:8765 — mobile_bridge.py
                 │
                 ▼
jarvis_engine.py — local routing, session context, SQLite memory
        ├── local commands and structured Android action requests
        ├── live Wikipedia / Open-Meteo providers (when online)
        └── Codex primary → OpenCode fallback (text-only, sandboxed)
```

The bridge planner understands phone actions, but it never executes them. The Android companion validates the structured request, performs permission checks and confirmation, dispatches the native intent/API, and returns the real result.

OpenClaw is not required by either launcher. Its existing installation, files, and legacy configuration are retained as rollback material and are not used by the Python-first runtime.

## Start the Android backend

In Termux, from this repository:

```bash
cd ~/jarvis-workspace
./jarvis-mobile.sh
```

This starts the Python engine, validates/opens the local SQLite stores, and binds the authenticated bridge to `127.0.0.1:8765`. It does not start the TUI microphone loop. Stop with Ctrl+C. For the separate terminal voice interface, use `./jarvis.sh`; it uses the same Python engine and existing TUI/STT/TTS integration.

Pairing is a one-time local action. With the backend running, run `python3 mobile_bridge.py --show-token` in a separate Termux session and enter the displayed token in **Controls → Secure Python Connection** in Android. Keep it private; do not put it in screenshots, shared logs, source files, or cloud configuration. The app stores it using Android Keystore. The launcher itself never prints the token.

## Configure the AI providers

Authenticate the CLIs once with their own login commands; JARVIS never asks for or stores their keys:

```bash
codex login
opencode auth login
```

`./jarvis-mobile.sh` selects `codex` as primary and `opencode` as fallback by default. Override the order or models with private environment variables if needed:

```bash
JARVIS_AI_PRIMARY=opencode JARVIS_AI_FALLBACK=codex ./jarvis-mobile.sh
JARVIS_CODEX_MODEL=<model> JARVIS_OPENCODE_MODEL=provider/model ./jarvis-mobile.sh
```

If the primary is missing, not logged in, times out, hits a quota/rate limit, or returns empty/unusable text, JARVIS tries the fallback and reports which provider answered. The provider child gets only a throwaway `HOME`/XDG tree plus a private copy of that CLI's auth file; it cannot read `~/.config/jarvis` or its own data directories from the real home. AI output is returned as text and is never executed.

To run without any cloud AI request:

```bash
JARVIS_MODE=offline ./jarvis-mobile.sh
```

Local time/date, explicit memory commands, Android app actions, and supported phone integrations remain local. Live search and weather report unavailable in offline mode. A missing, unauthenticated, or unreachable AI provider is reported honestly; JARVIS does not substitute canned answers.

## Memory and live search

Long-term facts explicitly saved with `Remember this: ...` are stored in `~/.local/share/jarvis/memory.sqlite3`. Memory commands include `What do you remember about me?`, `Forget memory 3`, and `Clear my memory`. Clearing requires the follow-up `confirm clear my memory`; say `cancel memory` to leave data unchanged. Conversation context is separate in `conversations.sqlite3`, bounded to recent turns, and scoped by assistant session. Secret-like content is excluded from both stores.

Explicit requests such as “search for …” use the separate no-key Wikipedia API provider and return source URLs. “What is the weather in …” uses Open-Meteo geocoding/current conditions. These services need internet; JARVIS distinguishes current provider results from AI explanation and reports failed searches instead of claiming they succeeded.

## Android controls and privacy

The Android app keeps package `dev.jarvis.companion`, Android TTS, app discovery, authenticated pairing, notification listener, and optional Accessibility service. The manual tap-to-talk conversation flow is removed. The microphone button now enables or disables a **visible foreground wake-phrase service**: it waits for "Hey Jarvis" (including "Hey Jarvis, activate"), replies "Yes, I'm listening", processes exactly one command, speaks the result, and returns to wake mode. A persistent notification shows while it listens, with a Stop action. Errors restart with exponential backoff (800 ms to 30 s), not a busy loop.

Android blocks background apps from launching other screens. To make wake-mode app activation work, Controls offers an explained **Display over other apps** permission. Without it, wake mode returns a specific permission-required message instead of a misleading "app not installed" failure. The permission does not read screen content and can be revoked in Android Settings.

The TTS picker enumerates installed voices, prefers a masculine-tagged voice for the selected locale, persists the choice, and offers a preview. If no masculine voice exists, JARVIS uses a lower pitch/rate fallback and explains how to install voice data. The orb reflects listening/thinking/speaking/error/offline state.

App activation resolves spoken names through launcher intents with aliases and fuzzy matching; ambiguous names prompt for a choice, and missing apps report "App not installed". Calls are placed only after an in-app confirmation **and** the Android Phone permission. SMS and WhatsApp actions display the recipient and draft, require confirmation, then open the native composer; JARVIS does not press Send. Notification access and Accessibility remain optional settings that the user must enable manually. Notification text is sent for summarization only after a separate preview/confirmation. Accessibility actions are limited to explicit navigation or a confirmed visible-label tap.

The bridge only binds loopback and requires a private bearer token for commands. It accepts bounded structured chat and action-result requests. The planner may return a validated action request, but only the Android companion executes it; sensitive actions cannot be downgraded to no-confirmation by the planner. Request IDs, in-flight handling, and issued-action replay protection are enforced. Health status is returned only to an authenticated health request and contains provider state, not secrets.

## Tests and Android build

Run the Python checks from the repository:

```bash
python3 -m py_compile jarvis*.py mobile_bridge.py mobile_host.py tui.py
python3 -m pytest -q
bash -n jarvis.sh jarvis-mobile.sh jarvis-bridge.sh
git diff --check
```

The Android project is under `android/` and uses the existing Gradle wrapper, AGP 8.11.1, Gradle 8.13, and package `dev.jarvis.companion`. The SDK is configured through `android/local.properties`; build locally with:

```bash
cd android
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. `.github/workflows/android-debug.yml` also provides a manual GitHub Actions build. See [JARVIS_APK_BUILD.md](JARVIS_APK_BUILD.md) for install steps.

See [JARVIS_SETUP.md](JARVIS_SETUP.md), [JARVIS_TEST_REPORT.md](JARVIS_TEST_REPORT.md), and [JARVIS_COMPLETION_STATUS.md](JARVIS_COMPLETION_STATUS.md) for setup, evidence, limits, and the current checkpoint.
