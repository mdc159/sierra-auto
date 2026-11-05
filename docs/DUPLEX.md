# Full-duplex voice (lane J)

**Written:** 2026-11-04. **Code:** `app/src/main/java/com/mdc159/sierra/voice/duplex/`, `DuplexSettingsActivity`. **Tests:** `app/src/test/java/com/mdc159/sierra/voice/duplex/`.

Full duplex means both sides can talk at once: the driver can cut Sierra off mid-sentence by speaking, and Sierra starts answering as soon as the service decides the driver has finished. The turn-based conversation (listen, think, speak, listen again) is lane G's; this is the realtime path.

Every claim below that could not be checked against the vendor's own documentation tonight is marked **unverified**. The vendor documentation sites (`platform.openai.com`, `developers.openai.com`, `elevenlabs.io`) are not reachable from the cloud session that wrote this, so protocol details were taken from the vendors' own SDK sources on GitHub (the OpenAI Node SDK realtime types, the ElevenLabs JS and Python SDKs), which define the wire formats. Pricing and latency numbers come from third-party pages found by search and are all **unverified**.

---

## 1. Architecture

```
                 ┌──────────────── DuplexController ────────────────┐
 MicSource ──▶ EchoGate ──▶ Resampler(mic→svc) ──▶ DuplexSession.sendAudio
 (car / phone)  (guarded mode)                            │ websocket
                                                          ▼
 PcmSink ◀── JitterBuffer ◀── Resampler(svc→spk) ◀── DuplexEvent.AssistantAudio
 (AudioTrack)     │                                       │
                  └── playedMs ──▶ session.interrupt() ◀── UserStartedSpeaking / tap
                                                          │
                                   ToolRegistry ◀── DuplexEvent.ToolCall ──▶ result sent back
                                       │
                                   AskAgentTool ──▶ AgentBridge ──▶ owner's agent (chat/completions)
```

| Piece | File | Role |
| --- | --- | --- |
| `DuplexSession` | `DuplexSession.kt` | The interface both services implement: `start(listener)`, `sendAudio`, `interrupt(playedMs)`, `sendText`, `close`; events for audio, transcripts (both sides, partial and final), tool calls, user-started-speaking, errors, end. |
| `WebSocketSessionBase` | `WebSocketSessionBase.kt` | OkHttp websocket, handshake timeout, JSON dispatch, tool execution off the socket thread, reconnect with backoff (3 tries by default), one `Ended`. |
| `OpenAiRealtimeSession` | `OpenAiRealtimeSession.kt` | OpenAI Realtime protocol. 24 kHz PCM both ways. |
| `ElevenLabsAgentSession` | `ElevenLabsAgentSession.kt` | ElevenLabs Agents protocol. Rates negotiated from the metadata event (16 kHz default). |
| `Resampler` | `Resampler.kt` | Linear interpolation, integer position arithmetic so chunking never changes the output. 16 kHz ↔ 24 kHz. |
| `JitterBuffer` | `JitterBuffer.kt` | Prefill (80 ms default) before the first byte plays, then runs dry; counts played milliseconds for truncation. |
| `EchoGate` | `EchoGate.kt` | Guarded mode. See section 5. |
| `DuplexController` | `DuplexController.kt` | Mic thread, speaker thread, focus, interruption plumbing. Fully testable with fakes. |
| `MicSource`, `PcmSink`, `FocusHandle` | `AudioIo.kt` | Interfaces. Android implementations in `AndroidAudio.kt`: `CarMicSource` (CarAudioRecord), `PhoneMic` (VOICE_COMMUNICATION + AEC + NS), `AudioTrackSink`, `AndroidFocus`. |
| `DuplexTool`, `ToolRegistry`, `AskAgentTool`, `AgentBridge` | `DuplexTool.kt` | Tool interface (name, JSON Schema, blocking `invoke`). `ask_agent(question)` forwards to the owner's agent through `AgentBridge`; `ConfiguredAgentBridge` (`Duplex.kt`) uses the agent from Sierra's main settings. |
| `DuplexConfig`, `DuplexConfigStore` | `DuplexConfig.kt`, `DuplexConfigStore.kt` | Settings, encrypted the same way as `ConfigStore`, in a separate file (`sierra_duplex_config`). |
| `DuplexSettingsActivity` | `DuplexSettingsActivity.kt` | Phone screen: provider, model or agent, voice, mode, keys, instructions, and **Try it on this phone**. |

