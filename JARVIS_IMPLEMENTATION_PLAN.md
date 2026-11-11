# JARVIS Implementation Plan

The order the work was done in, and what each step produced. Everything here
is on the branch `codex/jarvis-android-debug`.

## Stage 1 — Audit

Inspect the repository, the branches, the workflow history and the running
system. Reproduce the findings from the earlier reports rather than trusting
them.

**Produced:** `JARVIS_PROJECT_AUDIT.md` and the reproduction of every
historical finding.

## Stage 2 — The voice stack

The most urgent problem, so it went first.

1. A single language declaration in `VoiceCapabilities`, with English,
   Indian English, British English, Tamil and Hindi. The recogniser now uses
   all of it instead of a hard coded filter.
2. The platform is asked which languages really have a model, using
   `checkRecognitionSupport`, so the interface can say what is true.
3. The recogniser is biased towards the command vocabulary and the
   confidence scores are read. A low confidence result is treated as not
   understood.
4. The custom voice file falls back to the engine when it cannot be played.
5. A voice diagnostics screen that reads the real microphone level with
   `AudioRecord`, runs a real recognition and times it, and speaks a real
   test phrase and waits for the utterance to finish.

**Produced:** `VoiceCapabilities.kt`, `VoiceDiagnosticsActivity.kt`.

## Stage 3 — The application integration engine

Each supported application is declared once, with its package, its actions,
its permissions, what it cannot do and how each action is carried out.

Covered: WhatsApp, WhatsApp Business, the phone, messages, Telegram, Gmail,
Maps, Chrome, YouTube, the calendar, the camera, the gallery, files, settings
and the Play Store. Anything else installed is still reachable through the
application resolver.

**Produced:** `AppIntegrationEngine.kt` and a new tier in the intent engine.

## Stage 4 — The natural commands

The commands in the brief, each routed locally and each tested:

| Spoken | Result |
|---|---|
| "Open WhatsApp." | the WhatsApp package |
| "Open my chat with Ramesh." | the WhatsApp chat action with the contact name |
| "Send Hi to Ramesh." | the message action, with a spoken confirmation |
| "Call Ramesh." | the contact action, with a spoken confirmation |
| "Open YouTube." | the YouTube package |
| "Search for coding tutorials in Chrome." | the search action |
| "Open my gallery." | the gallery package |
| "Turn on the flashlight." | the device action |
| "Navigate to Mumbai." | the maps navigation action |
| "Read my latest notifications." | the notifications action, with a spoken confirmation |
| "Open settings." | the settings package |

## Stage 5 — The backend intelligence

1. The cloud providers were written and wired in: OpenAI, OpenRouter and any
   OpenAI compatible endpoint. Each declares what it can do, so a request is
   never sent to a model that cannot answer it.
2. The disconnected OpenRouter client was made reachable.
3. The tokens a provider reports are counted and shown.
4. A provider that fails is recorded with its real error and the next one is
   tried. When none can answer, that is what is said.

**Produced:** `jarvis_cloud_providers.py`, the capability filter in both
routers, the token accounting in the core router.

## Stage 6 — The named specialists and the external services

1. The six roles were assigned to the clusters.
2. NOVA was added as a real quality assurance agent that runs the test suite.
3. The service registry was written with a real check for every service.
4. The GitHub client was written, read only, and it found the credential
   that already existed.

**Produced:** `jarvis_core/services.py`, `jarvis_core/github.py`, the roles.

## Stage 7 — The interfaces

1. The live network draws the real topology with the real state of each node
   and each connection.
2. The diagnostics screen shows the agents, the external services with the
   state their check found, and the task counters.
3. The voice diagnostics screen shows the microphone, the recogniser, the
   languages, the speech engine and the last error.

## Stage 8 — The tests and the build

The test matrix is in `JARVIS_TEST_REPORT.md`. The build is a clean Gradle
build with the unit tests and the APK.

## What is deliberately not done

* **JARVIS does not rewrite its own code.** A self editing assistant that
  has access to the device is a security hazard. It gathers information,
  detects problems, repairs what it can safely repair, and proposes changes
  in a report for a person to review.
* **The GitHub access is read only.** It reads the repository, the branches,
  the commits, the issues, the pull requests and the workflow runs. Writing
  needs an explicit approval step that has not been built.
* **Play Protect is not bypassed.** The warning on a debug APK is expected.
  The build is signed with the standard debug key and the manifest is
  ordinary.
