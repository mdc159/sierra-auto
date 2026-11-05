# Integrator scripts

Shell scripts the integrator session used on 2026-11-04 to merge the ten first-wave lane
PRs into `main`. The record of that session is
[`app_docs/integration-2026-11-04.md`](../../app_docs/integration-2026-11-04.md).

They expect a worktree folder next to the repo (`../sierra-auto-wt` by default) whose
`integrate/` worktree is where PRs are checked out, merged with `main` and built. All paths
come from [`env.sh`](env.sh) and can be overridden from the environment.

| Script | What it does |
|---|---|
| `automerge.sh` | Merge loop until 23:50. Merges an open PR when its checks finished with none failed, `build` passed, it merges cleanly, its last commit is at least 12 minutes old, and it is not a draft or has its closing "unverified" comment. Asks a conflicting PR's session to merge `main`, once per head commit. Holds all merges while `main` is red. Logs to `$SIERRA_WT/automerge-log.md`. |
| `iwatch.sh [seconds] [skip...]` | Polls every 60 s and exits when an open PR looks ready, `main`'s build is red, or the time runs out (default 1500 s). |
| `ibuild.sh <label>` | `assembleDebug testDebugUnitTest` in the integrate worktree; log at `$INTEGRATE_LOGS/build-<label>.log`. |
| `ifull.sh <label>` | Adds `lintDebug` and lists each failed test; log at `$INTEGRATE_LOGS/full-<label>.log`. |
| `iprivacy.sh` | Refuses a merge whose tree names a `*.ts.net` host or a tailnet (100.64/10) address. |
| `ilog.sh "message"` | Appends a timestamped line (US Pacific) to the integrator log. |
| `keepboth.py <files>` | Resolves add-only conflict hunks by keeping both sides, `main`'s first, and prints each one for review. |

## Settings

| Variable | Default |
|---|---|
| `SIERRA_REPO` | `mdc159/sierra-auto` |
| `SIERRA_WT` | `<repo>/../sierra-auto-wt` |
| `INTEGRATE_DIR` | `$SIERRA_WT/integrate` |
| `INTEGRATE_LOGS` | `$SIERRA_WT/integrate-logs` |
| `INTEGRATE_LOG` | `$SIERRA_WT/integrate-log.md` |
| `SIERRA_TOOLCHAIN` | `D:/Projects/android-toolchain`, the same setting `scripts/_common.ps1` reads |
| `SIERRA_PRIVATE_PATTERNS` | unset. An extended regex of the real tailnet name and addresses for `iprivacy.sh` to refuse. This repo is public, so the real values live only in the environment; without them the script still runs its generic checks and says it skipped this one. |

The scripts need `gh` signed in to the repo, `git`, a POSIX shell (Git Bash on Windows)
and, for the build scripts, the Android toolchain.
