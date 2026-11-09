# Sierra Auto

Sierra is a personal Android Auto application for a 2018 GMC Sierra Denali and
Samsung Galaxy A54. It combines an agent conversation interface, nearby places,
voice diagnostics and read-only vehicle data. The Android app is Kotlin; the
optional Python bridge connects it to existing agents.

## As is

Reviewed **2026-11-08**, application baseline **`210448b`**. This is an internal
testing application with working build/distribution infrastructure. Reliable
end-to-end operation in the truck has not yet been established by the current
evidence. Workspace and documentation updates do not fix the application defects
recorded in [project status](docs/STATUS.md).

| Area | Implemented | Remaining acceptance or integration |
| --- | --- | --- |
| Android Auto | Home, Places, Talk, History, Truck/Codes, Mic test, Conversation | Current build on the actual head unit; host acceptance and audio routing |
| Places | OpenStreetMap providers, host-drawn map, navigation handoff | Stale-location handling and reporting actual handoff failures |
| Agent and voice | OpenAI-compatible chat/tools, transcription, spoken replies, follow-ups, separate duplex Conversation flow | Failure/cancellation paths, real microphone/echo/barge-in tests |
| Backend connection | Model gateway or Sierra bridge selection; Studio54 overlay; Hermes connection | Bridge security, conversation identity, disconnect and shutdown findings |
| Vehicle data | Demo/live standard OBD, classic Bluetooth/BLE transports, read allowlist, GM reader/catalog code | Real adapter/ECU evidence; GM live-screen integration; vehicle voice tools and alerts |
| Distribution | Signed GitHub development APKs and Google Play internal testing | Correct Play install provenance and truck visibility on the enrolled phone |

The last reviewed GitHub release was **v0.1.110**; the connected phone had the
**0.1.100005 Play-variant build**. These use different version-code sequences.
The phone opened successfully, but this review did not perform a live agent turn,
record audio or test the truck. Android reported Play as installer and shell as
initiator; real head-unit acceptance still needs checking.

Validation at the same application baseline: Android build/unit tests/lint passed
(569 tests: 567 passed, 2 live tests skipped; 0 lint errors, 124 warnings).
The native-Windows bridge suite had 100 passes and 4 failures; Linux CI was green.
See [status and evidence](docs/STATUS.md) and [testing](docs/TESTING.md) for scope,
known defects and commands. A successful build is not truck acceptance.

## Use it

1. On the enrolled phone, update Sierra through **Google Play internal testing**.
   New testers need the existing developer-account opt-in invitation; this is not
   a public production listing. [GitHub APK releases](https://github.com/mdc159/sierra-auto/releases/latest)
   are available for development, with installation limitations documented in
   [updates](docs/UPDATES.md) and [testing](docs/TESTING.md).
2. Open Sierra on the phone and grant microphone/location permissions as needed.
   For the configured Hermes agent, connect Tailscale and use **Connect to Hermes
   on Victoria** with the Studio54 settings. This configures the existing agent
   route; it does not prove backend health. Other OpenAI-compatible endpoints can
   be configured and checked with **Test connection**.
3. Use [microphone diagnostics](docs/MICROPHONE-DIAGNOSTICS.md) to verify capture
   before relying on Talk. Configure Bluetooth access separately for live OBD.
4. Follow the [user guide](docs/USER-GUIDE.md) and [parked truck test plan](docs/TRUCK-TEST-PLAN.md).

Credentials stay in encrypted phone settings, Studio54 or existing machine secret
storage. Do not put keys, tokens, keystores or private diagnostic captures in Git.

## Work in this repository

**Canonical Windows root: `D:\Projects\sierra-auto`.** All Sierra Auto source
checkouts, local evidence and experiments belong under this folder. Use the main
checkout for ordinary work; create a temporary worktree only when isolation or
simultaneous work requires one.

```text
app/                    Android app and JVM/Robolectric tests
bridge/                 Python HTTP bridge, adapters, deployment examples and tests
docs/                   Current status, operating guides, reference and next steps
scripts/                Windows device/build/workspace commands
tools/voice-probe/       Voice-provider diagnostic utility
.worktrees/             Ignored, temporary linked worktrees
  install-record/       Preserved unmerged installer experiment
.local/                 Ignored, local-only evidence and state
  reviews/              Code review and device inspection evidence
  runs/                 Build/device results and retired-worktree evidence
  state/dhu/            Desktop Head Unit session state
  downloads/releases/   Development APK downloads
  archive/              Recovery bundle, migration manifest, older integration files
```

```powershell
pwsh -File scripts/workspace.ps1 check
pwsh -File scripts/workspace.ps1 list
# Only if a second checkout is needed:
pwsh -File scripts/workspace.ps1 new -Name voice-fix -Branch fix/voice -Ref main
# After preserving/committing source changes:
pwsh -File scripts/workspace.ps1 remove -Name voice-fix
```

The helper rejects out-of-root paths and changed worktrees, preserves build
evidence and a recovery commit reference, and never force-removes work. Read
[AGENTS.md](AGENTS.md) and [workspace rules and consolidation record](docs/WORKSPACE.md)
before creating a checkout or running old scripts. `.local` and `.worktrees` are
not uploaded by Git; the local recovery archive is not an off-machine backup.

## Build and check

Use PowerShell 7, Git, JDK 17 and Android SDK platform 36. The shared toolchain on
this PC remains at `D:\Projects\android-toolchain`.

```powershell
$env:JAVA_HOME = 'D:\Projects\android-toolchain\jdk-17'
$env:ANDROID_HOME = 'D:\Projects\android-toolchain\android-sdk'
$env:GRADLE_USER_HOME = 'D:\Projects\android-toolchain\gradle-user-home'
.\gradlew.bat --no-daemon assembleDebug testDebugUnitTest lintDebug
pwsh -File scripts/test-workspace.ps1
```

For the bridge, use a project-local virtual environment:

```powershell
Set-Location bridge
uv sync --locked
uv run --locked pytest -q
```

The four known Windows bridge test failures are documented in
[bridge/README.md](bridge/README.md); do not report that suite as fully passing.
Optional Android live API tests require explicit `SIERRA_LIVE_*` configuration
and can contact paid services. Ordinary validation leaves those unset.

## Next steps

1. Fix the reviewed security/data-boundary problems: idea workflow triggering,
   destructive credential-store recovery, authenticated redirects, bridge
   conversation identity and request-body handling.
2. Repair bridge shutdown/stream cleanup and numerical speech formatting;
   resolve the four Windows test failures and device-smoke false-pass cases.
3. Verify Play installation provenance, microphone selection, Talk and duplex
   behavior on the actual phone/head unit using recorded acceptance checks.
4. Complete reliable OBD reconnect/no-data semantics, test the physical adapter,
   then connect GM readings and read-only vehicle tools to the agent.

The authoritative work order is [PLAN.md](docs/PLAN.md). Start with
[STATUS.md](docs/STATUS.md) for evidence, [ARCHITECTURE.md](docs/ARCHITECTURE.md)
for code boundaries, and [docs/lanes/README.md](docs/lanes/README.md) for the
historical assignments and their present disposition.
