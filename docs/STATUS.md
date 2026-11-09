# Verified status and next steps

## As is

Baseline: **2026-11-08, main `210448b`**. This is a functional internal-testing app, not yet a demonstrated reliable vehicle assistant. This documentation/workspace consolidation does **not** fix application defects.

## Evidence and how to read it

The consolidated review is local-only: `.local/reviews/2026-11-08/review.txt` after migration (original read-only source: `D:\Projects\sierra-auto-reviews\2026-11-08\review.txt`). Its supporting logs and specialist reports distinguish executed reproductions, source-only findings and phone observations. No new live provider, microphone, DHU, ECU or truck test was run for this documentation update. A fresh **canonical-root consolidation Android run** then passed `assembleDebug testDebugUnitTest lintDebug` at `210448b`, exit 0, **569 tests/567 passed/2 skipped**, lint **0 errors/124 warnings**, live providers disabled and no install. Result/log/summary are in `.local/runs/consolidation-20261108/`; these were read for documentation, not inferred from a report. External policy/pricing pages were not rechecked; linked research is historical, not a platform guarantee.

- Fresh review Android run: `gradlew.bat --no-daemon assembleDebug testDebugUnitTest lintDebug`, Windows/JDK 17/SDK 36: **569 tests, 567 passed, 2 live-configuration skips**, no failures; lint **0 errors, 124 warnings**. Builds still have deprecation/Gradle warnings.
- Bridge: `uv sync --locked`, `uv run --locked pytest -q`: **104 tests, 100 passed, 4 failed on native Windows**. Failures are timeout classification (502 expected/504 received), a UTF-8 log read using the default encoding, and two subprocess fixtures missing `SystemRoot`. `PYTHONUTF8=1` fixed the targeted encoding test; CLI `--check` worked with inherited Windows environment. Linux CI was green at review time. Neither a green Linux run nor those workarounds make the Windows suite green.
- GitHub release **v0.1.110** and Play internal **versionCode 100005** were available; successful Play upload used the same main commit after shortening an overlong release name. Internal testing is not a public production listing or a guarantee of future review acceptance.

## Implemented versus unfinished

| Area | As-is at the baseline | Not established / next integration |
| --- | --- | --- |
| Car UI | Home, Places, Talk, History, Truck/Codes, Mic test, Conversation; host templates and limits | Release-host acceptance and actual truck launch need recorded evidence |
| Places | OSM Overpass/Nominatim, host-drawn map, navigation intent | Navigation can report success before handoff; failed fresh location can use an arbitrarily stale fix |
| Talk | Capture/transcription, tool loop, follow-up turns, Android/gateway/ElevenLabs speech | Phone-recognizer permission fallback, legacy focus and failure-path work remain |
| Duplex | Separate Conversation flow; OpenAI/ElevenLabs websocket adapters, interruption/guard, tools | Current-build completed exchanges, echo, barge-in, duration and truck routing unverified |
| Agent routing | Two choices: model/gateway or bridge; independent speech credentials; Hermes setup | Named profiles, spoken profile switching and per-profile histories remain proposed; old `voice/realtime` setting is not wired to Talk |
| OBD | Demo and classic/BLE transports, standard readings and stored codes, deny-by-default command allowlist | No `read_vehicle`/`read_trouble_codes` tools, no configurable vehicle alerts; real adapter acceptance absent |
| GM data | Catalogs, decoders and `GmTruckReader` | Not wired into Truck UI or tools; transmission temperature unfinished; oil-life DID catalogs disagree (`119C`/`119F`); no truck validation |
| Delivery | Signed GitHub APKs and manual Play-internal AAB workflow; Play build hides sideload updater | Confirm actual Play install provenance on phone; do not merge the old installer experiment unchanged |
| Idea loop | `file_idea` creates `idea-voice`; workflow can produce a PR | Intended human gate is bypassable through model-supplied `@claude` in a new issue |

## Known defects (not fixed by these docs)

Priorities follow the review. Source paths are under `app/src/main/java/com/mdc159/sierra/` unless prefixed otherwise.

