# JARVIS Android Companion — Independent Review Report

Date: 2026-11-10
Repository: `/root/jarvis-workspace`
Branch: `jarvis/android-companion-review`

## Scope

Independent read-only review of the Android companion redesign and the backend changes for:

- free-only sequential OpenRouter model rotation on HTTP 429;
- authenticated mobile bridge integration;
- responsive Android layout and lifecycle behavior;
- masculine TTS voice selection and honest fallback behavior;
- security, resource handling, and test coverage.

The reviewer did not modify files. The implementer applied verified fixes only.

## Method

1. Static source inspection of `MainActivity.kt`, manifest, styles, `jarvis_openrouter.py`, `mobile_bridge.py`, `jarvis_engine.py`, `jarvis_search.py`, `tui.py`, and tests.
2. Two independent review passes focused on defects and integration behavior.
3. Follow-up verification pass after the final fixes.
4. Evidence checks run by the implementer: Python unittest suite, `py_compile`, shell syntax, `git diff --check`, Gradle unit tests, APK assembly, and a local bridge smoke test.

## Result Summary

- Critical defects remaining: **none found**.
- High-severity defects remaining: **none found** after fixes.
- Low-severity defects remaining: **none found** in the final verification of the reviewed areas; runtime device validation remains unavailable.
- Android build: **successful**.
- Android unit tests: **5/5 passed**.
- Python tests: **48/48 passed**.
- Bridge smoke test: health and command endpoints returned valid responses in offline mode.

## Findings and Fixes

| ID | Severity | File | Problem found | Resolution |
|---|---|---|---|---|
| H1 | High | `MainActivity.kt` | Health polling updated the orb/voice label while listening or speaking, overwriting active voice state. | Health updates are gated by `!voiceBusy && !listening`; polling runs on a separate single-thread executor (`healthIo`, `MainActivity.kt:65,679-721`). |
| H2 | High | `MainActivity.kt` | Controls dialog used fixed/limited space and could clip on small or landscape screens; no scroll container. | `showControls()` now uses a tracked scrollable `Dialog` sized to screen minus margins (`MainActivity.kt:381-420`). |
| H3 | High | `MainActivity.kt` | `conversationCheck` could be referenced before creation (lateinit path). | Checkbox is built eagerly with `buildControls()` before voice flow use. |
| H4 | High | `jarvis_openrouter.py` | Original client did not implement the required sequential free-model rotation and did not re-check all zero-price fields. | Sequential rotation implemented; configured model attempted first; only explicit 429 advances; catalog requires zero/missing prompt, completion, request, and image pricing (`jarvis_openrouter.py:57,113-114,139-166,221-236`). |
| M1 | Medium | `mobile_bridge.py` | Non-ASCII bearer tokens could raise `TypeError` during comparison and produce a 500-style failure. | Tokens are validated ASCII; comparisons are byte-wise with `hmac.compare_digest` (`mobile_bridge.py:38,59,157,180`). |
| M2 | Medium | `jarvis_engine.py` | Concurrent bridge/TUI turns could interleave shared pending-confirmation state. | Per-engine `threading.Lock` serializes `process()` (`jarvis_engine.py:45,68-72`). |
| M3 | Medium | `jarvis_openrouter.py`, `jarvis_search.py` | `HTTPError` responses were not explicitly closed on several error paths. | All reviewed paths close the error response before re-raising (`jarvis_openrouter.py:63-67,88,189`; `jarvis_search.py:51-58,98-105`). |
| M4 | Medium | `jarvis_openrouter.py` | Modality filtering accepted non-text models (for example `image->text` and, after the first fix, still `text->image`). | Both input and output sides must contain `text`; empty modality remains an explicit compatibility fallback (`jarvis_openrouter.py:69-78`). |
| L1 | Low | `MainActivity.kt` | `voiceBusy` could remain `true` after cancelled local actions, missing token, TTS unavailable, permission denial, or `onPause` when not listening. | `finishVoiceTurn()` clears the flag and is called from all non-terminal/cancel paths; `onPause` clears unconditionally (`MainActivity.kt:1414-1416,1253-1258`). |
| L2 | Low | `MainActivity.kt` | Slider height used raw pixels. | Slider height now uses `dp(...)`. |
| L3 | Low | `MainActivity.kt` | `setSingleLine(true)` after `maxLines` prevented intended wrapping in voice/dialog labels. | `setSingleLine(false)` is applied before `maxLines` in the affected labels. |
| L4 | Low | `MainActivity.kt` | Conversation auto-resume could start listening behind an open dialog. | TTS `onDone` checks `!dialogsShowing()` (`MainActivity.kt:1184`). |
| L5 | Low | `mobile_bridge.py` | A failing status provider could abort a health response. | Status provider exceptions are caught and filtered; invalid values are ignored. |
| L6 | Low | `mobile_bridge.py` | Header writes could also raise after a disconnected client. | Entire send path is inside `try` with `close_connection` in `finally` (`mobile_bridge.py:136-149`). |
| L7 | Low | `mobile_bridge.py` | Token validation allowed non-ASCII override values that cannot safely be sent as HTTP headers. | ASCII enforcement added for new and existing tokens (`mobile_bridge.py:38,59`). |

## Evidence

- `python3 -m unittest discover -s tests` → `Ran 48 tests ... OK`.
- `python3 -m py_compile jarvis*.py mobile_bridge.py mobile_host.py tui.py` → success.
- `bash -n jarvis.sh jarvis-mobile.sh jarvis-bridge.sh` → success.
- `git diff --check` → no whitespace errors.
- Gradle: `:app:testDebugUnitTest :app:assembleDebug` → `BUILD SUCCESSFUL`.
- Android test result: `tests="5" skipped="0" failures="0" errors="0"`.
- Bridge smoke test with `JARVIS_MODE=offline` and a temporary HOME:
  - public health → 200;
  - authenticated health → 200 with `runtime.mode=offline`;
  - command → 200 with a local time response.
- Secret scan of changed source files found no baked-in API key or bearer token.

## Residual Risks and Unverified Items

1. No Android emulator or device was available. Layout behavior at 320, 360, 393, 412, 480, and 800×360 logical widths, keyboard resizing, and real TTS voice metadata were not runtime-tested. The implementation was statically audited and compile-verified only.
2. Masculine voice availability depends on the device TTS engine. The app selects masculine-tagged voices when present, otherwise uses a documented pitch fallback and states that masculine sound is not guaranteed.
3. A zero-price model appearing in the OpenRouter catalogue does not guarantee live capacity. The implementation correctly reports exhaustion and never attempts a paid fallback.
4. `android/app/src/main/res/xml/accessibility_service.xml` and other staged project files contained pre-existing modifications before this task; they were not intentionally changed here.
5. Runtime end-to-end testing against a real OpenRouter account was not performed because no real key was used or required for the offline/local checks.
