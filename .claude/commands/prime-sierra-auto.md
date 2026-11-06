---
description: Prime context for Sierra Auto. A personal, sideloaded Android Auto app that puts the owner's agent on a 2018 GMC Sierra Denali's screen
argument-hint: [focus]
---

# Prime: Sierra Auto

Sierra Auto is a Kotlin Android app built on the Android for Cars App Library 1.7.0 (package
`com.mdc159.sierra`, POI category). It runs on a Galaxy A54 and projects over Android Auto to
the truck. The car screens are in `app/src/main/java/com/mdc159/sierra/car/` and phone settings
are Activities beside `MainActivity`. Features live in their own packages: `agent/`, `voice/`,
`places/`, `obd/`, `studio54/` and `update/`. `bridge/` is a separate Python server that fronts
the owner's agents as an OpenAI-compatible endpoint.

Focus: $ARGUMENTS (if empty, cover everything)

## Run

`git ls-files | head -100`

## Read

1. `README.md`: what the app does on the truck, how it is installed, how to build
2. `CLAUDE.md`: the engineering rules
3. `.claude/commands/conditional_docs.md`: which further docs to read for the task in hand
4. `docs/PLAN.md`: goal, "done means", milestones M1 to M5
5. `docs/android-auto-constraints.md`: section 0 (the sideloading risk), then the section for the task
6. `app/src/main/AndroidManifest.xml`: the car app service, Activities and permissions
7. `app/src/main/java/com/mdc159/sierra/car/SierraCarAppService.kt`: the entry point Android Auto starts
8. `app/src/main/java/com/mdc159/sierra/car/HomeScreen.kt`: the car home grid and where each screen starts
9. `app/src/main/java/com/mdc159/sierra/car/TalkScreen.kt` (first 150 lines): the main feature, voice in and agent reply out
10. `app/src/main/java/com/mdc159/sierra/agent/AgentClient.kt` (first 120 lines): how a question reaches the agent and how tools are called
11. `app/src/main/java/com/mdc159/sierra/MainActivity.kt`: the phone settings hub, one button row per feature
12. `app/build.gradle.kts`: dependencies, SDK levels and release signing from CI secrets
13. `.github/workflows/build.yml`: CI jobs (secret scan, lint, build, release)

## Focus areas

- `talk` or `voice`: also read `docs/VOICE.md`, `docs/DUPLEX.md`, `docs/VOICE-GATEWAY-CHECK.md` (section 1) and `app/src/main/java/com/mdc159/sierra/agent/tools/Tool.kt`
- `places`: also read `app/src/main/java/com/mdc159/sierra/places/NearbyPlaces.kt` and `app/src/main/java/com/mdc159/sierra/car/PlaceListScreen.kt`
- `obd` or `truck data`: also read `docs/OBD2-PLAN.md`, `docs/GM-PIDS.md` (sections 1 and 2), `app/src/main/java/com/mdc159/sierra/obd/ObdSafety.kt` and `app/src/main/java/com/mdc159/sierra/obd/live/TruckMonitor.kt`
- `updates` or `install`: also read `docs/UPDATES.md`, `docs/TESTING.md` and `app/src/main/java/com/mdc159/sierra/update/UpdateActivity.kt`
- `studio54` or `keys`: also read `docs/STUDIO54.md` and `app/src/main/java/com/mdc159/sierra/studio54/Studio54.kt`
- `bridge`: also read `bridge/README.md` and `docs/AGENT-BACKENDS.md`
- `ideas`: also read `docs/IDEA-LOOP.md`, `.github/workflows/claude.yml` and `app/src/main/java/com/mdc159/sierra/agent/tools/FileIdeaTool.kt`

## Hard rules

- Public repo: no keys, tokens, keystores or `.env` values; the private Tailscale hostname is always `<gateway-host>`.
- Car screens obey `docs/android-auto-constraints.md` and the Car App Quality guidelines.
- OBD-II is read-only; never loosen `ObdSafety`.
- Shared files (manifest, `app/build.gradle.kts`, `MainActivity.kt`) get small add-only edits only.
- Build and test: `./gradlew assembleDebug testDebugUnitTest`; lint: `./gradlew lintDebug`. CI green before merge.
- A DHU pass is not a truck pass. Mark anything not checked on the device as **unverified**.

## Summarize

Report back briefly:
- **Purpose**: who it is for, what it does on the truck screen today, and what is still unproven on the truck
- **Architecture**: phone app, Android Auto host, agent endpoint (gateway, provider or bridge), vault, voice services, OBD adapter
- **Key files**: the entry point, the screens and the feature packages, one line each
- **How to run and test**: the Gradle build and tests, CI, installing on the phone, and the DHU scripts
