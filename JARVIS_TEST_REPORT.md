# JARVIS test report

Recorded on 2026-11-10 in `/root/jarvis-workspace`, branch `jarvis/android-companion-review`.

## Passed

- `python3 -m unittest discover -s tests` — **78 tests, OK**. Coverage includes local routing, SQLite memory/conversation, mocked and real subprocess providers, provider fallback, bridge authentication, request-ID deduplication, structured action planning, planner confirmation enforcement, issued-action replay protection, action parsing, and mobile host singleton concurrency.
- `python3 -m py_compile jarvis*.py mobile_bridge.py mobile_host.py tui.py ai_provider.py` — passed.
- `bash -n jarvis.sh jarvis-mobile.sh jarvis-bridge.sh` — passed.
- `git diff --check` — passed.
- Real Codex provider smoke test through the new sandbox: `provider: codex`, `reply: OK`. The provider ran with a throwaway `HOME`, a private copy of `auth.json`, `--ephemeral`, `--ignore-user-config`, `--ignore-rules`, and a read-only sandbox.
- Isolated bridge smoke test (`JARVIS_MODE=offline`, temporary HOME/databases, test-only token):
  - public/authenticated health → 200, `authenticated=true`, `runtime.mode=offline`;
  - chat `open YouTube` → structured `open_app` request;
  - action result → accepted once with `status=succeeded`;
  - chat `What time is it?` → local time response.
- Android: `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` — **BUILD SUCCESSFUL**.
- Android unit tests — **21/21 passed**:
  - `ActionLedgerTest` 3/3
  - `StructuredCommandParserTest` 9/9
  - `VoiceCommandParserTest` 5/5
  - `WakePhraseTest` 4/4
- Debug APK:
  - Path: `android/app/build/outputs/apk/debug/app-debug.apk`
  - Size: `960334` bytes
  - SHA256: `a080565d9b4f6a89ea35a19332b84ecfbe55cd2ff454fd4ce55a9099743c3019`
  - Clean-build signature: APK Signature Scheme v2 verified, 1 signer
  - Copied to `/sdcard/Download/JARVIS-companion-debug.apk` (byte-identical)

## Fixed in this pass

- Provider subprocess isolation: throwaway HOME/XDG, private auth copy only, no `~/.config/jarvis` visibility.
- Output-cap path no longer raises a non-exception; real size-cap and descendant-process cleanup tests added.
- Control/bidi characters are stripped and the full output is checked.
- Bridge planner cannot downgrade sensitive-action confirmation and only accepts the exact action/argument schema.
- Action-result replay handling is atomic, requires an issued action ID, and rejects unknown/concurrent results.
- Authenticated health now exposes the provider chain (`codex->opencode`) and no longer emits legacy OpenRouter/model fields.
- Mobile host engine creation is thread-safe.
- App-name parsing rejects sentence-like chat (`Open source is important`, `Start the meeting notes`, `Run a quick calculation`).
- Wake service action-result POST moved off the main thread.
- Single-utterance commands are supported: "Hey Jarvis, activate YouTube" executes without a second prompt.
- Removed the unreachable manual tap-to-talk STT flow and stale confirmation helpers.

## Not run / residual limits

- No Android device or emulator is available in this environment. APK installation, launch, real microphone/wake detection, TTS voice metadata, screen-off behavior, accessibility, notification access, and physical intent dispatch were not runtime-tested.
- OpenCode fallback was verified with mocked subprocess results; this environment reports zero OpenCode credentials, so a live fallback call was not made.
- Wikipedia/Open-Meteo live calls were not repeated during this pass; mocked coverage remains.
- Codex is a read-only agent sandbox, not a separate UID/container. A hard multi-tenant filesystem boundary would require OS-level isolation beyond this app.

## Security checks

- No API key or bearer token is embedded in source or logs.
- Provider environment excludes JARVIS secrets and real HOME/XDG paths.
- Planner output is validated; native execution happens only in the Android companion.
- Sensitive actions require both the confirmation flag and a confirmation token; calls, SMS drafts, WhatsApp drafts, and notification reads cannot be silently downgraded.
- Bridge action results require a backend-issued action ID and are replay-protected.
- Temporary smoke tests used an isolated non-production token and temporary SQLite paths.

## Next command

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Then test pairing, "Hey Jarvis, activate", one-command processing, app activation, male TTS, permission failures, and duplicate protection on the device.
