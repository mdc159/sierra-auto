---
name: prime-sierra-auto
description: Prime context for Sierra Auto, a Kotlin Android Auto app with an optional Python agent bridge. Use when the user requests the Sierra primer or a focused project orientation.
---

# Prime: Sierra Auto

Sierra is a personal Android Auto app for a 2018 GMC Sierra Denali and a Galaxy
A54: agent conversation, nearby places, voice and read-only OBD data. The Kotlin
app is in `app/src/main/java/com/mdc159/sierra/`; the optional Python HTTP bridge
to existing agents is in `bridge/sierra_bridge/`.

Use the supplied invocation argument as the focus. If none was provided, give
an overview across subsystems rather than reading every focus-area file.

## Run

Run `git status --short --branch` and `git log --oneline -5`. Sample the tracked
file list with `git ls-files` if needed.

## Read

1. `AGENTS.md`: workspace, evidence and credential rules.
2. `README.md`: purpose, as-is table, layout, build and test commands.
3. `docs/STATUS.md` (first 80 lines): checkpoint, blockers and verified state.
4. `docs/WORKSPACE.md`: canonical checkout, worktree and evidence paths.
5. `.agents/skills/conditional-docs/SKILL.md`: which further docs match the task.
6. `docs/PLAN.md` (first 60 lines): authoritative work order.
7. `docs/ARCHITECTURE.md`: code boundaries and integration diagram.
8. `docs/DEVELOPMENT-WORKFLOW.md` (first 40 lines): dev → staging → Play internal → main.
9. `app/build.gradle.kts`: SDK levels, flavors and dependencies.
10. `app/src/main/AndroidManifest.xml`: activities, car service and permissions.
11. `app/src/main/java/com/mdc159/sierra/agent/AgentClient.kt`: agent call boundary.
12. `bridge/README.md` (first 60 lines): endpoints, adapters and test entry points.

## Focus areas

- `phone`: `docs/PHONE-CONVERSATION.md`, `app/src/main/java/com/mdc159/sierra/phone/PhoneActivity.kt`
- `car`: `docs/android-auto-constraints.md`, `app/src/main/java/com/mdc159/sierra/car/SierraCarAppService.kt`
- `voice`: `docs/VOICE.md`, `docs/DUPLEX.md`, `app/src/main/java/com/mdc159/sierra/voice/convo/ConversationController.kt`
- `obd`: `docs/OBD2-PLAN.md`, `docs/GM-PIDS.md`, `app/src/main/java/com/mdc159/sierra/obd/Elm327Client.kt`
- `studio54`: `docs/STUDIO54.md`, `app/src/main/java/com/mdc159/sierra/studio54/Studio54.kt`
- `bridge`: `bridge/sierra_bridge/server.py`, `bridge/sierra_bridge/adapters/base.py`
- `release`: `docs/UPDATES.md`, `.github/workflows/build.yml`, `.github/workflows/play.yml`
- `testing`: `docs/TESTING.md`, `docs/TRUCK-TEST-PLAN.md`

## Report

Briefly cover purpose and verified acceptance, architecture, relevant files,
validation commands, current branch, blockers and known test failures. Follow
`AGENTS.md` for workspace and authorization boundaries. Priming is read-only.
