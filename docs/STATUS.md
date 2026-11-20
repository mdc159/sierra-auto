# Verified status and next steps

## Development platform — 2026-11-20

Development moved from the retired Windows workspace to Linux. The canonical
checkout is `/home/mdc/Projects/sierra-auto` on the Omarchy box (Dell M6800);
the toolchain comes from `source .local/omarchy-toolchain/env.sh` (JDK 17,
SDK platform 36) and workspace/device operations use the bash scripts
([WORKSPACE.md](WORKSPACE.md)). Validated 2026-11-20 at revision `358e456` on
`dev/phone-voice-checkpoint`: Android `assembleDebug testDebugUnitTest
lintDebug` — **616 passed, 2 skipped, lint 0 errors/133 warnings**; bridge —
**123 passed, 1 skipped, 0 failures** (evidence: `.local/omarchy-validation/`).
Automated results only: no phone installation, live provider call or truck
acceptance. The A54 (SM-A546U1, Sierra 0.1.100009) is attached over USB for
authorized device work. The Windows-era records below keep their dates; the
four native-Windows bridge failures did not occur on Linux and are preserved
in workspace history, not claimed fixed in code.

## Development checkpoint — 2026-11-18

Source work is being checkpointed on `dev/phone-voice-checkpoint`, based on
`507453d`, for a draft PR into `staging`. The delivery path is now development
PR → staging → manual Google Play internal testing → acceptance → main. See
[workflow](DEVELOPMENT-WORKFLOW.md). No Play upload, phone update or truck test
has been performed as part of this repository checkpoint.

**2026-11-20 — promotion blockers fixed in source (revision `bd35bb5`).** The three
phone blockers and the two related review findings now have fixes with regression
tests that failed before each fix:

- Vault refresh and both production transcribers (Gateway, ElevenLabs) run OkHttp
  calls through `Call.executeCancellable`, which keeps `Call.cancel()` armed for the
  whole response including the body read, on an unconfined guard that does not need
  a free dispatcher thread. Stalled-header, stalled-body and single-thread-dispatcher
  regressions cancel in about 0.1–1.5 s instead of OkHttp's 20 s timeout.
- `PhoneViewModel` keeps the conversation ID and transcript across activity
  recreation; opening another app still stops the conversation.
- An agent 401 during a controller (transcriber) turn now reaches the Studio54
  vault-refresh hook exactly once. No automatic retry: the next Talk uses the refreshed key.
- `claude.yml` starts from and opens PRs against `staging`; a newly opened
  `idea-voice` issue no longer triggers the agent without the owner's `idea` label.

Android `assembleDebug testDebugUnitTest lintDebug` at `bd35bb5`: **638 tests, 636
passed, 2 skipped, lint 0 errors/133 warnings**. Each fix had an independent
cross-vendor source review with no remaining blocking findings. These are JVM and
Robolectric results with fakes and local stalled servers; **no phone, Play or truck
acceptance yet**. Car `TalkScreen` transcription still uses `runInterruptible`
around a blocking call (outside these blockers; next step). Earlier
security/backend defects below remain open.

The review also identified raw-URL label disclosure and unrestricted policy-evidence
output paths; corrections and focused regression checks are part of this checkpoint.

Fresh Android debug build/unit tests/lint passed: 617 tests, 615 passed, 2 skipped; zero lint errors and 133 warnings. The later endpoint-label correction has a separate focused regression run.

Fresh native-Windows bridge baseline: 110 tests, 106 passed and the same four
previously documented failures (502/504 expectation, UTF-8 log reading, and two
subprocess fixtures missing SystemRoot). After evidence-boundary tests were added, the full suite recorded 119 passed, 4 known failures and 1 skip. Final focused helper tests: 28 passed, 1 Windows symlink-privilege skip; the Windows junction case passed. Workspace lifecycle tests: 26 passed.
Changed workflows pass actionlint. The unrelated existing claude.yml concurrency.queue
key is not recognized by the installed actionlint version; this checkpoint does
not change that workflow. Secret scans of candidate source and the outgoing
workspace commit found no leaks.