Threads: OkHttp delivers socket messages on its reader thread; sessions decode and emit events there, so listeners must be quick (the controller just enqueues audio). Tools run on a single `duplex-tools` thread so a slow agent never blocks the socket. The controller owns a `duplex-mic` thread (blocking reads) and a `duplex-play` thread (blocking AudioTrack writes). Reconnects run on a `duplex-reconnect` thread.

### Interruption, in order

1. The service hears the driver. OpenAI sends `input_audio_buffer.speech_started` and, with `interrupt_response: true`, cancels its own response. ElevenLabs sends `interruption` with the last `event_id` that still counts.
2. The session emits `UserStartedSpeaking`. The controller clears the jitter buffer, flushes the AudioTrack, resets the output resampler and reads `playedMs` from the buffer.
3. The controller calls `session.interrupt(playedMs)`. OpenAI: `conversation.item.truncate` with `audio_end_ms = min(playedMs, received)` so the model's transcript matches what was actually heard (`response.cancel` is only sent when the server did not already cancel). ElevenLabs: audio events at or below the interrupt id are dropped; there is no client message to send.
4. A tap does the same from the other end: `tapInterrupt()` cuts playback, then OpenAI gets `response.cancel` + truncate; ElevenLabs gets a local mute until the agent's next turn, because the protocol has no client-side "stop talking" message (the agent's own VAD is the only interrupter). **Unverified** whether ElevenLabs's `user_activity` message has any effect on an audio agent; it is documented for text mode.

`playedMs` counts bytes handed to the AudioTrack, so it runs ahead of the speaker by the track's buffer (about 100 ms). The truncate point is therefore slightly late; harmless for a conversation.

### Long microphone sessions and Android Auto

