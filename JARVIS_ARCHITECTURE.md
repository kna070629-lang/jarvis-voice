# JARVIS Siri-Level Architecture — Implementation Plan and Results

This document records the audit, the design decisions taken from the research
phase, what was implemented, and the measurements that were actually taken on
the device. It is an honest record: anything that is not measured or not
working is marked as such.

## 1. Audit findings

The repository is a Python-first assistant with an Android companion app.

* The Python backend lives in this repository and runs on the phone through
  Termux. It serves an authenticated loopback API on `127.0.0.1:8765`.
* The Android companion is a Kotlin foreground service that listens for the
  wake phrase and executes device actions.
* Two branches had diverged from `master`. `codex/jarvis-android-debug` held
  an earlier copy of the Android app, including a whole duplicate package tree
  (`com/strognoff/jarvis`) and a second command parser (`VoiceCommandParser`)
  that nothing referenced. Both were dead code and were removed.
* `OpenRouter` was already integrated (`jarvis_openrouter.py`) but was not on
  the request path. The live path used a subprocess provider that spawned a
  fresh model runtime for every question.

## 2. What the research changed

| Source | Idea adopted | Where |
| --- | --- | --- |
| Home Assistant Assist | Handle commands locally first, escalate to the model only on a miss | `IntentEngine` tier order |
| OpenVoiceOS | Ordered confidence tiers with an explicit escalation ladder | `IntentEngine` stop / structured / device / local / escalate |
| OpenVoiceOS | A dedicated cancel tier that never reaches the model | `IntentEngine.stopWords` |
| Home Assistant | Pre-filter before matching, and keep the local corpus small | Pre-compiled regexes only |
| Rhasspy | Intent name to handler registry | `JarvisAction` and `ActionPluginRegistry` |
| Vosk / Android | Bias the recogniser towards the command vocabulary | `EXTRA_BIASING_STRINGS` |
| Android 16 | Confidence gated recognition | `EXTRA_CONFIDENCE_SCORES` |
| Android 16 | Check on-device recognition support instead of assuming it | `isOnDeviceRecognitionAvailable` |

Two findings from the research were deliberately **not** acted on:

* **openWakeWord on-device.** Android gives third party apps no low power
  wake word hardware. Home Assistant's own documentation states that a
  third party assistant cannot match Google Assistant's battery profile for
  this reason. The research also found that `RecognizerIntent.EXTRA_AUDIO_SOURCE`
  (API 33) would make it technically possible. It was not implemented because
  it needs a custom model, and the pre-trained openWakeWord models are
  CC BY-NC-SA 4.0, which is not a licence a released app can use.
* **Siri's architecture.** Only the pipeline shape was taken. No Apple
  authentication, private server or proprietary protocol was reproduced.

## 3. The local-first pipeline

```
VOICE INPUT
 -> WAKE WORD            (WakePhrase, foreground service)
 -> SPEECH RECOGNITION   (SpeechRecognizer, biased and confidence gated)
 -> LOCAL INTENT         (IntentEngine, ordered tiers)
 -> PERMISSION CHECK     (ActionPluginRegistry, ActionDispatcher)
 -> ANDROID ACTION
 -> VOICE CONFIRMATION   (spoken yes for sensitive actions)
```

The engine routes an utterance through ordered tiers. The cheapest tier that
understands the request wins, and the model is only asked when no local tier
can understand it.

| Tier | Handles | Cost |
| --- | --- | --- |
| 0 stop | "stop", "cancel", "never mind" | exact set lookup |
| 1 structured | app launch, call, message, alarm, navigation | pre-compiled regex |
| 2 device | flashlight, volume, media, timer, dialer | pre-compiled regex |
| 3 local | battery, device info, calculation, wifi, bluetooth, dnd, screen | pre-compiled regex + plugin |
| 4 escalate | everything else | the model |

The engine only routes. It never touches the device, so it has no Android
dependency and is unit tested on its own.

## 4. Plugin system

Every local capability implements `JarvisAction`:

```kotlin
interface JarvisAction {
    val id: String
    val description: String
    val requiredPermissions: List<String>
    val requiresNetwork: Boolean
    fun execute(context: Context?, arguments: Map<String, String>): ActionOutcome
}
```

`ActionOutcome` carries a real status. A plugin that could not run reports a
failure, it never claims success.

Registered plugins: `device.battery`, `device.info`, `device.calculate`,
`device.wifi`, `device.bluetooth`, `device.dnd`, `device.screen`.

## 5. Measured results on the device

Measured on this phone, not estimated.

**Local intent routing**, 2000 requests across ten phrases:

| Metric | Measured | Target |
| --- | --- | --- |
| p50 | 0.009 ms | under 200 ms |
| p95 | 0.014 ms | under 200 ms |
| max | 1.257 ms | under 200 ms |

The local tier is roughly four orders of magnitude faster than the target.

**Streamed answer**, end to end over the loopback bridge:

| Event | Time |
| --- | --- |
| first sentence audible | 5.6 s |
| second sentence audible | 6.1 s |
| full answer | 6.2 s |

**Model round trip**, warm server and session: 8 to 13 s. The free inference
tier is the floor here, not the local architecture.

**Model fallback**: the server provider is given a 25 second budget. When it
stalls the command line provider answers instead, so a request is never lost.

## 6. Test results

| Suite | Result |
| --- | --- |
| Android unit tests | 41 passed, 0 failed |
| Python tests | 111 passed, 0 failed |
| Clean Gradle build | BUILD SUCCESSFUL, 40 tasks executed |

The Android tests cover the intent engine tiers, the stop tier, the sensitive
action confirmation, the local routing latency budget, the plugin metadata and
the arithmetic safety rules.

## 7. What is still incomplete

* **On-device wake word.** Not implemented. See the reasoning in section 2.
* **Barge-in, interrupting the assistant's own speech.** The recogniser is
  stopped while the assistant talks, so the speech cannot be cut short yet.
* **Accessibility driven UI navigation.** `click_text` taps one control by its
  label. Multi step navigation inside an app is not implemented.
* **Contact writing.** Contacts are read only. There is no action that adds a
  contact or edits one.
* **Camera actions.** Only the flashlight is controlled. There is no photo or
  video action.
* **The free inference tier stalls.** The provider occasionally takes more
  than 25 seconds. The fallback covers it, but a faster tier would be better.

## 8. Android limitations that cannot be engineered around

* **No unrestricted background microphone.** `RECORD_AUDIO` is a while in use
  permission. A microphone foreground service is the supported route, and the
  process is killed if the app is swiped away.
* **A microphone foreground service cannot be started from the background** on
  Android 12 and later, except in narrow documented cases.
* **`SpeechRecognizer` is not designed for continuous recognition.** The
  platform documentation warns that the implementation may stream audio to
  remote servers and that continuous use is a battery and bandwidth problem.
  Wake mode therefore listens in short bursts and stops.
* **Wake word battery.** Google Assistant uses dedicated low power hardware
  that is not available to third party apps.
* **Background job quotas.** From Android 16, jobs started by a foreground
  service are subject to runtime quotas, and `setImportantWhileForeground` is
  ignored. The model calls are therefore made on plain threads inside the
  service rather than through WorkManager.

## 9. Build and install

```bash
cd android
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
# output: android/app/build/outputs/apk/debug/app-debug.apk
```

Copy the APK to the phone and tap it, allowing installation from that file
app, or install over USB with `adb install -r app-debug.apk`.

The backend runs on the phone and is started with `./jarvis-bridge-start.sh`.
It prints the pairing token, and the app needs that token saved under
**SECURE PYTHON CONNECTION** before it can reach the model.
