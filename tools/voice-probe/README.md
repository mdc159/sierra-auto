# voice-probe

Small Python probes that measure what the owner's LiteLLM gateway and ElevenLabs can do for the voice feature. The findings are written up in `docs/VOICE-GATEWAY-CHECK.md`; the raw numbers are in `results/`.

The gateway is reachable only on the owner's Tailscale network, so these run on the owner's PC, not in a cloud session or CI.

## Keys

The scripts read keys from the environment and nowhere else:

| Variable | Used for |
| --- | --- |
| `OPENAI_BASE_URL` | gateway base URL ending in `/v1` |
| `OPENAI_API_KEY` | gateway key |
| `ELEVENLABS_API_KEY` | `p06` and `p07` only |

Nothing prints or saves a key. Text that goes to the console or to `results/` passes through `scrub()` in `probe_common.py`, which removes the value of any environment variable whose name contains KEY, TOKEN, SECRET or PASSWORD.

## Running

```
uv sync                      # creates .venv here; never installs into system Python
./run.sh p00_models.py       # Git Bash on the owner's PC: runs under the studio54 launcher
```

`run.sh` wraps `~/.config/studio54/run.cmd 9530 -- .venv\Scripts\python.exe <script>`, which injects the three variables. Anywhere else, export them yourself and run `.venv/bin/python <script>`.

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

A full pass cost about $0.12 on the gateway and about 2,000 ElevenLabs characters on 2026-11-04. The gateway budget is shared with other agents, so do not loop these.

## Results

`results/*.json` are the runs behind the document, each stamped with `recorded_at`. Files with a suffix are follow-up runs: `04_chat_alt` (other models), `04_chat_effort_low`, `04_chat_repeat` (six runs each), `05_realtime_audio`, `07_pipeline` (chat on `xai/grok-4.20-non-reasoning`), `07_pipeline_oss120b`, `07_pipeline_oss120b_minitranscribe`, `07_pipeline_allgateway_rerun`.

Two things to know when reading them:

- `p01` was run more than once and text-to-speech is not deterministic, so the clips behind `02_stt.json` onward are the last takes (3.55 s and 13.85 s). The clips themselves are not committed (`audio/` is ignored; the long one is over 400 KB).
- The sentence splitter in `p04` and `p07` was tightened after most runs were recorded. It used to close a sentence when a stream chunk happened to end on `.`, `!` or `?`; it now waits for white space after the terminator. The recorded "first sentence" times are at most one token earlier than the current scripts would report. Only `07_pipeline_allgateway_rerun.json` was recorded with the current rule.

## Timing definitions

- Total latency: from sending the request to the last byte of the response.
- First byte: from sending the request to the first body bytes read off the socket (`iter_raw`).
- First token: the first streamed chunk with non-empty `delta.content`.
- First sentence: the first moment the streamed text holds a complete sentence.
- Realtime first audio: from `response.create` (text turns), from `input_audio_buffer.commit` (manual), or from the last speech chunk sent (server voice detection) to the first `response.output_audio.delta`.
