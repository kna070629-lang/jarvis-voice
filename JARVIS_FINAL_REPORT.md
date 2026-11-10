# JARVIS Android Companion — Final Report

Date: 2026-11-10
Branch: `jarvis/android-companion-review`

## 1. Summary

JARVIS now uses locally authenticated Codex and OpenCode CLIs as text providers instead of OpenRouter, runs both in isolated read-only sandboxes, and executes phone actions only through a validated plan plus the Android companion. The wake-phrase foreground service is the primary voice path; the unreachable manual tap-to-talk flow was removed.

## 2. Provider Migration

- `ai_provider.py` adds `CodexProvider`, `OpenCodeProvider`, `FallbackAIProvider`, and `AIProviderError`.
- Codex runs non-interactively with a throwaway `HOME`, private `CODEX_HOME` containing only a copied `auth.json`, `--ephemeral`, `--ignore-user-config`, `--ignore-rules`, and a read-only sandbox.
- OpenCode runs with `--pure`, an isolated `XDG_DATA_HOME` containing only a copied `auth.json`, and an inline config that denies every tool.
- Provider environment excludes JARVIS secrets, real `HOME`, `XDG_*`, and `CODEX_HOME`.
- Failures are explicit: not installed, not logged in, timeout, rate limited, empty output, and unusable output.
- A real Codex smoke call returned `provider: codex`, `reply: OK`.
- `jarvis_openrouter.py` and its tests remain only as historical code; the active chat path does not import them.

## 3. Bridge and Planner Security

- Authenticated loopback bridge with constant-time token comparison.
- Strict action schema: bounded type/argument names, eight arguments maximum, 240 characters per value.
- Planner cannot downgrade calls, SMS drafts, WhatsApp drafts, or notification reads to no-confirmation.
- Action results require a backend-issued action ID; replay, unknown IDs, and concurrent results are rejected atomically.
- Sensitive device text and secrets are never exposed in public health responses.
- Security response headers and body-size/read-timeout limits remain enforced.

## 4. Android Voice and Actions

- Visible foreground wake-phrase service listens for "Hey Jarvis", acknowledges, processes one command, speaks the result, and returns to wake mode.
- "Hey Jarvis, activate YouTube" executes in the same utterance without a second prompt.
- Exponential restart backoff (800 ms to 30 s) replaces tight retry loops.
- Action-result upload moved off the main thread.
- App activation resolves launcher intents with aliases and fuzzy matching; ambiguous names prompt, missing apps report specifically.
- Calls require confirmation plus the Phone permission; SMS/WhatsApp open the native composer after confirmation and are never sent automatically.
- Notification access and Accessibility remain optional and confirmation-gated.
- Android background-activity restrictions are handled with an explained, optional Display over other apps grant. Without it, wake mode reports a specific permission message instead of a false "app not installed" result.
- Duplicate delivery is blocked by the activity/service action ledger.
- Dark responsive UI, stateful orb, masculine-voice preference, preview, rate/pitch controls, and language shared with the wake service.

## 5. Evidence

- Python: `python3 -m unittest discover -s tests` → **78 tests, OK**.
- Python compile and shell syntax checks → success.
- `git diff --check` → clean.
- Android: `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` → **BUILD SUCCESSFUL**.
- Android unit tests → **21/21 passed** (ActionLedger 3, StructuredCommandParser 9, VoiceCommandParser 5, WakePhrase 4).
- Isolated bridge smoke test (offline, temporary HOME) → authenticated health, structured `open_app` plan, accepted action result, local time chat.
- APK:
  - Path: `android/app/build/outputs/apk/debug/app-debug.apk`
  - Size: `960334` bytes
  - SHA256: `a080565d9b4f6a89ea35a19332b84ecfbe55cd2ff454fd4ce55a9099743c3019`
  - Signature: APK Signature Scheme v2 verified (1 signer)
  - Downloads copy: `/sdcard/Download/JARVIS-companion-debug.apk`

## 6. Remaining Limitations

- No Android device/emulator is available here: install, pairing, wake detection, TTS voice metadata, screen-off behavior, permission dialogs, and physical action dispatch were not runtime-verified.
- OpenCode has no credentials in this environment; fallback behavior is covered by mocked tests, not a live call.
- Codex read-only sandbox is an agent-level isolation, not a separate OS user/container.
- Live weather/search calls were not repeated; mocked coverage remains.
