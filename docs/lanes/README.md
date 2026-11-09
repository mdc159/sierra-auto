# Historical lane scopes

These briefs record the November 4, 2026 first/second-wave design scopes, reconciled against **main `210448b` on 2026-11-08**. They are not active assignments, proof of completion, current branch inventory or authority to run automation. Old session/deadline/push/merge mandates have been retired. Use [current plan](../PLAN.md), [verified status/defects](../STATUS.md) and [truck test plan](../TRUCK-TEST-PLAN.md) for current work.

| Lane | Historical scope | Current summary |
| --- | --- | --- |
| [A](a-review.md) | Independent review | New consolidated review separates source/reproduction/device evidence |
| [B](b-talk-tools.md) | Talk tools | Tool loop and places/navigation/idea tools built; vehicle tools absent |
| [C](c-obd-live.md) | Live Truck | Demo/classic/BLE/UI built; real adapter unverified, NO DATA/reconnect defects |
| [D](d-updates.md) | Updates | Play internal delivered; non-Play updater remains experimental |
| [E](e-screen-tests.md) | Screen tests/CI | Broad software coverage; host/harness evidence gaps remain |
| [F](f-gm-pids.md) | GM research/reader | Catalog/decoder built, no live integration or truck accuracy proof |
| [G](g-voice.md) | Turn-based voice | Wired into Talk; old realtime setting unwired |
| [H](h-agent-bridge.md) | Python bridge | Adapters/delivery built; confirmed security/cleanup defects |
| [I](i-studio54-signin.md) | Vault sign-in | Implemented overlays/cache; store recovery remains destructive |
| [J](j-duplex-voice.md) | Duplex Conversation | Both service adapters and car wiring built; truck echo/Stop proof missing |
| [K](k-review-fixes.md) | Review repairs | Mixed completion, not blanket fixed |
| [L](l-vehicle-tools.md) | Voice vehicle integration | Core deliverables absent |
| [M](m-agent-profiles.md) | Named voice profiles | Only two-way gateway/bridge selector built |
| [N](n-docs-and-truck-plan.md) | Guides/acceptance | Missing guides now supplied in documentation consolidation |

Current workspace: root checkout is main; retained installer experiment is `.worktrees/install-record`, not a shipped fix. Local reviews/runs/state/archive/downloads belong under canonical `.local`; toolchain and credential directory stay external. See [WORKSPACE.md](../WORKSPACE.md) for workspace commands. Historical integration scratch tooling is not an active unattended pipeline.

## Next steps

Use the current defect work order rather than restarting old lane automation. Land focused regressions/fixes under explicit owner authorization, then run parked-first host/audio/adapter acceptance and update the corresponding guide with actual evidence. No old lane deadline, branch label or assignment establishes completion.
