# JARVIS Android companion

This is an additive native Android module in `android/`. It keeps the existing
Python/Termux assistant and OpenClaw integration in place. The Android app owns
speech recognition, Android TTS, app intents and optional Android services.
`mobile_bridge.py` exposes a small authenticated loopback API and delegates
chat to the existing `tui.send_message()` OpenClaw path.

## Development/build environment

The repository was inspected in Termux/PRoot. Java 21 and the system `aapt` are
present. The Android SDK platform/build tools, and `adb` are not installed. The
module is configured for Android Gradle Plugin 8.11.1, Gradle 8.13, Kotlin
2.2.21, compile SDK 35, and Java 17 bytecode. It has no third-party runtime
dependencies. The Android Gradle build was attempted and stopped because no
Android SDK location is installed; an APK has not been generated.

To build, install JDK 17 or 21 and Android SDK platform 35 and build tools
35.0.0, then run from this directory (the checked-in Gradle wrapper downloads
Gradle 8.13 and verifies its published SHA-256):

```sh
./gradlew assembleDebug
```

The debug APK output will be `android/app/build/outputs/apk/debug/app-debug.apk`.
This output path is a build instruction, not evidence that an APK exists.

## Local Python bridge

In Termux, start `./jarvis-mobile.sh` from the repository. It uses the existing
Python/OpenClaw message functions without starting the TUI's competing
microphone/VAD loop. On first launch it creates a private mode-0600 pairing
token at `~/.config/jarvis/mobile.token`, prints it, and binds only to
`127.0.0.1:8765`. Paste the printed token in the companion's Secure Python
Connection section; the app stores it encrypted with Android Keystore.
Keep Termux running while using the app. The app rejects non-loopback bridge
URLs and uses bearer authentication, request size limits, connection timeouts,
request IDs and duplicate suppression. It accepts only `chat` requests; there
is no shell or arbitrary-command endpoint. Logs omit message bodies. The regular
`jarvis.sh` TUI also starts the companion listener when run.

The backend request shape is:

```json
{"action":"chat","request_id":"unique-client-id","text":"user's recognized or typed message"}
```

Replies are text only. The app does not turn AI output into Android intents or
accessibility actions. The OpenClaw session remains the one already configured
by `tui.py`.

## Android behavior and permissions

- Microphone access is requested when the user taps Listen. Listening stops at
  the recognizer's end-of-utterance; there is no always-on listening or wake word.
- Android SpeechRecognizer supplies partial and final results, and Android TTS
  speaks replies with adjustable speed and pitch. Availability depends on the
  device's installed speech/TTS engines and language packs. Conversation mode
  can reopen recognition while the app remains open; it has no wake word or
  background microphone service.
- App discovery uses launchable activities visible to Android package
  visibility. Shortcuts use standard app launches, dialer, messaging, camera,
  Settings and clock intents where available.
- Accessibility is optional and must be enabled in Android Settings. Explicit
  controls provide Back/Home navigation and one exact-label click on a visible
  enabled control after user action. Password fields and sensitive labels are
  excluded. No gesture automation or security bypass is implemented.
- Notification access is optional and must be granted in Android Settings.
  The listener keeps up to 20 permitted title/text items in memory. A preview
  and separate confirmation are required before sending their text to OpenClaw.
- WhatsApp drafts show the recipient and text for confirmation, then open a
  WhatsApp conversation draft. The user presses Send in WhatsApp.

## Current verification boundary

Implemented source is not the same as a successful Android build or on-device
behavior. The Android SDK is missing and no Vivo device is attached through
`adb`; APK generation, installation, permission flows, speech engine behavior,
app intents and Android 14 behavior remain unverified. A local Kotlin unit test
exists for voice command parsing but could not run without the Android SDK.
