---
description: Generic prime. Sets up the standard doc layout and this repo's own prime-<repo> command on first run, then runs it
argument-hint: [focus | rebuild | docs]
---

# Prime

There is one generic `prime` in the library. Each repo gets its own primer, named
`prime-<repo>`, which this command creates the first time it runs in that repo and reuses
on every later run. The first run also brings the repo up to the standard doc layout below,
so every repo ends up shaped the same way. The generic command does no repo-specific
reading itself beyond what it needs to build those files.

Arguments: `$ARGUMENTS`
- empty: run the repo's primer, creating the layout and the primer first if missing
- `rebuild`: write the repo's primer and `conditional_docs.md` again, fill any missing
  layout files, then run the primer
- `docs`: fill any missing layout files and refresh `conditional_docs.md`, then run the
  primer (the primer itself is kept)
- anything else: a focus area, passed through to the repo's primer

## The standard layout (docs-standard v1)

Modelled on IndyDevDan's repos. Paths are relative to the repo root.

| Path | Holds |
|---|---|
| `README.md` | What the project is, how to set it up, run it and test it. For people and agents. |
| `CLAUDE.md` | The engineering rules: short, imperative, only what an agent must not get wrong. |
| `AGENTS.md`, `GEMINI.md` | A pointer for other harnesses: `# Engineering Rules` then `Read CLAUDE.md.` |
| `ai_docs/README.md` | Index of third-party reference docs the project relies on: one line each, a URL or a local file in `ai_docs/` plus what it covers. |
| `specs/` | Plans and specs for work that is coming or in progress, one file each (`<topic>.md`). |
| `app_docs/` | How the parts that are already built work, one file per feature or subsystem (`<feature>.md`). |
| `<prompts dir>/prime-<slug>.md` | The repo's primer. |
| `<prompts dir>/conditional_docs.md` | Which doc to read for which kind of task. Carries the `docs-standard: v1` marker. |

`specs/` and `app_docs/` start with a short `README.md` saying what goes in them and how
files are named, so the folders exist in git before they have content.

**Rules file.** If the repo already keeps its rules in `AGENTS.md` and has no `CLAUDE.md`,
leave the rules where they are: `AGENTS.md` is the rules file and `CLAUDE.md` (and
`GEMINI.md`) become pointers to it. Whichever file holds the rules, the others only point.

## 1. Work out the names

- **Repo root**: the git root (`git rev-parse --show-toplevel`). If this is not a git repo,
  use the current directory.
- **Repo slug**: the root directory's name, lowercased, with every run of characters other
  than `a-z` and `0-9` turned into a single `-`, and leading or trailing `-` trimmed.
  For example `Cody KODI` becomes `cody-kodi`.
- **Primer name**: `prime-<slug>`.
- **Prompts dir**: the project prompts directory of the harness you are running in:
  `.claude/commands` for Claude Code and `.pi/prompts` for Pi. In any other harness, ask
  the user where project commands live.
- **Primer path**: `<repo root>/<prompts dir>/prime-<slug>.md`.

## 2. Check what exists

Look for the primer at the primer path, and note any copy in the other harness's prompts
directory. Check every path in the standard layout and note which are missing.

- **Primer found, argument is empty or a focus**: run a quick staleness check. Pick the
  files the primer tells the agent to read and confirm that each still exists. If more
  than one is missing, tell the user which ones and offer `/prime rebuild`. If any layout
  files are missing, list them and offer `/prime docs`. Do not write anything. Go to step 5.
- **Primer found, argument is `docs`**: go to step 3, skip step 4, then go to step 5.
- **Primer not found, or argument is `rebuild`**: go to steps 3 and 4, then step 5.

Never edit or overwrite this generic `prime` file.

## 3. Explore and fill the layout

Explore the repo enough to know what a fresh agent must read first:

1. List the tracked files: `git ls-files | head -200`, or a directory listing outside git.
2. Read the context files that exist: `README*`, `CLAUDE.md`, `AGENTS.md`, `GEMINI.md`,
   `.hermes.md`, `CONTRIBUTING*`, and any `docs/` index.
