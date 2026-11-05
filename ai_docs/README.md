# AI Docs

Third-party reference for the libraries, services and hardware Sierra Auto depends on. One line each.
The project's own research and measurements on these are in `docs/` (see `.claude/commands/conditional_docs.md`).

## Android and Android Auto

https://developer.android.com/training/cars/apps - Android for Cars App Library: building a Car App Library app
https://developer.android.com/jetpack/androidx/releases/car-app - Car App Library releases (the app is on 1.7.0)
https://developer.android.com/training/cars/apps/poi - Point-of-interest app category, the category Sierra uses
https://developer.android.com/design/ui/cars/guides/templates/overview - Car templates, their limits and when to use each
https://developer.android.com/training/cars/apps/library/template-restrictions - Template stack and refresh restrictions
https://developer.android.com/reference/androidx/car/app/constraints/ConstraintManager - List and content limits the host reports
https://developer.android.com/docs/quality-guidelines/car-app-quality - Car App Quality guidelines (text length, driving restrictions)
https://developer.android.com/training/cars/apps/library/car-microphone - Recording from the car microphone (CarAudioRecord)
https://developer.android.com/training/cars/testing - Testing car apps, including the install-source rule for real vehicles
https://developer.android.com/training/cars/testing/dhu - Desktop Head Unit (DHU)
https://developer.android.com/reference/android/content/pm/PackageInstaller.Session - PackageInstaller sessions, used by the updater
https://developer.android.com/reference/android/content/pm/InstallSourceInfo - Install source as Android records it
https://support.google.com/androidauto/answer/6348019 - Android Auto help (owner side: setup and settings)
https://github.com/GitMag/UnknownAuto/wiki - UnknownAuto: third-party notes on getting sideloaded car apps listed (unofficial)
https://github.com/RikkaApps/Shizuku-API - Shizuku API, the optional install path in `update/`
https://play.google.com/console - Google Play Console, used by `.github/workflows/play.yml`

## Libraries

https://square.github.io/okhttp/ - OkHttp, the HTTP client for every network call
https://kotlinlang.org/docs/coroutines-overview.html - Kotlin coroutines
https://developer.android.com/jetpack/androidx/releases/security - AndroidX Security (EncryptedSharedPreferences for settings)
https://robolectric.org/ - Robolectric, runs the car screens under the JVM test host
https://docs.astral.sh/uv/ - uv, the Python package manager for `bridge/` and `tools/voice-probe/`

## Agent, voice and vault services

https://platform.openai.com/docs/api-reference/chat - OpenAI-compatible chat completions, the agent endpoint Talk calls
https://platform.openai.com/docs/api-reference/audio - OpenAI audio transcription and speech endpoints
https://platform.openai.com/docs/guides/realtime - OpenAI Realtime API, one of the full-duplex voice services
https://docs.litellm.ai/docs/ - LiteLLM, the owner's model gateway
https://docs.litellm.ai/docs/realtime - LiteLLM realtime passthrough
https://elevenlabs.io/docs - ElevenLabs text-to-speech, speech-to-text and Agents
https://infisical.com/docs - Infisical, the vault behind Studio 54 sign-in
https://github.com/earendil-works/pi - Pi coding agent, one of the bridge's adapters

## Places

https://nominatim.org/release-docs/latest/api/Search/ - Nominatim search, the main Places source
https://operations.osmfoundation.org/policies/nominatim/ - Nominatim usage policy (rate limit, user agent)
https://wiki.openstreetmap.org/wiki/Overpass_API - Overpass API, the Places fallback

## GitHub

https://docs.github.com/en/rest/issues/issues - Issues API, used by the idea loop (`file_idea`)
https://docs.github.com/en/rest/releases/releases - Releases API, used by the updater
https://github.com/anthropics/claude-code-action - Claude Code GitHub Action, used by `.github/workflows/claude.yml`
https://github.com/gitleaks/gitleaks - gitleaks, the secret scan in CI (rules in `.gitleaks.toml`)

## OBD-II

https://en.wikipedia.org/wiki/ELM327 - ELM327 adapter command set, overview
https://en.wikipedia.org/wiki/On-board_diagnostics - OBD-II services and standard PIDs, overview
https://scantool.net/scantool/downloads/product-documentation - OBDLink adapter documentation
