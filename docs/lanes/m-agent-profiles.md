# Lane M — historical second-wave named-agent scope

Historical branch/scope (2026-11-04): `agent-profiles`; named/aliased encrypted profiles, migration, add/edit/delete/Test UI, `ask_agent(name, question)` and `switch_agent`, attributed per-profile history, budgets/working cues and tests. Old ownership/integration/session mandates are retired.

## As-is — 2026-11-08, main `210448b`

Only the **two-way gateway/model versus bridge selector** is implemented, with separate URL/key/model settings, vault overlay, Hermes quick-connect and longer bridge budgets (120 s round/140 s exchange versus gateway 30/60 s). Duplex `ask_agent(question)` delegates to that selected target; it is **not** the proposed named-profile tool. No named-profile manager, spoken switch, per-profile history or general working-cue system is complete.

## Next steps

Design profile identity/history separation and stable bridge conversation IDs first (reply-text identity currently collides). Specify migration, aliases/ambiguity, attribution, cancellation and routing/budget tests before adding UI/tools. Keep speech credentials separate; no bridge token should reach transcription. [AGENT-BACKENDS.md](../AGENT-BACKENDS.md), [STATUS.md](../STATUS.md).
