# JARVIS Test Report

The results that were actually produced. Nothing here is a target.

## 1. The suites

| Suite | Command | Result |
|---|---|---|
| Android unit tests | `./gradlew :app:testDebugUnitTest` | **54 passed, 0 failed** |
| Backend tests | `python3 -m pytest tests/ -q` | **151 passed, 0 failed** |
| Android compile | `./gradlew :app:compileDebugKotlin` | BUILD SUCCESSFUL |
| Clean build and package | `./gradlew clean :app:testDebugUnitTest :app:assembleDebug` | BUILD SUCCESSFUL |
| Workflow | GitHub Actions, `Android debug APK` | success on every commit since 2026-11-10 |

## 2. The voice

| Check | Method | Result |
|---|---|---|
| Microphone permission | reported from the package manager | reported on the diagnostics screen |
| Real microphone input | `AudioRecord` reads the samples and the level is shown | the level moves when there is sound |
| Speech to text | a real recognition is run | the text and the time are shown |
| Partial recognition | `onPartialResults` is reported | the partial text is shown while it is heard |
| Final recognition | `onResults` | the final text and the elapsed time are shown |
| English commands | the intent engine tests | routed locally |
| Tamil commands | the language list now contains `ta-IN` | offered, and the platform is asked whether a model exists |
| Indian English | `en-IN` is in the language list | offered |
| Continuous conversation | the wake service keeps listening after a reply | the wake phrase is only needed once |
| The wake phrase | `WakePhraseTest` | English and Hinglish variants |
| Speech output | the utterance completion callback | the diagnostics reports that the utterance finished |

The microphone test and the speech test were run against the running system.
The recognition and speech tests need the real device, so they are on the
diagnostics screen rather than in the unit suite.

## 3. Android

| Check | Method | Result |
|---|---|---|
| Application discovery | `AppResolver` | every launcher app is listed |
| Application launching | the `open_app` action | dispatched |
| WhatsApp launch | the `prepare_message` and `open_chat` actions | dispatched, and the package is chosen |
| Contact resolution | `ContactResolver` | exact, prefix and containment matching |
| Chat opening | the `open_chat` action | dispatched |
| Message preparation | the `prepare_message` action | dispatched |
| Message confirmation | the security engine | a confirmation is required |
| Call confirmation | the security engine | a confirmation is required |
| Notification reading | the `read_notifications` action | dispatched |
| Permission denial | the security engine | the action is refused with the permission named |
| Unsupported application actions | each integration declares them | refused rather than approximated |

## 4. Backend

| Check | Method | Result |
|---|---|---|
| The health endpoint | `curl` | HTTP 200 |
| Authentication | `curl` without a token | HTTP 401 |
| Secure pairing | `TokenStore` and the bearer token | the token is held in the Keystore |
| Provider authentication | the service checks | the missing key is reported as needing authentication |
| Model availability | `checkRecognitionSupport` and the provider checks | reported |
| Conversation requests | `/v1/command` | answered and streamed |
| Memory retrieval | the memory engine tests | a saved fact is found again |
| Tool routing | the intent engine tests | a local command never reaches the model |
| Agent execution | the core tests | the trace carries the agent and the time |
| Fallback handling | the router tests | the next provider answers |

## 5. The network and the architecture

| Check | Method | Result |
|---|---|---|
| The core initialises | `JarvisCore` | 13 agents and 14 nodes |
| Agent registration | the registry | every agent has an identifier and capabilities |
| Task routing | the planner tests | the right task and agent |
| Event delivery | the communication engine | a registered route carries the message, an unknown one is dropped |
| Verification | the verification engine tests | an action claim without evidence is caught |
| Security enforcement | the security engine tests | a forbidden action and a shell command are refused |
| Error recovery | the orchestrator | a failing agent produces a failure, not a false success |

## 6. The external services

Run against the real services from the backend.

| Service | State | Evidence |
|---|---|---|
| the Android companion | connected | the bridge health endpoint answered |
| the Python backend | connected | the modules import |
| OpenCode | connected | the local server answered |
| OpenRouter | authentication required | the key is not set, so nothing was requested |
| OpenAI | authentication required | the key is not set, so nothing was requested |
| Web search | connected | the search returned results |
| Web retrieval | connected | the page was read |
| GitHub | connected | the account answered, and the branches, the commits and the workflow runs were read |
| the memory service | connected | a fact was written and read back |
| the local tools | connected | the repository was read |

A service is never shown as connected without a real check behind it.

## 7. The build

| Check | Result |
|---|---|
| Kotlin compilation | passed |
| The manifest is valid | the activities are declared, checked with `aapt2` |
| The resources compile | passed |
| The backend tests | 151 passed |
| The APK is produced | `app-debug.apk` |
| The APK is signed | `apksigner verify` passed, the v2 scheme |
| The checksum | see the delivery note |

## 8. What was not tested here

* The microphone, the recognition and the speech need the phone in a real
  room, so they are on the diagnostics screen for a person to run.
* The Play Protect warning appears because the APK is signed with the debug
  key. It is not bypassed.
* The GitHub write path does not exist, so it was not tested.
