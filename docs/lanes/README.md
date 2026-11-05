# Cloud session lanes (2026-11-04)

You are one of several cloud sessions working in parallel on mdc159/sierra-auto tonight (2026-11-04). A coordinator session on the owner's PC assigns lanes, builds every PR locally, tests it on the Android Auto emulator with the owner's real phone, and merges. Deadline: your PR must be CI-green by 11:00 PM PST. Push early and often so partial work is not lost.

Sierra Auto is a personal, sideloaded Android Auto app (Kotlin, Android for Cars App Library 1.7.0, package com.mdc159.sierra) for a 2018 GMC Sierra Denali, running on a Galaxy A54 (Android 16, Android Auto 17.9). On main today: Places (map plus nearby places, hand-off to Google Maps), Talk (car mic, speech-to-text, OpenAI-compatible agent, spoken reply), History, phone settings (MainActivity, plain Android Views), and an OBD-II groundwork package (obd/, not wired to any screen).

Read first: docs/PLAN.md and docs/android-auto-constraints.md. Any car screen must obey the constraints doc.

Rules for every session:
- Google's Maven is blocked in your sandbox, so do not run Gradle. Push your branch, open a PR right away (draft is fine), and use the GitHub Actions "build" workflow as your compiler and test runner: `gh run list`, `gh run view --log-failed`. Fix and push until it is green.
- Stay in your lane. Edit only the files your lane lists. Shared files (AndroidManifest.xml, app/build.gradle.kts, MainActivity.kt) may only get small add-only edits that your lane names. Never reformat or reorder them.
- Phone settings for your feature go in your own new Activity, reached by one new button row in MainActivity.
- This repo is public. Never commit a key, token or keystore.
- Merge origin/main into your branch before you finish. Do not merge your PR; leave it for the coordinator.
- Finish with a PR comment that lists: what is covered by tests, what is unverified, and exact steps the coordinator should run on the phone or emulator to check it.
- If you find a bug outside your lane, file a GitHub issue (label `bug`) with file:line and a failure scenario. Do not fix it yourself.

Your lane is the single file in this folder that your prompt named. Do only that lane.

| Lane | File | Branch |
| --- | --- | --- |
| A: independent reviewer | a-review.md | none (issues and PR comments only) |
| B: Talk tools | b-talk-tools.md | talk-tools |
| C: live truck data | c-obd-live.md | obd-live |
| D: updates away from the PC | d-updates.md | updates |
| E: screen tests and CI quality | e-screen-tests.md | screen-tests |
| F: GM-specific truck data | f-gm-pids.md | gm-pids |