Evidence is local-only under `.local/reviews/2026-11-17-development-checkpoint/`.
The directory date is the session start date. Each validation report states its
source and scope; historical November 9 phone evidence below is not a new test.
The installer and older merge branches remain preserved for a separate review.

## As is

Current phone work: **2026-11-09**, based on consolidated main `507453d`.
The full review below remains the **2026-11-08 application baseline `210448b`**;
its unresolved defects are not erased by the phone changes.

### November 9 PCM playback correction — current build 100009

- Signed **100009** is installed in place with the same certificate. Jana voice
  ID and `eleven_v3` preferences survived the update; the key remains vault-backed.
- Network chunks are now joined at PCM16 sample boundaries before playback. A
  truncated final sample fails clearly, and an odd player write cannot enter the
  old zero-progress loop. Three regressions verify split chunks, exact audio
  preservation, sentence boundaries and truncated-response cleanup.
- `assembleRelease` (including release lint vital checks) and the focused TTS,
  phone and Studio54 suites passed: **106 tests, all passed**. The full 614-test
  suite and debug lint results below belong to 100008; they were not rerun in full
  after the PCM correction. Independent source review found no blocking issue.
- Physical A54 Test voice result: **`OK: ElevenLabs (eleven_v3) (5.5 s including
  playback)`**. The configured voice ID is Jana. AudioFlinger recorded consumed
  frames and normal track release, unlike the stalled 100008 attempt. Human
  comparison with Victoria and the actual truck route remain separate acceptance.
- A normal typed greeting to Victoria then received “Hola, jefe. Good to hear
  from you—what’s happening?” and completed Jana playback before returning to Ready.
  This checks the conversation screen's real agent-to-speech path, not just its
  sample button. The earlier spoken-input check remains the 100007 observation.
- Evidence: `.local/runs/victoria-voice-match-20261109/pcm-fix/`, including diagnosis,
  build/test/signature results, independent review, phone UI and audio-track evidence.

### November 9 Victoria voice integration — build 100008 (superseded)

- Signed **100008** was installed in place on the A54, with matching certificate and
  retained enrollment/agent settings. This is an ADB update, not a Play publication.
- Matched Victoria's configured ElevenLabs **Jana** voice
  (`jXJ93ZaMvKJrPorQEQlr`) and **`eleven_v3`** model. Authenticated provider checks
  returned the named voice and valid PCM audio using the phone's fresh vault profile.
- The phone-specific ElevenLabs entry now holds Victoria's existing working
  credential. The shared vault value was a key identifier rejected by ElevenLabs;
  it was left unchanged for infrastructure follow-up. No key rotation was needed.
- Sierra resolves that key in memory for standard phone/car VoiceStack and Test
  voice, preserving typed fallback and all non-key preferences. It does not put
  the vault key into editable fields or the voice-settings store. Separate duplex
  settings and car system-recognizer/Repeat Android output are unchanged.
- Full release build/tests/lint passed: **614 tests, 612 passed, 2 skipped;
  lint 0 errors, 133 warnings**. Independent source review found no blocking issue.
  Phone Check connection received `ready` from Hermes after installation.
- The first Eleven v3 phone sample exposed a separate PCM playback stall: an odd
  network read left one byte that AudioTrack could not consume, so the old retry
  loop never advanced. Audio reached the track but playback did not complete.
  The follow-up carries incomplete samples between reads and rejects truncated
  responses; physical acceptance of that correction is recorded above.
- Evidence: `.local/runs/victoria-voice-match-20261109/`. Current source remains
  `main@507453d` plus uncommitted changes; `.worktrees/install-record` is retained
  and untouched. Current-build truck acceptance remains untested.

### November 9 phone microphone repair — build 100007

