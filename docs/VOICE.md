# Conversational voice for Talk

**As-is:** 2026-11-08, main `210448b`; lane G's original work is historical. Turn-based capture/transcription, selected speech output and follow-up mode are **wired into Talk**, with diagnostic input selection. The separate duplex Conversation flow is wired too ([DUPLEX.md](DUPLEX.md)); the older `voice/realtime` loop and saved switch are not wired to Talk.

Measured figures below are historical desktop synthetic-audio probes (#39, [VOICE-GATEWAY-CHECK.md](VOICE-GATEWAY-CHECK.md), November 4, LiteLLM 1.102.1, one to ten calls per cell, 82–99 ms RTT), not current phone/truck latency or provider availability. No completed current-build duplex exchange or actual truck audio pass was established by the November 8 review; it made no recording or live call. Earlier phone handshake evidence does not prove a spoken turn. Pricing remains unverified; check the configured account before paid tests.

Known gaps remain: destructive ConfigStore/Studio54/Duplex settings recovery, recognizer permission fallback, legacy Repeat/History focus callbacks, private ElevenLabs signed-URL cancellation and native mic Stop blocking. Diagnostics/cleanup improvements do not prove the intermittent truck silence fixed. [STATUS.md](STATUS.md) and [TRUCK-TEST-PLAN.md](TRUCK-TEST-PLAN.md) define the evidence and next steps.

## 1. The three options

| | A. Turn-based with streamed voice (built, wired) | B. Older realtime loop (built, unwired) | C. ElevenLabs custom-LLM hosting (proposal; separate Agents client is built) |
| --- | --- | --- | --- |
| How it works | Car mic → energy end-of-speech → `/v1/audio/transcriptions` → chat completion (tools stay as they are) → `/v1/audio/speech` streamed sentence by sentence to an `AudioTrack` | One websocket to `/v1/realtime`; mic PCM up, voice PCM down, server VAD decides turns; the model speaks directly | ElevenLabs hosts the voice agent (their STT, VAD, TTS); it calls back to *your* LLM over HTTPS for the text |
| Where the agent logic lives | In the app (AgentClient, tool calling from lane B) | In the realtime model; tool calls would have to be re-implemented on realtime events | On ElevenLabs' side, which calls a **public** OpenAI-compatible URL |
| Needs | Gateway URL and key (already configured) | Gateway realtime route working; a realtime model routed by LiteLLM | ElevenLabs account with Agents; a public HTTPS URL for the custom LLM (the gateway is on Tailscale, so a tunnel or the lane-H bridge exposed publicly); the ElevenLabs Android SDK or their WebRTC/websocket protocol |
| Interrupting Sierra | Mic action can stop playback/listen; physical Stop/focus behavior still needs testing | Server VAD `speech_started` event drops queued audio; also mic tap | Handled by ElevenLabs |
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

Provider/model choices are selectable in Voice settings and factory fallback depends on configuration. The probe recommendation was gateway `openai/gpt-4o-mini-transcribe` or ElevenLabs `scribe_v2`, plus ElevenLabs `eleven_flash_v2_5` or gateway speech. Do not assume entering a key automatically selects a provider; inspect the saved selection. Ways to cut further, in order of payoff: shorter `silenceMs`; stream the chat completion and start TTS on the first complete sentence (saves most of the completion time; the splitter must wait for whitespace after `.`, which `SentenceChunker` already does, because #39 saw "3.2 miles" cut at "3.").

Cost was not established for actual phone conversations. Provider/account/gateway billing and growing context can differ; use the dated probe spend only as historical evidence and confirm current account limits before authorized calls.

### B. Realtime speech-to-speech

Lowest latency and the most natural barge-in, because the server hears the driver while it speaks. **Measured (#39):** the gateway's realtime route works at `ws://<gateway>/v1/realtime?model=…` with only the bearer key, in OpenAI's current (non-beta) event format; `openai/gpt-realtime-2.1-mini` took 1.1 s to open the socket and 1.1 s from end of speech to first audio with a 500 ms server silence window; `openai/gpt-realtime-2.1` 2.6 s cold dial and 1.3 s; Gemini Live and Grok voice also answered through the same route. So open the socket when the mic session starts, which `RealtimeVoiceLoop` does. No current realtime price or comparative cost guarantee is established here; check the selected account/model before testing. It sidesteps lane B's tool calling, and the probes did not test tool calls/barge-in/long sessions. This older loop stays unwired; the separate duplex implementation supplies those APIs but still needs physical acceptance.

What exists in the older, unwired package: `RealtimeConfig` (default `openai/gpt-realtime-2.1-mini`, 500 ms server VAD), `RealtimeEvents` (encode/decode, both the GA and the 2024 beta event names), `RealtimeClient` (OkHttp websocket), `Resampler` (16 kHz car mic → 24 kHz wire), and `RealtimeVoiceLoop` (full duplex pump with barge-in). The **Realtime** switch in Voice settings is saved/off by default but does not activate this loop in Talk. The implemented car realtime entry is **Conversation**, backed by `voice/duplex/`, not this package.

Still **unverified** without a device:

- Playing 24 kHz PCM through `AudioTrack` while `CarAudioRecord` is open. Android allows simultaneous capture and playback; whether the head unit's echo path makes server VAD trigger on Sierra's own voice is the main risk of this option.
- Gemini Live quirks from #39 (16 kHz input declared in `session.update`, no `session.updated`, `response.done` up to 11 s after `response.output_audio.done`): the loop waits for `session.updated` before sending audio, so Gemini models would time out with the current client. Pick an OpenAI realtime model until that is handled.
- Phone-side behaviour of the websocket over a mobile link, and reconnecting after a drop.

### C. ElevenLabs Agents with a custom LLM

ElevenLabs Agents handle service-side VAD, interruption and TTS; voice quality was not comparatively measured. Their "custom LLM" option points the agent at an OpenAI-compatible chat-completions URL, which must be reachable from ElevenLabs' servers, so the Tailscale-only gateway would need a public front (Cloudflare Tunnel, Tailscale Funnel, or the lane-H bridge on a public VPS). The app would use the ElevenLabs Android SDK or their websocket protocol. This custom-LLM hosting topology is not implemented/tested by the probes. The **separate ElevenLabs Agents websocket client is implemented** in `voice/duplex/` and reaches the private selected agent through phone-side `ask_agent`, without publishing the tailnet agent URL. Agents latency/cost were not measured by #39 and are not inferred from TTS first-byte timings; verify the configured account during an authorized test.

ElevenLabs plain TTS **is** built as a `SpeechOutput` (option A with an ElevenLabs voice), using `POST /v1/text-to-speech/{voice}/stream?output_format=pcm_24000` with the `xi-api-key` header. **Measured (#39):** it streams for real, first byte 0.18–0.40 s for `eleven_flash_v2_5` regardless of text length, 0.22–0.32 s for `eleven_v4_turbo`; `pcm_24000` works on the owner's account. ElevenLabs transcription (`POST /v1/speech-to-text`, `model_id=scribe_v2`) is built too and was the fastest transcriber measured. The code reports an MP3 reply as a clear error rather than playing noise.

## 2. Implemented turn-based components

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
- On Android Auto, `CarAudioRecord` (1.7.0, `ProjectedCarAudioRecord`) does not record in Sierra's process: `startRecording()` calls the host's `openMicrophone` and `read()` reads the pipe the host returns. So Android's while-in-use microphone rule for background apps does not apply to Sierra's own process. Whether the host itself checks Sierra's foreground state is **unverified**. Checked from the library bytecode on 2026-11-06.
- **Diagnostics (2026-11-06, after the truck heard nothing in Talk or Conversation):** every recorded turn logs one line of levels and the outcome. Read or share them in **Microphone & diagnostics** on the phone without a cable; developers can also use `adb logcat -s SierraMic`. They hold no audio and no text.
  - Each line has the outcome, the verdict, `meanRms`, `peakRms`, `quietestRms`, `maxSample`, `silent`, the floor and threshold, and the floor carried from the last turn.
  - A recording of only zeros (at least 1 s, no sample above 2) now reads "The selected microphone sent only silence. Check microphone access on the phone." instead of "Didn't hear anything."
  - On the first question of a Talk, or after a Done tap, audio the energy gate called empty still goes to the transcriber, so a road-noise floor or a quiet head-unit mic can't drop a real question. Ordinary follow-up turns keep the old rule, so a quiet cabin still ends a conversation. The phone diagnostic mode overrides local detection for Talk screens with a twenty-second recording window and unconfirmed-audio submission. The risk: road noise sent this way may be transcribed as a few stray words.
  - Full-duplex logs its mic levels every 5 s, and its stop reason.
- TTS and `AudioTrack` playback go out like any phone audio over Android Auto. Use `USAGE_ASSISTANT`, not the navigation channel (quality rule DD-1). Which usage the head unit routes best is **unverified**; test on the truck.
- Template steps: the Talk flow must stay a refresh of the same template type while listening/thinking/speaking, or budget its steps (§4 of the constraints doc). Current Talk uses bounded templates and fresh follow-up screens; tool status is pushed/popped while Thinking text stays fixed. Changing Message text is not automatically a free refresh. Validate real-host flow/quota rather than assuming state-machine tests prove it.
- The DHU can feed audio with `mic begin` / `mic play file.wav`, and `mic reject on` simulates a refused mic, to test end-of-speech and `HOST_ENDED` without claiming physical truck routing.

## 4. Current Talk wiring and next checks

`TalkScreen.start()` loads the effective Studio54/typed configuration and input selection. Automatic uses the capture pipeline when transcription is configured and the car API supports it; explicit Car/Phone selection also requires transcription. Otherwise Automatic uses `PhoneRecognizer`.

`startConversation()` builds the configured transcriber, a tool-calling `ConversationAgent`, shared session speech via `TalkVoice`, and a single-turn `ConversationController`. It records history and diagnostic stage events. With conversation mode enabled, the screen replaces itself with a fresh listening follow-up after each answer; noise floor is carried across turns. Done completes capture. Screen destruction requests mic/controller stop and cancels work; shared output is session-owned rather than closed per screen. The legacy recognizer/Repeat/History path still uses `Speaker`.

Next: parked tests for first utterance/unconfirmed audio, permission denial, focus, follow-up mic reopens, background Phone service, stop phrases, silent PCM, stalled native operations and timeout recovery. Preserve actual input/device/level diagnostics; no physical routing guarantee follows from the selected label. Fix source-confirmed lifecycle/store defects before claiming all error paths bounded.

## Next steps

Repair store recovery, recognizer permission fallback and legacy focus; add cancellation/stalled-native/HTTP regressions for the separate duplex path. Record the full parked input → transcription → selected agent → speaker → stop chain on the current build, then follow-ups/focus/reconnect. Preserve diagnostic source/levels and exact provider/model evidence instead of equating desktop probes with physical routing. See [TRUCK-TEST-PLAN.md](TRUCK-TEST-PLAN.md).

## 5. Unverified items (summary)

Verified from the PC by #39 and therefore **not** on this list any more: gateway `pcm` output from `gpt-4o-mini-tts` (raw 24 kHz, rate inferred from byte counts), ElevenLabs `pcm_24000` on the owner's account, the realtime route, bearer auth and GA session shape, and the stage latencies in section 1.

- Completed current-build phone/truck speech exchanges: mobile round trips, `AudioTrack` routing, echo, the car microphone's behaviour across turns. Narrow historical phone handshake/profile checks are recorded separately in STATUS.md.
- All price figures.
- Realtime tool calls, barge-in and long sessions (#39 did not test them).
- Echo from the car speakers triggering server VAD in realtime mode.
- Repeated `CarAudioRecord` sessions across many turns, and the host's mic indicator behaviour between them.
- `AudioTrack` with `USAGE_ASSISTANT` routing on the Sierra head unit.
- The energy thresholds (`minSpeechRms = 500`, ratio 2.5, uncalibrated threshold 1500, 600 ms calibration) were tuned on synthetic audio only; a real cabin may need the ratio or the calibration window changed. All of them are in `EndpointConfig`.
