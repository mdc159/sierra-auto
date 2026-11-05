# Testing on the real phone

Scripts for testing Sierra on the phone with the Android Auto Desktop Head Unit (DHU), from a Windows PC. They live in `scripts/` and need PowerShell 7.

Cloud sessions cannot run these. They exist so that whoever is at the PC can install a build, drive the car screen and collect evidence with one command each.

## Read this first: a DHU pass is not a truck pass

Android Auto does not check where an app was installed from when the head unit is the DHU. On the truck it does. Sierra opened on the DHU with every install source tried, including none. The findings and the evidence are in [issue #36](https://github.com/mdc159/sierra-auto/issues/36); `docs/UPDATES.md` covers what to do about it.

So these scripts can show that a build installs, starts, draws its screens and does not crash. They cannot show that the truck will list it.

## Setup

One time, on the PC:

| Need | Where the scripts look |
|---|---|
| Android SDK with `platform-tools` and the DHU (`sdkmanager "extras;google;auto"`) | `%SIERRA_TOOLCHAIN%\android-sdk`, else `ANDROID_HOME` |
| JDK 17 (only for `build-pr.ps1`) | `%SIERRA_TOOLCHAIN%\jdk-17`, else `JAVA_HOME` |
| GitHub CLI `gh`, signed in | `PATH` |
| Release keystore and passwords (only for `build-pr.ps1`) | `%USERPROFILE%\.config\sierra-auto\release.jks` and `signing.env` |

`SIERRA_TOOLCHAIN` defaults to `D:\Projects\android-toolchain`. Other settings: `SIERRA_SERIAL` (adb serial, when more than one device is attached), `SIERRA_RUNS` (output folder, default `%USERPROFILE%\sierra-auto-runs`), `SIERRA_STATE` (DHU session state, default `%LOCALAPPDATA%\sierra-auto\dhu`).

`signing.env` holds three lines, `SIGNING_STORE_PASSWORD=`, `SIGNING_KEY_ALIAS=` and `SIGNING_KEY_PASSWORD=`. Neither file belongs in any repo.

One time, on the phone:

1. Turn on USB debugging and accept the PC's key.
2. Turn on Android Auto developer mode (in Android Auto settings, tap the version row ten times).
3. In Android Auto settings, open the three-dot menu and tap **Start head unit server**. The server is a service inside Android Auto that is not exported, so adb cannot start it; only this menu can.

**Keep the phone unlocked while testing.** A locked phone keeps an existing DHU session alive, but nobody can start the head unit server, answer a Play Protect question or change an Android Auto setting on it.

## The scripts

| Script | What it does |
|---|---|
| `install-phone.ps1` | Installs the latest release, `-Tag v0.1.N`, or `-Apk file`, with Play as installer of record. Prints version and install record. |
| `dhu.ps1 start` | Forwards port 5277, checks the phone's head unit server, starts the DHU with its console held open, waits until the session is connected and Sierra is offered. |
| `dhu.ps1 send <command>` | Sends one DHU console command and prints the answer. |
| `dhu.ps1 tap <x> <y>` | Taps the car screen. |
| `dhu.ps1 status` | DHU, install record, head unit server, lock state, session, what is on the car screen. |
| `dhu.ps1 stop` | Quits the DHU, if this script started it. |
| `shot.ps1 [-Out file.png]` | Saves the car screen as a PNG. |
| `car-logs.ps1` | Follows logcat filtered to Sierra and Android Auto's car-app tags. `-Dump` prints the buffer. `-HostDump` saves Android Auto's own state dump. |
| `build-pr.ps1 <number>` | Builds a PR in a throwaway worktree, runs its unit tests, signs a release build with the local key and installs it over the Sierra on the phone. |
| `smoke.ps1` | Install, DHU, open Sierra, screenshots of Home, Places and Talk, and the logs, into a timestamped folder. |

Each script has `Get-Help scripts\<name>.ps1 -Full`.

Typical session:

```powershell
scripts\install-phone.ps1            # latest release
scripts\dhu.ps1 start
scripts\dhu.ps1 tap 180 165          # Talk on the Home grid
scripts\shot.ps1
scripts\car-logs.ps1 -Dump -Since '11-04 18:30:00.000'
scripts\dhu.ps1 stop
```

Or all at once: `scripts\smoke.ps1`.

Screenshots, logs and built APKs go to the runs folder, outside the repo.

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

or `scripts\smoke.ps1 -SkipInstall -MicWav C:\audio\question.wav`. The DHU's sample files in `<sdk>\extras\google\auto\voice` are 16 kHz, 16-bit, mono WAV; use the same format. This needs a DHU that `dhu.ps1` started, and it exercises the car-microphone path, which Sierra uses only when a transcription URL is set.

## Building a PR onto the phone

```powershell
scripts\build-pr.ps1 24
```

A debug build has a different signature from the release on the phone, so it would not install over it: Android would demand an uninstall, and the agent settings would be lost. `build-pr.ps1` therefore builds the PR as a release signed with the local copy of the release key, through the same four environment variables that `app/build.gradle.kts` reads in CI (`SIGNING_KEYSTORE_PATH`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`). The passwords are set only for the release build step and are never printed. The script then checks with `apksigner` that the APK is not debug-signed.

It builds with the versionCode already on the phone. The PR build installs over the release, and the next release still installs over the PR build. The version name on the phone does not change, so check `build-pr.ps1`'s output, not the phone, to know what is installed. `install-phone.ps1` puts the latest release back.

A build runs the PR's own Gradle scripts on this PC, the release step with the signing passwords in the environment. The script refuses PRs from forks unless `-TrustFork` is given. For any PR you did not write, read its changes to `*.gradle.kts`, `gradle/` and `gradlew` first.

## Evidence: where to look

- **`car-logs.ps1 -HostDump`** is the best single source. `dumpsys activity service com.google.android.projection.gearhead` holds the last 500 lines of Android Auto's internal log, the validator state (`unknownSourcesEnabled=`, the `CarInfo` of the head unit) and what is in the foreground (`primaryRegionAppPackageName:`). It works with the phone locked and needs no log settings. The full dump names other apps on the phone; quote lines from it, do not post it whole.
- **`HostConfig: App: [com.mdc159.sierra/.car.SierraCarAppService] ...`** in logcat means the host bound to Sierra. This is the proof that the app opened.
- **`CAR.VALIDATOR`** logs `Package DENIED; <reason> [package]` when it rejects an app. `failed all other checks` is the install-source rejection. The DHU never produces it.
- **`CarApp.H.Tem: Error: ...`** is the template host reporting an error for the app. Seen here for a failed bind after an uninstall.
- **`CarApp.H.Tem: Host received new template (type: ...)`** is logged each time the app sends a screen. `smoke.ps1` uses it to tell that a tap landed.
- Dump logs soon after the event. Twenty minutes later Android Auto's lines were gone from the logcat buffer here while everything around them was still there. `car-logs.ps1 -VerboseTags` raises `CAR.VALIDATOR`, `GH.AppNotifier` and `GH.ConnLoggerV2` until the phone restarts, which adds the validator's debug lines; `-ResetTags` puts them back.

## Pitfalls

- **The DHU quits when its stdin closes.** Started from a script with no console it exits at once. `dhu.ps1 start` runs it under a small background process that holds a pipe to its stdin, which is also how console commands reach it.
- **`desktop-head-unit.exe --usb` does not work on this PC.** The scripts use the adb tunnel.
- **A DHU that `dhu.ps1` did not start has no console.** The scripts leave such a DHU alone. `dhu.ps1 tap` and `shot.ps1` still work with it, through its window: taps are posted to the window and the picture is the window's own client area, so the mouse does not move and nothing else on the desktop is captured. `send`, `screenshot` and `mic play` need a DHU started by `dhu.ps1`; close the other one by hand first.
- **"Waiting for phone" with nothing in the phone's log** means the head unit server is stuck, usually after a DHU session died. On the phone: Android Auto settings, three-dot menu, **Stop head unit server**, then **Start head unit server**.
- **The DHU exits if it starts before the phone listens on 5277.** `dhu.ps1 start` checks first and prints what to tap.
- **Play Protect can stall an install.** It sometimes asks on the phone whether to send the app for scanning, and `adb install` waits for the answer for ever. On a locked phone the question is invisible. `install-phone.ps1` reports this, waits up to `-TimeoutSec` and then cancels the waiting install.
- **`adb install -r` without `-i` resets the installer of record to none.** `install-phone.ps1` always passes `-i com.android.vending`. The record cannot be set after the install; `pm set-installer` is refused.
- **A release older than the one on the phone will not install** (`INSTALL_FAILED_VERSION_DOWNGRADE`). Going back means uninstalling, which loses the settings.
- **The app grid can show a stale Sierra icon.** After an uninstall the DHU's launcher still drew it. Judge by whether Sierra opens and by the `HostConfig` line.
- **Uninstalling Sierra while it is on the car screen** leaves "com.mdc159.sierra has encountered an unexpected error" there. After an update (`install -r`) the host rebinds by itself and the screen comes back.
- **Tap points go stale.** `smoke.ps1` taps the points in `scripts/screens.json`. When the Home layout or the app grid changes, take a screenshot with `shot.ps1`, read the new coordinates off it and update the file. The smoke run fails the step when a tap brought no new screen.
- **A minimised DHU window cannot be captured.** Taps still reach it, screenshots do not. With a DHU that `dhu.ps1` started, `shot.ps1` uses the DHU's own `screenshot` command instead.

## What the smoke test proves

`smoke.ps1` passes when: the install succeeded with Play as installer of record, the DHU session is connected, Android Auto reports Sierra in the foreground, the template host logged a new template after the Places tap and after the Talk tap, all three screenshots were saved, Sierra is still in the foreground at the end, and the log has no crash and no template host error.

It does not read the screenshots. Open `home.png`, `places.png` and `talk.png` and look.