- The failed Talk attempts were discarded before transcription: A54 speech peaked
  around 138–279 RMS, below the car-derived 500 RMS minimum. Phone capture now uses
  a 40 RMS minimum with adaptive noise rejection and preserves an unconfirmed
  first utterance. Follow-up silence remains filtered; digital silence is rejected.
- Signed **100007** was installed in place with the same certificate and retained
  settings. Full Android validation passed: **607 tests, 605 passed, 2 skipped;
  lint 0 errors, 132 warnings**. Focused regression coverage includes quiet speech,
  continuous follow-up, immediate speech during calibration and digital silence.
- Physical phone evidence: the user's spoken “Victoria, can you hear me?” appeared
  in the transcript and Victoria replied “I’m here, jefe. Your message came
  through—what’s up?” This verifies microphone → transcription → agent response.
  Human-confirmed playback and a current-build truck exchange remain separate.
- Local evidence: `.local/runs/phone-voice-fix-20261109/`, including build/signature
  results, independent review and `first-spoken-exchange.xml`.

### November 9 initial phone work — build 100006 (historical)

- Added a phone conversation launcher with Talk/Stop, follow-up listening,
  transcript, typed messages, Settings and Tailscale shortcuts. Agent replies go
  directly to the selected route, including the real Hermes agent on Victoria.
- Added explicit conversation IDs to phone conversation and car Talk. New
  AgentClient instances also default to a fresh ID; the bridge's legacy reply-text
  fallback for older clients remains and is not claimed fixed.
- Added cancellation of stalled phone chat HTTP calls and actionable errors for
  unreachable Tailscale endpoints, including numeric tailnet addresses.
- Live diagnosis: Tailscale was stopped on the A54. Opening it restored private
  DNS and Studio54 access. Always-on Tailscale was enabled, lockdown off.
- Remaining network block at diagnosis: the tailnet policy excludes the A54 from
  Victoria's bridge TCP 8443. The PC, using the exact phone vault profile, received
  `Victoria connection ready.` from Hermes. No server restart was necessary.
  Both enrolled profiles' existing Tailscale API key was rejected with HTTP 401,
  so the narrow policy grant was supplied for the administrator to save.
- Signed Play-variant **100006** was installed in place over USB after comparing
  signing certificates. The phone shows the new launcher and retains its Hermes
  configuration and microphone permission. This was an ADB update, not a Play upload.
- Fresh `assembleRelease testDebugUnitTest lintDebug -Pplay=true -PversionCode=100006`
  passed: **603 tests, 601 passed, 2 skipped; lint 0 errors, 132 warnings**.
  The policy-helper tests pass (expanded to 15 in the credential follow-up below).
  The full bridge suite was not rerun.
- Physical A54 checks: large-font layout and keyboard input usable; a typed prompt
  appeared in the transcript; Stop cancelled a pending agent request without a late
  error. Talk captured audio, silence ended the session, and a second Talk/Stop cycle
  released the microphone (Android app-ops showed a completed 2.475-second capture).
  The unattended acoustic attempt contained only quiet audio, so it did not establish
  successful speech recognition. The existing Android voice sample completed in
  6.8 seconds immediately before the update; actual audibility was not independently
  measured. The new app's connection check still fails at the tailnet boundary.
- **Morning blocker, cleared in the afternoon:** the phone-to-Victoria grant was
  missing. The November 9 recheck below confirms it is now present and phone TCP
  access succeeds. A completed phone voice exchange and truck acceptance were
  **NOT VERIFIED at that point**. Neither a backend restart nor a phone reinstall was needed
  for this recheck.
- Source state at this handoff: `main@507453d` plus uncommitted phone/network/docs
  changes; retained worktree `.worktrees/install-record` is untouched. Workspace
  check passes. APK, signer comparison, build logs, test summary, screenshots,
  UI captures and review are local-only in `.local/runs/phone-victoria-20261109/`.

### November 9 credential follow-up

