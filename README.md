# Sierra Auto

A personal Android Auto app that puts my own agent on the screen of my 2018 GMC Sierra Denali.

It is sideloaded, not published to the Play Store. Each GitHub Release has an APK you can install from the phone. To use it, turn on Android Auto developer mode and enable "Unknown sources". See `docs/android-auto-constraints.md` §0: Google says that option may not cover Car App Library apps.

See [`docs/PLAN.md`](docs/PLAN.md).

## Install

1. On the phone, open the latest release, download `sierra-auto.apk` and install it: <https://github.com/mdc159/sierra-auto/releases/latest>. Every build is signed with the same key and has a higher `versionCode`, so each one installs over the last.
2. Open **Sierra** on the phone. Grant location and microphone permission.
3. Enter the agent settings and tap **Test connection**:
   - **Base URL**: any OpenAI-compatible endpoint. `https://host`, `https://host/v1` and the full `/chat/completions` URL all work.
   - **API key** and **Model**.
   - **Transcription URL** (optional): an OpenAI-compatible `/v1/audio/transcriptions` endpoint. When it is set, Sierra records with the car microphone. When it is blank, Sierra uses the phone's speech recognizer.

   Settings are stored encrypted on the phone. No key ever goes in this repo.

   Plain `http://` is allowed only for `*.ts.net` (a Tailscale gateway such as `http://studio54.tailfedd3b.ts.net:4000/v1`), `localhost`, `127.0.0.1` and the emulator alias `10.0.2.2`. Every other endpoint must be `https://`.
4. Optional, for the idea loop: tap **Idea settings** and paste a fine-grained GitHub token (repository access: only `sierra-auto`; permission: Issues, read and write). **Test token** checks it without creating anything.
5. Plug into the truck and open **Sierra** in Android Auto.

## On the truck screen

- **Talk**: speak, and Sierra transcribes the question, asks the agent, shows a short reply and reads it aloud. Use **Ask again** for a follow-up; Sierra keeps the last few turns. The agent has tools, so speaking does things: "find gas nearby" searches the Places sources and reads back the nearest; "take me to the Shell" hands off to Google Maps; "file an idea: …" opens a GitHub issue labelled `idea` (needs a token, see **Idea settings** on the phone). While a tool runs, the screen says what it is doing ("Finding fuel nearby…"). Endpoints that do not support OpenAI tool calling still work as plain chat.
- **Places**: Fuel, Food, Rest areas and Parking near you on a host-drawn map. Pick one, then **Navigate** hands off to Google Maps. Results come from OpenStreetMap (Nominatim, with Overpass as a fallback).
- **History**: recent questions and answers. Tap one to hear it again.

## Build

```
./gradlew assembleDebug testDebugUnitTest
```

You need JDK 17 and the Android SDK (platform 36). CI (`.github/workflows/build.yml`) builds and tests every PR. On every push to `main` it also builds a signed release APK and publishes it as Release `v0.1.<run>`.

To run the optional live API tests, set `SIERRA_LIVE_BASE_URL`, `SIERRA_LIVE_API_KEY` and `SIERRA_LIVE_MODEL`. For the transcription test, also set `SIERRA_LIVE_STT_MODEL` and `SIERRA_LIVE_TTS_MODEL`. When these are unset, the live tests are skipped.
