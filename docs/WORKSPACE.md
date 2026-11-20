# Workspace: as is and next steps

## As is — 2026-11-20

`/home/mdc/Projects/sierra-auto` on the Linux development machine (Omarchy,
Dell M6800) is the canonical checkout and the one development platform. The
platform switch was validated at revision `358e456`
(`dev/phone-voice-checkpoint`). Windows-era facts (the `D:\Projects` layout,
the PowerShell scripts, the consolidation table and the native-Windows bridge
failures) are preserved in
[History: Windows era](#history-windows-era-until-2026-11-20) below.

Workspace and device operations run through the bash scripts in `scripts/`
(shared helpers in `scripts/_common.sh`, tap points in `scripts/screens.json`):

```bash
bash scripts/workspace.sh check
bash scripts/workspace.sh list
bash scripts/workspace.sh new task-name fix/task-name main   # only when isolation requires it
bash scripts/workspace.sh remove task-name
bash scripts/test-workspace.sh                               # after changing workspace helpers
```

Ordinary tasks use the main checkout; `new` creates only `.worktrees/<name>`
children and refuses an existing destination. `check` rejects external
registered worktrees, sibling folders named after this repository, missing
ignore rules and external output overrides. `remove` refuses tracked changes,
untracked files and unfamiliar ignored files; it copies and hashes build
reports, test results and distributable outputs to
`.local/runs/worktree-<name>-<timestamp>-<id>`, writes a manifest, preserves
the commit at `refs/sierra/worktrees/<name>/<timestamp-id>` and uses Git's
non-forced removal. Reusing a worktree name preserves each prior ref.

Device/build helpers: `scripts/install-phone.sh`, `scripts/dhu.sh`,
`scripts/smoke.sh`, `scripts/shot.sh`, `scripts/car-logs.sh` and
`scripts/build-pr.sh` (see [TESTING.md](TESTING.md)). `build-pr.sh` creates a
uniquely named `.worktrees/pr-*` checkout; successful builds retain results
under `.local/runs`, failed builds and an explicit keep preserve the worktree
for investigation. `install-phone.sh` downloads development APKs to
`.local/downloads/releases/<tag>`. These scripts build/install only when
explicitly invoked; a workspace check does neither. Explicit screenshot/log
output paths must stay under canonical `.local`; relative filenames use the
shell's current directory.

The canonical root comes from Git's common directory, so current helper code
called from a linked worktree still uses the main checkout's `.local` directory.
Relative output overrides are resolved beneath `.local`, independent of the
calling shell's current directory.

| Setting | Default | Override rule |
| --- | --- | --- |
| `SIERRA_RUNS` | `<canonical root>/.local/runs` | Must stay below canonical `.local` |
| `SIERRA_STATE` | `<canonical root>/.local/state/dhu` | Must stay below canonical `.local` |
| `SIERRA_SERIAL` | The sole authorized USB device | Explicit device serial when needed |

The JDK/SDK locations come from the toolchain environment below, not from a
workspace variable.

### Toolchain on this machine

`source .local/omarchy-toolchain/env.sh` exports `JAVA_HOME` (mise Temurin
17.0.21+9) and `ANDROID_HOME=$HOME/.local/share/android-sdk` (platform 36,
platform-tools 37.0.3, build-tools 36.0.0, cmdline-tools). Gradle uses the
default `~/.gradle` user cache; the bridge uses a project-local `.venv` via
`uv`. The Android Auto Desktop Head Unit 2.0 (linux build, 2022-03-30) is
installed inside that SDK and runs through `.local/omarchy-toolchain/run-dhu.sh`
with its bundled runtime libraries. Everything is user-scoped and outside
tracked source; `local.properties` points at the SDK and stays Git-ignored.
Toolchain evidence: `.local/omarchy-toolchain/toolchain-evidence.md`.

### Linux validation — 2026-11-20, revision `358e456`

- Android: `./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug` —
  **616 passed, 2 skipped** (the two live-API tests without `SIERRA_LIVE_*`),
  lint **0 errors, 133 warnings**.
- Bridge: `cd bridge && uv sync --locked && uv run --locked pytest -q` —
  **123 passed, 1 skipped, 0 failures**.
- Evidence: `.local/omarchy-validation/` (Gradle log, bridge sync/test logs,
  DHU install/help logs).

These are automated software results only: no phone installation, live
provider call or truck acceptance was performed. The A54 (SM-A546U1) with
Sierra 0.1.100009 is attached over USB for authorized device work; attached is
not acceptance. The four native-Windows bridge failures of the retired
workspace did not occur in this run (see history below).

### What belongs outside

Signing files and other credentials remain in their established machine secret
storage; never move them into `.local` or Git. Global tool caches and agent
session storage are managed by their tools. Remote bridge services remain on
their existing hosts.

### Local files and Git

`.local` and `.worktrees` are excluded from Git. They are not uploaded by a commit
or push. The Git bundle protects source history locally; raw reports and local
archives need their own off-machine backup if that is required. Do not treat a
second directory on this drive as a disaster-recovery backup.

## History: Windows era (until 2026-11-20)

Until 2026-11-20 the canonical root was `D:\Projects\sierra-auto` on Mike's
Windows machine, using PowerShell 7 and `scripts/*.ps1`. On 2026-11-20 the
`install-record` worktree and branch were removed in a branch cleanup; the
experiment is preserved as tag `archive/install-record` (draft PR #44 closed).
At the 2026-11-08 consolidation it had been the sole retained linked worktree:
`.worktrees/install-record`, branch `install-record`, commit `f52dcdc`, an
unmerged installer experiment preserved byte-for-byte, with older scripts and
documentation kept only as a historical snapshot.

Consolidation performed 2026-11-08 into the then-canonical Windows root:

| Former location | Current disposition |
| --- | --- |
| `D:\Projects\sierra-auto-wt\installrec` | `.worktrees/install-record` (later removed; tag `archive/install-record`) |
| `sierra-auto-wt\microphone-diagnostics` | Merged source remains in Git; worktree retired; reports, test results and APK outputs preserved |
| `D:\Projects\sierra-auto-review-20261108` | Temporary review worktree retired; source baseline and build evidence preserved |
| `D:\Projects\sierra-auto-reviews` | `.local/reviews` |
| Loose scripts/logs in `sierra-auto-wt` | `.local/archive/consolidation-2026-11-08/legacy-integration` |
| `%USERPROFILE%\sierra-auto-runs` | `.local/runs` |
| `%LOCALAPPDATA%\sierra-auto\dhu` | `.local/state/dhu` |
| Sierra development APKs in Windows Temp | `.local/downloads/releases` |
| Sierra review/probe files and old temporary PR folder | `.local/archive/consolidation-2026-11-08/legacy-temp` |

The migration manifest is
`.local/archive/consolidation-2026-11-08/manifest.json` (old/new paths,
SHA-256 hashes, dispositions). The same archive holds
`sierra-auto-before.bundle`, verified before migration, plus before/after
worktree lists and branch/tag references. No feature branch was merged or
deleted as part of consolidation. The shared Windows toolchain lived at
`D:\Projects\android-toolchain`; signing files stayed in
`%USERPROFILE%\.config\sierra-auto`. `D:\Projects\Sierra` is a separate
repository containing an August 2026 general agent-platform proposal and no
Android Auto application; it is not an additional Sierra Auto checkout.

Bridge tests on native Windows never passed fully: the suite ended with four
environment-specific failures (502/504 unreachable-fixture expectation, a
default-encoding UTF-8 log read, and two subprocess fixtures missing
`SystemRoot`); the last Windows run (2026-11-18) recorded 119 passed,
4 failed, 1 skipped. They did not occur in the 2026-11-20 Linux run and are
not claimed fixed in code.

Raw review files describe the paths as they existed when captured; those dated
records are preserved unchanged. Use the table above to locate their material.
The archive's loose integration scripts are reference material, not supported
commands; some contain old paths and remote merge actions — do not run them as
a shortcut.

## Next steps

- Keep every future task within this layout; run the workspace check at start
  and handoff. Repository instructions and helper checks enforce the supported
  workflow; arbitrary external commands can still bypass them.
- The `workspace` GitHub workflow still parses the retired `.ps1` scripts on a
  Windows runner; port it to the bash scripts (and a Linux runner) as part of
  the script-port follow-up. `bash scripts/test-workspace.sh` provides the
  local parse/lifecycle checks meanwhile.
- Decide the installer experiment's future on its merits. Rebase/test it only
  as an explicit feature task; consolidation does not establish that workaround.
- Review existing integrator/documentation PRs against these current files before
  merging, to avoid restoring obsolete paths and session instructions.
- Keep current facts in [STATUS.md](STATUS.md), work order in [PLAN.md](PLAN.md),
  and dated/raw evidence local. Archive stale experiments deliberately rather
  than accumulating unnamed sibling checkouts.
