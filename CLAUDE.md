# Engineering Rules

- This repository is public. Never commit a key, token, keystore or `.env` value. Never write the owner's private Tailscale hostname or tailnet addresses; use a placeholder such as `<gateway-host>`.
- Every car screen must obey `docs/android-auto-constraints.md` and Google's Car App Quality guidelines: template and list limits, text length, no typing while the truck moves.
- OBD-II access is read-only. Never loosen `ObdSafety` (deny by default, no UDS writes or sessions, no adapter reprogramming). The rules are in `docs/OBD2-PLAN.md` section 8 and are tested in `ObdSafetyTest`.
- Shared files (`app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, `MainActivity.kt`) get small add-only edits. Never reformat or reorder them. A feature's phone settings go in its own Activity, reached by one new button row in `MainActivity`.
- Build and test with `./gradlew assembleDebug testDebugUnitTest`, and lint with `./gradlew lintDebug` (JDK 17, Android SDK platform 36). CI (`.github/workflows/build.yml`: secret scan, lint, build) must be green before a merge.
- The bridge (`bridge/`, Python 3.11+) uses uv: `uv sync --locked`, then `uv run pytest -q`.
- A pass on the Desktop Head Unit is not a truck pass. The DHU skips the install-source check that the truck applies (`docs/TESTING.md`, issue #36).
- In docs, mark every claim that was not checked on the phone or truck, or against the vendor's own documentation, as **unverified**.
- Work lands on `main` by pull request.