| Priority | Boundary / files | Evidence and concrete next step |
| --- | --- | --- |
| P1 | Idea review gate: `.github/workflows/claude.yml`, `agent/tools/FileIdeaTool.kt` | Source-confirmed: `idea-voice` issue title/body containing `@claude` can trigger automation with the owner's actor identity. Require a separate trusted review action or exclude voice-created issue mention triggers. No issue was created to test this. |
| P1 | Credentials: `agent/ConfigStore.kt`, `studio54/Studio54Store.kt`, `voice/duplex/DuplexConfigStore.kt`, `car/TalkScreen.kt` | Source-confirmed: any encrypted-store open failure deletes preferences and retries; Talk loads outside its error handler. Preserve data and surface retryable errors; reset only by explicit choice. `VoiceSettingsStore` is already nondestructive. |
| P1 | Redirects: `bridge/sierra_bridge/adapters/openai.py`, `bridge/deploy/publish_phone_profile.py` | Off-origin bearer forwarding reproduced on loopback in adapter; publisher uses the same default opener. Disable authenticated redirects or enforce same-origin without credential forwarding on change. |
| P1 | Conversation identity: `bridge/sierra_bridge/conversations.py`, `server.py` | Identical reply text collides (reproduced with `Okay.`). Android supplies no explicit conversation ID. Add stable client IDs scoped by profile/model and regression tests for equal replies. |
| P1 | HTTP framing: `bridge/sierra_bridge/server.py` | Unauthorized negative Content-Length reaches `read(-1)` (reproduced); partial bodies lack request deadlines. Reject/close unauthorized requests without draining, validate framing, bound reads and connection resources. Tailnet reachability is not a substitute for this. |
| P2 | CLI stop: `bridge/sierra_bridge/__main__.py` | SIGINT deadlock reproduced: `shutdown()` runs on `serve_forever()` thread. Shut down on another thread and verify adapter cleanup and bounded exit. |
| P2 | Streaming disconnect: `bridge/sierra_bridge/server.py` | Broken pipe repro skips `_finish_turn` (#48). Use bounded `finally` cleanup, close generators and tolerate failed error writes. |
| P2 | ECU silence: `obd/Elm327Client.kt`, `car/TruckCodesScreen.kt` | Source-confirmed `NO DATA` becomes empty codes; absent monitor status can yield “No stored codes” (#21); tests encode it. Preserve unknown/no-response separately from valid zero codes. |
| P2 | Reconnect/key-off: `obd/live/TruckMonitor.kt` | Physical reconnect exceptions exit before retry branch; repeated missing-ECU cycles can continue while ATRV answers. Classify transient/permanent errors and bound missing-ECU polling. Battery drain was not measured. |
| P2 | Voice stop/focus: `voice/duplex/ElevenLabsAgentSession.kt`, `DuplexController.kt`, `voice/diagnostics/Microphones.kt`, `voice/Speaker.kt` | Source-only conditional failures: signed-URL HTTP lookup is outside handshake timeout/cancellation; native mic open/close can block Stop on main thread; stale utterance callbacks can release newer focus and speak errors are ignored. Add stalled HTTP/native-device/focus regressions. |
| P2 | Navigation/location: `car/TalkScreen.kt`, `car/CarLimits.kt`, `places/PhoneLocation.kt` | Source-confirmed: success precedes posted UI handoff; fresh-fix failure falls back without age bound. Propagate actual host result; reject or label stale fixes. |
| P2 | Device evidence harness: `scripts/smoke.ps1`, `scripts/_window.ps1` | Source-confirmed: mic result discarded, absent car.log can PASS, screenshot fallback captures overlapping desktop pixels (#45). Fix result propagation/missing-evidence states and capture privacy before treating automation as acceptance. Workspace path changes alone do not fix these. |
| P2 | Numerical speech: `bridge/sierra_bridge/speech.py` | Reproduced: feeding `The cost is 1.` then `25 dollars.` changes `1.25` to `1. 25`. Defer ambiguous punctuation and test every split; affects incrementally fed plain replies too. |

Other specialist hypotheses need targeted tests before elevation: Pi capacity/deadlines, subprocess descendants/output limits, incomplete upstream SSE, tool-contract pass-through, profile history mixing, vault refresh identity races, GM header restoration after timeout, cancelled adapter tests and overlapping non-Play APK downloads. Do not imply all were reproduced. Issue #29's `content:null` decoding is fixed; #16's old permanent RMS cap is no longer the current detector. Issue #23's real release-host coverage remains a gap.

## Phone evidence boundary

Read-only USB inspection: **SM-A546U1 Galaxy A54, Android 16/API 36**, installed `com.mdc159.sierra` **0.1.100005 / 100005**, non-debuggable; signer `CN=Sierra Auto, O=mdc159`. Play-specific updater permissions were absent. Version, UI and manifest match the Play feature set, but no embedded source SHA or byte-for-byte Play-served comparison was available: version is not exact source provenance.

Install record was **installer `com.android.vending`, initiator `com.android.shell`**, not a demonstrated Play/Play record. `InstallRecord` predicts a real-head-unit visibility risk; actual truck acceptance was **not tested**, so neither definite acceptance nor definite rejection is claimed. Install/update from Play and record both fields plus real host launch.

Microphone/location were granted; notifications and Bluetooth CONNECT/SCAN were not. Cold-start opened settings successfully. Bridge was selected and gateway fields were vault-backed. Diagnostics showed Automatic input, override off, system mute false, phone mic service off and “No voice attempt recorded yet.” That describes the current diagnostic store, not all past use. No microphone recording, transcription, agent turn, DHU or truck test occurred during the review.

Earlier evidence has narrower scope: 2026-11-04 desktop voice probes, 2026-11-06 authenticated Hermes/profile checks and build 100003 reaching duplex “Listening.” None proves a completed current-build truck exchange; an intermittent truck mic failure was not reproducible on demand.

## Work order

1. Reproduce/fix the P1 boundaries with small regressions before relying on automation, credential retention or backend isolation.
2. Repair bridge exit/disconnect and misleading diagnostic/navigation results; make Windows tests portable; expand voice lifecycle/failure tests.
3. Confirm Play provenance, then execute the parked-first [truck acceptance plan](TRUCK-TEST-PLAN.md), retaining redacted evidence under `.local/runs/`.
4. Validate one real adapter with standard PIDs and key-off/reconnect before adding GM UI, voice vehicle tools or alerts. Unknown is never healthy.
5. Reconcile issues by evidence, not lane assignment; selectively reassess draft installer work in `.worktrees/install-record`. Historical integration scratch scripts/logs are archival, not an authorized unattended release pipeline.

See [user guide](USER-GUIDE.md), [architecture](ARCHITECTURE.md), [testing](TESTING.md) and historical [lane scopes](lanes/README.md).
