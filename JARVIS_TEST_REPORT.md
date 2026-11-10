# JARVIS test report

Recorded on 2026-11-09 in `/root/jarvis-workspace`, branch `master`.

## Passed

- `python3 -m py_compile jarvis*.py mobile_bridge.py mobile_host.py tui.py` — passed.
- `pytest -q` — **34 passed**. Tests cover SQLite memory save/retrieve/delete/session separation and long-token rejection, conversation persistence, local-only routing, credential-like prompt rejection, mocked OpenRouter success, free model pricing enforcement, paid/free-alias rejection, timeout/rate-limit/offline behavior, setup file permissions, web/weather provider parsing, mobile authentication, duplicate request protection, and engine integration.
- `bash -n jarvis.sh jarvis-mobile.sh jarvis-bridge.sh` — passed.
- `git diff --check` — passed.
- XML parsing for the Android manifest, values, and XML resources — passed.
- A controlled runtime check started `./jarvis-mobile.sh` with an isolated temporary database, `JARVIS_MODE=offline`, and a test-only token. It observed startup on `127.0.0.1:8765`, a public health response without runtime details, an authenticated health response with offline/memory status, and an authenticated local “What time is it?” command returning a device-local time. The temporary test process and its databases were stopped/cleaned afterward.

## Blocked or not run

- `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` — **failed before compilation** because Android SDK location is unavailable (`ANDROID_HOME`/`ANDROID_SDK_ROOT` unset; no `sdkmanager`). Gradle reported `SDK location not found`. Therefore Kotlin compilation, Android unit tests, APK assembly, APK signature/package verification, and installation were not run.
- Android XML resources parse, but this does not substitute for an Android Gradle build.
- No connected `adb` or `kotlinc` is available.
- OpenRouter real provider connectivity/authentication was not tested. No API key is configured in this environment, and no real cloud request was made. Free-model selection and API handling were tested only with mocked responses. No paid request was made.
- Wikipedia/Open-Meteo were tested with mocked responses; live search/weather was not called during tests.
- Android microphone, TTS, app intents, call/SMS/WhatsApp confirmation dialogs, notification access, Accessibility, and the new interface were not physically tested in this session. The user previously reported microphone, bridge, and audible TTS working before this migration; that prior device state is preserved, not freshly reverified.
- No APK was produced, installed, or launched.

## Security checks

- No credential-shaped key/token value was found in the inspected changed runtime/config/UI/docs files.
- The bridge integration test verifies unauthenticated chat rejection and local loopback binding; duplicate request IDs do not repeat processing.
- The controlled launcher test used an isolated non-production token and temporary SQLite paths. No user credential was printed.

## Exact next command

Once this reviewed source is present on a GitHub branch, run the existing workflow manually:

```bash
gh workflow run android-debug.yml --ref <reviewed-branch>
```

Then inspect the run and download its `jarvis-android-debug-apk` artifact as described in [JARVIS_APK_BUILD.md](JARVIS_APK_BUILD.md). This command has not been run because the current changes are local and uncommitted.
