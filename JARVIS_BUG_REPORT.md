# JARVIS Bug Report

Every entry was reproduced on the device or in the test suite. The status
column says what is true now, not what was intended.

## B1 — The companion could not reach the backend

| | |
|---|---|
| **Component** | `WakeWordService.chatWithBackend`, `BackendClient` |
| **Severity** | critical |
| **Reproduced** | Say a command in wake mode. The assistant said "Backend unavailable: connection failed". |

**Root cause.** The streaming request was made on the main thread.
Android refuses network work there and raises
`NetworkOnMainThreadException`, whose message is null. The error handler
fell back to a default string and printed "connection failed", which made it
look like the server was down. The server was fine.

**Repair.** The request moved to its own thread, and the activity path uses
the background executor. The error handler now reports the real exception.

**Evidence.** A wake mode request now reaches the backend and the answer is
spoken. The unit tests cover the call.

**Status.** Fixed.

## B2 — The assistant could go silent

| | |
|---|---|
| **Component** | `CustomVoicePlayer`, `MainActivity.speakResponse`, `WakeWordService.speak` |
| **Severity** | critical |
| **Reproduced** | Choose a custom voice file that cannot be decoded, then speak a reply. Nothing was said. |

**Root cause.** A chosen voice file replaced the text to speech engine
completely. There was no path back to the engine, so one bad file silenced
the assistant for every reply.

**Repair.** The player reports the failure separately from the completion.
Both the activity and the service fall back to the Android engine.

**Evidence.** `CustomVoicePlayer` has two callbacks and the callers use the
failure path to reach the engine.

**Status.** Fixed.

## B3 — The assistant could not hear Tamil

| | |
|---|---|
| **Component** | `WakeWordService.languageCandidates`, `MainActivity` |
| **Severity** | high |
| **Reproduced** | Ask for the language list. Only English (US), English (India) and Hindi (India) were offered. |

**Root cause.** The candidate filter was hard coded to English and Hindi, so
Tamil was dropped before the recogniser ever saw it. Indian English was only
reachable if it had been saved by hand.

**Repair.** The language list is now a single declaration in
`VoiceCapabilities` and the recogniser uses all of it. The diagnostics screen
asks the platform which languages really have a model, using
`checkRecognitionSupport`.

**Evidence.** The unit test checks that `ta-IN` and `en-IN` are present.

**Status.** Fixed.

## B4 — The supported applications were not reachable

| | |
|---|---|
| **Component** | `AppIntegrationEngine`, `IntentEngine` |
| **Severity** | high |
| **Reproduced** | Say "open my chat with ramesh" or "navigate to mumbai". The request went to the model instead of the phone. |

**Root cause.** There was no application integration layer. Only a handful
of hard coded regular expressions existed, and they did not cover the
applications on the phone, their deep links or their unsupported actions.

**Repair.** `AppIntegrationEngine` declares each supported application with
its package, its actions, its permissions and what it cannot do. The intent
engine has a tier that routes to it, and the actions run through the
dispatcher with the permission check and the confirmation.

**Evidence.** 14 unit tests cover the routing, the arguments and the
refusals.

**Status.** Fixed.

## B5 — The application activity was not declared in the manifest

| | |
|---|---|
| **Component** | `AndroidManifest.xml` |
| **Severity** | high |
| **Reproduced** | Install a build with the diagnostics activity. Tapping the button raised an activity not found error. |

**Root cause.** The manifest edit was written to the wrong path, so the
activity existed in the source but not in the manifest.

**Repair.** The manifest now declares `NetworkActivity`,
`VoiceDiagnosticsActivity` and `DiagnosticsActivity`.

**Evidence.** The manifest in the built APK is checked with `aapt2 dump`.

**Status.** Fixed.

## B6 — The network view could not show the outer ring

| | |
|---|---|
| **Component** | `jarvis_core.topology` |
| **Severity** | medium |
| **Reproduced** | Add a seventh cluster and read the topology. It raised a key error. |

