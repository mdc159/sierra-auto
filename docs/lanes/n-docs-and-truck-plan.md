LANE N: make the project readable, and plan the first truck test. Branch `docs-truck-plan`.
Second wave, started 7:20 PM PST. All ten first-wave lanes are merged; work from current main. An integrator on the owner's PC builds and merges ready PRs automatically, so post your closing comment when you are done and it will be picked up. The owner is away: do not ask the owner questions; decide, record the decision in the PR, and continue. Commit with the git identity already configured in your environment, and do not put any email address on a command line.

Ten sessions built this app in one evening and nothing has run on the truck. The docs are scattered across lane write-ups, and README.md still describes the first hour. The owner is not an Android developer and wants plain language.

You own for this wave: README.md and everything under docs/ except docs/OBD2-PLAN.md and docs/GM-PIDS.md (lane L) and docs/lanes/. Do not edit code, tests or workflows; if you find a doc that is wrong because the code changed, fix the doc, and if you find a bug, file an issue.

This repository is public. Never write the owner's private Tailscale hostname; use a placeholder such as `<gateway-host>`. Search the docs for any place it has leaked and replace it (issue #19).

Write, from the code and docs on main, checking claims against the source instead of repeating the lane write-ups:
1. README.md: what Sierra Auto is, what it can do today, what is unproven, how it gets onto the phone (read docs/UPDATES.md and issue #36: the Desktop Head Unit accepts any install, a real head unit is expected to require a Play-recorded install started by the system installer or Play), and where to go next. Short.
2. docs/USER-GUIDE.md: first-time setup on the phone in order (permissions, Studio 54 sign-in, agent, voice, OBD demo mode), then what each car screen does and what to say to it. Screens and buttons by their real labels.
3. docs/TRUCK-TEST-PLAN.md: every "unverified" or "not tested on a device" item from the docs, the PR closing comments (#15, #24, #25, #26, #27, #30, #31, #33, #37, #41) and issues #36 and #39, merged into one ordered checklist for the first session in the truck: a parked part of about fifteen minutes first (does the app appear, each screen, microphone, speakers, echo, the steering-wheel button), then a part for a passenger while driving. Each step says what to do, what a pass looks like, and what to capture if it fails. Put the steps that could block everything else first.
4. docs/ARCHITECTURE.md: one page with a Mermaid diagram of how the phone app, Android Auto, the gateway, the vault, the voice services, the OBD adapter and the agent bridge fit together, and which package owns what.
5. Update docs/PLAN.md with what was actually built and what remains, and docs/IDEA-LOOP.md if the idea flow changed (the label for voice-filed ideas changed in a follow-up to Talk tools).
6. Work through the review points in issue #43 on docs/HARDWARE.md.
