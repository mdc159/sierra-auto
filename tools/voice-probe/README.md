# voice-probe

## As is — 2026-11-08, main `210448b`

Small implemented Python probes for the owner's LiteLLM gateway and ElevenLabs. [Findings](../../docs/VOICE-GATEWAY-CHECK.md) and `results/` are **historical November 4 desktop synthetic-audio measurements**, not current provider availability, account pricing or phone/truck evidence. No probes/live requests were rerun for this documentation update. Current Android integration and remaining defects are in [VOICE](../../docs/VOICE.md), [DUPLEX](../../docs/DUPLEX.md) and [STATUS](../../docs/STATUS.md).

The gateway is reachable only on the owner's Tailscale network, so these run on the owner's Linux box (omarchy), not in a cloud session or CI.

## Keys

The scripts read keys from the environment and nowhere else:

| Variable | Used for |
| --- | --- |
| `OPENAI_BASE_URL` | gateway base URL ending in `/v1` |
| `OPENAI_API_KEY` | gateway key |
| `ELEVENLABS_API_KEY` | `p06` and `p07` only |

Shared `say()`/`save()` helpers scrub environment credential values whose names contain KEY, TOKEN, SECRET or PASSWORD **when the value is at least eight characters**, plus selected key-like shapes. This is useful redaction, not a guarantee all output/transcripts/URLs are private. Inspect outputs and avoid short dummy/real tokens, raw shell environment dumps or captured launcher credentials.

## Running

```
uv sync --locked             # creates .venv here; never installs into system Python
uv run --locked python p00_models.py   # with the three variables in the environment
```

`./run.sh <script> [args]` runs a probe under the studio54 launcher (`~/.config/studio54/run ${S54_AGENT:-omarchy} -- .venv/bin/python <script>`), which injects the three variables without printing them. Alternatively supply environment secrets securely and use `uv run --locked python <script>` from this directory, never system Python.

| Script | What it does | Calls it makes |
| --- | --- | --- |
| `p00_models.py` | Names of the variables delivered, `/v1/models`, per-model mode and price, this key's spend (printed, not saved) | free |
| `p01_make_clips.py` | Makes the test clips with the gateway's text-to-speech: 16 kHz mono WAV, clean plus 15 dB and 3 dB road-like noise, into `audio/` | 2 speech |
| `p02_stt.py` | `/v1/audio/transcriptions`: latency and word error rate for four models | 28 transcriptions |
| `p03_tts.py` | `/v1/audio/speech`: formats, time to first byte, chunk timeline. `extras` adds a one-word input and other models | 29, or 9 with `extras` |
| `p04_chat.py` | `/v1/chat/completions` streaming: first token, first sentence, tool call. Options: model names, `runs=N`, `mix`, `tools=0`, `effort=low`, `name=label` | 3 per model by default |
| `p05_realtime.py` | `/v1/realtime` websocket: `session.created`, text prompt to audio. `audio` speaks the short clip into the session, with manual commit and with server voice detection | 1 session per model, 2 with `audio` |
| `p06_elevenlabs.py` | ElevenLabs directly: model list, streaming speech, transcription | 7 speech, 5 transcriptions |
| `p07_pipeline.py` | The whole turn: clip, transcript, streaming chat, first sentence, first audio byte. Options: `chat=MODEL`, `stt=MODEL`, `combos=1,2`, `name=label` | 9 turns |

Run `p01` before `p02`, `p05 audio`, `p06` and `p07` (they read `audio/` and `results/01_clips.json`), and `p06` before `p07` (it reads the voice id).

A full pass reportedly cost about $0.12 on the gateway and about 2,000 ElevenLabs characters on 2026-11-04; that is not a current price/usage guarantee. Request counts above describe script defaults and can change with options/errors. The gateway budget is shared, so obtain approval before any paid rerun and do not loop these.

`probe_common.py` uses script-relative `audio/` and `results/`, **not** `SIERRA_RUNS`/`SIERRA_STATE`; a rerun can overwrite historical tracked JSON. Preserve existing results before any authorized run and inspect the diff afterward. Keep new evidence under canonical `/home/mdc/Projects/sierra-auto/.local/runs/voice-probe-<date>` and do not export project results to home/temp/sibling trees. The existing script-local outputs are also within this root; no code/path behavior was changed by these docs. [Workspace](../../docs/WORKSPACE.md).

## Results

`results/*.json` are the runs behind the document, each stamped with `recorded_at`. Files with a suffix are follow-up runs: `04_chat_alt` (other models), `04_chat_effort_low`, `04_chat_repeat` (six runs each), `05_realtime_audio`, `07_pipeline` (chat on `xai/grok-4.20-non-reasoning`), `07_pipeline_oss120b`, `07_pipeline_oss120b_minitranscribe`, `07_pipeline_allgateway_rerun`.

Two things to know when reading them:

- `p01` was run more than once and text-to-speech is not deterministic, so the clips behind `02_stt.json` onward are the last takes (3.55 s and 13.85 s). The clips themselves are not committed (`audio/` is ignored; the long one is over 400 KB).
- The sentence splitter in `p04` and `p07` was tightened after most runs were recorded. It used to close a sentence when a stream chunk happened to end on `.`, `!` or `?`; it now waits for white space after the terminator. The recorded "first sentence" times are at most one token earlier than the current scripts would report. Only `07_pipeline_allgateway_rerun.json` was recorded with the current rule.

## Next steps

- Preserve dated raw measurements and exact script/model/options/environment identity before an authorized minimal rerun; verify routes/account access, not stale model or latency guarantees.
- Add human/cabin/device tests separately through the [truck plan](../../docs/TRUCK-TEST-PLAN.md). These probes cannot prove physical mic routing, mobile latency, echo, barge-in, focus or host acceptance.
- Test every numeric/URL split position in production speech formatting. The tightened probe splitter is **not** proof the Python bridge's reproduced `1.` + `25` decimal bug is fixed.
- Record errors, sample size, first-byte-versus-audible-output boundaries and cost; do not rank current provider quality/pricing from one historical evening.

## Timing definitions

- Total latency: from sending the request to the last byte of the response.
- First byte: from sending the request to the first body bytes read off the socket (`iter_raw`).
- First token: the first streamed chunk with non-empty `delta.content`.
- First sentence: the first moment the streamed text holds a complete sentence.
- Realtime first audio: from `response.create` (text turns), from `input_audio_buffer.commit` (manual), or from the last speech chunk sent (server voice detection) to the first `response.output_audio.delta`.