**Root cause.** The position rings were hard coded to six points while the
cluster list was free to grow, so the extra cluster had no position.

**Repair.** The rings are built from the length of the list.

**Evidence.** The unit test checks that every node has a position.

**Status.** Fixed.

## B7 — A failed task said nothing

| | |
|---|---|
| **Component** | `jarvis_core.orchestrator` |
| **Severity** | medium |
| **Reproduced** | Run the test suite through the quality agent when it fails. The answer was empty. |

**Root cause.** The orchestrator stopped at the first failure before it took
the message the agent had produced.

**Repair.** The result is taken before the loop breaks.

**Evidence.** The quality agent now reports the real failure output.

**Status.** Fixed.

## B8 — The word was in the wrong place for the speech line

| | |
|---|---|
| **Component** | `jarvis_core.clusters`, `jarvis_core.engines.planner` |
| **Severity** | medium |
| **Reproduced** | Say "turn on the flashlight" or "volume up". The planner matched the general opening rule instead of the device rule. |

**Root cause.** The rules only accepted the state word after the noun, while
people say it before. The pattern for a call also took the first group, which
was empty for the second alternative.

**Repair.** The rules accept both orders and take the first group that
actually matched.

**Evidence.** The device commands are routed and the spoken line is correct.

**Status.** Fixed.

## B9 — The network geometry drifted from the code

| | |
|---|---|
| **Component** | `jarvis_core.topology` |
| **Severity** | low |
| **Reproduced** | Compare the node count in the topology with the test expectation. |

**Root cause.** The tests hard coded 13 nodes and 6 clusters, so adding the
quality agent broke them.

**Repair.** The topology is the single definition and the tests read from it.

**Evidence.** The tests count the kinds rather than the totals.

**Status.** Fixed.

## B10 — The agent registry was written with Kotlin syntax

| | |
|---|---|
| **Component** | `jarvis_core.registry` |
| **Severity** | medium |
| **Reproduced** | Import the package. It raised a syntax error. |

**Root cause.** A Kotlin property was written into the Python module during
a patch.

**Repair.** The attribute is a plain Python class attribute.

**Evidence.** The package imports and 151 tests pass.

**Status.** Fixed.

## B11 — GitHub could not be reached from the backend

| | |
|---|---|
| **Component** | `jarvis_core.github`, `jarvis_core.services` |
| **Severity** | medium |
| **Reproduced** | The service check reported "no GitHub credential is available". |

**Root cause.** The credential was only read from the environment, but the
account is signed in through the GitHub CLI, which stores its token in its
own configuration file.

**Repair.** The credential is taken from the environment first and then from
the CLI configuration. The token is never logged and never returned.

**Evidence.** The service now reports connected and reads the real branches,
commits and workflow runs.

**Status.** Fixed.

## B12 — The play store was called a gallery

| | |
|---|---|
| **Component** | `AppIntegrationEngine` |
| **Severity** | low |
| **Reproduced** | Read the integration list. The gallery and the play store shared a package name. |

**Root cause.** A copy and paste error when the list was written.

**Repair.** The play store points at `com.android.vending`.

**Evidence.** The unit test checks the expected packages.

**Status.** Fixed.

## Still open

| Item | Why it is open |
|---|---|
| The speech cannot be interrupted | the recogniser is stopped while the assistant speaks, so it cannot hear itself being cut off. Starting the recogniser during the speech would make it hear the assistant and act on its own voice. |
| The voice engine is the only option on this device | `com.google.android.tts` is the only engine installed, so there is no fallback engine to add. |
| The model tier is slow | the free inference tier answers in 8 to 13 seconds. A local model would be faster but there is no room for one in the current build. |
| The play protect warning on install | a debug APK signed with the debug key always warns. Installing from an unknown source is what triggers it. Nothing bypasses it. |
