# Installing Sierra for the truck

**Written:** 2026-11-04. **Phone:** Galaxy A54 (SM-A546U1), Android 16, Android Auto 17.9, system
installer `com.google.android.packageinstaller` version `17-15481065`.

A real head unit lists Sierra only when Android says the install came from Google Play. This page
records how far that can be reached without a Play account. The research behind it is in
[UPDATES.md](UPDATES.md) (items 1 to 13) and in issue #36.

## Where this stands

**The wanted record has not been produced on the A54, and with the phone as it is it cannot be
by sideloading.** Two things on this phone block each other:

1. The system installer (version 17) keeps the "record Play as installer" request only when an
   **ordinary app** launches it. When the **shell** launches it (adb, Shizuku), the request is
   dropped. Measured.
2. The phone has a work profile (Microsoft Intune Company Portal). It sets the device-wide
   restriction `no_install_unknown_sources_globally`. With that set, the system installer refuses
   every launcher that is **not** privileged, which is every ordinary app, Sierra included. Measured
   as far as Settings and `dumpsys`; the refusal itself is read from the installer's code.

So the shell is allowed to install but loses the Play name, and an app would keep the Play name but
is not allowed to install. What is left is at the end of this page under "What the owner can do".

## The record to aim for

```
installerPackageName  = com.android.vending
initiatingPackageName = com.google.android.packageinstaller
```

Read it with `adb shell dumpsys package com.mdc159.sierra`, or on the phone under
Sierra → **Updates and install record**, which also prints a verdict line.

## What was measured on the A54 (2026-11-04)

| # | How it was installed | installer of record | initiator | Wanted? |
| --- | --- | --- | --- | --- |
| 1 | `adb install -r -i com.android.vending` | `com.android.vending` | `com.android.shell` | no |
| 2 | `adb shell am start … InstallStart` with the Play extra (command at the end), dialog confirmed on the phone | `com.google.android.packageinstaller` | `com.google.android.packageinstaller` | no |
| 3 | Sierra's own button **Install through the system installer (Play record)** | not reached | | |

Row 2 also wrote `originatingPackageName=com.android.shell`, `packageSource=3`, and this line in
the event log at the moment the dialog opened:

```
snet_event_log: [236687884,2000,Invalid EXTRA_INSTALLER_PACKAGE_NAME]
```

2000 is the shell's uid. That is the installer saying it removed the requested name.

Row 3: Sierra's check for "may I install apps" came back false and it opened Settings → Install
unknown apps → Sierra, where the switch reads **Allow permission, Disabled by admin** and cannot be
moved. `adb shell appops set com.mdc159.sierra REQUEST_INSTALL_PACKAGES allow` does not change that
(tried, then set back to default). `adb shell dumpsys user` shows why:

```
Device policy global restrictions:
  ensure_verify_apps
  no_install_unknown_sources_globally
```

and `dumpsys device_policy` names the profile owner of user 10 as
`com.microsoft.windowsintune.companyportal`.

## Why the shell loses the name

