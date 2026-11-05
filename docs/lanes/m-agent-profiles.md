LANE M: several agents, switchable by voice. Branch `agent-profiles`.
Second wave, started 7:20 PM PST. All ten first-wave lanes are merged; work from current main. An integrator on the owner's PC builds and merges ready PRs automatically, so post your closing comment when you are done and it will be picked up. The owner is away: do not ask the owner questions; decide, record the decision in the PR, and continue. Commit with the git identity already configured in your environment, and do not put any email address on a command line.

Today the app talks to one configured agent. The owner runs several: models behind a private gateway, a Hermes agent, Pi coding agents on a server (reachable through the bridge in bridge/, see docs/AGENT-BACKENDS.md). The owner wants to say "ask Hermes ..." from the truck.

You own for this wave: new files under agent/profiles/, a new AgentProfilesActivity, new tool files under agent/tools/ for switching, and their tests. You may add one registration line to the existing tool registry and one button row to MainActivity.kt, and you may use the existing agent-config overlay hook that the Studio 54 sign-in added; if you need a change inside agent/ConfigStore.kt or agent/AgentClient.kt, lane K owns those files tonight, so keep it to an add-only hook and say so in your PR. Lane L owns obd/ and car/Truck*.kt; lane N owns README.md and docs/. Do not edit those.

Build:
1. Named agent profiles: name, spoken aliases, base URL, model, and where the key comes from (typed on the phone and stored encrypted, or a secret name read from the Studio 54 sign-in's SecretSource). One profile is the default. The current single configuration becomes the first profile without the owner re-entering anything.
2. AgentProfilesActivity on the phone: add, edit, delete, choose the default, and "Test" each profile.
3. Two tools for the Talk agent and the full-duplex voice: `ask_agent(name, question)` for a one-off question to another profile, with the answer spoken back and attributed ("Hermes says ..."), and `switch_agent(name)` to change who answers from now on. Fuzzy-match spoken names against aliases; when the match is unclear, say the choices instead of guessing.
4. The car's History screen shows which agent answered each turn, within the template limits in docs/android-auto-constraints.md.
5. A per-profile option for a longer timeout, since a coding agent behind the bridge can take a while, with a spoken "still working" cue instead of silence.
6. Unit tests with fake endpoints for profile storage and migration, name matching, both tools, and the timeout cue.
