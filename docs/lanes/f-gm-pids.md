# Lane F — historical GM research scope

Historical branch/scope (2026-11-04): `gm-pids`; sourced Mode 22 DID/header/formula tables, typed readers/decoders and fake-response tests under `obd/gm/`, without weakening the command allowlist. Requested first-truck comparison steps and confidence labels. Old file-ownership mandates are retired.

## As-is — 2026-11-08, main `210448b`

GmDids, GmResponseDecoder and GmTruckReader exist. Source-count confidence is not vehicle validation; none of these readings is truck-proven. They are not wired into live Truck or agent tools. Legacy oil-life DID `119C` disagrees with newer `119F` (#32). Exact installed powertrain needs confirmation.

## Next steps

Reconcile catalogs and prove header restoration after timeouts, then validate standard OBD before parked GM discovery. Start with transmission DID `1940`, record raw reply/header and cluster comparison cold/warm. Do not rely on plausible values or research ranges as health thresholds. [GM-PIDS.md](../GM-PIDS.md), [truck plan](../TRUCK-TEST-PLAN.md).
