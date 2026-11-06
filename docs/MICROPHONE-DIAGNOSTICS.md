# Microphone diagnostics and Hermes on Victoria

The truck microphone worked again during this investigation. Its original failure is
intermittent and has not been reproduced on the physical truck. Automatic microphone
selection remains the default. The release adds evidence for the next failure and
fixes independently reproduced Conversation startup/stop cleanup defects.

## On the phone, without a USB cable

Update Sierra through Google Play internal testing. Keep Tailscale connected.

- **Connect to Hermes on Victoria** refreshes the existing Studio54 sign-in, selects
  Hermes and tests it. No credential entry is needed. The Who answers selection saves with the
  other settings; choose A model and Save to return to the existing chat model.
- **Microphone & diagnostics** has Automatic, Car and Phone choices. Stop an active
  voice session before switching. Automatic retains the previous selection behavior.
- To use the phone mic from Android Auto, tap **Enable phone microphone for Android
  Auto** while this phone screen is visible and grant microphone permission. The
  foreground notification's Stop button disables access. Enable again after a reboot
  or if Android stops Sierra. This is not a wake-word listener.
- **Test phone mic only** records five seconds into memory and reports levels.
  **Test phone mic + transcription** also submits that clip to the configured speech
  endpoint, displays the returned words and records only their count in diagnostics.
- On the truck, **Sierra → Mic test** captures five seconds from the selected input
  and saves level statistics. It does not make a network request.
- If Talk misses speech, enable **Talk diagnostic mode** temporarily: record until
  Done or twenty seconds. It bypasses local speech detection but still reports digital
  silence. Conversation uses streaming voice and does not use this detector.
- After a failed attempt, return to the phone and **Copy diagnostics** or **Share
  diagnostics**. The bounded report survives an app restart. No developer mode, ADB,
  computer connection or logcat extraction is required.

Reports include app/Android version, selected input, permission/mute status, microphone
levels, detector outcomes, transcription/agent stages and error codes. They contain no
audio, transcripts, API keys or endpoint addresses. Clearing the report clears the
stored events. Sharing creates a text attachment in the app cache. Normal Talk history
continues to use the existing history store; this feature does not change its policy.

The phone requests the built-in input. Android ultimately chooses the route, so the
report includes the actual AudioRecord device type and client-silenced state when
available. Android's microphone privacy switch is not publicly queryable by this app;
check Quick Settings if capture is silent. An app switch cannot promise which physical
microphone Google/Gemini uses for its own wake word.

## Independent agent and speech routing

Hermes chat goes to the authenticated Sierra bridge on Victoria, model `hermes`.
The bridge's configured Hermes adapter calls the running Hermes agent, not a bare
model. Existing typed chat model settings are preserved. Transcription, gateway TTS,
and OpenAI Realtime continue to use their existing endpoints and keys. Missing Hermes
credentials produce a setup error; there is no silent fallback to generic chat.

Talk goes directly to the selected agent after transcription. Conversation is still
the realtime voice assistant; its `ask_agent` tool uses the selected agent. Say
"ask the agent" to delegate to Hermes. Selecting Hermes does not replace the realtime
voice engine or imply that every casual realtime exchange invokes Hermes.

Vault entries in the already enrolled `s54-agent-sierra-a54` project:

- `SIERRA_BRIDGE_URL`: `https://victoria.tailfedd3b.ts.net:8443/v1`
- `SIERRA_BRIDGE_TOKEN`: the existing Victoria bridge bearer token

`bridge/deploy/publish_phone_profile.py` owns reconciliation of these two entries.
Run it on Studio54 with JSON `{"token":"..."}` supplied on stdin from Victoria's
existing `/etc/sierra-bridge/env`, through an in-memory SSH pipe. Never put the real
token on a command line, in a file export or in terminal output. The script uses
Studio54's existing controller and refuses to create a missing phone project.
It verifies authenticated bridge discovery before publishing. If Studio54's outbound
tailnet policy prevents the bridge check, run `--check-only` from the operator client
first, and publish the identical in-memory input with `--client-verified`. Both steps
are required in that case. No gateway key or enrollment is changed.

Live verification on 2026-11-06: Victoria's local authenticated bridge and the HTTPS
tailnet endpoint answered. An actual chat request using the phone's refreshed vault
identity returned HTTP 200 and "Hermes on Victoria is ready." The desktop client can
reach Victoria; Studio54's own outgoing connection to Victoria:8443 timed out, so the
operator-client validation path was used. Phone/truck connectivity still depends on
that device's Tailscale connection.

## Local verification before the pull request

Use an isolated worktree, not the user's active checkout:

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest assembleDebug lintDebug
.\gradlew.bat --no-daemon bundleRelease lintRelease -Pplay=true -PversionCode=100004
python -m unittest discover -s bridge/tests -p test_phone_profile.py
```

Robolectric uses Android API 35, AndroidX's car FakeHost with PCM microphone input,
real car screen templates, and mock transcription/chat servers. Regression cases
cover startup failure, stop during handshake, Done unblocking native reads, exclusive
mic ownership, stalled reads, foreground-service shutdown, signal versus silence,
permission denial, report redaction and separate Hermes/transcription credentials.
This verifies software behavior; it does not emulate the truck's audio routing or
prove the physical phone mic/background capture works on Mike's device. The new
in-app tests provide that final evidence after the Play update.

Local release bundles use the repo's debug-signing fallback. The existing Play workflow
uses the protected release signing key and uploads to the internal track after local
tests and PR checks pass. The phone microphone service declares the microphone
foreground type and permissions, starts only from an explicit visible phone action,
does not restart on boot, and exposes a Stop notification action.

Final combined local check on 2026-11-06: 561 Android tests, 559 passed and two
skipped, zero failures. Debug APK, Play-format release AAB, debug lint and release
lint all completed successfully. The two profile-publication unit tests also passed.
The check included the existing agent-profile work from PR #52. Lint has existing
nonfatal warnings; this is not a claim of warning-free output.

References: [Android microphone foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone),
[foreground start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start),
[AudioRecord routing](https://developer.android.com/reference/android/media/AudioRecord).