3. Find the entry points, build and run commands (`justfile`, `Makefile`, `package.json`,
   `pyproject.toml`, `Cargo.toml`, `build.gradle*` and similar), the main configuration
   files and the source directories that hold the core logic.
4. Skim the existing doc files (`docs/`, `specs/`, `app_docs/`, `ai_docs/`, other root
   `*.md`) far enough to tell, for each, what it covers and whether it is a plan, a
   description of built behaviour, or third-party reference.

Then create each **missing** layout file. Write only what you found:

- `README.md`: purpose, setup, run and test commands, where the code lives.
- `CLAUDE.md` (or the rules file): hard rules stated in the repo's own docs, comments or
  config, plus any that the build and tooling make plain (package manager, formatter,
  test command). Keep it short. If you found no rules, write the heading and one line:
  `TODO: add the rules an agent must not break.` Never invent rules.
- `AGENTS.md`, `GEMINI.md`: the pointer, exactly as in the table.
- `ai_docs/README.md`: the external libraries, APIs and tools the project depends on,
  each with its official docs URL and one line on what it covers. Leave out anything you
  cannot name a real source for.
- `specs/README.md`, `app_docs/README.md`: what belongs there and the naming, in two or
  three lines.

Mark anything you could not determine as `TODO:` rather than guessing.

**Never overwrite, rename, move or delete an existing file.** If an existing file falls
short of the standard (a README with no run commands, rules mixed into a long CLAUDE.md,
plans and feature docs sharing one `docs/` folder), leave it as it is and list the gap in
your report with a one-line suggestion. Existing docs stay where they are; the read guide
in step 4 points to them.

## 4. Write the read guide and the primer

**`conditional_docs.md`** at `<repo root>/<prompts dir>/conditional_docs.md`, written
fresh each time this step runs:

```markdown
<!-- docs-standard: v1 -->
# Conditional Documentation Guide

Read only the docs whose conditions match the task in hand. Do not read everything.

## Conditional Documentation

- README.md
  - Conditions:
    - When first understanding the project structure
    - When you need the commands to set up, run or test the project
- <path>
  - Conditions:
    - When <kind of task or area of the code>
...
```

List every doc file in the repo (layout files, existing `docs/` files, other root `*.md`,
READMEs inside source directories), each with one to three concrete conditions. Paths are
relative to the repo root.

**The primer** at the primer path. Use this shape, fill it with what you found, and keep
it under about 80 lines:

```markdown
---
description: Prime context for <project name>. <One sentence on what the project is>
argument-hint: [focus]
---

# Prime: <project name>

<Two or three sentences: what the project is, its stack, and where the important code lives.>

Focus: $ARGUMENTS (if empty, cover everything)

## Run

`git ls-files | head -100`

## Read

1. `README.md`: <why it matters, in one line>
2. `CLAUDE.md`: the engineering rules
3. `<prompts dir>/conditional_docs.md`: which further docs to read for the task in hand
4. `<file>`: <why it matters, in one line>
...

## Focus areas

- `<area>`: also read `<files>`
...

## Hard rules

- <The rules from the rules file, in brief>

## Summarize

Report back briefly:
- **Purpose**: <what to cover>
- **Architecture**: <what to cover>
- **Key files**: <what to cover>
- **How to run and test**: <what to cover>
```

Choose **8 to 15** files for the Read list in total. Prefer docs and core modules over
generated files, lock files, tests, vendored code and assets. For long files, name the
part to read, such as "first 100 lines". Leave out the focus-areas section if the repo has
no clear separate areas.

Requirements for everything this command writes:
- Every path it names must exist now. Check each one before saving.
- No `!` shell execution or `@file` inclusion, so the same files work in Claude Code and Pi.
- No secrets, tokens or `.env` values.

## 5. Report and run

Tell the user, briefly:
- which files were created, and which layout files already existed
- the gaps found in existing files, with the suggestions
- that the new files are uncommitted and can be committed with the repo, so later sessions
  and other machines reuse them

Then read `prime-<slug>.md` and carry out its instructions now, in this session, treating
any focus argument (anything other than `rebuild` or `docs`) as its `$ARGUMENTS`. After this,
the user can also call it directly as `/prime-<slug>`.