The morning Studio54 controller check independently confirmed the same rejected key in the
canonical shared vault (version 1, September 27). No Tailscale OAuth credential
exists there. The Sierra policy helper now supports a vault-held OAuth client,
automatic short-lived access-token renewal and one bounded refresh on HTTP 401;
15 helper tests pass. This support is local and tested with fake HTTP responses,
not a live OAuth client. Initial trust-credential bootstrap and the phone grant
were pending at that point. See [renewable credentials](PHONE-CONVERSATION.md#renewable-automation-credentials)
for the subsequently deployed server-only setup.

### November 9 afternoon access recheck

- Deployed Studio54 `tailnet.py --check` passes using an **OAuth pair from
  `s54-server`**. The credentials remain on the server; this check retrieved an
  access token and read the actual policy without printing or copying secrets.
- The policy already includes `autogroup:member` → `tag:mdc`, `tcp:8443`, from
  Studio54's declared `tailnet-grants.json`. This is the deployed tag-based grant,
  rather than the earlier proposed single-phone rule. No policy edit was needed.
- The USB-connected A54 successfully opens TCP 8443 to `100.112.150.24`; its
  Always-on VPN remains `com.tailscale.ipn`. **The prior network blocker is cleared.**
- A fresh `sierra-a54` launcher still receives the rejected legacy shared API key.
  That does not invalidate the new server-only OAuth route. Policy administration
  now belongs on Studio54; distributing its OAuth pair to the phone is unnecessary.
- After the phone was unlocked, Sierra's own Check connection returned
  `Hermes answered through the bridge: "ready"`. A typed test received
  `Victoria is connected.`; a follow-up correctly recalled the test word `bogey`.
  Both turns returned to Ready without a reported speech-output error. This
  verifies the actual phone agent route and follow-up context. Spoken input was
  subsequently verified on 100007 above; playback and truck acceptance are separate.
- Evidence: `.local/runs/tailscale-recheck-20261109/`. Sierra remains
  `main@507453d` with the existing uncommitted phone/network/docs work; no new APK
  was installed and no shared service was restarted in this recheck.

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
| P1 | Legacy conversation identity: `bridge/sierra_bridge/conversations.py`, `server.py` | Identical reply text collides (reproduced with `Okay.`). November 9 phone and car Talk changes supply stable IDs, but older clients can still hit the server's reply-text fallback. Remove ambiguous legacy matching and verify cross-client isolation before closing the server defect. |
| P1 | HTTP framing: `bridge/sierra_bridge/server.py` | Unauthorized negative Content-Length reaches `read(-1)` (reproduced); partial bodies lack request deadlines. Reject/close unauthorized requests without draining, validate framing, bound reads and connection resources. Tailnet reachability is not a substitute for this. |
| P2 | CLI stop: `bridge/sierra_bridge/__main__.py` | SIGINT deadlock reproduced: `shutdown()` runs on `serve_forever()` thread. Shut down on another thread and verify adapter cleanup and bounded exit. |
| P2 | Streaming disconnect: `bridge/sierra_bridge/server.py` | Broken pipe repro skips `_finish_turn` (#48). Use bounded `finally` cleanup, close generators and tolerate failed error writes. |
| P2 | ECU silence: `obd/Elm327Client.kt`, `car/TruckCodesScreen.kt` | Source-confirmed `NO DATA` becomes empty codes; absent monitor status can yield “No stored codes” (#21); tests encode it. Preserve unknown/no-response separately from valid zero codes. |
| P2 | Reconnect/key-off: `obd/live/TruckMonitor.kt` | Physical reconnect exceptions exit before retry branch; repeated missing-ECU cycles can continue while ATRV answers. Classify transient/permanent errors and bound missing-ECU polling. Battery drain was not measured. |
| P2 | Voice stop/focus: `voice/duplex/ElevenLabsAgentSession.kt`, `DuplexController.kt`, `voice/diagnostics/Microphones.kt`, `voice/Speaker.kt` | Source-only conditional failures: signed-URL HTTP lookup is outside handshake timeout/cancellation; native mic open/close can block Stop on main thread; stale utterance callbacks can release newer focus and speak errors are ignored. Add stalled HTTP/native-device/focus regressions. |
| P2 | Navigation/location: `car/TalkScreen.kt`, `car/CarLimits.kt`, `places/PhoneLocation.kt` | Source-confirmed: success precedes posted UI handoff; fresh-fix failure falls back without age bound. Propagate actual host result; reject or label stale fixes. |
| P2 | Device evidence harness: `scripts/smoke.sh` (ported from `smoke.ps1`/`_window.ps1`) | Source-confirmed: mic result discarded, absent car.log can PASS, screenshot fallback can capture unrelated on-screen content (#45). Fix result propagation/missing-evidence states and capture privacy before treating automation as acceptance. Workspace path changes alone do not fix these. |
| P2 | Numerical speech: `bridge/sierra_bridge/speech.py` | Reproduced: feeding `The cost is 1.` then `25 dollars.` changes `1.25` to `1. 25`. Defer ambiguous punctuation and test every split; affects incrementally fed plain replies too. |

Other specialist hypotheses need targeted tests before elevation: Pi capacity/deadlines, subprocess descendants/output limits, incomplete upstream SSE, tool-contract pass-through, profile history mixing, vault refresh identity races, GM header restoration after timeout, cancelled adapter tests and overlapping non-Play APK downloads. Do not imply all were reproduced. Issue #29's `content:null` decoding is fixed; #16's old permanent RMS cap is no longer the current detector. Issue #23's real release-host coverage remains a gap.

## Phone evidence boundary

Read-only USB inspection: **SM-A546U1 Galaxy A54, Android 16/API 36**, installed `com.mdc159.sierra` **0.1.100005 / 100005**, non-debuggable; signer `CN=Sierra Auto, O=mdc159`. Play-specific updater permissions were absent. Version, UI and manifest match the Play feature set, but no embedded source SHA or byte-for-byte Play-served comparison was available: version is not exact source provenance.

Install record was **installer `com.android.vending`, initiator `com.android.shell`**, not a demonstrated Play/Play record. `InstallRecord` predicts a real-head-unit visibility risk; actual truck acceptance was **not tested**, so neither definite acceptance nor definite rejection is claimed. Install/update from Play and record both fields plus real host launch.

Microphone/location were granted; notifications and Bluetooth CONNECT/SCAN were not. Cold-start opened settings successfully. Bridge was selected and gateway fields were vault-backed. Diagnostics showed Automatic input, override off, system mute false, phone mic service off and “No voice attempt recorded yet.” That describes the current diagnostic store, not all past use. No microphone recording, transcription, agent turn, DHU or truck test occurred during the review.

Earlier evidence has narrower scope: 2026-11-04 desktop voice probes, 2026-11-06 authenticated Hermes/profile checks and build 100003 reaching duplex “Listening.” None proves a completed current-build truck exchange; an intermittent truck mic failure was not reproducible on demand.

## Work order

1. Reproduce/fix the P1 boundaries with small regressions before relying on automation, credential retention or backend isolation.
2. Repair bridge exit/disconnect and misleading diagnostic/navigation results; expand voice lifecycle/failure tests. (The former "make Windows tests portable" item retired with the Windows workspace; see the platform note above.)
3. Confirm Play provenance, then execute the parked-first [truck acceptance plan](TRUCK-TEST-PLAN.md), retaining redacted evidence under `.local/runs/`.
4. Validate one real adapter with standard PIDs and key-off/reconnect before adding GM UI, voice vehicle tools or alerts. Unknown is never healthy.
5. Reconcile issues by evidence, not lane assignment; selectively reassess draft installer work preserved as tag `archive/install-record`. Historical integration scratch scripts/logs are archival, not an authorized unattended release pipeline.

See [user guide](USER-GUIDE.md), [architecture](ARCHITECTURE.md), [testing](TESTING.md) and historical [lane scopes](lanes/README.md).
