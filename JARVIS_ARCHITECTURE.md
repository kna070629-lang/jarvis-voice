# JARVIS Architecture

## 1. The shape

The system has three levels and one place where everything is decided.

```
                    the outer clusters
              ATLAS   LUNA   VEGA
                \      |      /
                 \     |     /
            NOVA -- TITAN -- ORION
                \     |     /
                 the inner engines
            memory  planner  router
            security  verification  comms
                     \  |  /
                      the core
```

| Level | Nodes | What they do |
|---|---|---|
| Core | 1 | plans, routes, authorises, verifies, combines |
| Inner | 6 | memory, task planning, model router, security, verification, context and communication |
| Outer | 7 | voice and conversation, research and web, development and coding, Android and device control, automation and tools, knowledge and learning, quality assurance |

The geometry lives in `jarvis_core/topology.py`. The backend and the
interface both read it, so the picture cannot drift from the code.

* The core sits at the centre.
* The six engines form a compact ring around it.
* The clusters sit on a wider ring, turned so they do not line up into spokes.
* Each engine is joined to the two engines two steps away, which produces the
  crossing connections, and to the one opposite it.
* Each cluster reaches the engine that owns it and one more across the ring,
  so it has a direct and an indirect path.

## 2. The specialists

The named roles are logical roles. They share the model router, so there are
not six separate model instances.

| Role | Node | Responsibility |
|---|---|---|
| ATLAS | `cluster.automation` | orchestration and workflows |
| VEGA | `cluster.research`, `cluster.knowledge` | research, web intelligence, long term knowledge |
| ORION | `cluster.development` | code analysis, repository inspection, GitHub |
| LUNA | `cluster.voice` | conversation and language |
| TITAN | `cluster.device` | Android and device operations |
| NOVA | `cluster.qa` | verification and quality assurance |

NOVA is real. It runs the backend test suite, reads the exit code and
reports the summary. It never claims a suite passed when it did not.

## 3. The message protocol

Every communication uses one envelope.

| Field | Meaning |
|---|---|
| `task_id` | the work this belongs to |
| `agent_id` | who handled it |
| `source` | who sent it |
| `destination` | who should receive it |
| `task_type` | what kind of work it is |
| `context_reference` | the shared context it may read |
| `permissions` | what it is allowed to do |
| `status` | where it got to |
| `result` | what it produced |
| `evidence` | what proves it |
| `timestamp` | when |

**An agent never calls another agent.** Everything goes through the
communication engine to a registered destination. A message to a
destination that was never registered is dropped and counted, so a model
cannot invent a target and an agent cannot reach a subsystem it was not
given.

## 4. The request pipeline

```
voice input
 -> wake phrase              WakePhrase, foreground service
 -> speech recognition      SpeechRecognizer, biased and confidence gated
 -> intent tier 0           cancel words, never escalated
 -> intent tier 1           the supported applications
 -> intent tier 1b          the structured device actions
 -> intent tier 1c          the local device controls
 -> intent tier 2           the local plugin registry
 -> intent tier 3           escalate to the model
 -> permission check        the security engine
 -> action dispatch         the dispatcher, then the application
 -> execution result
 -> user response
```

A request that any local tier understands never reaches the model.

## 5. The Android side

| Layer | Files |
|---|---|
| Service and recognition | `WakeWordService.kt`, `WakePhrase.kt`, `VoiceCapabilities.kt` |
| Routing | `IntentEngine.kt` |
| Local plugins | `JarvisAction.kt`, `LocalActions.kt` |
| Device actions | `ActionDispatcher.kt`, `ActionLedger.kt`, `AppIntegrationEngine.kt` |
| Applications | `AppResolver.kt`, `ContactResolver.kt` |
| Speech output | `VoicePreferences.kt`, `CustomVoicePlayer.kt` |
| Display | `VoiceWaveformView.kt`, `NeuralNetworkView.kt`, `NeuralTopology.kt` |
| Panels | `NetworkActivity.kt`, `DiagnosticsActivity.kt`, `VoiceDiagnosticsActivity.kt` |
| Backend link | `BackendClient.kt`, `TokenStore.kt` |

## 6. The Python side

| Layer | Files |
|---|---|
| The core | `jarvis_core/orchestrator.py` |
| The protocol | `jarvis_core/messages.py` |
| The registry | `jarvis_core/registry.py` |
| The graph | `jarvis_core/graph.py` |
| The context | `jarvis_core/context.py` |
| The bus | `jarvis_core/events.py` |
| The shape | `jarvis_core/topology.py` |
| The clusters | `jarvis_core/clusters.py` |
| The engines | `jarvis_core/engines/` |
| The services | `jarvis_core/services.py` |
| GitHub | `jarvis_core/github.py` |
| The providers | `ai_provider.py`, `jarvis_server_provider.py`, `jarvis_cloud_providers.py` |
| The bridge | `mobile_bridge.py`, `mobile_host.py` |

## 7. The security boundary

* The security engine refuses the forbidden actions whatever a model asks
  for, and refuses anything that looks like a shell command.
* A missing permission stops the action.
* Calls, messages, opening a chat and reading notifications need a spoken
  confirmation.
* The verification engine catches a result that claims an action happened
  without carrying evidence, and a result that reports a failure and a
  success at the same time.
* The GitHub client is read only. Nothing in the code writes to a
  repository, opens a pull request or pushes.
* The pairing token and the provider keys never leave the backend, and the
  app never holds a provider key.

## 8. The external services

A service is only shown as connected when its connection was really tested.

| Service | How it is tested |
|---|---|
| the Android companion | a real request to the bridge health endpoint |
| the Python backend | the modules really import |
| OpenCode | a real request to the local server |
| OpenRouter | the model list is requested with the real key |
| OpenAI | the model list is requested with the real key |
| Web search | a real search is run |
| Web retrieval | a real page is read |
| GitHub | the real credential is used to read the account |
| the memory service | a fact is written and read back |
| the local tools | the repository is really read |

The states are connected, degraded, disconnected, authentication required,
permission required, error and unknown.