Android Auto's validator reads the *initiating* package first, and looks at the installer of record
only when the initiator is the system package installer (UPDATES.md item 8, issue #36 step 7). So
the system installer has to do the install, and it has to be told to record Play. It takes that
request from the extra `android.intent.extra.INSTALLER_PACKAGE_NAME` and decides whether to keep it
by looking at **who launched it**:

| Installer code | Keeps the name when the launcher | Launcher that works |
| --- | --- | --- |
| AOSP up to Android 16, and the old `InstallStart` class still inside version 17 | holds `INSTALL_PACKAGES` | the shell (adb, Shizuku) |
| Google package installer 17, `v2.model.InstallRepository.performPreInstallChecks` (this phone) | does **not** hold `INSTALL_PACKAGES` | an ordinary app |

The second row is the reverse of the first. Read from the APK pulled off the phone
(`/system/priv-app/GooglePackageInstaller`, `dexdump -d`): after
`PackageManager.checkPermission("android.permission.INSTALL_PACKAGES", callingPackage)` the new class
has `if-nez → keep`, the old class in the same APK has `if-eqz → keep`. The calling package is the
activity's launcher (`getLaunchedFromPackage()`), which for `am start` and for Shizuku is
`com.android.shell`. The new flow is used whenever the OS is newer than SDK 36.0; there is no switch.
UnknownAuto's source says the same ("some Android 17 builds have that check inverted") and retries
the other way.

The same method, a few lines earlier, is what stops an ordinary app on this phone: a launcher that
is neither privileged nor holding `INSTALL_PACKAGES` is checked against `no_install_apps`,
`no_install_unknown_sources` and `no_install_unknown_sources_globally`, and any of them ends the
install with "… set in place. Cannot install."

Because the shell is the launcher in both, **Install through Shizuku** and UnknownAuto's Shizuku
path give row 2 on this phone. UnknownAuto's own-app path is an ordinary app and is blocked like
Sierra.

## What is in the app and the script

- Sierra → Updates has a new button, **Install through the system installer (Play record)**. Sierra
  launches the system installer itself with the Play request. On a phone with installer 17 and no
  such policy this is the launch the code keeps the name for. **Not seen working anywhere yet.**
- The Updates screen now says when a device policy forbids installs, instead of sending the owner
  to a switch that cannot be moved, and its verdict line names the case where the system installer
  dropped the Play name.
- `scripts\install-truck.ps1` does the whole thing from the PC: adb install, then drives the
  Updates screen (Check, Download, the new button), confirms the system dialog and checks the
  record. It stops at once on a phone with the policy. On the A54 it was run as far as the
  download; the rest is untested.

## What is proven and what is not

- **Proven on the phone:** rows 1 and 2, the event log line, the "Disabled by admin" switch and the
  restriction in `dumpsys user`.
- **Read from code, not run:** that installer 17 keeps the name for an ordinary app; that it
  refuses an ordinary app under this policy; that Android Auto 17.9 accepts the wanted record and
  rejects rows 1 and 2 (issue #36). The Desktop Head Unit skips the check and cannot confirm any of
  it. **The truck is the only test.**
- **Not known:** whether the truck really hides Sierra with row 1. Android Auto also asks the Play
  Store whether it vouches for the package, and nobody has seen that answer for a sideloaded app.

## What the owner can do

In order of cost:

1. **Test the truck with what is on the phone now** (row 1). It costs nothing and it is the one
   thing nobody has measured. Force-stop Android Auto first:
   `adb shell am force-stop com.google.android.projection.gearhead`. A rejection reads
   `Package DENIED; failed all other checks [com.mdc159.sierra]` in
   `adb shell dumpsys activity service com.google.android.projection.gearhead`.
2. **Play internal testing** (UPDATES.md option a, 25 USD once). A real Play install gives installer
   and initiator `com.android.vending`, is not an unknown source, and so is not touched by the work
   profile's policy. This is the only route that works with the phone as it is.
3. **Remove the work profile, or have its admin lift "install from unknown sources"**, then run
   `scripts\install-truck.ps1`. Expected to give the wanted record, from the code; not measured.
4. **Use a phone without a work profile** for the truck.

## The shell launch, for the record

```
adb push sierra-auto.apk /sdcard/Download/sierra-auto.apk
adb shell content query --uri content://media/external/file --projection _id:_data --where "_data LIKE '%sierra-auto.apk'"
adb shell am start -n com.google.android.packageinstaller/com.android.packageinstaller.InstallStart \
  -a android.intent.action.INSTALL_PACKAGE -d content://media/external/file/<_id> \
  -t application/vnd.android.package-archive \
  --es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending \
  --ez android.intent.extra.NOT_UNKNOWN_SOURCE true --grant-read-uri-permission
```

A `file://` URI does not resolve. On an installer older than 17 this is expected to give the wanted
record; on the A54 it gives row 2.
