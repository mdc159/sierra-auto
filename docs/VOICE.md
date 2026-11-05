# Conversational voice for Talk

**Written:** 2026-11-04 (lane G). **Status:** phase 1 code is on the branch and unit-tested against fake servers and fake devices. Nothing in this document has been run on the phone or the truck. Where a figure says **measured**, it comes from issue #39 (`docs/VOICE-GATEWAY-CHECK.md` in PR #38): probes run from the owner's PC on the Tailscale network on 2026-11-04 against the LiteLLM gateway (1.102.1) and ElevenLabs, one to ten calls per figure, 82–99 ms round trip to the gateway. From the truck every stage adds a mobile round trip, which is not measured. Every claim that could not be checked is marked **unverified**.

Today Talk is push-to-talk: tap, one question, one reply read by Android's built-in voice. The goal is a hands-free conversation with a high-quality voice. This doc compares the ways to get there, says what the code on this branch does, and lists what still has to be proven on a device.

## 1. The three options

| | A. Turn-based with streamed voice (built) | B. Realtime speech-to-speech through the gateway (built, experimental, off) | C. ElevenLabs Agents with a custom LLM (not built) |
| --- | --- | --- | --- |
| How it works | Car mic → energy end-of-speech → `/v1/audio/transcriptions` → chat completion (tools stay as they are) → `/v1/audio/speech` streamed sentence by sentence to an `AudioTrack` | One websocket to `/v1/realtime`; mic PCM up, voice PCM down, server VAD decides turns; the model speaks directly | ElevenLabs hosts the voice agent (their STT, VAD, TTS); it calls back to *your* LLM over HTTPS for the text |
| Where the agent logic lives | In the app (AgentClient, tool calling from lane B) | In the realtime model; tool calls would have to be re-implemented on realtime events | On ElevenLabs' side, which calls a **public** OpenAI-compatible URL |
| Needs | Gateway URL and key (already configured) | Gateway realtime route working; a realtime model routed by LiteLLM | ElevenLabs account with Agents; a public HTTPS URL for the custom LLM (the gateway is on Tailscale, so a tunnel or the lane-H bridge exposed publicly); the ElevenLabs Android SDK or their WebRTC/websocket protocol |
| Interrupting Sierra | Mic tap (barge-in) stops playback and starts listening. Always works, no server needed | Server VAD `speech_started` event drops queued audio; also mic tap | Handled by ElevenLabs |
| Keeps tool calling from lane B | Yes, unchanged | No, would need porting (untested on this path) | Partly; tools would run on ElevenLabs' side or via webhooks |
| Measured from the PC (#39), end of recorded speech to first audio | 1.2–1.9 s with ElevenLabs speech-to-text and voice; 2.7–5.2 s all on the gateway | 1.1 s (`gpt-realtime-2.1-mini`, 500 ms server silence window included) | Not measured |
| Verified in this branch | Request shapes, streaming playback, focus handling, state machine: unit tests only | Wire format and event handling against a fake websocket server: unit tests only | Nothing |

### A. Turn-based with streamed voice (recommended first)

This is what `ConversationController` + `StreamedSpeechOutput` implement. It keeps everything the app already does (gateway chat, lane B's tools, history) and only changes how audio goes in and out.

Latency per turn, from the moment the driver stops talking to the first sound of the answer. Stage figures are **measured** from the PC (#39); the sum on the truck is **unverified**:

| Step | Measured (PC, #39) | Notes |
| --- | --- | --- |
| End-of-speech detection | 1.2 s by default | `EndpointConfig.silenceMs`. Shorter feels snappier but clips slow talkers; #39 assumed 0.5–0.8 s |
| Transcription | ElevenLabs `scribe_v2` 0.36–0.84 s; `openai/gpt-4o-mini-transcribe` 0.74–1.06 s; `groq/whisper-large-v3-turbo` 0.59–1.4 s | All with zero word errors on synthetic speech; 0.9–2.3 s inside the full pipeline. Not a ranking for a real voice in a real cab |
| Chat completion | `groq/openai/gpt-oss-120b` first token 0.52–0.66 s, tool call 0.49 s | We wait for the *whole* reply today (no chat streaming); a 2-sentence reply adds roughly 0.5–1.5 s. Without tools attached the models invented fuel stations, so keep lane B's tools on |
| First voice bytes | ElevenLabs `eleven_flash_v2_5` 0.18–0.40 s; gateway `openai/gpt-4o-mini-tts` 1.0–1.1 s per sentence, 3.6 s for four sentences | **The gateway's `/v1/audio/speech` does not stream**: it buffers the whole upstream file, so first byte grows with the text. One request per sentence (what `StreamedSpeechOutput` does) keeps it to about a second per sentence. ElevenLabs streams for real |
| **Total to first sound, after the silence window** | **1.2–1.9 s** with ElevenLabs speech-to-text and voice; **1.6–2.7 s** gateway speech-to-text + ElevenLabs voice; **2.7–5.2 s** all on the gateway | Plus the mobile round trips from the truck |

Defaults chosen from this: transcription `openai/gpt-4o-mini-transcribe` on the gateway, or ElevenLabs `scribe_v2` when a key is entered (both selectable in Voice settings); voice ElevenLabs `eleven_flash_v2_5` when a key is entered, else gateway `gpt-4o-mini-tts`, else Android. Ways to cut further, in order of payoff: shorter `silenceMs`; stream the chat completion and start TTS on the first complete sentence (saves most of the completion time; the splitter must wait for whitespace after `.`, which `SentenceChunker` already does, because #39 saw "3.2 miles" cut at "3.").

Cost (list prices, **unverified** for the owner's gateway and plan): `gpt-4o-mini-tts` is billed per token, about $0.015 per minute of audio per OpenAI's pricing page; `whisper-large-v3-turbo` on Groq is about $0.04 per hour of audio; `gpt-4o-mini-transcribe` is about $0.003 per minute. ElevenLabs Flash is billed per character against the plan's quota. A ten-minute conversation of 20 turns is in the cents.

### B. Realtime speech-to-speech

Lowest latency and the most natural barge-in, because the server hears the driver while it speaks. **Measured (#39):** the gateway's realtime route works at `ws://<gateway>/v1/realtime?model=…` with only the bearer key, in OpenAI's current (non-beta) event format; `openai/gpt-realtime-2.1-mini` took 1.1 s to open the socket and 1.1 s from end of speech to first audio with a 500 ms server silence window; `openai/gpt-realtime-2.1` 2.6 s cold dial and 1.3 s; Gemini Live and Grok voice also answered through the same route. So open the socket when the mic session starts, which `RealtimeVoiceLoop` does. Costs more: realtime audio is billed per audio token in and out, on the order of $0.06 per minute of conversation (**unverified**, check the current pricing page). It sidesteps lane B's tool calling, and tool calls, barge-in and sessions longer than a few seconds were **not tested** on this path (#39), so it stays a separate, experimental switch.

What is on the branch: `RealtimeConfig` (default `openai/gpt-realtime-2.1-mini`, 500 ms server VAD), `RealtimeEvents` (encode/decode, both the GA and the 2024 beta event names), `RealtimeClient` (OkHttp websocket), `Resampler` (16 kHz car mic → 24 kHz wire), and `RealtimeVoiceLoop` (full duplex pump with barge-in). It is behind **Realtime** in Voice settings, off by default, and nothing on the car screen uses it yet.

Still **unverified** without a device:

- Playing 24 kHz PCM through `AudioTrack` while `CarAudioRecord` is open. Android allows simultaneous capture and playback; whether the head unit's echo path makes server VAD trigger on Sierra's own voice is the main risk of this option.
- Gemini Live quirks from #39 (16 kHz input declared in `session.update`, no `session.updated`, `response.done` up to 11 s after `response.output_audio.done`): the loop waits for `session.updated` before sending audio, so Gemini models would time out with the current client. Pick an OpenAI realtime model until that is handled.
- Phone-side behaviour of the websocket over a mobile link, and reconnecting after a drop.

### C. ElevenLabs Agents with a custom LLM

ElevenLabs' conversational agents give the best voices and handle VAD, interruption and TTS on their side. Their "custom LLM" option points the agent at an OpenAI-compatible chat-completions URL, which must be reachable from ElevenLabs' servers, so the Tailscale-only gateway would need a public front (Cloudflare Tunnel, Tailscale Funnel, or the lane-H bridge on a public VPS). The app would use the ElevenLabs Android SDK or their websocket protocol. Not built and **not tested** (#39); listed so the trade-off is on record. Latency: ElevenLabs quotes sub-second turn latency for agents (**unverified**). Cost: agent minutes are billed separately from TTS characters, around $0.08–0.10 per minute on paid plans (**unverified**).

ElevenLabs plain TTS **is** built as a `SpeechOutput` (option A with an ElevenLabs voice), using `POST /v1/text-to-speech/{voice}/stream?output_format=pcm_24000` with the `xi-api-key` header. **Measured (#39):** it streams for real, first byte 0.18–0.40 s for `eleven_flash_v2_5` regardless of text length, 0.22–0.32 s for `eleven_v4_turbo`; `pcm_24000` works on the owner's account. ElevenLabs transcription (`POST /v1/speech-to-text`, `model_id=scribe_v2`) is built too and was the fastest transcriber measured. The code reports an MP3 reply as a clear error rather than playing noise.

## 2. What the branch builds (phase 1)

```
voice/tts/     SpeechOutput, SentenceChunker, AudioFocusGate (+AndroidAudioFocus), PcmPlayer (+AudioTrackPlayer),
               TtsRequestFactory (GatewayTts, ElevenLabsTts), StreamedSpeechOutput, AndroidSpeechOutput (+TtsEngine)
voice/stt/     MicSource (+CarMicSource), EndOfSpeechDetector/EndpointConfig, UtteranceRecorder,
               Transcriber (+GatewayTranscriber, ElevenLabsTranscriber)
voice/convo/   ConversationController (+ConversationAgent, ConversationConfig, ConversationState, EndReason),
               VoiceSettings, VoiceSettingsStore (encrypted), VoiceStack (factory)
voice/realtime/ RealtimeConfig, RealtimeEvents, Resampler, RealtimeClient, RealtimeVoiceLoop  (experimental)
VoiceSettingsActivity (phone)
```

- **SpeechOutput**: `speak(text)` suspends until done, stopped or failed; `stop()` is barge-in. All three implementations take transient `USAGE_ASSISTANT` focus (ducking) before the first sound and release it after, per `docs/android-auto-constraints.md` §7. Network voices cut the reply into sentences: first sentence alone for a fast start, later ones packed to about 220 characters; fetching runs ahead of playback.
- **End-of-speech**: RMS per mic buffer against an adaptive noise floor (minimum seen, creeping up slowly when the cabin gets louder). Until a floor has been measured the threshold is `maxThresholdRms` (so speech from the very first chunk counts); after that it is `max(minSpeechRms, floor × ratio)` with no cap. If nothing quieter than the cap is heard within `calibrationMs`, the quietest chunk so far becomes the floor, so a cabin that is steadily louder than the cap ends the "utterance" after `silenceMs` instead of running to `maxMs`. The floor measured on one turn seeds the next (`initialNoiseFloor`, carried by `ConversationController` and by `TalkVoice` across screens), so from the second turn on a loud cabin is known before the driver speaks and only a voice above it counts. Speech is confirmed only after `minSpeechMs` of sound; the utterance ends after `silenceMs` of quiet. Defaults: 1.2 s silence, 7 s no-speech, 20 s cap, 0.6 s calibration. Known gap: on the very first turn in a cabin louder than the cap, about 1.8 s of noise is sent to the transcriber once, which answers with nothing ("Didn't catch that."); a seeded floor prevents the repeat.
- **ConversationController**: `Listening → Thinking → Speaking → Listening…`. Ends on a stop phrase ("stop", "that's all", "never mind", with filler words tolerated), on `silenceTimeoutMs` with nobody talking, when the host ends the mic session, on `stop()`, or on an error (which it speaks first). Mic tap while speaking interrupts and listens; mic tap while listening finishes the utterance now. Everything is injected (`MicSource`, `Transcriber`, `ConversationAgent`, `SpeechOutput`, `AudioFocusGate`), which is how the 16 controller tests run with fakes and no clock.
- **Voice settings**: voice provider (Android, gateway, ElevenLabs), gateway model and voice, ElevenLabs key/voice/model, transcription provider (gateway model, or ElevenLabs `scribe_v2`), conversation mode on/off, silence timeout, realtime switch and model. Stored in `EncryptedSharedPreferences` (`sierra_voice_config`), the same scheme as the agent key; an open failure never deletes the store.

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

Verified from the PC by #39 and therefore **not** on this list any more: gateway `pcm` output from `gpt-4o-mini-tts` (raw 24 kHz, rate inferred from byte counts), ElevenLabs `pcm_24000` on the owner's account, the realtime route, bearer auth and GA session shape, and the stage latencies in section 1.

- Everything from the phone and the truck: mobile round trips, `AudioTrack` routing, echo, the car microphone's behaviour across turns.
- All price figures.
- Realtime tool calls, barge-in and long sessions (#39 did not test them).
- Echo from the car speakers triggering server VAD in realtime mode.
- Repeated `CarAudioRecord` sessions across many turns, and the host's mic indicator behaviour between them.
- `AudioTrack` with `USAGE_ASSISTANT` routing on the Sierra head unit.
- The energy thresholds (`minSpeechRms = 500`, ratio 2.5, uncalibrated threshold 1500, 600 ms calibration) were tuned on synthetic audio only; a real cabin may need the ratio or the calibration window changed. All of them are in `EndpointConfig`.
