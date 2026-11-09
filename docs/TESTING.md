# Testing on the real phone

Scripts for testing Sierra on the phone with the Android Auto Desktop Head Unit (DHU), from a Windows PC. They live in `scripts/` and need PowerShell 7.

These require an authorized local Windows/device environment; cloud-only tests cannot emulate the physical routing. Baseline **2026-11-08, main `210448b`**: see [STATUS.md](STATUS.md) for the review's 569 Android tests (567 passed/2 skipped), lint warnings and four native-Windows bridge failures. Consolidation then reran `assembleDebug testDebugUnitTest lintDebug` in the canonical root at `210448b`: exit 0, the same 569 tests/2 skips and lint 0 errors/124 warnings, with live providers disabled and no install. Evidence: `.local/runs/consolidation-20261108/{android-build.log,android-result.json,test-summary.json}`. Bridge figures remain the earlier review run, not a new bridge pass. Workspace-path consolidation is separate from the unchanged application baseline.

## Read this first: a DHU pass is not a truck pass

Historical DHU sessions opened Sierra with every install source tried, including none ([issue #36](https://github.com/mdc159/sierra-auto/issues/36)); that observation is not a platform guarantee for every host/version. Play internal testing is now operational. The November 8 phone record was installer Play / initiator shell, not proven direct Play provenance; actual truck launch was not tested. [UPDATES.md](UPDATES.md) explains the record heuristic and preferred Play channel.

So these scripts can show that a build installs, starts, draws its screens and does not crash. They cannot show that the truck will list it.

## Setup

One time, on the PC:

| Need | Where the scripts look |
|---|---|
| Android SDK with `platform-tools` and the DHU (`sdkmanager "extras;google;auto"`) | `%SIERRA_TOOLCHAIN%\android-sdk`, else `ANDROID_HOME` |
| JDK 17 (only for `build-pr.ps1`) | `%SIERRA_TOOLCHAIN%\jdk-17`, else `JAVA_HOME` |
| GitHub CLI `gh`, signed in | `PATH` |
| Release keystore and passwords (only for `build-pr.ps1`) | `%USERPROFILE%\.config\sierra-auto\release.jks` and `signing.env` |

`SIERRA_TOOLCHAIN` defaults to external `D:\Projects\android-toolchain`; `SIERRA_SERIAL` selects adb serial when needed. Consolidated workspace defaults resolve the **canonical Git common-dir root**, even when invoked from a linked tree: `SIERRA_RUNS` defaults to `<root>/.local/runs`, `SIERRA_STATE` to `<root>/.local/state/dhu`, release downloads to `<root>/.local/downloads/releases`. Runs/state overrides must remain under canonical **`.local`**, not merely somewhere under root or in a linked tree. All project output stays under `D:\Projects\sierra-auto`; the shared toolchain and credential directory are intentional external dependencies.

`signing.env` holds three lines, `SIGNING_STORE_PASSWORD=`, `SIGNING_KEY_ALIAS=` and `SIGNING_KEY_PASSWORD=`. Neither file belongs in any repo.

One time, on the phone:

1. Turn on USB debugging and accept the PC's key.
2. Turn on Android Auto developer mode (in Android Auto settings, tap the version row ten times).
3. In Android Auto settings, open the three-dot menu and tap **Start head unit server**. The server is a service inside Android Auto that is not exported, so adb cannot start it; only this menu can.

**Keep the phone unlocked while testing.** A locked phone keeps an existing DHU session alive, but nobody can start the head unit server, answer a Play Protect question or change an Android Auto setting on it.

## The scripts

| Script | What it does |
|---|---|
| `install-phone.ps1` | Development install of latest GitHub release, `-Tag v0.1.N`, or `-Apk file`; requests Play as installer of record but remains shell-initiated. Prints version/record; does not prove Play provenance. |
| `dhu.ps1 start` | Forwards port 5277, checks the phone's head unit server, starts the DHU with its console held open, waits until the session is connected and Sierra is offered. |
| `dhu.ps1 send <command>` | Sends one DHU console command and prints the answer. |
| `dhu.ps1 tap <x> <y>` | Taps the car screen. |
| `dhu.ps1 status` | DHU, install record, head unit server, lock state, session, what is on the car screen. |
| `dhu.ps1 stop` | Quits the DHU, if this script started it. |
| `shot.ps1 [-Out file.png]` | Saves the car screen as a PNG. |
| `car-logs.ps1` | Follows logcat filtered to Sierra and Android Auto's car-app tags. `-Dump` prints the buffer. `-HostDump` saves Android Auto's own state dump. |
| `build-pr.ps1 <number>` | Builds/tests a PR in a managed `.worktrees` child, signs with the local release key and can install on the phone. Requires explicit trust/authorization; failed release builds retain the tree for inspection. |
| `smoke.ps1` | Install, DHU, open Sierra, screenshots and logs in a timestamped folder. Known false-PASS paths mean results need manual verification (below). |
| `workspace.ps1 check\|list\|new\|remove` | Canonical workspace check/inventory and safe linked-tree lifecycle, creating only `.worktrees` children. Does not authorize installs or application fixes. |

Each script has `Get-Help scripts\<name>.ps1 -Full`. Explicit screenshot/log `-Out` paths must also be within canonical `.local`; relative filenames resolve against the shell's current directory, e.g. `scripts\shot.ps1 -Out .local/runs/home.png` from root. Default/override paths reject symlink/junction escapes.

## Next steps

Repair smoke mic-result propagation, missing-log PASS and overlapping-desktop capture; path consolidation does not fix those defects. Add release-host and failure/lifecycle regressions, make Windows bridge fixtures portable, then record real parked phone/truck evidence using [TRUCK-TEST-PLAN.md](TRUCK-TEST-PLAN.md). `.github/workflows/workspace.yml` adds Windows parser/lifecycle checks for workspace changes; local checks were performed, but the new workflow has not yet run on GitHub.

Typical session:

```powershell
# Development session only: install changes the phone's source record.
# Use Play for normal truck updates; do not run without device-change approval.
scripts\install-phone.ps1            # latest GitHub release (not Play installation)
scripts\dhu.ps1 start
scripts\dhu.ps1 tap 180 165          # Talk on the Home grid
scripts\shot.ps1
scripts\car-logs.ps1 -Dump -Since '11-04 18:30:00.000'
scripts\dhu.ps1 stop
```

Or all at once: `scripts\smoke.ps1`.

Screenshots, logs and built APKs go to canonical `.local/runs` (ignored, inside the project). Reviews live in `.local/reviews`, retained evidence in `.local/archive`. The consolidated linked tree is `.worktrees/install-record`; it is an unshipped installer experiment, not an active release tree. `workspace.ps1 remove` refuses changed/untracked or unfamiliar ignored files, preserves build reports/test-results/APKs under `.local/runs/worktree-NAME-DATE-ID` with a manifest and recovery ref `refs/sierra/worktrees/NAME/TIMESTAMP-ID`, and never force-deletes. Follow [WORKSPACE.md](WORKSPACE.md) for commands; do not restart archived integration scripts.

## DHU console commands

From the DHU's own `help` (DHU 2.0, build 2022-03-30). Send them with `dhu.ps1 send`.

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

```powershell
scripts\dhu.ps1 tap 180 165                      # open Talk
scripts\dhu.ps1 send mic play C:\audio\question.wav
scripts\shot.ps1
```

or `scripts\smoke.ps1 -SkipInstall -MicWav C:\audio\question.wav`. The DHU's sample files in `<sdk>\extras\google\auto\voice` are 16 kHz, 16-bit, mono WAV; use the same format. This needs a DHU that `dhu.ps1` started, and it exercises the host microphone path when selected. Automatic uses capture/transcription when configured and host-supported; explicit input selection also needs a transcription route. System-recognizer fallback is a different path.

## Building a PR onto the phone

```powershell
scripts\build-pr.ps1 24
```

A debug build has a different signature from the release on the phone, so it would not install over it: Android would demand an uninstall, and the agent settings would be lost. `build-pr.ps1` therefore builds the PR as a release signed with the local copy of the release key, through the same four environment variables that `app/build.gradle.kts` reads in CI (`SIGNING_KEYSTORE_PATH`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`). The passwords are set only for the release build step and are never printed. The script then checks with `apksigner` that the APK is not debug-signed.

It builds with the versionCode already on the phone. With compatible signing/version this can replace the installed release, but installation rewrites source provenance. The version name may remain unchanged, so retain the actual commit/build output as identity evidence. A normal GitHub APK (110 at review) cannot replace Play code 100005 without a suitable version/signing plan; `install-phone.ps1` is not an unconditional restore command. Prefer the intended Play channel after testing.

A build runs the PR's own Gradle scripts on this PC, the release step with the signing passwords in the environment. The script refuses PRs from forks unless `-TrustFork` is given. For any PR you did not write, read its changes to `*.gradle.kts`, `gradle/` and `gradlew` first.

## Evidence: where to look

- **`car-logs.ps1 -HostDump`** is the best single source. `dumpsys activity service com.google.android.projection.gearhead` holds the last 500 lines of Android Auto's internal log, the validator state (`unknownSourcesEnabled=`, the `CarInfo` of the head unit) and what is in the foreground (`primaryRegionAppPackageName:`). It works with the phone locked and needs no log settings. The full dump names other apps on the phone; quote lines from it, do not post it whole.
- **`HostConfig: App: [com.mdc159.sierra/.car.SierraCarAppService] ...`** in logcat means the host bound to Sierra. This is the proof that the app opened.
- **`CAR.VALIDATOR`** logs `Package DENIED; <reason> [package]` when it rejects an app. `failed all other checks` is the install-source rejection. Historical DHU tests did not exercise this truck rejection boundary.
- **`CarApp.H.Tem: Error: ...`** is the template host reporting an error for the app. Seen here for a failed bind after an uninstall.
- **`CarApp.H.Tem: Host received new template (type: ...)`** is logged each time the app sends a screen. `smoke.ps1` uses it to tell that a tap landed.
- Dump logs soon after the event. Twenty minutes later Android Auto's lines were gone from the logcat buffer here while everything around them was still there. `car-logs.ps1 -VerboseTags` raises `CAR.VALIDATOR`, `GH.AppNotifier` and `GH.ConnLoggerV2` until the phone restarts, which adds the validator's debug lines; `-ResetTags` puts them back.

## Pitfalls

- **The DHU quits when its stdin closes.** Started from a script with no console it exits at once. `dhu.ps1 start` runs it under a small background process that holds a pipe to its stdin, which is also how console commands reach it.
- **`desktop-head-unit.exe --usb` does not work on this PC.** The scripts use the adb tunnel.
- **A DHU that `dhu.ps1` did not start has no console.** The scripts leave such a DHU alone. `dhu.ps1 tap` and `shot.ps1` still work with it, through its window: taps are posted to the window, but screenshot fallback can copy visible desktop pixels, including overlapping windows. Inspect/redact the PNG before sharing; the old “nothing else captured” privacy claim was false. `send`, `screenshot` and `mic play` need a DHU started by `dhu.ps1`; close the other one by hand first.
- **"Waiting for phone" with nothing in the phone's log** means the head unit server is stuck, usually after a DHU session died. On the phone: Android Auto settings, three-dot menu, **Stop head unit server**, then **Start head unit server**.
- **The DHU exits if it starts before the phone listens on 5277.** `dhu.ps1 start` checks first and prints what to tap.
- **Play Protect can stall an install.** It sometimes asks on the phone whether to send the app for scanning, and `adb install` waits for the answer for ever. On a locked phone the question is invisible. `install-phone.ps1` reports this, waits up to `-TimeoutSec` and then cancels the waiting install.
- **`adb install -r` without `-i` resets the installer of record to none.** `install-phone.ps1` always passes `-i com.android.vending`. The record cannot be set after the install; `pm set-installer` is refused.
- **A release older than the one on the phone will not install** (`INSTALL_FAILED_VERSION_DOWNGRADE`). Going back means uninstalling, which loses the settings.
- **The app grid can show a stale Sierra icon.** After an uninstall the DHU's launcher still drew it. Judge by whether Sierra opens and by the `HostConfig` line.
- **Uninstalling Sierra while it is on the car screen** leaves "com.mdc159.sierra has encountered an unexpected error" there. After an update (`install -r`) the host rebinds by itself and the screen comes back.
- **Icons can be wrong after an update under a live session.** After v0.1.19 was updated in place to v0.1.60 with the DHU connected, the Home grid drew a map pin for Talk, nothing for Places, a white square for Truck and the launcher mountain for History. The code asks for the right drawables. The pattern matches the host still using the old APK's resource table: v0.1.60 added `ic_history` and `ic_truck`, which shifts the drawable IDs, and each wrong icon is what the new ID meant in the old APK. This was not confirmed by restarting Android Auto, so treat it as the likely cause. Before filing an icon bug, restart Android Auto and the DHU session and look again.
- **Tap points go stale.** `smoke.ps1` taps the points in `scripts/screens.json`. When the Home layout or the app grid changes, take a screenshot with `shot.ps1`, read the new coordinates off it and update the file. The smoke run fails the step when a tap brought no new screen.
- **A minimised DHU window cannot be captured.** Taps still reach it, screenshots do not. With a DHU that `dhu.ps1` started, `shot.ps1` uses the DHU's own `screenshot` command instead.

## What the smoke test proves

The intended checks are installation, connected DHU/foreground app, host templates after taps, saved screenshots, continued foreground state and no recorded crash/host error. **A script PASS is not sufficient evidence:** the reviewed `smoke.ps1` discards the microphone command result before marking PASS, and missing `car.log` can pass the no-crash check. Workspace path fixes do not repair these application/device-harness findings (#45).

Inspect `home.png`, `places.png`, `talk.png`, mic-command exit/result and the existence/time coverage of `car.log`. Missing evidence is BLOCKED/NOT RUN, not success. Fix propagation/capture privacy before relying on unattended smoke summaries. For actual physical acceptance use [TRUCK-TEST-PLAN.md](TRUCK-TEST-PLAN.md). No DHU, phone recording or truck run was performed for this docs consolidation.
