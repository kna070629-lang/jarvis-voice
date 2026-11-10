# JARVIS setup and recovery

## Termux backend

```bash
cd ~/jarvis-workspace
./jarvis-mobile.sh
```

This runs the Python-first bridge on `127.0.0.1:8765` without starting the TUI microphone loop. Keep Termux open while using the Android companion. Stop with Ctrl+C. Restart with the same command. For the separate Python terminal voice interface, use `./jarvis.sh`.

The backend creates/uses local SQLite databases under `~/.local/share/jarvis/` with owner-only permissions. It prints the non-secret runtime mode, AI provider order, search provider name, and memory state. It never prints the pairing token or provider credentials.

## Pair the Android app

With the backend running, open a second Termux session and run:

```bash
cd ~/jarvis-workspace
python3 mobile_bridge.py --show-token
```

Enter that private token in the installed app under **Controls → Secure Python Connection → Save token and connect**. Do not share the terminal output or a screenshot containing the token. The Android app retains it in its existing Android Keystore-backed store. The token is stable across backend restarts.

If Android reports offline, confirm the Termux backend is still running, then return to or relaunch the app so its authenticated loopback health check runs again. Confirm Termux and the Android app are on the same Android user/profile; Android loopback behavior across isolated profiles is not verified here.

## Configure the AI providers

JARVIS uses the locally installed agent CLIs as text-only chat providers. Authenticate them once with their own commands; do not put keys in this repository:

```bash
codex login
opencode auth login
```

The launcher defaults to Codex primary and OpenCode fallback. You can override the order or model per run:

```bash
cd ~/jarvis-workspace
JARVIS_AI_PRIMARY=opencode JARVIS_AI_FALLBACK=codex ./jarvis-mobile.sh
JARVIS_AI_TIMEOUT=90 ./jarvis-mobile.sh
JARVIS_CODEX_MODEL=<model> JARVIS_OPENCODE_MODEL=provider/model ./jarvis-mobile.sh
```

Each provider runs non-interactively in a throwaway temp home with an empty working directory. Codex uses a read-only sandbox, `--ephemeral`, no user config, no rules, and only a private copy of its auth file. OpenCode runs with `--pure` and every tool denied by inline config. Timeouts, output limits, and process-group cleanup are enforced. JARVIS reports the provider that answered and distinguishes not installed, not logged in, timeout, rate limit, empty output, and unusable output.

The legacy `python3 jarvis_setup.py configure-openrouter` command and `jarvis_openrouter.py` remain for history and old tests, but neither is used by the active chat path.

Offline operation is explicit:

```bash
JARVIS_MODE=offline ./jarvis-mobile.sh
```

Offline mode handles local time/date, explicit memory operations, and Android actions supported by the companion. It makes no AI-provider or live-search request. Online but unauthenticated providers are reported as "not logged in" instead of returning a canned answer.

## User-approved memory

- `Remember this: I prefer concise answers`
- `What do you remember about me?`
- `Forget memory 3`
- `Clear my memory`, then `confirm clear my memory`
- `cancel memory` cancels a pending clear

The long-term memory database is separate from the bounded conversation context. Use the assistant commands to inspect/delete facts; direct database editing is not necessary. Secret-like input is not saved or sent to online providers.

## Android permissions and controls

The microphone button enables the visible foreground wake-phrase service (`RECORD_AUDIO` + `FOREGROUND_SERVICE_MICROPHONE`). It listens for "Hey Jarvis", acknowledges by voice, processes one command, and returns to wake mode. A persistent notification shows while it listens and includes a Stop action. Disable it from the app, the notification, or the service stop command. Android speech recognition may use the device's configured recognition service; the selected language is shared with the wake service. Voice language, speed, pitch, and the preferred masculine voice are under Controls.

App activation resolves names through launcher intents with aliases/fuzzy matching. To launch apps from the background wake service, open **Controls → Explain and allow Display over other apps**. Android blocks background activity starts by default; without this grant, wake mode reports "Permission needed: allow Display over other apps" rather than claiming the app is missing. This permission does not read screen content. To enable optional notification or Accessibility features, use the explanation buttons in **Controls**; enable them only if desired.

Calls are placed only after an in-app confirmation and require the Android Phone permission; without it JARVIS returns a specific permission message and offers the app settings. SMS/WhatsApp drafts show recipient and body, ask for confirmation, and open the native composer; the user must review and send there. Notification summaries preview permitted text and ask before sending it to the configured Python assistant. Accessibility actions are limited to back/home/recents and a confirmed exact visible-label tap.

## Python checks

```bash
cd ~/jarvis-workspace
python3 -m py_compile jarvis*.py mobile_bridge.py mobile_host.py tui.py
python3 -m pytest -q
bash -n jarvis.sh jarvis-mobile.sh jarvis-bridge.sh
git diff --check
```

See [JARVIS_TEST_REPORT.md](JARVIS_TEST_REPORT.md) for the actual latest results, and [JARVIS_APK_BUILD.md](JARVIS_APK_BUILD.md) for Android build/install instructions.
