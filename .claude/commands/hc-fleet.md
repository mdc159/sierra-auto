---
description: Use the shared fleet playbook for delegating to Herdr Core workers when HC_TERMINAL_ID is set.
argument-hint: "[task]"
---

Read `.agents/skills/hc-fleet/SKILL.md` from the checkout root and follow it only in a Herdr Core terminal. If `HC_TERMINAL_ID` is unset, report that the fleet skill does not apply and do not launch workers.

User-supplied task: $ARGUMENTS
