# Testing on the real phone

Scripts for testing Sierra on the phone with the Android Auto Desktop Head Unit (DHU). They live in `scripts/`, run under bash from the Linux development machine and need the toolchain environment (`source .local/omarchy-toolchain/env.sh`).

These require an authorized local device environment; cloud-only tests cannot emulate the physical routing. Linux baseline **2026-11-20, revision `358e456`** on `dev/phone-voice-checkpoint`: Android `assembleDebug testDebugUnitTest lintDebug` — 616 passed, 2 skipped, lint 0 errors/133 warnings; bridge — 123 passed, 1 skipped, 0 failures (evidence: `.local/omarchy-validation/`). The attached A54 (SM-A546U1) runs Sierra 0.1.100009. The Windows-era baseline (2026-11-08, main `210448b`: 569 tests/2 skips and four native-Windows bridge failures) is preserved in [WORKSPACE.md](WORKSPACE.md) history. Automated results are not phone or truck acceptance.

## Read this first: a DHU pass is not a truck pass

Historical DHU sessions opened Sierra with every install source tried, including none ([issue #36](https://github.com/mdc159/sierra-auto/issues/36)); that observation is not a platform guarantee for every host/version. Play internal testing is now operational. The November 8 phone record was installer Play / initiator shell, not proven direct Play provenance; actual truck launch was not tested. [UPDATES.md](UPDATES.md) explains the record heuristic and preferred Play channel.

So these scripts can show that a build installs, starts, draws its screens and does not crash. They cannot show that the truck will list it.

## Setup

One time, on the Linux machine:

| Need | Where the scripts look |
|---|---|
| Toolchain environment | `source .local/omarchy-toolchain/env.sh` (exports `JAVA_HOME` and `ANDROID_HOME`) |
| Android SDK with `platform-tools` and the DHU (`sdkmanager "extras;google;auto"`) | `$ANDROID_HOME` (`~/.local/share/android-sdk`) |
| JDK 17 (only for `build-pr.sh`) | from env.sh (mise Temurin 17.0.21+9), else `JAVA_HOME` |
| GitHub CLI `gh`, signed in | `PATH` |
| Release keystore and passwords (only for `build-pr.sh`) | the established external signing location on this machine, never inside the repo |

`SIERRA_SERIAL` selects the adb serial when more than one device is attached. Workspace defaults resolve the **canonical Git common-dir root**, even when invoked from a linked tree: `SIERRA_RUNS` defaults to `<root>/.local/runs`, `SIERRA_STATE` to `<root>/.local/state/dhu`, release downloads to `<root>/.local/downloads/releases`. Runs/state overrides must remain under canonical **`.local`**, not merely somewhere under root or in a linked tree. All project output stays under `/home/mdc/Projects/sierra-auto`; the shared toolchain and credential locations are intentional external dependencies.

`signing.env` holds three lines, `SIGNING_STORE_PASSWORD=`, `SIGNING_KEY_ALIAS=` and `SIGNING_KEY_PASSWORD=`. Neither file belongs in any repo.

One time, on the phone:

1. Turn on USB debugging and accept the machine's key.
2. Turn on Android Auto developer mode (in Android Auto settings, tap the version row ten times).
3. In Android Auto settings, open the three-dot menu and tap **Start head unit server**. The server is a service inside Android Auto that is not exported, so adb cannot start it; only this menu can.

**Keep the phone unlocked while testing.** A locked phone keeps an existing DHU session alive, but nobody can start the head unit server, answer a Play Protect question or change an Android Auto setting on it.

## The scripts

| Script | What it does |
|---|---|
| `install-phone.sh` | Development install of latest GitHub release, a tag or a local APK; requests Play as installer of record but remains shell-initiated. Prints version/record; does not prove Play provenance. |
| `dhu.sh start` | Forwards port 5277, checks the phone's head unit server, starts the DHU with its console held open, waits until the session is connected and Sierra is offered. |
| `dhu.sh send <command>` | Sends one DHU console command and prints the answer. |
| `dhu.sh tap <x> <y>` | Taps the car screen. |
| `dhu.sh status` | DHU, install record, head unit server, lock state, session, what is on the car screen. |
| `dhu.sh stop` | Quits the DHU, if this script started it. |
| `shot.sh` | Saves the car screen as a PNG. |
| `car-logs.sh` | Follows logcat filtered to Sierra and Android Auto's car-app tags; dump options print or save the buffer, including Android Auto's own state dump. |
| `build-pr.sh <number>` | Builds/tests a PR in a managed `.worktrees` child, signs with the local release key and can install on the phone. Requires explicit trust/authorization; failed release builds retain the tree for inspection. |
| `smoke.sh` | Install, DHU, open Sierra, screenshots and logs in a timestamped folder. Known false-PASS paths mean results need manual verification (below). |
| `workspace.sh check\|list\|new\|remove` | Canonical workspace check/inventory and safe linked-tree lifecycle, creating only `.worktrees` children. Does not authorize installs or application fixes. |

Read each script's header for its exact options. Explicit screenshot/log output paths must stay within canonical `.local`; relative filenames resolve against the shell's current directory (from the root, output belongs under `.local/runs`). Default/override paths reject symlink escapes.

## Next steps

Repair smoke mic-result propagation, missing-log PASS and capture privacy; path consolidation does not fix those defects. Add release-host and failure/lifecycle regressions, then record real parked phone/truck evidence using [TRUCK-TEST-PLAN.md](TRUCK-TEST-PLAN.md). The `workspace` GitHub workflow runs shellcheck, `bash scripts/workspace.sh check` and `bash scripts/test-workspace.sh` on `ubuntu-latest`; run the same locally.

Typical session:

```bash
source .local/omarchy-toolchain/env.sh
# Development session only: install changes the phone's source record.
# Use Play for normal truck updates; do not run without device-change approval.
scripts/install-phone.sh           # latest GitHub release (not Play installation)
scripts/dhu.sh start
scripts/dhu.sh tap 180 165         # Talk on the Home grid
scripts/shot.sh
scripts/dhu.sh stop
```

Or all at once: `scripts/smoke.sh`.

Screenshots, logs and built APKs go to canonical `.local/runs` (ignored, inside the project). Reviews live in `.local/reviews`, retained evidence in `.local/archive`. No linked worktree is retained; the unshipped installer experiment is preserved as tag `archive/install-record`. `workspace.sh remove` refuses changed/untracked or unfamiliar ignored files, preserves build reports/test-results/APKs under `.local/runs/worktree-NAME-DATE-ID` with a manifest and recovery ref `refs/sierra/worktrees/NAME/TIMESTAMP-ID`, and never force-deletes. Follow [WORKSPACE.md](WORKSPACE.md) for commands; do not restart archived integration scripts.

## DHU console commands

From the DHU's own `help` (DHU 2.0 linux, build 2022-03-30; captured in `.local/omarchy-validation/dhu-help.log`). Send them with `dhu.sh send`.

| Command | Effect |
|---|---|
| `tap <x> <y>` | Touch. (0, 0) is the top-left corner; the default screen is 800x480. |
| `screenshot <file.png>` | Save the car screen. Needs a connected session ("Don't have video focus" otherwise). |
| `mic play <file.wav>` | Play a WAV file as the car microphone. |
| `mic repeat` | Play the last WAV again. |
| `mic begin` | Press the voice button (keycode 84). |
| `mic reject on\|off` | Refuse microphone requests, to test that path. |
| `keycode back`, `keycode home`, `keycode search`, ... | Hardware keys. |
| `day`, `night` | Day/night sensor. |
| `restrict all`, `restrict none` | Driving restrictions (no keyboard, limited message length, ...). |
| `location <lat> <long> [accuracy] [altitude] [speed] [bearing]` | Set the car's location. |
| `speed`, `fuel`, `range`, `odometer`, ... | Other sensors; `help <command>` gives the arguments. |
| `sleep <seconds>` | Pause, for scripted sequences. |
| `help [command]`, `quit` | |

### Testing Talk without speaking

`mic play` feeds a recording into the car microphone, so the Talk screen can be tested with nobody talking:

```bash
scripts/dhu.sh tap 180 165                            # open Talk
scripts/dhu.sh send 'mic play ~/audio/question.wav'
scripts/shot.sh
```

`scripts/smoke.sh` can run the same sequence; see its header for skipping the install and feeding a WAV. The DHU's sample files in `<sdk>/extras/google/auto/voice` are 16 kHz, 16-bit, mono WAV; use the same format. This needs a DHU that `dhu.sh` started, and it exercises the host microphone path when selected. Automatic uses capture/transcription when configured and host-supported; explicit input selection also needs a transcription route. System-recognizer fallback is a different path.

## Building a PR onto the phone

```bash
scripts/build-pr.sh 24
```

A debug build has a different signature from the release on the phone, so it would not install over it: Android would demand an uninstall, and the agent settings would be lost. `build-pr.sh` therefore builds the PR as a release signed with the local copy of the release key, through the same four environment variables that `app/build.gradle.kts` reads in CI (`SIGNING_KEYSTORE_PATH`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`). The passwords are set only for the release build step and are never printed. The script then checks with `apksigner` that the APK is not debug-signed.

It builds with the versionCode already on the phone. With compatible signing/version this can replace the installed release, but installation rewrites source provenance. The version name may remain unchanged, so retain the actual commit/build output as identity evidence. A normal GitHub APK (110 at review) cannot replace Play code 100005 without a suitable version/signing plan; `install-phone.sh` is not an unconditional restore command. Prefer the intended Play channel after testing.

A build runs the PR's own Gradle scripts on this machine, the release step with the signing passwords in the environment. The script refuses PRs from forks unless an explicit trust option is given. For any PR you did not write, read its changes to `*.gradle.kts`, `gradle/` and `gradlew` first.

## Evidence: where to look

- **`car-logs.sh` host dump** is the best single source. `dumpsys activity service com.google.android.projection.gearhead` holds the last 500 lines of Android Auto's internal log, the validator state (`unknownSourcesEnabled=`, the `CarInfo` of the head unit) and what is in the foreground (`primaryRegionAppPackageName:`). It works with the phone locked and needs no log settings. The full dump names other apps on the phone; quote lines from it, do not post it whole.
- **`HostConfig: App: [com.mdc159.sierra/.car.SierraCarAppService] ...`** in logcat means the host bound to Sierra. This is the proof that the app opened.
- **`CAR.VALIDATOR`** logs `Package DENIED; <reason> [package]` when it rejects an app. `failed all other checks` is the install-source rejection. Historical DHU tests did not exercise this truck rejection boundary.
- **`CarApp.H.Tem: Error: ...`** is the template host reporting an error for the app. Seen here for a failed bind after an uninstall.
- **`CarApp.H.Tem: Host received new template (type: ...)`** is logged each time the app sends a screen. `smoke.sh` uses it to tell that a tap landed.
- Dump logs soon after the event. Twenty minutes later Android Auto's lines were gone from the logcat buffer here while everything around it was still there. Raising the `CAR.VALIDATOR`, `GH.AppNotifier` and `GH.ConnLoggerV2` log tags until the phone restarts adds the validator's debug lines; reset them afterwards.

## Pitfalls

- **The DHU quits when its stdin closes.** Started from a script with no console it exits at once. `dhu.sh start` runs it under a small background process that holds a pipe to its stdin, which is also how console commands reach it.
- **The scripts use the adb tunnel, not the `--usb` accessory transport.** The DHU runs on this machine through `.local/omarchy-toolchain/run-dhu.sh` with its bundled runtime libraries.
- **A DHU that `dhu.sh` did not start has no console.** The scripts leave such a DHU alone. `dhu.sh tap` and `shot.sh` still work with it, through its window: taps are posted to the window, but a screenshot fallback can capture other on-screen content. Inspect/redact the PNG before sharing; the old “nothing else captured” privacy claim was false. `send`, `screenshot` and `mic play` need a DHU started by `dhu.sh`; close the other one by hand first.
- **"Waiting for phone" with nothing in the phone's log** means the head unit server is stuck, usually after a DHU session died. On the phone: Android Auto settings, three-dot menu, **Stop head unit server**, then **Start head unit server**.
- **The DHU exits if it starts before the phone listens on 5277.** `dhu.sh start` checks first and prints what to tap.
- **Play Protect can stall an install.** It sometimes asks on the phone whether to send the app for scanning, and `adb install` waits for the answer for ever. On a locked phone the question is invisible. `install-phone.sh` reports this and cancels the waiting install after a bounded timeout.
- **`adb install -r` without `-i` resets the installer of record to none.** `install-phone.sh` always passes `-i com.android.vending`. The record cannot be set after the install; `pm set-installer` is refused.
- **A release older than the one on the phone will not install** (`INSTALL_FAILED_VERSION_DOWNGRADE`). Going back means uninstalling, which loses the settings.
- **The app grid can show a stale Sierra icon.** After an uninstall the DHU's launcher still drew it. Judge by whether Sierra opens and by the `HostConfig` line.
- **Uninstalling Sierra while it is on the car screen** leaves "com.mdc159.sierra has encountered an unexpected error" there. After an update (`install -r`) the host rebinds by itself and the screen comes back.
- **Icons can be wrong after an update under a live session.** After v0.1.19 was updated in place to v0.1.60 with the DHU connected, the Home grid drew a map pin for Talk, nothing for Places, a white square for Truck and the launcher mountain for History. The code asks for the right drawables. The pattern matches the host still using the old APK's resource table: v0.1.60 added `ic_history` and `ic_truck`, which shifts the drawable IDs, and each wrong icon is what the new ID meant in the old APK. This was not confirmed by restarting Android Auto, so treat it as the likely cause. Before filing an icon bug, restart Android Auto and the DHU session and look again.
- **Tap points go stale.** `smoke.sh` taps the points in `scripts/screens.json`. When the Home layout or the app grid changes, take a screenshot with `shot.sh`, read the new coordinates off it and update the file. The smoke run fails the step when a tap brought no new screen.
- **A minimised DHU window cannot be captured.** Taps still reach it, screenshots do not. With a DHU that `dhu.sh` started, `shot.sh` uses the DHU's own `screenshot` command instead.

## What the smoke test proves

The intended checks are installation, connected DHU/foreground app, host templates after taps, saved screenshots, continued foreground state and no recorded crash/host error. **A script PASS is not sufficient evidence:** the reviewed harness (now `smoke.sh`) discards the microphone command result before marking PASS, and a missing `car.log` can pass the no-crash check. Workspace path fixes do not repair these application/device-harness findings (#45).

Inspect `home.png`, `places.png`, `talk.png`, mic-command exit/result and the existence/time coverage of `car.log`. Missing evidence is BLOCKED/NOT RUN, not success. Fix propagation/capture privacy before relying on unattended smoke summaries. For actual physical acceptance use [TRUCK-TEST-PLAN.md](TRUCK-TEST-PLAN.md). No DHU, phone recording or truck run was performed for the 2026-11-20 platform documentation update.
