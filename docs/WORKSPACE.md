# Workspace: as is and next steps

## As is — 2026-11-08

`D:\Projects\sierra-auto` is the canonical local checkout. It is on `main` at
application baseline `210448b`; workspace/documentation changes are applied on
top of that baseline. Source was fast-forwarded from the older local checkout.

**Update 2026-11-20:** the `install-record` worktree and branch were removed in a
branch cleanup; the experiment is preserved as tag `archive/install-record`
(draft PR #44 closed). The paragraph below records the 2026-11-08 state.

The sole retained linked worktree is `.worktrees/install-record`, branch
`install-record`, commit `f52dcdc`. It contains an unmerged installer experiment.
Its source and ignored local files were preserved byte-for-byte (Git updated its
own worktree pointer). Its older scripts and documentation are a historical
snapshot: use the canonical checkout's tools and current documentation.

### Consolidated material

| Former location | Current disposition |
| --- | --- |
| `D:\Projects\sierra-auto-wt\installrec` | `.worktrees/install-record` |
| `sierra-auto-wt\microphone-diagnostics` | Merged source remains in Git; worktree retired; reports, test results and APK outputs preserved |
| `D:\Projects\sierra-auto-review-20261108` | Temporary review worktree retired; source baseline and build evidence preserved |
| `D:\Projects\sierra-auto-reviews` | `.local/reviews` |
| Loose scripts/logs in `sierra-auto-wt` | `.local/archive/consolidation-2026-11-08/legacy-integration` |
| `%USERPROFILE%\sierra-auto-runs` | `.local/runs` |
| `%LOCALAPPDATA%\sierra-auto\dhu` | `.local/state/dhu` |
| Sierra development APKs in Windows Temp | `.local/downloads/releases` |
| Sierra review/probe files and old temporary PR folder | `.local/archive/consolidation-2026-11-08/legacy-temp` |

The migration manifest is
`.local/archive/consolidation-2026-11-08/manifest.json`. It records old/new paths,
SHA-256 hashes and dispositions. Retired worktrees were clean and their commits
were already ancestors of current main. Their build reports, test XML and APKs
were kept; compiled intermediates and dependency caches were discarded.
The same archive holds `sierra-auto-before.bundle`, verified before migration,
plus before/after worktree lists and branch/tag references. No feature branch was
merged or deleted as part of consolidation.

Raw review files describe the paths as they existed when captured; those dated
records are preserved unchanged. Use the table above to locate their material.
The archive's loose integration scripts are reference material, not supported
commands. Some contain old paths and remote merge actions; do not run them as a
shortcut. Reconcile reusable parts with the existing integrator-tooling PR first.

### What belongs outside

The shared Android toolchain remains at `D:\Projects\android-toolchain`.
Signing files stay in the existing `%USERPROFILE%\.config\sierra-auto` secret
directory; other credentials remain in their machine environment/vault. Global
tool caches and agent session storage are managed by their tools. Remote bridge
services remain on their existing hosts. The consolidation concerns local project
checkouts and project-owned artifacts, not those shared services or secrets.
`D:\Projects\Sierra` is a separate repository containing an August 2026
general agent-platform proposal and no Android Auto application. It was inspected
for scope and left unchanged; it is not an additional Sierra Auto checkout.

### Local files and Git

`.local` and `.worktrees` are excluded from Git. They are not uploaded by a commit
or push. The Git bundle protects source history locally; raw reports and local
archives need their own off-machine backup if that is required. Do not treat a
second directory on this drive as a disaster-recovery backup.

## Supported workflow

From the canonical root, with PowerShell 7:

```powershell
pwsh -File scripts/workspace.ps1 check
pwsh -File scripts/workspace.ps1 list
pwsh -File scripts/workspace.ps1 new -Name task-name -Branch fix/task-name -Ref main
pwsh -File scripts/workspace.ps1 remove -Name task-name
```

Ordinary tasks use the main checkout; new worktrees are for actual concurrent or
isolated work. `check` rejects external registered worktrees, sibling folders
named after this repository, missing ignore rules and external output overrides.
`new` refuses an existing destination and accepts only a single safe folder name.
Paths are validated against directory traversal and existing symlinks/junctions.

`remove` refuses tracked changes, untracked files and unfamiliar ignored files.
It copies and hashes build reports, test results and distributable outputs to
`.local/runs/worktree-<name>-<timestamp>-<id>`, writes a manifest, and preserves
the commit at an immutable `refs/sierra/worktrees/<name>/<timestamp-id>` before
Git removes the clean worktree. Reusing a worktree name preserves each prior ref.
Branch refs remain intact. This also preserves detached PR-build commits.
Known disposable Gradle/Python caches and compiled intermediates are removed.
Inspect/preserve any other local files before retrying a refused removal.

`build-pr.ps1` creates a uniquely named `.worktrees/pr-*` checkout. Successful
builds retain results under `.local/runs`; failed builds and `-Keep` preserve the
worktree for investigation. It no longer force-removes or overwrites a prior
worktree. `install-phone.ps1` downloads development APKs to
`.local/downloads/releases/<tag>`. These scripts still build/install only when
explicitly invoked; a workspace check does neither.
Explicit `-Out` filenames for `shot.ps1` and `car-logs.ps1` must also stay under
canonical `.local`; relative filenames use the shell's current directory.
For example, from the root use `-Out .local/runs/home.png`.

| Setting | Default | Override rule |
| --- | --- | --- |
| `SIERRA_RUNS` | `<canonical root>/.local/runs` | Must stay below canonical `.local` |
| `SIERRA_STATE` | `<canonical root>/.local/state/dhu` | Must stay below canonical `.local` |
| `SIERRA_TOOLCHAIN` | `D:\Projects\android-toolchain` | Shared SDK/JDK location; may be external |
| `SIERRA_SERIAL` | The sole authorized USB device | Explicit device serial when needed |

The canonical root comes from Git's common directory, so current helper code
called from a linked worktree still uses the main checkout's `.local` directory.
Relative output overrides are resolved beneath `.local`, independent of the
calling shell's current directory. Use canonical scripts when working on older
branches that do not yet contain this helper.

## Next steps

- Keep every future task within this layout; run the workspace check at start
  and handoff. Repository instructions and helper checks enforce the supported
  workflow; arbitrary external commands can still bypass them.
- Decide the installer experiment's future on its merits. Rebase/test it only
  as an explicit feature task; consolidation does not establish that workaround.
- Review existing integrator/documentation PRs against these current files before
  merging, to avoid restoring obsolete paths and session instructions.
- Run `pwsh -File scripts/test-workspace.ps1` after changing workspace helpers.
  Its disposable fixture repositories and results stay in `.local/tests`.
  The new `workspace` GitHub workflow runs the parse/lifecycle checks on Windows
  for script/ignore/workflow changes. It has been validated locally; its first
  GitHub execution awaits publication of these changes.
- Keep current facts in [STATUS.md](STATUS.md), work order in [PLAN.md](PLAN.md),
  and dated/raw evidence local. Archive stale experiments deliberately rather
  than accumulating unnamed sibling checkouts.
