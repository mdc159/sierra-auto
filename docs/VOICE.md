# Conversational voice for Talk

**Written:** 2026-11-04 (lane G). **Status:** phase 1 code is on the branch and unit-tested against fake servers and fake devices. Nothing in this document has been run on the phone, the truck or the owner's gateway unless it says so. Every claim that could not be checked is marked **unverified**.

Today Talk is push-to-talk: tap, one question, one reply read by Android's built-in voice. The goal is a hands-free conversation with a high-quality voice. This doc compares the ways to get there, says what the code on this branch does, and lists what still has to be proven on a device.

## 1. The three options

| | A. Turn-based with streamed voice (built) | B. Realtime speech-to-speech through the gateway (built, experimental, off) | C. ElevenLabs Agents with a custom LLM (not built) |
| --- | --- | --- | --- |
| How it works | Car mic → energy end-of-speech → `/v1/audio/transcriptions` → chat completion (tools stay as they are) → `/v1/audio/speech` streamed sentence by sentence to an `AudioTrack` | One websocket to `/v1/realtime`; mic PCM up, voice PCM down, server VAD decides turns; the model speaks directly | ElevenLabs hosts the voice agent (their STT, VAD, TTS); it calls back to *your* LLM over HTTPS for the text |
| Where the agent logic lives | In the app (AgentClient, tool calling from lane B) | In the realtime model; tool calls would have to be re-implemented on realtime events | On ElevenLabs' side, which calls a **public** OpenAI-compatible URL |
| Needs | Gateway URL and key (already configured) | Gateway realtime route working; a realtime model routed by LiteLLM | ElevenLabs account with Agents; a public HTTPS URL for the custom LLM (the gateway is on Tailscale, so a tunnel or the lane-H bridge exposed publicly); the ElevenLabs Android SDK or their WebRTC/websocket protocol |
| Interrupting Sierra | Mic tap (barge-in) stops playback and starts listening. Always works, no server needed | Server VAD `speech_started` event drops queued audio; also mic tap | Handled by ElevenLabs |
| Keeps tool calling from lane B | Yes, unchanged | No, would need porting | Partly; tools would run on ElevenLabs' side or via webhooks |
| Verified in this branch | Request shapes, streaming playback, focus handling, state machine: unit tests only | Wire format and event handling against a fake websocket server: unit tests only | Nothing |

### A. Turn-based with streamed voice (recommended first)

