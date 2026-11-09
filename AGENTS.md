# Sierra Auto repository instructions

## Canonical workspace

- On Mike's Windows machine, this project lives at `D:\Projects\sierra-auto`.
  Keep all Sierra Auto checkouts, experiments, reports, downloads and temporary
  project files underneath it. Never create sibling `sierra-auto-*` folders.
- Read `README.md`, `docs/STATUS.md` and `docs/WORKSPACE.md` first. Run
  `pwsh -File scripts/workspace.ps1 check` before creating another checkout and
  before handing work back.
- Work in the main checkout by default. If isolation or concurrent work requires
  a linked worktree, use the canonical root's `scripts/workspace.ps1 new` command.
  Its only destination is `.worktrees/<name>`. Git branches do not require extra
  folders. Do not create another clone or a bare repository for routine tasks.
- Use scripts from the canonical checkout for workspace/device operations.
  Preserved older branches and archived scripts may contain obsolete external
  output paths. Do not execute them as current tooling.
- Put ad hoc/private evidence under `.local`, not beside the repository or in
  a new user-home/Temp folder. Use the helpers in `scripts/_workspace.ps1`.
  `SIERRA_RUNS` and `SIERRA_STATE`, if set, must point beneath canonical `.local`.
  Maintained probe fixtures/results already under `tools/voice-probe` can retain
  their established layout; do not add credentials or private captures there.
- `.local` and `.worktrees` are Git-ignored and local-only. Keep reusable code in
  `scripts`/`tools`, maintained guides in `docs`, and explicit dated observations
  in status/evidence records. Do not copy private evidence into tracked docs.
- Shared Android SDK/JDK/Gradle caches and machine-managed credentials remain at
  their established external locations. Do not copy secrets into this project.

## Preserve work and finish cleanly

- Inspect tracked, untracked and ignored files before relocating/removing work.
  Preserve unique commits and useful evidence; verify copies before deletion.
- Use `scripts/workspace.ps1 remove -Name <name>` for a finished linked worktree.
  It refuses changed/untracked source and unfamiliar ignored files, preserves
  build evidence and a recovery ref, and uses Git's non-forced removal.
  Do not replace this with `Remove-Item -Recurse`, `git clean -fdx` or forced Git
  removal. Never remove another active task's worktree.
- Do not merge unfinished feature branches just to consolidate directories.
  `.worktrees/install-record` is an unmerged experiment, not the current app.
- At handoff, report current branch/revision, relevant tests, remaining worktrees,
  evidence locations and known gaps. Remove temporary worktrees you finished
  with; explicitly identify any retained for ongoing work.

## Development and evidence

- Use native Windows/PowerShell for this checkout. Python dependencies belong
  in `bridge/.venv` via `uv`; never install project dependencies system-wide.
- Use JDK 17 and SDK platform 36. Run checks appropriate to the change; workspace
  tooling changes require `pwsh -File scripts/test-workspace.ps1`.
- Preserve keys and native credentials; never print values. Live provider calls,
  device installation, GitHub issue creation and release publication are not
  implied by a normal local build or documentation edit.
- Describe implementation, automated test results and physical phone/truck
  acceptance separately. Keep maintained documentation in **As is** and **Next
  steps** terms. Date observations and identify their source revision.
- `docs/lanes` records old assignments; those files are not current session
  instructions or evidence that their original deliverables were completed.
- User instructions take precedence over these repository defaults.
