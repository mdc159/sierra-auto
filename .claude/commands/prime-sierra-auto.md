---
description: Prime context for Sierra Auto. Personal Android Auto app (Kotlin) with an optional Python agent bridge for a 2018 GMC Sierra
argument-hint: [focus]
---

# Prime: Sierra Auto

Sierra is a personal Android Auto (Car App Library, POI) app for a 2018 GMC Sierra Denali and a Galaxy A54:
agent conversation, nearby places, voice and read-only OBD data. The Kotlin app is in `app/src/main/java/com/mdc159/sierra/`;
the optional Python HTTP bridge to existing agents (Pi, Hermes, CLIs) is in `bridge/sierra_bridge/`.

Focus: $ARGUMENTS (if empty, cover everything)

## Run

`git ls-files | head -100`
`git status --short` and `git log --oneline -5`

## Read

1. `README.md`: purpose, as-is table, layout, build and test commands
2. `AGENTS.md`: the engineering rules (workspace, evidence, credentials)
3. `.claude/commands/conditional_docs.md`: which further docs to read for the task in hand
4. `docs/STATUS.md` (first 80 lines): current checkpoint, promotion blockers, open defects
5. `docs/PLAN.md` (first 60 lines): authoritative work order
6. `docs/ARCHITECTURE.md`: code boundaries and integration diagram
7. `docs/DEVELOPMENT-WORKFLOW.md` (first 40 lines): dev/* → staging → Play internal → main
8. `app/build.gradle.kts`: SDK levels, flavors, dependencies
9. `app/src/main/AndroidManifest.xml`: activities, car service, permissions
10. `app/src/main/java/com/mdc159/sierra/agent/AgentClient.kt`: how the app calls the agent
11. `bridge/README.md` (first 60 lines): bridge endpoints, adapters, known Windows test failures

## Focus areas

- `phone`: also read `docs/PHONE-CONVERSATION.md`, `app/src/main/java/com/mdc159/sierra/phone/PhoneActivity.kt`
- `car`: also read `docs/android-auto-constraints.md`, `app/src/main/java/com/mdc159/sierra/car/SierraCarAppService.kt`
- `voice`: also read `docs/VOICE.md`, `docs/DUPLEX.md`, `app/src/main/java/com/mdc159/sierra/voice/convo/ConversationController.kt`
- `obd`: also read `docs/OBD2-PLAN.md`, `docs/GM-PIDS.md`, `app/src/main/java/com/mdc159/sierra/obd/Elm327Client.kt`
- `studio54`: also read `docs/STUDIO54.md`, `app/src/main/java/com/mdc159/sierra/studio54/Studio54.kt`
- `bridge`: also read `bridge/sierra_bridge/server.py`, `bridge/sierra_bridge/adapters/base.py`
- `release`: also read `docs/UPDATES.md`, `.github/workflows/build.yml`, `.github/workflows/play.yml`
- `testing`: also read `docs/TESTING.md`, `docs/TRUCK-TEST-PLAN.md`

## Hard rules

- Canonical Windows root `D:\Projects\sierra-auto`; no sibling `sierra-auto-*` folders, no extra clones. Linked worktrees only via `scripts/workspace.ps1 new` into `.worktrees/<name>`; remove with `scripts/workspace.ps1 remove`.
- Private evidence goes under `.local`; never copy it into tracked docs. Never force-remove, `git clean -fdx` or delete unique work.
- JDK 17, SDK 36; Python deps only in `bridge/.venv` via `uv`. Workspace tooling changes need `pwsh -File scripts/test-workspace.ps1`.
- Never print or commit keys; live provider calls, device installs, issue creation and releases need explicit authorization.
- Report implementation, automated tests and physical phone/truck acceptance separately; docs stay in dated **As is** / **Next steps** terms. `docs/lanes` is history, not instructions.

## Summarize

Report back briefly:
- **Purpose**: what Sierra does and its current as-is/acceptance state
- **Architecture**: app packages (car, phone, agent, voice, obd, places, studio54, update) and the bridge
- **Key files**: entry points and the files relevant to the focus
- **How to run and test**: Gradle build/test/lint, bridge `uv run pytest`, workspace check, plus current branch, open blockers and known test failures
