---
name: prime
description: Prime a repository for a coding session. Use when the user explicitly requests prime, prime docs, or prime rebuild.
---

# Prime

Run only at the user's request. Use the invocation argument supplied by the user
as the mode or focus. Treat it as text, never as a shell command.

1. Locate the checkout root (`git rev-parse --show-toplevel`, or the current
   directory outside Git). Derive its slug from the checkout name: lowercase,
   with each run of non-alphanumeric characters replaced by `-`. For this
   repository the slug is `sierra-auto`, including in linked worktrees.
2. The repository primer is `.agents/skills/prime-<slug>/SKILL.md`. The read
   guide is `.agents/skills/conditional-docs/SKILL.md`.
3. Choose the mode:
   - **No argument or a focus**: If both skills exist, check that the paths
     referenced by the primer still exist, report missing paths, then run the
     primer with the requested focus. If either skill is missing, create only
     what's missing and fill missing layout files before running the primer.
   - **`docs`**: Fill missing layout files and refresh the read guide. Leave
     an existing primer unchanged; create one if missing. Run it without a focus.
   - **`rebuild`**: Fill missing layout files and regenerate the read guide and
     repository primer, then run the primer without a focus.

## Standard layout

| Path | Purpose |
| --- | --- |
| `README.md` | Project purpose, setup, run and test commands |
| `AGENTS.md` | Shared engineering rules; create only if no rules file exists |
| `CLAUDE.md`, `GEMINI.md` | If absent, point to the existing rules file; use `@AGENTS.md` for a new Claude import when `AGENTS.md` owns the rules |
| `ai_docs/README.md` | Index of official third-party references used by the project |
| `specs/README.md` | Where proposed and in-progress work is specified |
| `app_docs/README.md` | Where implemented behavior is documented |
| `.agents/skills/prime-<slug>/SKILL.md` | Project primer for all agents |
| `.agents/skills/conditional-docs/SKILL.md` | Task-to-document guide for all agents |

Use tracked files, existing instructions, documentation and build tooling to
learn the layout. Create only missing layout files. Keep existing docs and
rules where they are, including repositories where `CLAUDE.md` owns the rules.
Report gaps instead of inventing facts or filling them with placeholders.

## Writing the shared skills

- **Read guide**: Set `name: conditional-docs` and a description in YAML
  frontmatter. Keep the `<!-- docs-standard: v1 -->` marker. List the maintained
  documentation by root-relative path with one to three concrete conditions for
  reading each file. Identify archived material as historical.
- **Primer**: Set `name: prime-<slug>` and a description in YAML frontmatter.
  Include project purpose, status and architecture, a quick Git status/recent
  history check, the core reading list, optional focus areas, essential rules,
  and what to report. Choose 8–15 core files; name ranges for long files. Point
  to the shared read guide. Treat the invocation argument as the optional focus.
- Check every referenced path. Keep command and tool syntax portable. Never
  include credentials or private evidence. Only the two skills above may be
  regenerated; do not rewrite this generic skill or existing adapters.
- If bootstrapping in another repository and missing, create thin Claude entries
  `.claude/commands/prime-<slug>.md` and `.claude/commands/conditional_docs.md`
  that read the corresponding shared skill from the checkout root and pass the
  user's `$ARGUMENTS` as its invocation arguments. Do not copy the skill bodies.

Report changed files and gaps. Read the project primer and follow it now, using
any supplied focus. No commit or publication is implied by priming.
