# Sierra Auto — MVP Plan

**Started:** 2026-11-04  
**Owner:** mdc159  
**Vehicle:** 2018 GMC Sierra Denali, 8" factory infotainment, wired Android Auto  
**Phone:** Samsung Galaxy A54 (Android), Android Auto developer mode with "Unknown sources" enabled

## Goal

A personal Android Auto app that puts the owner's own agent on the truck's screen. It is sideloaded, not published to the Play Store.

## Done means

A GitHub Release on `mdc159/sierra-auto` contains an installable, consistently signed APK. On the truck screen, "Sierra" appears in Android Auto and offers:

1. **Places.** A host-drawn map plus a list of nearby places (PlaceListMapTemplate or the current equivalent). Selecting a place hands navigation off to Google Maps.
2. **Talk.** Tap the mic and speak. The app records through the car microphone (CarAudioRecord), transcribes the audio, sends the text to the configured agent, then shows the reply on screen and speaks it with TTS through the car speakers.
3. **Settings, on the phone.** The agent endpoint is any OpenAI-compatible base URL, API key and model. An optional transcription endpoint can be set too. If none is configured, Android's on-device SpeechRecognizer is the fallback. No key ever lives in the repo.

## Milestones

| # | Milestone | Truck-testable |
| --- | --- | --- |
| M1 | "Hello, truck" Car App Library screen; CI builds the signed APK and publishes a Release | Yes, proves the pipeline end to end |
| M2 | Places map, with hand-off to Google Maps navigation | Yes |
| M3 | Talk screen: voice in, agent reply out (text and speech) | Yes, this is the MVP |
| M4 | Polish: phone settings UI, conversation history, error and timeout handling, "no endpoint configured" guidance | Bonus |
| M5 | Idea loop: speak a feature idea, a GitHub issue is filed, a cloud Claude agent opens a PR, CI builds a new APK | Later |

## Constraints

- Android Auto apps are template-based (Android for Cars App Library). See `docs/android-auto-constraints.md`.
- Category: point-of-interest for the MVP. The host draws the map, so no map SDK is needed.
- Signing: the release keystore lives only in GitHub Actions secrets (`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`). This repository is public, so never commit a keystore or a key.
- Any feature meant for use while driving must respect the Car App Quality guidelines: text length, list limits, no typing while moving.
