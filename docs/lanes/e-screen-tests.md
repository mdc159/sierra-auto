LANE E: screen tests and CI quality. Branch `screen-tests`.
Early builds tonight crashed on the car because templates broke host rules (two titled buttons in an action strip; map-list rows without a distance). Unit tests did not catch it. Your job is to make that class of bug fail in CI.
You own: everything under app/src/test/ that exists on main today, .github/workflows/build.yml, lint configuration, and bug fixes in car/Place*.kt, car/HistoryScreen.kt, car/CarLimits.kt, places/, net/ and voice/. Do not edit car/TalkScreen.kt, car/HomeScreen.kt, agent/, obd/, or MainActivity.kt; file an issue (label `bug`) for anything you find there. Other lanes add their own tests for their own new code; do not edit theirs.
Shared-file edits allowed: app/build.gradle.kts (add-only test dependencies and lint options).
Do:
1. Add `androidx.car.app:app-testing` with Robolectric. For every car Screen on main, build its template under TestCarContext in each state (loading, empty, error, permission missing, a full list longer than the host limit, very long strings) and assert that it builds without throwing. Add tests proving the two crashes above would now be caught.
2. Voice and network edge cases that have no tests yet: Pcm/WAV encoding, timeouts, malformed JSON from the agent and from the place sources.
3. CI: run Android lint and fail on errors; upload test and lint reports as artifacts; add Gradle caching; add a secret scan (gitleaks or similar) so that a committed key or keystore fails the build. Keep the release job's behavior and the signing-secret handling exactly as they are, and keep total PR build time under about 5 minutes.
4. Fix what your tests expose in the files you own. Keep each fix in its own commit with the failing test.