From `docs/android-auto-constraints.md` section 7 (Google's car-microphone page and the `CarAudioRecord` reference):

- Take **exclusive transient audio focus** before recording and give it back after (`AndroidFocus`, `USAGE_ASSISTANT` + `CONTENT_TYPE_SPEECH`). Media pauses for the whole conversation. Focus loss (a phone call) ends the session.
- `CarAudioRecord` is 16 kHz, 16-bit mono (`audio/l16`). `read()` returning a negative value means the driver dismissed the mic on the car screen; the controller ends the session on that.
- The host shows a recording indicator while the car mic is open ([voice] in the constraints doc). There is **no documented maximum duration** for a `CarAudioRecord` session; the search found nothing either way. A conversation of several minutes is therefore **unverified** and is item 3 of the truck test plan.
- Car App API level 5 or higher is needed for the car mic; the phone path (`PhoneMic`) has no such requirement.
- Playback goes through the phone's `AudioTrack` with the same attributes, which Android Auto routes to the car speakers like any phone audio (**unverified** as official Car App Library guidance, same note as the TTS path).
- DD-1: never use the navigation audio channel. We use `USAGE_ASSISTANT`.
- A Car App screen that starts a session must still obey the template step quota: refresh one `MessageTemplate`/`PaneTemplate` for status, never push a template per turn.

---

## 2. The two services compared

| | OpenAI Realtime (websocket) | ElevenLabs Agents (websocket) |
| --- | --- | --- |
| What runs where | One model does hearing, thinking and speaking (speech-to-speech). Our code only moves audio and runs tools. | ElevenLabs runs STT, turn-taking, an LLM of your choice (or your own) and its TTS. Our code moves audio and runs client tools. |
| Endpoint | `wss://<base>/realtime?model=<model>`; `Authorization: Bearer <key>`. Base is `https://api.openai.com/v1` or the owner's gateway. The GA API needs no `OpenAI-Beta` header (**unverified**: no official page could be read; the Node SDK sends none). | `wss://api.elevenlabs.io/v1/convai/conversation?agent_id=<id>` for a public agent; for a private agent, `GET /v1/convai/conversation/get-signed-url?agent_id=<id>` with header `xi-api-key`, then connect to the returned `signed_url`. The conversation token (`/v1/convai/conversation/token`) is for WebRTC only. |
| Audio in / out | 16-bit PCM mono **24 kHz** both ways ("only a 24kHz sample rate is supported" for `audio/pcm` in the SDK types), base64 in JSON. We resample from the 16 kHz car mic. | PCM at the rate set in the agent (`pcm_16000` default, up to `pcm_48000`; `ulaw_8000` also exists and is refused by our code). Base64 in JSON. |
| Turn detection | Server VAD (`threshold`, `prefix_padding_ms`, `silence_duration_ms`) or semantic VAD. We use server VAD, 500 ms silence. | Built in; tuned in the dashboard. |
| Interruption | `speech_started` + automatic cancel; client truncates with `conversation.item.truncate`. | `interruption` event with an `event_id` watermark. |
| Tools | `tools` in `session.update`; `response.function_call_arguments.done` → `conversation.item.create {function_call_output}` → `response.create`. | "Client tools" defined in the dashboard; `client_tool_call` → `client_tool_result {tool_call_id, result, is_error}`. |
| Transcripts | Input transcription is a separate model (`gpt-4o-mini-transcribe` by default here) and arrives as deltas plus a final. Output transcript deltas come with the audio. | `user_transcript` (final only), `internal_tentative_agent_response` (partial), `agent_response` (final), `agent_response_correction` after an interruption. |
| Keep-alive | None required by the protocol; OkHttp pings every 20 s. | Server `ping` must be answered with `pong` (done). |
| Delay (speech end → first audio) | Third-party measurements cluster around **0.5 to 1 s** for `gpt-realtime` class models over a websocket; OpenAI itself recommends WebRTC for browsers and mobile because the websocket path adds client-side buffering. **Unverified.** | ElevenLabs quotes ~75 ms TTS model latency for Flash v2.5; independent measurements put TTS time-to-first-byte at **~250 ms median**, and the full turn (STT + LLM + TTS) at roughly **0.6 to 1.2 s** depending on the LLM. **Unverified.** |
| Voice quality | Native speech-to-speech; voices like `marin` and `cedar` (the SDK's built-in list). Expressive, but fewer voices and no cloning. | Best-in-class TTS, thousands of voices, cloning. The owner can pick the exact voice in the dashboard or override `voice_id` from the phone. |
| Cost per minute | Token priced. Search results (**unverified**, all third-party, 2026): `gpt-realtime` audio **$32 / 1M input tokens, $64 / 1M output tokens**, cached input $0.40; `gpt-realtime-mini` **$10 / $20**. At roughly 600 input tokens and 1200 output tokens per spoken minute, a minute where both sides talk half the time is about **$0.06 (full) or $0.02 (mini)**, before the growing context is re-billed each turn. Vendors quoting real sessions see **$0.15 to $0.45 per minute** for the full model once context accumulates. Sources: [forasoft], [lavx], [cekura], [layer3]. | Platform fee of about **$0.08 per conversation minute** on every self-serve plan (reported as $0.10 before a May 2026 cut), plus LLM tokens passed through at cost (fractions of a cent per minute for small models), plus plan minutes (for example 275 minutes on Creator, 12,375 on Business). **Unverified.** Sources: [macha], [cloudzero], [superdupr]. |
| What must be publicly reachable | Nothing of ours. The phone connects out to OpenAI or to the owner's gateway. If the gateway is on the tailnet, the phone must be on the tailnet. | Nothing of ours, **unless** the agent uses a custom LLM: then ElevenLabs's servers must reach an OpenAI-compatible chat URL on the public internet (not a tailnet name). The owner's agent is reached instead through the `ask_agent` client tool, which runs on the phone, so no public URL is needed. |
| Gateway (LiteLLM) | LiteLLM documents a `/v1/realtime` websocket route that forwards to OpenAI with `?model=`. Whether the owner's gateway has it enabled and forwards `session.updated` is **unverified** (lane brief). `OpenAiRealtimeSession` tolerates a gateway that forwards audio but swallows `session.updated` (it continues after the handshake timeout with a warning). | Not involved. ElevenLabs is called directly with the owner's ElevenLabs key. |
| Account setup | An API key. | See section 3. |

**Recommendation for tonight:** test OpenAI Realtime first against OpenAI directly (fewest unknowns), then against the gateway. Test ElevenLabs once the agent exists in the dashboard. Keep both; the owner's direction allows a combination, and the `DuplexProvider` switch costs nothing.

### Where the "Gateway voice check results" fit

The lane brief says a worker is measuring the owner's gateway tonight and will post an issue or PR comment titled "Gateway voice check results". At the time of writing (2026-11-05 02:30 UTC) no such issue or comment exists on `mdc159/sierra-auto`. When it appears, the numbers that matter here are: whether `/v1/realtime` upgrades at all through LiteLLM; whether `session.updated` comes back; and the measured round-trip for a short utterance. Until then the gateway realtime route is **unverified** and the default base URL in `DuplexConfig` is OpenAI itself.

---

## 3. ElevenLabs: what has to exist in the account first

All of this is done once in the ElevenLabs dashboard (Agents). Field names below follow the generated API types; dashboard labels may differ slightly (**unverified** wording).

1. **Create an agent.** Give it the Sierra system prompt (or leave it and let the phone override it, step 5), pick the LLM, pick the voice.
2. **Audio formats.** Set the agent's *output* audio format to a PCM rate (`pcm_16000` or `pcm_24000`; the metadata event reports it and the app negotiates). Set the *user input* audio format to `pcm_16000`, which is what the car mic and `PhoneMic` produce. µ-law is refused by the app.
3. **Client tool `ask_agent`.** Add a client tool named exactly `ask_agent` with one required string parameter `question` ("The question for the owner's agent, in plain English") and the description from `AskAgentTool`. Turn on *wait for response* (the agent must wait for `client_tool_result` before answering). Without this tool the agent cannot reach the owner's agent.
4. **Authentication.** Either leave the agent public (connect by `agent_id`, no key on the phone) or require authentication, in which case the phone needs the ElevenLabs API key and fetches a signed URL per session. Signed URLs are short-lived (**unverified**: about 15 minutes), which is fine because the app fetches a fresh one on every connect and reconnect.
5. **Overrides.** The phone sends `conversation_config_override` with the system prompt and, if set, `tts.voice_id`. ElevenLabs only applies overrides that are enabled per field in the agent's security settings; otherwise they are ignored or the connection is refused (**unverified** which). Enable "prompt" and, if you want the phone to pick the voice, "voice id".
6. **Billing.** Agents minutes come from the plan's quota; LLM usage is extra. Check the plan has conversation minutes before the truck test.

What the phone sends on connect (from `ElevenLabsAgentSession.buildInitiation`):

```json
{"type":"conversation_initiation_client_data",
 "conversation_config_override":{"agent":{"prompt":{"prompt":"…"}},"tts":{"voice_id":"…"}},
 "source_info":{"source":"sierra_auto","version":"0.1"}}
```

---

## 4. OpenAI Realtime: the session we configure

Sent as `session.update` right after the socket opens (from `OpenAiRealtimeSession.buildSessionUpdate`):

```json
{"type":"session.update","session":{
  "type":"realtime",
  "output_modalities":["audio"],
  "instructions":"…",
  "audio":{
    "input":{"format":{"type":"audio/pcm","rate":24000},
             "turn_detection":{"type":"server_vad","threshold":0.5,"prefix_padding_ms":300,
                               "silence_duration_ms":500,"create_response":true,"interrupt_response":true},
             "transcription":{"model":"gpt-4o-mini-transcribe"}},
    "output":{"format":{"type":"audio/pcm","rate":24000},"voice":"marin"}},
  "tools":[{"type":"function","name":"ask_agent","description":"…",
            "parameters":{"type":"object","properties":{"question":{"type":"string"}},"required":["question"]}}],
  "tool_choice":"auto"}}
```

Mic frames: `{"type":"input_audio_buffer.append","audio":"<base64 pcm16 24k>"}`. Assistant audio: `response.output_audio.delta` (`response.audio.delta` from a beta-pinned gateway is also accepted). Tool results: `conversation.item.create` with `{"type":"function_call_output","call_id":…,"output":…}` then `response.create`.

Defaults the owner may want to change on the phone: model (`gpt-realtime`; the gateway lists `openai/gpt-realtime-2.1`, so enter that name when using the gateway), voice, transcription model (blank turns input transcription off, which saves money and removes the "You:" lines).

---

## 5. The echo problem, and the guarded mode

In the truck the assistant's voice comes out of the door speakers and straight back into the cabin microphone. A service with server-side VAD hears that as the driver speaking and interrupts itself, forever. Three defences, selectable on the phone:

1. **Full duplex** (`DuplexMode.FULL_DUPLEX`). Everything the mic hears is sent. Works with a headset, a quiet cab at low volume, or if the head unit's microphone path has its own echo canceller (**unverified** for the 2018 Sierra; many car hands-free paths do cancel echo for phone calls, but that path may not apply to the Android Auto `CarAudioRecord` stream).
2. **Guarded** (`DuplexMode.GUARDED`, the default). `EchoGate` tracks the RMS of what is being played. While audio is playing, and for 250 ms after (speaker tail), a mic frame is replaced by silence unless its RMS is at least `guardRatio` × the playback RMS **and** above an absolute floor (400). Silence, not nothing, is sent so the service's audio timeline stays continuous. The driver can still barge in by speaking clearly louder than the playback; otherwise a **tap** on the Interrupt button (phone) or the car-screen action (phase 2) cuts the reply.
3. **Phone path** (`PhoneMic`). On the phone the VOICE_COMMUNICATION source engages Android's acoustic echo canceller, so the phone test can run full duplex on the loudspeaker. This is how the owner tests tonight.

The guard ratio is a setting (`guardRatio`, default 2.0) because the right value depends on the cab, the volume and the microphone placement. Tuning procedure is below.

Known weakness of the gate: it compares the mic against the level of the audio **as sent to the speaker**, not as it arrives at the microphone. Acoustic gain (volume knob) is unknown to the app, so the ratio is relative. If the head unit volume is high, echo RMS may exceed `guardRatio` × playback RMS and leak through. The fix is a lower volume or a higher ratio; a calibration step that measures the echo level during a known test tone would be the next improvement.

---

## 6. Step-by-step truck test plan (echo problem)

Prerequisites: the duplex settings saved on the phone, an OpenAI key (or the gateway) with access to a realtime model, phone on the truck's USB, Android Auto showing Sierra. For tests 1 and 2 the phone screen is used, parked. Nothing here is to be done while driving.

**Test 0: phone only, no truck (tonight).** Phone settings → Full-duplex voice → provider OpenAI → *Try it on this phone*. Hold the phone like a call. Say "What's the oil change interval on a 2018 Sierra?" Expect: "You:" line appears (transcription), Sierra answers in speech, "Sierra:" line appears. Say something while Sierra is talking: it should stop within about half a second. Then switch to the phone's loudspeaker at full volume, mode Full duplex: if Sierra interrupts itself (status shows "Heard you, stopping…" while nobody spoke), the echo canceller is not coping; switch to Guarded and repeat. Then ask "Ask my agent what's on my calendar today" and expect a `→ ask_agent(...)` line, the agent's answer relayed by voice. Record: time from end of your sentence to first audio (count or use a stopwatch), number of false interruptions per minute of Sierra speaking.

**Test 1: truck, parked, engine off, phone screen, guarded mode.** Repeat test 0 from the phone but with audio routed through the truck (the phone's audio follows Android Auto when connected). The microphone is still the phone's at this stage. Note false interruptions at head-unit volume 10, 15, 20 (or the unit's equivalent). Expected: guarded mode holds at normal volume; full duplex self-interrupts at higher volume.

**Test 2: truck, parked, car microphone (phase 2 wiring, or a debug build with `CarMicSource`).** Start the conversation from the car screen. Watch for the host's recording indicator. Say a sentence, wait for the answer, interrupt the answer by talking over it at a normal voice level. Expected in guarded mode: interruption works when you speak at normal level with volume around 15, fails (gated) if the volume is very high; tap interrupts always. Expected in full duplex: self-interruption unless the head unit cancels echo.

**Test 3: duration.** Parked, engine running (alternator noise), hold a conversation for five minutes with pauses up to 30 s. Expected: no `Ended` without a reason, no reconnects. This checks the undocumented `CarAudioRecord` session length and the websocket idle behaviour.

**Test 4: focus.** During a conversation, receive a phone call. Expected: the session ends with "Audio focus lost" and media or the call takes over. After the call, start a new conversation. Then play music, start a conversation: music pauses; end it: music resumes.

**Test 5: road noise, driving, passenger operates the phone.** At 45 mph with the windows up, guarded mode: ask a question, let Sierra answer, interrupt by voice. Count false interruptions in a minute of Sierra talking. If road noise reaches the floor (RMS 400 at 16-bit) it does not matter because the gate only blocks while Sierra plays; what matters is whether the service's VAD fires on road noise during silence. If it does, raise OpenAI `threshold` (0.5 → 0.7) in `buildSessionUpdate`; this is the one knob not yet exposed on the phone.

**Tuning the guard ratio.** Start at 2.0. If Sierra self-interrupts in guarded mode, raise to 3.0 then 4.0. If you cannot barge in by voice at a ratio that stops echo, use the tap and consider lowering the head unit volume; the app cannot distinguish a loud echo from a loud driver with a ratio alone.

**What to write down for the coordinator:** provider, mode, ratio, volume setting, false interruptions per minute, time-to-first-audio, whether voice barge-in worked, and the ElevenLabs `conversationId` or the OpenAI response ids if something odd happened (both are in the logs via the transcript panel).

---

## 7. Phase 2 wiring (not done: `talk-tools` was not merged)

The smallest change to `car/TalkScreen.kt` once lane B's tools are in main:

1. Add a `Conversation` action to the Talk screen's `Replied`/`Failed` `MessageTemplate`/`PaneTemplate` (there is room: the pane has two actions, the message template has one). On click, build `DuplexController(session = Duplex.session(DuplexConfigStore.load(carContext), tools), mic = CarMicSource(carContext), sink = AudioTrackSink(), focus = AndroidFocus(carContext), mode = config.mode, guardRatio = config.guardRatio, listener = …)` and `start()` it on `Dispatchers.IO`. Show a single `MessageTemplate("Conversation… tap Stop to end")` with two actions, **Stop** (`controller.stop()`) and **Interrupt** (`controller.tapInterrupt()`), and refresh its text with the latest final transcripts (same title, so each update is a refresh, not a step; see constraints doc section 4). Pop the screen on `Ended`.
2. Adapt lane B's `Tool` interface to `DuplexTool`: a one-class adapter `class TalkToolAdapter(val t: agent.tools.Tool) : DuplexTool` mapping name, description, JSON Schema and a blocking invoke; register `find_places`, `navigate_to`, `file_idea` alongside `ask_agent` in `Duplex.defaultTools`. Both interfaces are (name, schema, invoke), so the adapter is a few lines.
3. Mic permission: reuse `CarMic.hasPermission` and the existing `NeedsMic` state.

---

## 8. Unverified items (summary)

- Every number in the cost and delay rows of section 2 (third-party sources only; vendor pricing pages were unreachable).
- That the GA OpenAI websocket needs no `OpenAI-Beta` header; that the owner's LiteLLM gateway forwards `/v1/realtime` and `session.updated`.
- ElevenLabs dashboard wording for audio formats, client tools and overrides; signed-URL lifetime; whether `user_activity` affects an audio agent.
- Maximum `CarAudioRecord` session length; whether the Sierra head unit cancels echo on the Android Auto mic path; how the car routes `USAGE_ASSISTANT` playback.
- Everything in the truck test plan, by definition.

## Sources

Vendor code read for the protocols (authoritative for field names):

- OpenAI Node SDK realtime types: https://github.com/openai/openai-node/blob/master/src/resources/realtime/realtime.ts (event names, `session.update` shape, `audio/pcm` 24 kHz note, `conversation.item.truncate`, `interrupt_response`), and `src/realtime/internal-base.ts` (`/realtime?model=` URL building).
- ElevenLabs JS SDK: https://github.com/elevenlabs/packages — `packages/types/generated/types/asyncapi-types.ts` (all websocket message schemas and the audio format enum), `packages/client/src/utils/WebSocketConnection.ts` (URL, signed URL, metadata handling), `packages/client/src/utils/overrides.ts` (initiation message).
- ElevenLabs Python SDK: https://github.com/elevenlabs/elevenlabs-python — `conversational_ai/conversation.py` (`user_audio_chunk`, interruption watermark, `pong`, `client_tool_result`), `conversational_ai/conversations/raw_client.py` (`GET v1/convai/conversation/get-signed-url`, `v1/convai/conversation/token`).
- LiteLLM realtime proxy docs: https://docs.litellm.ai/docs/realtime

Third-party pages for prices and delays (all **unverified**):

[forasoft]: https://www.forasoft.com/blog/article/openai-realtime-api-pricing
[lavx]: https://news.lavx.hu/article/openai-realtime-api-pricing-in-2026-what-4-000-measured-sessions-actually-cost
[cekura]: https://www.cekura.ai/blogs/openai-realtime-api-pricing
[layer3]: https://www.layer3labs.io/guides/openai-realtime-api-pricing
[macha]: https://www.getmacha.com/blog/elevenlabs-agents-pricing-explained
[cloudzero]: https://www.cloudzero.com/blog/elevenlabs-pricing/
[superdupr]: https://superdupr.com/blog/elevenlabs-pricing
[deepgram]: https://deepgram.com/blog/is-elevenlabs-real-time-what-developers-need-to-know

- forasoft: https://www.forasoft.com/blog/article/openai-realtime-api-pricing
- lavx: https://news.lavx.hu/article/openai-realtime-api-pricing-in-2026-what-4-000-measured-sessions-actually-cost
- cekura: https://www.cekura.ai/blogs/openai-realtime-api-pricing
- layer3: https://www.layer3labs.io/guides/openai-realtime-api-pricing
- macha: https://www.getmacha.com/blog/elevenlabs-agents-pricing-explained
- cloudzero: https://www.cloudzero.com/blog/elevenlabs-pricing/
- superdupr: https://superdupr.com/blog/elevenlabs-pricing
- deepgram (ElevenLabs latency measurements, a competitor): https://deepgram.com/blog/is-elevenlabs-real-time-what-developers-need-to-know
