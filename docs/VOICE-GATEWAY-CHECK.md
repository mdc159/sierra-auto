# Voice gateway check: what the gateway and ElevenLabs can really do

**Measured on:** 2026-11-04, 18:01 to 18:25 PST (UTC-8)
**Measured from:** the owner's Windows PC on the Tailscale network, not from the phone or the truck
**Gateway:** LiteLLM 1.102.1 at `http://studio54.tailfedd3b.ts.net:4000/v1` (version from its `x-litellm-version` response header)
**Network baseline:** 82 to 99 ms per round trip to the gateway on an open connection, 170 to 210 ms for the first request on a new one
**Probe scripts and raw results:** `tools/voice-probe/` (every number below is in `tools/voice-probe/results/*.json` unless marked otherwise)

Everything here is measured unless it is marked **inferred** or **not tested**. Sample sizes are small (one to ten calls per cell), so read the numbers as ranges, not averages. All times are milliseconds on the client's clock, from sending the request.

---

## 1. Short answers

| Question | Answer |
| --- | --- |
| Does speech-to-text work through the gateway? | Yes, all four models. 0.6 to 2.0 s for a 3.5 s or 14 s clip when called back to back, 0.9 to 2.3 s inside the full pipeline. No transcription errors on the kept run. |
| Does text-to-speech work through the gateway? | Yes for `openai/gpt-4o-mini-tts` and `openai/tts-1` in pcm, wav, mp3 and opus. Gemini works in pcm and wav only, takes 3 to 7 s and is limited to 3 requests a minute. |
| Does the gateway stream speech audio? | **No. It buffers the whole file**, then sends it in chunks. First audio byte: 0.7 to 0.9 s for one word, 1.0 to 1.7 s for one sentence (up to 2.3 s for a long one), 3.6 s for four sentences. |
| Fastest chat model with working tool calls? | `groq/openai/gpt-oss-120b`: first sentence complete in 0.55 to 0.7 s in 8 of 8 standalone runs. One stall of 9.7 s in 18 pipeline runs. |
| Does the realtime websocket work? | **Yes.** `session.created` arrives and audio comes back for OpenAI, Gemini Live and xAI models. End of speech to first audio: 1.1 to 1.3 s with OpenAI models, including the server's 0.5 s end-of-speech wait. |
| ElevenLabs directly? | Streams for real: first audio byte in 0.18 to 0.40 s (`eleven_flash_v2_5`). Speech-to-text (`scribe_v2`) in 0.36 to 0.84 s. |
| Best end-to-end delay? | Realtime through the gateway: about 1.1 to 1.3 s from end of speech. Turn-based with ElevenLabs for ears and voice: 1.2 to 1.9 s after the app decides the turn is over. Turn-based on the gateway alone: 2.7 to 5.2 s. |

## 2. Recommended defaults

### Lowest delay

1. **Realtime speech-to-speech through the gateway**, model `openai/gpt-realtime-2.1-mini`, websocket `ws://studio54.tailfedd3b.ts.net:4000/v1/realtime?model=openai/gpt-realtime-2.1-mini`, 24 kHz PCM both ways, server voice detection with a 500 ms silence window. Measured 1144 ms from end of speech to first audio (one run), 637 to 812 ms from a text prompt (two runs). Open the socket when the mic session starts: dialing plus `session.created` took 0.4 to 1.1 s, and 2.6 s on the first connection of the evening. Tool calling over realtime is **not tested**, so the Talk tools from lane B are not proven on this path.
2. **Turn-based, when the ElevenLabs key is on the phone** (keeps the existing chat and tools path):
   - speech-to-text: ElevenLabs `scribe_v2` (425 to 793 ms in the pipeline);
   - chat: gateway `groq/openai/gpt-oss-120b`, streaming, default reasoning effort, speak each sentence as it completes;
   - voice: ElevenLabs `eleven_flash_v2_5`, `output_format=pcm_24000`, streaming (179 to 341 ms to first byte in 15 pipeline runs).
   - Measured from "clip complete" to first audio byte: 1228 and 1354 ms (3.5 s question), 1897 ms (14 s question).
