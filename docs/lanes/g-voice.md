# Lane G — historical conversational-voice scope

Historical branch/scope (2026-11-04): `voice`; streamed sentence speech outputs, end-of-speech detection, follow-up controller, voice settings and older experimental realtime loop. Phase 2 requested Talk wiring. Session gates/deadlines and exclusive ownership are retired.

## As-is — 2026-11-08, main `210448b`

Turn-based pipeline is wired into Talk when capture/transcription is available, with tools/history/follow-ups and diagnostic input selection. Android/gateway/ElevenLabs output and transcription implementations exist. Adaptive detector no longer has the old permanent RMS cap. Saved legacy realtime settings are **not wired into Talk**; use lane J's separate duplex Conversation. November 4 probe numbers are desktop synthetic-audio observations, not truck latency/accuracy guarantees.

## Next steps

Verify capture/detection/transcription/output and focus on the actual phone/host, including silence, unconfirmed first utterance, follow-ups and cancellation. Repair permission fallback, legacy speaker callbacks and unsafe store recovery; retain diagnostic evidence for intermittent truck silence. [VOICE.md](../VOICE.md), [microphone diagnostics](../MICROPHONE-DIAGNOSTICS.md).