This is what `ConversationController` + `StreamedSpeechOutput` implement. It keeps everything the app already does (gateway chat, lane B's tools, history) and only changes how audio goes in and out.

Expected latency per turn, measured from the moment the driver stops talking to the first sound of the answer (**estimates, unverified on the truck**):

| Step | Typical | Notes |
| --- | --- | --- |
| End-of-speech detection | 1.2 s | `EndpointConfig.silenceMs`. Shorter feels snappier but clips slow talkers; 0.8–1.5 s is the usual range |
| Transcription | 0.3–1.0 s with `groq/whisper-large-v3-turbo`; 1–3 s with `openai/gpt-4o-transcribe` | Groq figures from their published benchmarks, **unverified** through LiteLLM |
| Chat completion, first token | 0.5–2 s | Depends on the model behind the gateway. We wait for the *whole* reply today (no chat streaming); a 2-sentence reply adds roughly 0.5–1.5 s |
| First TTS bytes | 0.3–1 s | `gpt-4o-mini-tts` starts streaming before the whole file is rendered; the first chunk is the first sentence only, so it is short |
| **Total to first sound** | **about 3–5 s** | Plus the silence window. Push-to-talk today is similar, so the win is hands-free, not speed |

Ways to cut it later, in order of payoff: shorter `silenceMs`; stream the chat completion and start TTS on the first complete sentence (saves most of the completion time); run transcription on Groq.

Cost (list prices, **unverified** for the owner's gateway and plan): `gpt-4o-mini-tts` is billed per token, about $0.015 per minute of audio per OpenAI's pricing page; `whisper-large-v3-turbo` on Groq is about $0.04 per hour of audio; `gpt-4o-transcribe` is about $0.006 per minute. A ten-minute conversation of 20 turns is in the cents.

### B. Realtime speech-to-speech

Lowest possible latency (OpenAI quotes well under a second to first audio for `gpt-realtime`; **unverified**) and the most natural barge-in, because the server hears the driver while it speaks. Costs more: realtime audio is billed per audio token in and out, roughly $32 per million input audio tokens and $64 per million output for `gpt-realtime` at the time of writing, which is on the order of $0.06 per minute of conversation (**unverified**, check the current pricing page). It also sidesteps lane B's tool calling, so for now it is a separate, experimental path.

What is on the branch: `RealtimeConfig`, `RealtimeEvents` (encode/decode, both the GA and the 2024 beta event names), `RealtimeClient` (OkHttp websocket), `Resampler` (16 kHz car mic → 24 kHz wire), and `RealtimeVoiceLoop` (full duplex pump with barge-in). It is behind **Realtime** in Voice settings, off by default, and nothing on the car screen uses it yet.

What could **not** be verified without a device or the gateway:

- Whether the owner's LiteLLM has the realtime route enabled and what URL it expects. LiteLLM documents `/v1/realtime?model=…` as an OpenAI-compatible websocket proxy; the client builds exactly that from the agent base URL (`ws://` for the plain-http Tailscale gateway). **Unverified.**
- Whether `openai/gpt-realtime-2.1` accepts the GA `session.update` shape (`session.type = "realtime"`, `audio.input.format`). The 2024 beta shape is available with `legacyEvents = true` if the server rejects the GA one. **Unverified.**
- Whether LiteLLM forwards the `Authorization: Bearer` header as the gateway key or expects `api-key`. The client sends `Authorization` plus `OpenAI-Beta: realtime=v1`. **Unverified.**
- Playing 24 kHz PCM through `AudioTrack` while `CarAudioRecord` is open. Android allows simultaneous capture and playback; whether the head unit's echo path makes server VAD trigger on Sierra's own voice is **unverified** and the main risk of this option.

### C. ElevenLabs Agents with a custom LLM

ElevenLabs' conversational agents give the best voices and handle VAD, interruption and TTS on their side. Their "custom LLM" option points the agent at an OpenAI-compatible chat-completions URL, which must be reachable from ElevenLabs' servers, so the Tailscale-only gateway would need a public front (Cloudflare Tunnel, Tailscale Funnel, or the lane-H bridge on a public VPS). The app would use the ElevenLabs Android SDK or their websocket protocol. Not built; listed so the trade-off is on record. Latency: ElevenLabs quotes about 75 ms for Flash TTS and sub-second turn latency for agents (**unverified**). Cost: agent minutes are billed separately from TTS characters, around $0.08–0.10 per minute on paid plans (**unverified**).

ElevenLabs plain TTS **is** built as a `SpeechOutput` (option A with an ElevenLabs voice), using `POST /v1/text-to-speech/{voice}/stream?output_format=pcm_24000` with the `xi-api-key` header. Whether `pcm_24000` is allowed on the owner's plan is **unverified** (ElevenLabs gates some PCM rates by tier); the code reports an MP3 reply as a clear error rather than playing noise.

## 2. What the branch builds (phase 1)

```
voice/tts/     SpeechOutput, SentenceChunker, AudioFocusGate (+AndroidAudioFocus), PcmPlayer (+AudioTrackPlayer),
               TtsRequestFactory (GatewayTts, ElevenLabsTts), StreamedSpeechOutput, AndroidSpeechOutput (+TtsEngine)
voice/stt/     MicSource (+CarMicSource), EndOfSpeechDetector/EndpointConfig, UtteranceRecorder, Transcriber (+GatewayTranscriber)
voice/convo/   ConversationController (+ConversationAgent, ConversationConfig, ConversationState, EndReason),
               VoiceSettings, VoiceSettingsStore (encrypted), VoiceStack (factory)
voice/realtime/ RealtimeConfig, RealtimeEvents, Resampler, RealtimeClient, RealtimeVoiceLoop  (experimental)
VoiceSettingsActivity (phone)
```

- **SpeechOutput**: `speak(text)` suspends until done, stopped or failed; `stop()` is barge-in. All three implementations take transient `USAGE_ASSISTANT` focus (ducking) before the first sound and release it after, per `docs/android-auto-constraints.md` §7. Network voices cut the reply into sentences: first sentence alone for a fast start, later ones packed to about 220 characters; fetching runs ahead of playback.
- **End-of-speech**: RMS per mic buffer against an adaptive noise floor (minimum seen, creeping up slowly when the cabin gets louder). Until a floor has been measured the threshold is `maxThresholdRms` (so speech from the very first chunk counts); after that it is `max(minSpeechRms, floor × ratio)` with no cap, so a cabin louder than the cap cannot read as endless speech. If nothing quieter than the cap is heard within `calibrationMs`, the quietest chunk so far becomes the floor and "speech" that never stood out above it is dropped. Speech is confirmed only after `minSpeechMs` of sound; the utterance ends after `silenceMs` of quiet. Defaults: 1.2 s silence, 7 s no-speech, 20 s cap, 0.6 s calibration.
- **ConversationController**: `Listening → Thinking → Speaking → Listening…`. Ends on a stop phrase ("stop", "that's all", "never mind", with filler words tolerated), on `silenceTimeoutMs` with nobody talking, when the host ends the mic session, on `stop()`, or on an error (which it speaks first). Mic tap while speaking interrupts and listens; mic tap while listening finishes the utterance now. Everything is injected (`MicSource`, `Transcriber`, `ConversationAgent`, `SpeechOutput`, `AudioFocusGate`), which is how the 16 controller tests run with fakes and no clock.
- **Voice settings**: provider (Android, gateway, ElevenLabs), gateway model and voice, ElevenLabs key/voice/model, transcription model, conversation mode on/off, silence timeout, realtime switch. Stored in `EncryptedSharedPreferences` (`sierra_voice_config`), the same scheme as the agent key.

## 3. What Android Auto allows for a continuous mic session

From `docs/android-auto-constraints.md` §7 and the pages it cites:

- The car microphone is `CarAudioRecord` (Car App API level 5). Take audio focus first with `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`, which pauses media; stop recording if focus is lost. `UtteranceRecorder` and `RealtimeVoiceLoop` do exactly this.
- While the app records, **the host shows its own microphone indicator** and the driver can dismiss it on the car screen, which makes `read()` return -1. The controller treats that as the end of the conversation (`EndReason.HOST_ENDED`). The `-1` behaviour comes from a search summary of the reference, **unverified** against the full page.
- Google's UX guidance says to stop recording when the user stops talking and to provide a mic entry point. There is no documented limit on how long one `CarAudioRecord` session may stay open, and no documented rule against re-opening it after each reply. **Unverified, and the biggest open question for continuous mode:** the constraints doc only documents tap-started sessions. The host may refuse a second `openMicrophone` without a fresh user action, or cap a session's length. In 1.7.0 a refused `openMicrophone` shows up as an immediate `-1` from the first `read()`, which `UtteranceRecorder` reports as "Car microphone unavailable. Try again." (and the conversation ends with that message) rather than as a silent dismissal. The DHU is the place to find out: `mic begin`, then let the controller run three turns. If the host throttles or rejects repeated sessions, the fallback is to keep one session open across turns and let `EndOfSpeechDetector` segment it; the code is one small change away from that (keep `mic.start()` outside the per-turn recorder).
- Only one `CarAudioRecord` at a time per app is a safe assumption (**unverified**). The controller never has two.
- TTS and `AudioTrack` playback go out like any phone audio over Android Auto. Use `USAGE_ASSISTANT`, not the navigation channel (quality rule DD-1). Which usage the head unit routes best is **unverified**; test on the truck.
- Template steps: the Talk flow must stay a refresh of the same template type while listening/thinking/speaking, or budget its steps (§4 of the constraints doc). Phase 2 wiring should show the conversation on one `MessageTemplate` (Listening… / Thinking… / the reply) and only push a new screen for the final reply pane, as `TalkScreen` does today.
- The DHU can feed audio with `mic begin` / `mic play file.wav`, and `mic reject on` simulates a refused mic, which is how the coordinator can test end-of-speech and `HOST_ENDED` without the truck.

## 4. Phase 2 wiring (not done on this branch; see the PR comment)

`TalkScreen` should, when `VoiceSettings.conversationMode` is on and the car mic is available:

1. Build `VoiceStack.speechOutput(carContext, settings, agentConfig)` once per session (instead of `session.speaker`), `VoiceStack.transcriber(settings, agentConfig)`, and a `ConversationAgent` that calls `AgentClient.chat(session.conversation.messagesFor(text))` (the lane-B version with tools) and records the exchange.
2. Create `ConversationController(CarMicSource(carContext), transcriber, agent, speech, AndroidAudioFocus(carContext), settings.conversationConfig(), onState = { invalidate() }, onExchange = { h, r -> History.add(...) })` and `launch { controller.run() }` in `lifecycleScope`.
3. Render `controller.state.value`: Listening → "Listening…" message with the mic icon and a **Done** action that calls `onMicTapped()`; Thinking → loading message; Speaking → the reply pane with the mic action calling `onMicTapped()` (barge-in); Ended → the reply pane with **Ask again** (or the error with **Try again**).
4. On screen destroy, `controller.stop()` and `speech.close()`.

## 5. Unverified items (summary)

- LiteLLM passes `response_format: "pcm"` through to `gpt-4o-mini-tts` unchanged (the code also accepts WAV and rejects MP3 with a readable error).
- `pcm_24000` output on the owner's ElevenLabs plan.
- All latency and price figures above.
- LiteLLM realtime route, auth header, and GA vs beta session shape for `openai/gpt-realtime-2.1`.
- Echo from the car speakers triggering server VAD in realtime mode.
- Repeated `CarAudioRecord` sessions across many turns, and the host's mic indicator behaviour between them.
- `AudioTrack` with `USAGE_ASSISTANT` routing on the Sierra head unit.
- The energy thresholds (`minSpeechRms = 500`, ratio 2.5, uncalibrated threshold 1500, 600 ms calibration) were tuned on synthetic audio only; a real cabin may need the ratio or the calibration window changed. All of them are in `EndpointConfig`.
