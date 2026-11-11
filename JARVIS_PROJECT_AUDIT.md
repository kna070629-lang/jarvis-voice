# JARVIS Project Audit

Audit of `kna070629-lang/jarvis-voice` on the branch `codex/jarvis-android-debug`,
carried out against the working tree and the running system on the device.

Every finding below was reproduced. Nothing is carried over from an earlier
report without being checked again.

## 1. Repository state

| | |
|---|---|
| Remote | `https://github.com/kna070629-lang/jarvis-voice.git` (a fork of `strognoff/jarvis-voice`) |
| Branch | `codex/jarvis-android-debug` |
| Android namespace | `dev.jarvis.companion` (the `com.strognoff.jarvis` tree is gone) |
| Application id | `dev.jarvis.companion` |
| Kotlin sources | 21 in `android/app/src/main/java/dev/jarvis/companion` |
| Kotlin tests | 5 classes, 54 tests |
| Python modules | 30 at the top level plus the `jarvis_core` package |
| Python tests | 151 tests |
| CI | GitHub Actions, workflow `Android debug APK`, green on every commit since 2026-11-10 |

## 2. Git history

The last four runs of the workflow failed on 2026-11-09 and every run since
has passed:

| Run | Result |
|---|---|
| `Add native JARVIS Android companion and CI build` | failure |
| `Fix Android Kotlin source set configuration` | failure |
| `Fix legacy accessibility resource for Android build` | failure |
| `Fix Android voice slider callback types` | success |
| every run after the merge | success |

The three failures are the AAPT2 and source set problems from the earlier
reports. The build configuration now sets
`java.setSrcDirs(listOf("src/main/java/dev/jarvis/companion"))`, so only the
current package compiles, and the workflow installs build tools 35.0.0 with
JDK 17. **Status: resolved.** The workflow finishes in about a minute.

Issues are disabled on the repository and there are no pull requests.

## 3. The Android entry point

`MainActivity` is the only launcher activity. The foreground service is
`WakeWordService`. `JarvisAccessibilityService` and
`JarvisNotificationListener` are optional and user granted. The companion
talks to the Python backend over `127.0.0.1:8765` with a bearer token that
is held in the Android Keystore through `TokenStore`.

## 4. Backend endpoints

| Method | Path | Purpose | Auth |
|---|---|---|---|
| GET | `/health` | the service status | optional |
| GET | `/selfcheck` | the health checks | required |
| GET | `/v1/network` | the live topology | required |
| GET | `/v1/node` | the detail of one node | required |
| GET | `/v1/services` | the real service checks | required |
| POST | `/v1/command` | a chat request | required |
| POST | `/v1/command` | `action: orchestrate` | required |

Every protected path returns 401 without the token and 200 with it. That was
checked with `curl` against the running backend.

## 5. Provider configuration

| Provider | Where | Used |
|---|---|---|
| OpenCode, local server | `jarvis_server_provider.py` | yes, the primary |
| OpenCode, one process per request | `ai_provider.py` | yes, the fallback |
| OpenRouter | `jarvis_cloud_providers.py`, `jarvis_openrouter.py` | selectable, not configured |
| OpenAI | `jarvis_cloud_providers.py` | selectable, not configured |
| Any OpenAI compatible endpoint | `jarvis_cloud_providers.py` | selectable |

`jarvis_openrouter.py` had a complete client with a free model policy and a
rotation on rate limits, and **nothing referenced it**. It is now reachable
through the `openrouter` provider name.

## 6. Historical findings, rechecked

| Finding from the earlier report | Result now | Evidence |
|---|---|---|
| Duplicate `com.strognoff.jarvis` tree | gone | `find` returns nothing |
| OpenClaw endpoint on 18789 refuses connections | not used | the endpoint was migrated to OpenCode on 4096; only a function name still mentions OpenClaw |
| Speech recognition hung | **partly real** | the recogniser had no error recording and the language list was limited |
| Text to speech produced no sound | **real** | a chosen custom voice file replaced the engine with no fallback |
| OpenRouter returned key errors | not reproducible | the key was never set, so no request was made |
| Android to backend connectivity unverified | **was broken** | the streaming request ran on the main thread and was refused |
| AAPT2 daemon problems | resolved | the workflow and the local build both succeed |
| The APK was blocked by Play Protect | not reproducible | the build is a normal debug APK signed with the debug key |

## 7. Components that were disconnected or unfinished

| Component | Problem | Now |
|---|---|---|
| `jarvis_openrouter.py` | a complete client that nothing used | reachable through the provider chain |
| `VoiceCommandParser.kt` | a second parser that nothing used | removed, with its test |
| `com/strognoff/jarvis` | a duplicate of the whole companion | removed |
| `jarvis_router.py`, `jarvis_actions.py` | the backend had two routers | the action planner is the one the bridge uses |
| `WakeWordService` error state | the last recognition error was never kept | it is recorded and shown in the diagnostics |
| Language support | English and Hindi only | English, Indian English, Tamil and Hindi, checked against the platform |

## 8. Permissions in the shipped manifest

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | the microphone and the recogniser |
| `CALL_PHONE` | placing a call after a confirmation |
| `READ_CONTACTS` | calling a person and opening a chat by name |
| `POST_NOTIFICATIONS` | the foreground service notification |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | the listening service |
| `SYSTEM_ALERT_WINDOW` | launching an app from the background |
| `INTERNET` | the loopback bridge and the model providers |

Nothing in the manifest asks for an unrestricted background microphone, and
the service uses the microphone foreground service type, which is the
supported route.

## 9. What the audit found wrong

The full list with reproduction, root cause, severity, repair and evidence is
in `JARVIS_BUG_REPORT.md`. The serious ones were the main thread network call
that made the companion report a connection failure, the custom voice file
that could silence the assistant, and the language list that could not hear
Tamil.