3. **Turn-based on the gateway alone** (no ElevenLabs): `openai/gpt-4o-mini-transcribe`, `groq/openai/gpt-oss-120b`, `openai/gpt-4o-mini-tts` with `response_format=pcm`, one request per sentence. Measured, across both gateway speech-to-text models, 2742 to 4262 ms on the short question and 4291 to 5170 ms on the long one, plus one 13.9 s run caused by a stalled chat stream.

### Best voice quality

Voice quality is a listening judgment and was **not measured**; this ranking is **inferred** from the vendors' own descriptions plus the latency measured here.

1. ElevenLabs `eleven_v4_turbo`: ElevenLabs calls it its most expressive real-time model. First byte 216, 269 and 320 ms, and it streams (19.4 s of audio delivered over 3.1 s). This is the pick for a conversation.
2. ElevenLabs `eleven_v4`: described as its highest quality model. First byte 494 ms (one run). Fine for longer read-outs.
3. Gateway only: `openai/gpt-4o-mini-tts` (accepts an `instructions` field for tone, not tested here), at 1.0 to 1.7 s per sentence. The realtime voices (`marin` on `openai/gpt-realtime-2.1`) are the other gateway option.

### What this means for lane G (voice)

- The "gateway `/v1/audio/speech` with streamed playback" speech output cannot start speaking before the whole file arrives. Sentence-level chunking is required, not optional, and each sentence still costs about a second. Request sentence N+1 while sentence N plays.
- Ask for `response_format=pcm` (raw 24 kHz, 16-bit, mono, little-endian; the rate is **inferred** from OpenAI's documentation and the byte counts) and write it straight to an `AudioTrack`. Sniff the first four bytes: Gemini models can return a WAV file (`RIFF`) when asked for pcm.
- The realtime route is real, so the experimental realtime client can be pointed at the gateway as it is.
- A sentence splitter must wait for white space after `.`, `!` or `?`. Splitting at the end of a stream chunk cut "3.2 miles" at "3." during these runs.
- Without tools attached, both `grok-4.20` and `gpt-oss-120b` invented fuel stations and transmission temperatures, and one reply contained Markdown (`**[Station Name]**`). Keep the tools attached and tell the model to answer in plain speech.

---

## 3. Speech-to-text through the gateway

Request that worked for all four models:

```
POST {base}/audio/transcriptions
Authorization: Bearer <gateway key>
Content-Type: multipart/form-data
  file            = clip.wav  (audio/wav, 16 kHz, mono, 16-bit)
  model           = groq/whisper-large-v3-turbo
  response_format = json
  language        = en
-> 200 {"text": "..."}
```

Clips: speech made by `openai/gpt-4o-mini-tts` (voice alloy), resampled to 16 kHz mono. Short: "Find the nearest gas station with diesel on my route." (3.55 s). Long: "I'm towing the camper up Highway 97 toward Bend tonight. What is the transmission temperature right now, and is there a rest area with truck parking in the next thirty miles? Also remind me to check the trailer brakes." (13.85 s). Noise is low-frequency-weighted, road-like noise at 15 dB and 3 dB signal-to-noise.

Total latency in ms. Clean clips were sent twice.

| Model | 3.55 s clean | 13.85 s clean | 3.55 s, 15 dB | 13.85 s, 15 dB | 13.85 s, 3 dB | Word errors |
| --- | --- | --- | --- | --- | --- | --- |
| `groq/whisper-large-v3-turbo` | 884, 588 | 1159, 987 | 748 | 1397 | 1291 | 0 |
| `groq/whisper-large-v3` | 634, 590 | 1953, 1311 | 1102 | 1581 | 1243 | 0 |
| `openai/gpt-4o-mini-transcribe` | 742, 740 | 943, 1057 | 841 | 908 | 866 | 0 |
| `openai/gpt-4o-transcribe` | 804, 750 | 1142, 1163 | 739 | 1123 | 1106 | 0 |

- The gateway's own figure for the upstream call (`x-litellm-response-duration-ms`) was 180 to 440 ms less than the client total. That difference is upload and network.
- An earlier pass with different takes of the same sentences (3.95 s and 13.35 s; console output only, not kept as a file) gave 0.70 to 1.21 s on the short clip and 0.88 to 2.12 s on the long one. Its single error: `groq/whisper-large-v3` heard "Ben" for "Bend" on the 15 dB long clip.
- Inside the end-to-end runs (section 8) the same calls were slower: `groq/whisper-large-v3-turbo` 880 to 1796 ms on the short clip (10 runs) and 1651 to 2344 ms on the long one (5 runs); `openai/gpt-4o-mini-transcribe` 967 to 1256 ms (4 runs) and 1618, 2095 ms. Those calls each opened a new connection. The rest of the gap is unexplained.
- Accuracy caveat: synthetic speech with synthetic noise is easy. These results show that the route works and that none of the models is fragile; they do not rank the models for a real voice in a real cab. **Not tested:** a human voice, real cabin noise, the car microphone.
- No clear speed winner. `openai/gpt-4o-mini-transcribe` was the steadiest on the long clip and is the suggested gateway default; `groq/whisper-large-v3-turbo` is a quarter of the price ($0.04 against $0.18 per hour of audio in the gateway's price table) and equally fast on short clips.

## 4. Text-to-speech through the gateway

Request that worked:

```
POST {base}/audio/speech
Authorization: Bearer <gateway key>
Content-Type: application/json
{"model": "openai/gpt-4o-mini-tts", "voice": "alloy", "input": "...", "response_format": "pcm"}
-> 200, Content-Type: audio/pcm, Transfer-Encoding: chunked, no Content-Length
```

Short sentence ("The nearest diesel is two miles ahead on the right.", 51 characters). First audio byte / last byte, ms:

| Model | pcm | wav | mp3 | opus |
| --- | --- | --- | --- | --- |
| `openai/gpt-4o-mini-tts` | 1049 / 1374 (repeats 1101, 1047) | 1329 / 1669 | 1573 / 1734 | 1017 / 1100 |
| `openai/tts-1` | 1000 / 1240 (repeats 1052, 4732) | 1605 / 1845 | 1331 / 1492 | 1465 / 1546 |
| `gemini/gemini-3.8-flash-lite-tts` (voice Kore) | 4200 / 4445, body is a WAV file | 3236 / 3773 | 400 | 400 |

The first bytes of each response were checked against the format asked for: pcm is headerless, wav starts `RIFF`, mp3 has an MPEG frame header, opus is in an Ogg container (`OggS`).

Other inputs, pcm, first byte / last byte:

| Model | One word ("Okay.") | Four sentences (308 characters, about 20 s of audio) |
| --- | --- | --- |
| `openai/gpt-4o-mini-tts` | 868 / 1027, 712 / 873 | 3626 / 4929 |
| `openai/tts-1` | 1522 / 1601, 796 / 876 | 1627 / 2615 |
| `gemini/gemini-3.8-flash-lite-tts` | not run | 6631 / 7109 |

Also measured once each on the short sentence: `openai/gpt-4o-mini-tts-2025-12-15` 1209 / 1529; `openai/tts-1-hd` 1831 / 2072; `gemini/gemini-3.8-flash-tts` 4446 / 4692 (WAV body); `gemini/gemini-2.5-flash-preview-tts` 5651 / 5892 (raw PCM body).

### The gateway buffers

Three observations, all pointing the same way:

1. The response headers do not arrive until the first audio byte does, and they carry `x-litellm-response-duration-ms`, the gateway's measure of the finished upstream call. In all 26 successful requests that figure was 100 to 175 ms less than the time to first byte (for example 3498 ms against 3626 ms). The gateway has the whole file before it sends anything.
2. Time to first byte grows with the length of the text: 0.7 to 0.9 s for one word, 1.0 to 1.1 s for one sentence, 3.6 s for four sentences (`gpt-4o-mini-tts`).
3. After the first byte the rest arrives at link speed: 1.0 MB, 20.85 s of audio, in 1.3 s.

`"stream_format": "sse"` in the request changed nothing: the reply was the same raw PCM, first byte at 3220 ms. The chunked transfer encoding makes it look like a stream; it is a buffered file sent in pieces. Whether a newer LiteLLM or a gateway setting can pass the upstream stream through was not investigated.

## 5. Chat through the gateway

Request that worked:

```
POST {base}/chat/completions
Authorization: Bearer <gateway key>
{"model": "groq/openai/gpt-oss-120b", "stream": true, "max_tokens": 400,
 "stream_options": {"include_usage": true},
 "messages": [{"role": "system", "content": "..."}, {"role": "user", "content": "..."}],
 "tools": [{"type": "function", "function": {"name": "find_places", "description": "...",
            "parameters": {"type": "object",
                           "properties": {"category": {"type": "string", "enum": ["fuel", "diesel", "..."]}},
                           "required": ["category"]}}}],
 "tool_choice": "auto"}
-> 200, server-sent events: data: {"choices":[{"delta":{"content":"..."}}]} ... data: [DONE]
```

Tool calls arrive as `choices[0].delta.tool_calls[i].function.name` and `.arguments` fragments to concatenate, ending with `finish_reason: "tool_calls"`. The gpt-oss models also stream their reasoning in a separate delta field before the answer.

Plain question: "I'm towing a camper uphill and my transmission temperature just hit 215 degrees. Should I be worried?", system prompt asking for two short spoken sentences. Tool request: "Find me a diesel station nearby." "First sentence" is when the first full sentence had arrived, which is when text-to-speech can start.

| Model | Runs | First token | First sentence | Whole reply | `find_places` call |
| --- | --- | --- | --- | --- | --- |
| `groq/openai/gpt-oss-120b` | 8 | 524 to 659 | 548 to 693 | 617 to 764 | well formed, 489 ms |
| `groq/openai/gpt-oss-20b` | 2 | 2239, 559 | 2245, 575 | 2318, 636 | well formed, 599 ms |
| `xai/grok-4.20-non-reasoning` | 10 | 476 to 609, and one 3771 | 523 to 750, and one 3771 | 636 to 820, and one 3782 | well formed, 5041 ms then 663 ms |
| `gemini/gemini-2.5-flash-lite` | 8 | 496 to 760, first call 1273 | 584 to 806, first call 2004 | 623 to 843, first call 2815 | well formed, 684 ms |
| `openai/gpt-5.4-nano` | 8 | 694 to 966 | 803 to 1172 | 1028 to 1409 | well formed, 1239 ms |
| `openai/gpt-6-luna` | 2 | 3514, 2012 | 3674, 2154 | 3859, 2406 | well formed, 1490 ms |
| `gemini/gemini-3.5-flash-lite` | 2 | 3536, 2465 | 3591, 2580 | 3670, 2676 | well formed, 2211 ms |
| `gemini/gemini-3.1-flash-lite` | 2 | failed (503) | | | well formed, but after 119 s |
| `gemini/gemini-3.8-flash` | 6 | failed (503 five times, 429 once) | | | failed |
| `openai/gpt-4.1-nano` | 3 | failed (404, deprecated) | | | failed |

Every model that answered the tool request returned `find_places` with valid JSON arguments `{"category": "diesel"}`.

- `groq/openai/gpt-oss-120b` was the steadiest. In the pipeline runs its time from request to first sentence was 415 to 1198 ms in 17 of 18 runs; the other run stalled mid-sentence for about 9 s (first token on time, first sentence 8.9 s later).
- `xai/grok-4.20-non-reasoning` is as fast when it is fast, but 7 of its 21 calls across all runs took 2.5 to 8.2 s to produce a first token. The gateway's own overhead in those calls was under 40 ms, so the wait was upstream.
- `"reasoning_effort": "low"` (results in `04_chat_effort_low.json`, taken while the network was unsettled): `gpt-oss-120b` 438 and 598 ms to first token but an **empty reply with no tool call** on the tool request; `gpt-6-luna` 1455 and 1403 ms; `gpt-5.4-nano` 1051 and 778 ms. Leave reasoning effort at its default for `gpt-oss-120b`.
- Gemini through this gateway was unreliable tonight: two of the four chat models tried never returned a normal answer.
- The gateway reported 2 to 40 ms of its own overhead per call (`x-litellm-overhead-duration-ms`; 48 of 49 calls, the other reported a negative value).

## 6. Realtime through the gateway

The gateway accepts a websocket with the bearer key and no other header:

```
ws://studio54.tailfedd3b.ts.net:4000/v1/realtime?model=openai/gpt-realtime-2.1
Authorization: Bearer <gateway key>
```

The first server event is `session.created`. The session is in OpenAI's current (non-beta) event format: `session.type = "realtime"`, `output_modalities = ["audio"]`, input and output `audio/pcm` at 24000 Hz, voice `marin`.

Client events that worked:

```
{"type": "session.update", "session": {"type": "realtime", "instructions": "...",
   "audio": {"input": {"format": {"type": "audio/pcm", "rate": 24000},
                       "turn_detection": {"type": "server_vad", "silence_duration_ms": 500, "create_response": true}}}}}
{"type": "conversation.item.create", "item": {"type": "message", "role": "user",
   "content": [{"type": "input_text", "text": "..."}]}}
{"type": "input_audio_buffer.append", "audio": "<base64 16-bit PCM>"}
{"type": "input_audio_buffer.commit"}        (only with "turn_detection": null)
{"type": "response.create"}
```

Server events to handle: `response.output_audio.delta` (base64 PCM), `response.output_audio_transcript.delta`, `response.output_audio.done`, `response.done`, and with voice detection `input_audio_buffer.speech_started`, `speech_stopped` and `committed`.

Text prompt in, speech out, two turns per session. "Session ready" is from dialing to `session.created`; first audio is from `response.create`.

| Model | Session ready | First audio, turn 1 / turn 2 | `response.done`, turn 1 / turn 2 |
| --- | --- | --- | --- |
| `openai/gpt-realtime-2.1` | 2563 (first connection of the run) | 968 / 818 | 1497 / 2005 |
| `openai/gpt-realtime-2.1-mini` | 1130 | 812 / 637 | 1023 / 1200 |
| `gemini/gemini-3.8-live` | 639 | 640 / 823 | 2803 / 6508 |
| `gemini/gemini-3.1-flash-live-preview` | 543 | 685 / 637 | 1702 / 5967 |
| `xai/grok-voice-3.0` | 440 | 779 / 674 | 939 / 1534 |

Speech in, speech out, using the 3.55 s clip. "Manual": voice detection off, clip appended in full, clock starts at `input_audio_buffer.commit`. "Server detection": clip sent at real-time pace then silence, clock starts when the last speech chunk is sent, so the 500 ms silence window is inside the figure. One run each.

| Model | Manual commit to first audio | End of speech to first audio, server detection | Understood the clip |
| --- | --- | --- | --- |
| `openai/gpt-realtime-2.1` | 718 | 1310 | yes |
| `openai/gpt-realtime-2.1-mini` | 1088 | 1144 | yes |
| `xai/grok-voice-3.0` | 951 | 1522 | yes |
| `gemini/gemini-3.8-live` | 1598 | 1870 | yes |

"Understood" means the spoken reply was about finding diesel on the route.

Gemini Live is reachable the same way: same route, `model=gemini/gemini-3.8-live` or `gemini/gemini-3.1-flash-live-preview`, and the gateway translates Gemini's protocol into the same OpenAI event names. Differences seen:

- Input was sent as 16 kHz PCM (declared in `session.update`) and was understood. No `session.updated` came back.
- `response.done` arrives 0.8 to 11 s after `response.output_audio.done`. Go back to listening on `response.output_audio.done`.
- The output sample rate was not reported in `session.created`; 24 kHz is **inferred** from the byte counts.
- `xai/grok-voice-3.0` kept its own voice detection on even when `turn_detection` was set to null.

**Not tested** on the realtime route: tool calls, interrupting a reply (barge-in), ephemeral client tokens, WebRTC, sessions longer than a few seconds, `openai/gpt-live-1` (LiteLLM's documentation says that family uses a different route, `/v1/live/sessions`).

## 7. ElevenLabs directly

Model ids come from ElevenLabs' models page (read 2026-11-04) and were confirmed against `GET /v1/models` on the owner's account: `eleven_flash_v2_5` and `eleven_v4_turbo` are the two listed at about 100 ms, `eleven_v4` is the highest quality, `scribe_v2` is the current speech-to-text model (`scribe_v1` and the `eleven_turbo_*` models are deprecated). Voice used: the first premade voice on the account, "Bella", `hpp4J3VqNfWAUOO0d1Us`.

```
POST https://api.elevenlabs.io/v1/text-to-speech/{voice_id}/stream?output_format=pcm_24000
xi-api-key: <ElevenLabs key>
{"text": "...", "model_id": "eleven_flash_v2_5"}
-> 200, raw 24 kHz 16-bit mono PCM, streamed

POST https://api.elevenlabs.io/v1/speech-to-text
xi-api-key: <ElevenLabs key>
multipart: model_id = scribe_v2, file = clip.wav, language_code = en, tag_audio_events = false
-> 200 {"text": "..."}
```

Text-to-speech, first byte / last byte, ms:

| Model | Short sentence | Four sentences (about 19.5 s of audio) |
| --- | --- | --- |
| `eleven_flash_v2_5` | 400 / 471, 192 / 247 | 191 / 577 |
| `eleven_v4_turbo` | 216 / 657, 269 / 704 | 320 / 3069 |
| `eleven_v4` | 494 / 842 | not run |

Across the pipeline runs `eleven_flash_v2_5` gave a first byte in 179 to 341 ms (15 more calls). Time to first byte does not grow with the length of the text, so this is real streaming.

Speech-to-text (`scribe_v2`), same clips as section 3, no word errors:

| Clip | Total ms |
| --- | --- |
| 3.55 s clean | 376, 355 (pipeline: 538, 425, 527, 502) |
| 13.85 s clean | 837 (pipeline: 793, 669) |
| 13.85 s, 15 dB | 668 |
| 13.85 s, 3 dB | 434 |

**Not tested:** `scribe_v2_realtime` (streaming transcription over websocket), ElevenLabs' websocket text input, ElevenLabs Agents.

## 8. End to end

### Turn-based

The clock starts when the recorded clip is complete and stops at the first audio byte of the reply: upload, transcript, streaming chat, first full sentence, text-to-speech request for that sentence, first byte. Connections to ElevenLabs were already open; each gateway transcription opened a new one. **The time the app waits in silence before deciding the driver has finished is not included.**

| Combination | 3.55 s question | 13.85 s question |
| --- | --- | --- |
| ElevenLabs `scribe_v2`, `groq/openai/gpt-oss-120b`, ElevenLabs `eleven_flash_v2_5` | 1354, 1228 | 1897 |
| `groq/whisper-large-v3-turbo`, `gpt-oss-120b`, ElevenLabs `eleven_flash_v2_5` | 1830, 1628 | 2609 |
| `openai/gpt-4o-mini-transcribe`, `gpt-oss-120b`, ElevenLabs `eleven_flash_v2_5` | 1725, 1897 | 2707 |
| `groq/whisper-large-v3-turbo`, `gpt-oss-120b`, `openai/gpt-4o-mini-tts` (all gateway) | 4262, 3571, 3565, 3667 | 4291, 5170 |
| `openai/gpt-4o-mini-transcribe`, `gpt-oss-120b`, `openai/gpt-4o-mini-tts` (all gateway) | 3337, 2742 | 13887 (chat stream stalled 9 s) |

Where the time goes in a typical short-question run, ms:

| Combination | Transcribe | Chat to first sentence | Speech to first byte |
| --- | --- | --- | --- |
| ElevenLabs ears and voice, gateway chat | 502 | 545 | 181 |
| Gateway ears and chat, ElevenLabs voice | 880 | 507 | 241 |
| All gateway | 1542 | 659 | 1370 |

The first runs used `xai/grok-4.20-non-reasoning` for chat (`07_pipeline.json`). With it the same three combinations took 1318 to 7876 ms on the short question and 3562 to 10456 ms on the long one, because of the slow first tokens described in section 5.

### Estimate from end of speech to first spoken audio

| Stack | Measured part | Added by assumption | Estimate |
| --- | --- | --- | --- |
| Realtime, `openai/gpt-realtime-2.1-mini`, server voice detection | 1144 ms, end of speech to first audio delta, silence window included | none | **about 1.1 to 1.3 s** from this PC |
| Turn-based, ElevenLabs ears and voice, `gpt-oss-120b` | 1228 to 1354 ms after the turn is declared over | 500 to 800 ms of silence before the app declares it (**inferred**; it is the lane G threshold, not a measurement) | **about 1.7 to 2.2 s** |
| Turn-based, gateway ears and chat, ElevenLabs voice | 1628 to 1897 ms | same | about 2.1 to 2.7 s |
| Turn-based, all gateway | 2742 to 4262 ms | same | about 3.2 to 5.1 s |

From the truck every figure will be higher: the phone reaches the gateway over a mobile connection through Tailscale, and each stage is at least one round trip. The size of that addition is **not measured**; a guess is 100 to 300 ms per stage on LTE (**inferred**). Playback start (audio focus, `AudioTrack` buffer) is also outside these numbers.

## 9. What failed, with the exact errors

| What | Result |
| --- | --- |
| `openai/gpt-4.1-nano`, chat | 404: "The model `gpt-4.1-nano` has been deprecated, learn more here: https://platform.openai.com/docs/deprecations." It is still listed by `/v1/models`. |
| `gemini/gemini-3.8-flash`, chat | 503 five times: "This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later." (status UNAVAILABLE), after 5 to 9 s each. Then 429 once (quota). |
| `gemini/gemini-3.1-flash-lite`, chat | 503 twice with the same message, after 8 s and 35 s. One tool request succeeded after 119 s. |
| Gemini text-to-speech, more than 3 calls a minute | 429: "You exceeded your current quota ... Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 3, model: gemini-3.8-flash-lite-tts". The Gemini key behind the gateway is on the free tier for this model. |
| Gemini text-to-speech, mp3 or opus | 400: "Gemini TTS only produces raw PCM16 audio, so response_format='mp3' is not supported. Supported response formats: pcm, wav." |
| Gemini text-to-speech, `response_format=pcm` | 200 with `Content-Type: audio/pcm`, but the body is a WAV file for the 3.8 models (raw PCM for `gemini-2.5-flash-preview-tts`). |
| `groq/canopylabs/orpheus-v1-english`, text-to-speech | 400: "The model `canopylabs/orpheus-v1-english` requires terms acceptance. Please have the org admin accept the terms at https://console.groq.com/playground?model=canopylabs%2Forpheus-v1-english." |
| `"stream_format": "sse"` on `/v1/audio/speech` | Accepted and ignored: same buffered raw PCM. |
| `groq/openai/gpt-oss-120b` with `"reasoning_effort": "low"` and tools | 200, but an empty reply and no tool call (one of one). |
| `openai/tts-1`, one short request | 4732 ms to first byte against about 1000 ms otherwise. |
| `groq/openai/gpt-oss-120b`, one pipeline run | Stream stalled about 9 s mid-sentence. |

## 10. Coverage and limits

Covered: the four speech-to-text models, three text-to-speech models and four formats named in the brief, plus `tts-1-hd`, one dated `gpt-4o-mini-tts` snapshot, two more Gemini speech models and Groq's Orpheus; ten chat models across Groq, Gemini, OpenAI and xAI; five realtime models across OpenAI, Gemini and xAI; ElevenLabs `eleven_flash_v2_5`, `eleven_v4_turbo`, `eleven_v4` and `scribe_v2`.

Not covered: anything from the phone or the truck; a human voice or real cabin noise; realtime tool calls and barge-in; streaming speech-to-text; long conversations; behavior under the gateway's weekly budget limit; time of day other than one evening; voice quality by ear.

Cost of this check: about $0.12 on the gateway (this machine's key spend went from $0.833 to $0.952 during the session), and on ElevenLabs about 2,000 characters of speech (22 requests) and 90 seconds of transcription (11 requests).
