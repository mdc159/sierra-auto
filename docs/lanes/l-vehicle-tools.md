# Lane L — historical second-wave vehicle-tools scope

Historical branch/scope (2026-11-04): `vehicle-tools`; join OBD/live/GM to Talk and duplex, add spoken vehicle/codes data, live transmission temperature, threshold alerts and tests; reconcile NO DATA (#21), init persistence (#20), oil-life DID (#32), hardware/docs (#35). This scope is not evidence that these features landed; old ownership/session mandates are retired.

## As-is — 2026-11-08, main `210448b`

Standard Truck/Codes and standalone GM readers exist separately. **`read_vehicle`/`read_trouble_codes` tools, live GM Truck integration and configurable threshold/voice alerts are absent.** NO DATA still becomes empty codes; reconnect transient errors can exit early. Oil-life catalogs still disagree. Init still sends `ATSP6`; adapter persistence/behavior needs investigation, not a blanket “never writes settings” claim.

## Next steps

Fix unknown-versus-zero and reconnect/key-off semantics with regressions; reconcile init/persistence and DID catalogs. Validate one physical adapter/standard PIDs before GM integration. Then implement a bounded, fresh snapshot service with explicit demo/unsupported/stale states for UI and voice, never clear codes. Alerts need independently validated units/thresholds, once-only/cooldown semantics and parked acceptance, not model guesses. [OBD2-PLAN.md](../OBD2-PLAN.md), [GM-PIDS.md](../GM-PIDS.md).
