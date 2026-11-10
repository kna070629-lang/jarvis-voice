# Android companion

This is the existing Android module at `android/`; its package identity remains `dev.jarvis.companion`. The active Kotlin source set is explicitly limited to `android/app/src/main/java/dev/jarvis/companion`. The older `com/strognoff/jarvis` source tree is retained and is not compiled.

The app uses Android `SpeechRecognizer` for tap-to-speak input, Android `TextToSpeech` for responses, and the existing encrypted pairing token store. It connects to the Python-first backend at `127.0.0.1:8765`; cleartext is permitted by network security configuration only for loopback. The Python server does not bind to the LAN.

The home view now uses a forest-green background and an organic orb whose animation follows the app's idle, listening, thinking, speaking, error, and unavailable states. Listening uses the SpeechRecognizer RMS callback. Speaking uses a speaking-state animation because the current Android TTS integration does not expose playback amplitude. There is no background microphone or wake-word service.

Use **Controls** to pair, choose language and voice rate/pitch, discover launchable apps, and open the optional Accessibility/notification settings. Those special permissions remain user-controlled. Accessibility exposes only the existing explicit Back/Home and exact visible-label action. Notification text remains on-device until its separate summary confirmation is accepted.

The local voice parser recognizes explicit app-open requests and confirmed drafts/calls. WhatsApp and SMS drafts show recipient and text before opening the native composer; JARVIS never sends them. A call request asks before opening the system dialer, where Android still requires the user to place the call.

Android build details and the current SDK/APK status are in [JARVIS_APK_BUILD.md](JARVIS_APK_BUILD.md).
