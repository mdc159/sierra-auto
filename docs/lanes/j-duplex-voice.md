# Lane J — historical full-duplex scope

Historical branch/scope (2026-11-04): `duplex-voice`; service/session interface, OpenAI Realtime and ElevenLabs Agents websockets, resampling/jitter/focus, car/phone sources, Guarded/full-duplex modes, client tools, phone test UI and fake-server tests. Phase 2 requested car Conversation and Talk-tool adaptation; old deadlines/merge prerequisites are retired.

## As-is — 2026-11-08, main `210448b`

Both adapters, phone settings/test, `ask_agent`, adapted Talk tools and car Conversation/Interrupt/Stop are wired. November 6 build 100003 reached Listening on phone; no completed current-build exchange/echo/barge-in/truck routing pass is established. Private ElevenLabs signed-URL HTTP lookup lacks bounded cancellation; native mic startup/cleanup can block Stop.

## Next steps

Add stalled HTTP/native-mic lifecycle regressions, then parked phone and real-host exchange, interrupt, focus, duration and recovery tests. Measure false interruptions at safe volume and preserve diagnostics; guard ratio is not proven echo cancellation. Account tools/overrides are provider prerequisites. [DUPLEX.md](../DUPLEX.md), [truck plan](../TRUCK-TEST-PLAN.md).
