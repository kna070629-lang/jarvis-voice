# JARVIS setup and recovery

## Termux backend

```bash
cd ~/jarvis-workspace
./jarvis-mobile.sh
```

This runs the Python-first bridge on `127.0.0.1:8765` without starting the TUI microphone loop. Keep Termux open while using the Android companion. Stop with Ctrl+C. Restart with the same command. For the separate Python terminal voice interface, use `./jarvis.sh`.

The backend creates/uses local SQLite databases under `~/.local/share/jarvis/` with owner-only permissions. It prints the non-secret runtime mode, OpenRouter configuration state, model identifier, search provider name, and memory state. It never prints the pairing token or cloud key.

## Pair the Android app

With the backend running, open a second Termux session and run:

```bash
cd ~/jarvis-workspace
python3 mobile_bridge.py --show-token
```

Enter that private token in the installed app under **Controls → Secure Python Connection → Save token and connect**. Do not share the terminal output or a screenshot containing the token. The Android app retains it in its existing Android Keystore-backed store. The token is stable across backend restarts.

If Android reports offline, confirm the Termux backend is still running, then return to or relaunch the app so its authenticated loopback health check runs again. Confirm Termux and the Android app are on the same Android user/profile; Android loopback behavior across isolated profiles is not verified here.

## Configure free-only OpenRouter

Run the interactive setup; key input is hidden and the key is written to a private local file:

```bash
cd ~/jarvis-workspace
python3 jarvis_setup.py configure-openrouter
```

Obtain a real key from your OpenRouter account before running this setup. Do not place the key in shell history, source code, Git, or a shared terminal transcript. The file is `~/.config/jarvis/secrets.json` with mode `0600`, inside a `0700` directory. A process environment variable `OPENROUTER_API_KEY` is also supported for users who manage secrets outside the project.

JARVIS checks `OPENROUTER_MODEL` against live OpenRouter model metadata before every chat request. Only an explicit `:free` model with zero prompt and completion prices is accepted. The default is `google/gemma-4-31b-it:free`; to select another model, set `OPENROUTER_MODEL` to its exact free variant identifier. If pricing or availability cannot be verified, no chat call is made. No provider or paid model fallback is allowed. OpenRouter usage remains subject to the provider's account and free-tier limits.

Offline operation is explicit:

```bash
JARVIS_MODE=offline ./jarvis-mobile.sh
```

Offline mode handles local time/date, explicit memory operations, and Android actions supported by the companion. It makes no OpenRouter or live-search request. Without a configured key, web search can still return live Wikipedia results when online; natural AI responses are clearly reported as unavailable.

## User-approved memory

- `Remember this: I prefer concise answers`
- `What do you remember about me?`
- `Forget memory 3`
- `Clear my memory`, then `confirm clear my memory`
- `cancel memory` cancels a pending clear

The long-term memory database is separate from the bounded conversation context. Use the assistant commands to inspect/delete facts; direct database editing is not necessary. Secret-like input is not saved or sent to online providers.

## Android permissions and controls

The app requests microphone permission when the user taps the microphone. Android speech recognition may use the device's configured recognition service. Android TTS must have a compatible installed voice/language. Voice language, speed, and pitch are under Controls.

App opening uses launchable-app intents. To enable optional capabilities, open **Controls** and use the app's explanation buttons to visit Android settings. Enable notification access or Accessibility there only if desired. Android will require the user to approve those settings. No background microphone or screen-off wake-word feature is provided.

Calls open the dialer after a confirmation prompt; the user must place the call from the dialer. SMS/WhatsApp drafts show recipient and body, ask for confirmation, and open the native composer; the user must review and send there. Notification summaries preview permitted text and ask before sending it to the configured Python assistant, which may use the configured cloud AI provider.

## Python checks

```bash
cd ~/jarvis-workspace
python3 -m py_compile jarvis*.py mobile_bridge.py mobile_host.py tui.py
python3 -m pytest -q
bash -n jarvis.sh jarvis-mobile.sh jarvis-bridge.sh
git diff --check
```

See [JARVIS_TEST_REPORT.md](JARVIS_TEST_REPORT.md) for the actual latest results, and [JARVIS_APK_BUILD.md](JARVIS_APK_BUILD.md) for Android build/install instructions.
