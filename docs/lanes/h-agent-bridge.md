# Lane H — historical bridge scope

Historical branch/scope (2026-11-04): `agent-bridge`; Python/uv/local-venv HTTP bridge, command/Pi/Hermes/OpenAI adapters, bearer auth, model routing, speech trimming, full logs, fake-backend tests and Linux/tailnet deployment. Named phone profiles were a proposal, not part of this lane's implementation.

## As-is — 2026-11-08, main `210448b`

Bridge endpoints/adapters/deployment exist; November 6 Hermes/profile checks provide narrow live evidence, not a current truck exchange. Linux CI green at review; native Windows 100/104 tests passed, four fixture/portability failures. Confirmed defects include reply-text identity collision, off-origin bearer redirect, unbounded auth-body read, shutdown deadlock, dropped-stream cleanup and decimal chunk formatting.

## Next steps

Fix boundary defects before broader deployment; use explicit client conversation IDs scoped to profile/model. Add bounded disconnect/shutdown tests and portable fixtures. Validate real Pi protocol/flags against the installed binary separately from fake tests. Restrict network/service privileges and do not assume full tool pass-through. [bridge README](../../bridge/README.md), [STATUS.md](../STATUS.md).
