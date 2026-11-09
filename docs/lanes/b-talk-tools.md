# Lane B — historical Talk-tools scope

Historical branch/scope (2026-11-04): `talk-tools`; `agent/`, TalkScreen, tools, IdeaSettingsActivity, HTTP allowlist and tests. Requested a four-round tool loop with timeout/fallback, places/navigation/idea tools and short status templates. This is not an active ownership mandate.

## As-is — 2026-11-08, main `210448b`

AgentClient implements bounded model/tool rounds and plain-chat fallback. `find_places`, `navigate_to`, `file_idea` are wired to Talk and adapted to duplex. HTTP is allowed for tailnet hostnames and named development hosts, not arbitrary remote IPs. Voice ideas now use **`idea-voice`**, not the historical requested `idea`. Vehicle tools are absent.

## Next steps

Repair premature navigation success and stale-location fallback. Repair workflow mention-trigger bypass before relying on `idea-voice` as owner review. Add stable bridge conversation identity; verify backend tool-contract limitations instead of assuming all compatible endpoints execute phone tools. See [idea loop](../IDEA-LOOP.md), [backends](../AGENT-BACKENDS.md), [defects](../STATUS.md).
