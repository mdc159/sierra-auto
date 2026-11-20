# Sierra Auto — current plan

As of **2026-11-08, main `210448b`**. Started 2026-11-04 for a 2018 GMC Sierra Denali, factory 8-inch infotainment and wired Android Auto; phone is a Galaxy A54, Android 16. Exact powertrain/adapter details still need physical confirmation.

## Goal and current delivery

Put the owner's agent on the truck screen, with useful places, speech and trustworthy read-only vehicle information. **Play internal testing is implemented**, alongside signed GitHub APK releases; this is no longer a sideload-only project and is not a public production listing. Prefer Play for truck installations and updates. A release or DHU pass is not truck acceptance.

## Built versus accepted

| Milestone | Software as-is | Acceptance still needed |
| --- | --- | --- |
| M1 delivery/Home | Signed release pipeline, Play-internal workflow, Home grid | Confirm actual Play installation provenance and current release-host launch |
| M2 Places | OSM nearby/search, host-drawn map and navigation handoff | Accurate/fresh real location, actual host handoff; known stale-fix and premature-success defects |
| M3 Talk | Capture/transcription, bounded tool loop (`find_places`, `navigate_to`, `file_idea`), speech and follow-up turns | Recorded phone/truck audio exchange, permission/focus/failure paths |
| M4 settings/history | Phone settings, encrypted stores, Studio54 overlay, two-way model/bridge selection, history, microphone diagnostics | Nondestructive store recovery; current source still deletes several stores on open failure |
| M5 idea loop | Voice issue creation and issue-to-PR workflow | Repair `@claude` bypass of intended owner review gate before relying on it |
| Vehicle data | Demo/live standard reader, classic/BLE, Truck/Codes; separate GM catalogs/readers | Real adapter/ECU evidence; fix NO DATA/reconnect; GM Truck integration, `read_vehicle`, alerts absent |
| Duplex | OpenAI/ElevenLabs Conversation flow with tools and guard/interruption | Completed current-build exchanges, echo/barge-in, Stop, reconnect and actual truck routing |

Named profiles/spoken profile switching remain proposed. The older `voice/realtime` package and saved switch are not wired into Talk; the separate duplex Conversation flow is wired. Lane assignments are historical scopes, not completion evidence.

## Next steps / done means

Follow [verified status and prioritized defects](STATUS.md). First repair P1 boundaries (automation, store retention, redirects, conversation identity, HTTP framing), then cleanup and misleading results. Validate Play provenance and a parked truck session before passenger-operated road testing. Validate standard OBD on one adapter before GM/tools/alerts. Preserve unknown, unsupported and stale states in UI and speech; never invent vehicle readings.

A reliable MVP requires the recorded launch → microphone → transcript → selected-agent answer → car speaker → interrupt/stop → recovery chain in [TRUCK-TEST-PLAN.md](TRUCK-TEST-PLAN.md), not just successful builds.

## Constraints and references

- Template/host limits: [android-auto-constraints.md](android-auto-constraints.md); no typing/settings while moving, no navigation-channel misuse. Internal-track delivery does not certify category/policy acceptance.
- Signing secrets/keystore never belong in the public repository. Local unsigned-release fallback is debug signing; production artifacts require protected signing inputs.
- Canonical workspace is `/home/mdc/Projects/sierra-auto` on the Linux development machine; the installer experiment is preserved as tag `archive/install-record` and local evidence lives in `.local/{reviews,runs,state,archive,downloads}`. The JDK/SDK toolchain comes from `.local/omarchy-toolchain/env.sh`; signing credentials remain external. See [WORKSPACE.md](WORKSPACE.md) for current layout/commands. Workspace changes do not repair application defects.
- Start here: [USER-GUIDE.md](USER-GUIDE.md), [ARCHITECTURE.md](ARCHITECTURE.md), [TESTING.md](TESTING.md), [UPDATES.md](UPDATES.md).
