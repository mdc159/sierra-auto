# Updating Sierra away from the PC

**Written:** 2026-11-04 (lane D). **Phone:** Galaxy A54, Android 16, Android Auto 17.9. **App:** Car App Library (`androidx.car.app`) app, category POI, package `com.mdc159.sierra`.

The owner wants to install new builds while travelling, with no PC. This page records what Android Auto checks before it lists a Car App Library app, compares four ways to get a new build onto the phone, recommends one, and documents the one-time Play Console setup for `.github/workflows/play.yml`.

Every claim is marked **confirmed** (Google documentation or AOSP source) or **inferred** (third-party decompiling, community testing, or my own reading). Treat inferred items as hypotheses to test on the truck.

---

## 1. What Android Auto checks before it shows a Car App Library app

### Confirmed

1. **The "Unknown sources" developer option does not cover Car App Library apps.** Google: "Android Auto has a developer option that lets you run apps that aren't installed from a trusted source. This setting applies to media, messaging notifications, and parked apps but doesn't apply to apps built using the Android for Cars App Library." The same page: "To test your app in real vehicles, you must install it from a trusted source such as Google Play," and suggests Internal App Sharing or an Internal Test Track. ([testing])
2. **Android keeps two names for how an app was installed.** `InstallSourceInfo.getInstallingPackageName()` is "the installer of record" and "can be modified via `PackageManager.setInstallerPackageName`". `getInitiatingPackageName()` is "the package that requested the installation" and "continues to identify the actual package that performed the install or update" even when the installer of record is changed. For an app installed over adb the installing package may be null. ([installsource])
3. **Only privileged installers can claim another installer name.** `SessionParams.setInstallerPackageName`: "Only applications with `INSTALL_PACKAGES` permission are allowed to set an installer that is not the caller's own installer package name, otherwise it will cause a `SecurityException`." ([sessionparams])
4. **Shell installs are recorded as the shell.** In AOSP `PackageInstallerService.createSessionInternal`, when the caller is root or shell the session's initiating name is forced to `com.android.shell` (`installerPackageName = SHELL_PACKAGE_NAME`), and a different *requested* installer name is accepted only with `INSTALL_PACKAGES` (the shell has it). The result is stored with `InstallSource.create(initiatingPackageName, originatingPackageName, installerPackageName, …)`. So `adb install -i com.android.vending` gives installing = Play, initiating = shell. ([pis], [installsourcejava])
5. **The system package installer strips a requested installer name from unprivileged callers.** `InstallStart.onCreate` reads `EXTRA_INSTALLER_PACKAGE_NAME`; if it differs from the calling package and the caller lacks `INSTALL_PACKAGES`, the extra is removed (lines ~157-168). The install continues. ([installstart])
6. **Any session install rewrites the record.** In `InstallPackageHelper`, "For standard install (install via session), the installSource isn't null" and it is stored with `pkgSetting.setInstallSource(installSource)` on every install, including updates. There is no branch that keeps the old installer when the new session has one. ([iph])
7. **`setPackageSource` is informational.** "This is informational and may be used as a signal by the system." Values: unspecified, store, local file, downloaded file, other. ([sessionparams])

### Inferred

8. **The validator and its order (decompiled Android Auto 17.7.666914, `CAR.VALIDATOR` log tag).** A Fermata contributor reports: the app must exist and be enabled; on Android 11+ the validator reads the *initiating* package and uses the installing package only when the initiator is `com.google.android.packageinstaller`; a server block list is checked; `automotive_app_desc.xml` must declare the type ("Uses for TEMPLATE not defined" otherwise); **a developer head unit (for example "Desktop Head Unit") skips all later installer checks** when a server flag is on; then the app is allowed if a server override, a Google first-party package, an installer on a server list, a cached prior validation, Play vouching (`Finsky.IsValid`), or installer `com.android.vending` with a Google-signed Play Store applies; then a server allowlist; then "Unknown sources", which admits MEDIA, NAVIGATION, NOTIFICATION, SMS, SERVICE, OEM and native apps but **not PROJECTION, TEMPLATE or MESSAGING**; otherwise "Package DENIED; failed all other checks". ([fermata])
9. **What a working record looks like.** aa-torque measured three records on an Android 16 emulator: plain `adb install` gives installer null and initiator `com.android.shell`; `adb install -i com.android.vending` gives installer Play and initiator `com.android.shell`; KingInstaller through Shizuku gives installer `com.android.vending` and initiator `com.google.android.packageinstaller`. Only the last one made Android Auto serve a real `sdk_impl.jar` to that (legacy projection) app. Their conclusion: "Both fields matter, and only the package installer can set the initiator." ([aatorque70], [aatorque126])
10. **UnknownAuto's acceptance rule.** Its source checks `VENDING.equals(installer) && (VENDING.equals(initiating) || initiating.contains("packageinstaller"))`, and treats the shell as initiator as always rejected. It sets the record by running, as the shell through Shizuku, `ActivityTaskManager.startActivity` with calling package `com.android.shell` to open the system installer's `InstallStart` with `EXTRA_INSTALLER_PACKAGE_NAME=com.android.vending` and `EXTRA_NOT_UNKNOWN_SOURCE`. On Android 14 and older the installer must be started with UnknownAuto as the result target; on 15+ no result target is needed. Some Android 17 builds keep the mark only when the app, not the shell, starts the installer, so it retries the other way once. ([unknownauto], [unknownauto-src])
11. **Tonight's DHU observation proves less than it seems.** The lane brief says an APK installed with `adb install -i com.android.vending` appears in Android Auto on the Desktop Head Unit. Items 4, 8 and 9 together say that record has initiator `com.android.shell` and that the DHU skips the installer checks. So the DHU result is expected regardless of installer, and the truck is expected to hide the app. **Unverified on the Sierra's head unit.**
12. **No signature or licence check beyond Play's own.** Nobody reports a certificate check on the app itself, apart from the Play Store being Google-signed and the server allowlist being tied to Google certificates. Play vouching (`Finsky.IsValid`) exists but its inputs are unknown. ([fermata])
13. **Samsung's package installer.** One UI ships its own installer fork. UnknownAuto's `contains("packageinstaller")` rule suggests the validator accepts OEM forks as initiator, but the decompile quotes only the Google name. **Unverified on the A54.**

### Sources I could not reach from the sandbox

`support.google.com` (Play Console help on internal testing and internal app sharing) and `shizuku.rikka.app` did not resolve from this session, so the Play tester limits and Shizuku setup steps below come from search snippets of those pages and from Shizuku's GitHub README. They need to be allowed in the session's network settings to be checked directly.

---

## 2. The options

Costs are one-time unless stated. "Record" means the install record from section 1.

### (a) Play Console internal testing, published from CI — recommended

| | |
| --- | --- |
| **Record** | Installer `com.android.vending`, initiator `com.android.vending`. The only route Google documents for real vehicles ([testing]). Every later update through Play keeps it (item 6 rewrites the record, but Play is the one writing). |
| **One-time setup** | Play developer account (25 USD, identity verification; a personal account created after 13 Nov 2023 needs a 12-tester, 14-day closed test only before *production* access, which we never need). Create the app, enroll in Play App Signing and **upload the existing release key as the app signing key** so the Play build has the same certificate as the GitHub build. Upload one bundle by hand, create an internal testing release, add the owner's Google account as a tester, open the opt-in link on the phone. Google Cloud service account invited into Play Console; its JSON key becomes the `PLAY_SERVICE_ACCOUNT_JSON` secret. Full steps in section 4. |
| **Cost** | 25 USD once. Nothing per update. |
| **Per update** | Actions → **play** → Run workflow. Wait a few minutes for Play to process. On the phone, Play Store → Manage apps → Update (or wait for auto-update). No PC, no cable, no Shizuku. |
| **Risk** | New apps must upload an App Bundle, not an APK ([aab-required]); `play.yml` builds `bundleRelease`. The Play build gets `versionCode = 100000 + run number`, so it always outranks a GitHub release and the two never collide. Internal testing publishes without full review per community reports, but the app still has to pass basic console checks (store listing, data safety form, target API). If Google ever rejects a POI app that contains a chat agent (quality rule PC-1), internal testing is "still the same app and the same policy" ([aatorque128]); that is the main unknown. Play App Signing with the uploaded key is irreversible, so keep the key safe. |

### (b) Shizuku install that records Play as installer

| | |
| --- | --- |
| **Record** | Installer `com.android.vending`, initiator the system package installer (item 9). Accepted on the phones the two projects tested; not yet tested on the A54. |
| **One-time setup** | Install Shizuku from Play. Developer options → Wireless debugging → pair Shizuku once with the pairing code. Install UnknownAuto (GitHub, free, Apache-licensed) **or** use Sierra's own **Install through Shizuku** button in Updates. Grant Sierra (or UnknownAuto) the Shizuku permission on first use. |
| **Cost** | Free. |
| **Per update** | After every reboot: turn Wireless debugging on, open Shizuku, tap Start (the pairing is kept). Then Updates → Check → Download → **Install through Shizuku** → confirm the system dialog → come back and read the record. Wi-Fi must be on for Wireless debugging; a hotspot from another device works. |
| **Risk** | Shizuku stops at every reboot, so an update needs three or four taps of setup first. The mechanism is undocumented and version-dependent: UnknownAuto already needed an Android 17 workaround, and Android 14 and older need a different launch shape (not implemented in Sierra; the A54 is on 16). Samsung's installer fork is unverified (item 13). Play Protect may ask to scan. |

### (c) Wireless debugging from the phone itself

| | |
| --- | --- |
| **Record** | Installer whatever `-i` says, initiator `com.android.shell` (item 4). **Expected to be rejected** on a real head unit (items 8 and 9). |
| **One-time setup** | A terminal with an adb client on the phone (Termux with the `android-tools` package, or an app such as LADB), paired to the phone's own Wireless debugging. |
| **Cost** | Free. |
| **Per update** | Enable Wireless debugging, connect adb to `localhost`, download the APK, `adb install -r -i com.android.vending sierra-auto.apk`. |
| **Risk** | Same record as tonight's USB test, which only passes because the DHU skips the check. Fiddly on the road. Not recommended. |

### (d) In-app updater through PackageInstaller sessions (built, in Updates → Install)

| | |
| --- | --- |
| **Record** | Installer `com.mdc159.sierra`, initiator `com.mdc159.sierra`. **Loses the Play record** on every use: item 6 says the session's source replaces the stored one, and item 3 says Sierra cannot claim Play as installer. |
| **One-time setup** | Settings → Install unknown apps → allow Sierra (the Updates screen has a button for it). |
| **Cost** | Free. |
| **Per update** | Updates → Check → Download → Install → confirm. Once Sierra is its own installer of record, later updates can be silent (`UPDATE_PACKAGES_WITHOUT_USER_ACTION`). |
| **Risk** | Only useful where the record does not matter: on the DHU, or for phone-side testing. On a Play-installed app on Android 14+ the dialog warns that the update comes from a different source. After a Play move, Play builds have higher versionCodes, so the Updates screen reports GitHub as older and does not offer a downgrade. |

### Recommendation

**Move to (a), Play internal testing, as the update channel.** It is the only path Google documents, it needs nothing but the Play Store on the road, and it keeps the record on every update without any trick. The 25 USD and an evening of console setup are the whole cost.

**Until the Play account exists, use (b)** with UnknownAuto (proven on other phones) or Sierra's own Shizuku button (same mechanism, unverified on the A54). Read the record on the Updates screen after each install and keep the Shizuku pairing.

**Keep (d) for the DHU and for phone-side checks.** It is the plain "download a release and install it" path and it tells you, on screen, that it changed the record. (c) is not worth the effort.

Finally, **test the truck once with each record** (adb-with-Play-installer, Shizuku, and later Play) so that items 8, 9 and 11 stop being inferred.

---

## 3. The Updates screen (phone)

Open **Sierra** on the phone → **Updates and install record**.

- **How this build was installed** shows installer of record, initiating package, package source and update owner, plus a verdict line using the rule from item 10. Re-read it after every install.
- **Check for updates** reads `https://api.github.com/repos/mdc159/sierra-auto/releases/latest`, parses the `versionCode` from the tag (`v0.1.<run>`), and says whether it is newer, the same, or older than the installed build.
- **Download and verify signer** downloads `sierra-auto.apk` to the app cache and compares the APK's signing certificates (SHA-256) with the installed app's. A mismatch or a different package name refuses to go further.
- **Install (Sierra becomes the installer)** is option (d).
- **Install through Shizuku** is option (b). The button is enabled only when Shizuku is running, Sierra has its permission and a verified APK is downloaded.

The app builds and runs without the Shizuku app on the phone. Shizuku's client library (`dev.rikka.shizuku:api` and `:provider`, 13.1.5, Maven Central) is bundled; nothing else is.

---

## 4. One-time Play Console setup (for `play.yml`)

Do this on the PC once. Afterwards each Play build is Actions → **play** → Run workflow.

1. **Developer account.** <https://play.google.com/console> → create a personal developer account (25 USD, identity verification). Production access (the 12-tester closed test) is never needed for internal testing.
2. **Create the app.** All apps → Create app → name "Sierra", app, free. Fill the mandatory declarations as they come up (privacy policy URL can be the repo README; data safety: the app sends voice and text to the endpoint the owner configures and stores settings on the device; no ads).
3. **Play App Signing with the existing key.** Release → Setup → App signing → choose "Use an existing key" / export and upload a key from a Java keystore. Google's PEPK tool encrypts the private key from the release keystore (the one in `SIGNING_KEYSTORE_BASE64`) and the console takes the output. This makes the Play-served APK carry the same certificate as the GitHub releases, so either can update the other. The same keystore then also serves as the upload key, which is what `play.yml` signs the bundle with.
4. **First upload by hand.** Build locally (`./gradlew bundleRelease -PversionCode=100000 -Pplay=true` with the four `SIGNING_*` environment variables set, or download `sierra-auto.aab` from a `play.yml` run that failed at the upload step) and upload it to Release → Testing → Internal testing → Create new release. The upload API refuses a package the console has never seen. Use 100000 so the first `play.yml` run (100001) is newer. `-Pplay=true` matters: without it Play rejects the bundle because `UPDATE_PACKAGES_WITHOUT_USER_ACTION` is restricted (seen 2026-11-05).

Done for Sierra on 2026-11-05: app created, Play App Signing uses the existing key (the app-signing and upload SHA-256 fingerprints both match the release keystore), release 100000 is live on Internal testing, tester list "Owner", service account invited with "Release apps to testing tracks" on Sierra only, `PLAY_SERVICE_ACCOUNT_JSON` set. Play's "automatic protection" (installer check) is on. It is under App integrity → Automatic protection if it ever gets in the way.
5. **Testers.** Internal testing → Testers → create a list with the owner's Google account (up to 100 testers). Copy the opt-in link, open it on the phone, tap "Become a tester", then install from the Play Store listing.
6. **Service account for CI.** Google Cloud console → create a project (or reuse one) → enable **Google Play Android Developer API** → IAM → Service accounts → create one with no project roles → Keys → add key → JSON. In Play Console → Users and permissions → Invite new users → the service account's email → App permissions: Sierra → release to testing tracks (and "Release apps to production, exclude devices, and use Play App Signing" is **not** needed). Wait for the invitation to become active (minutes).
7. **Repository secret.** Settings → Secrets and variables → Actions → `PLAY_SERVICE_ACCOUNT_JSON` = the full JSON text of the key. The four `SIGNING_*` secrets already exist for `build.yml`.
8. **Run it.** Actions → play → Run workflow → status `completed`. The run summary shows the versionCode. On the phone, the Play Store offers the update within minutes.

Until step 7 is done, running the workflow prints "Play upload skipped: missing repository secret(s): …" in the log and run summary and ends green. It never touches `build.yml` or the GitHub Releases.

**versionCode scheme.** GitHub release `v0.1.N` has versionCode `N` (the build.yml run number). Play builds use `100000 + play.yml run number`. Both increase, Play always wins, and the in-app updater never offers a downgrade.

---

## Sources

[testing]: https://developer.android.com/training/cars/testing
[installsource]: https://developer.android.com/reference/android/content/pm/InstallSourceInfo
[sessionparams]: https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams
[session]: https://developer.android.com/reference/android/content/pm/PackageInstaller.Session
[pis]: https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/pm/PackageInstallerService.java
[installsourcejava]: https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/pm/InstallSource.java
[installstart]: https://github.com/aosp-mirror/platform_frameworks_base/blob/main/packages/PackageInstaller/src/com/android/packageinstaller/InstallStart.java
[iph]: https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/pm/InstallPackageHelper.java
[fermata]: https://github.com/AndreyPavlenko/Fermata/issues/846
[aatorque70]: https://github.com/YuhiAida/aa-torque/issues/70
[aatorque126]: https://github.com/YuhiAida/aa-torque/issues/126
[aatorque128]: https://github.com/YuhiAida/aa-torque/issues/128
[homecar]: https://github.com/RenanEspinar/HomeCar/issues/9
[unknownauto]: https://github.com/GitMag/UnknownAuto
[unknownauto-src]: https://github.com/GitMag/UnknownAuto/tree/main/src/com/mag/unknownauto
[shizuku-api]: https://github.com/RikkaApps/Shizuku-API
[upload-action]: https://github.com/r0adkll/upload-google-play
[aab-required]: https://support.google.com/googleplay/android-developer/answer/9844679
[internal-testing]: https://support.google.com/googleplay/android-developer/answer/9845334
[internal-sharing]: https://support.google.com/googleplay/android-developer/answer/9303479

Visible list of the same sources:

- `testing`: https://developer.android.com/training/cars/testing (Google)
- `installsource`: https://developer.android.com/reference/android/content/pm/InstallSourceInfo (Google)
- `sessionparams`: https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams (Google)
- `session`: https://developer.android.com/reference/android/content/pm/PackageInstaller.Session (Google)
- `pis`: AOSP `PackageInstallerService.java` (GitHub mirror)
- `installsourcejava`: AOSP `InstallSource.java` (GitHub mirror)
- `installstart`: AOSP package installer `InstallStart.java` (GitHub mirror)
- `iph`: AOSP `InstallPackageHelper.java` (GitHub mirror)
- `fermata`: Fermata issue 846, decompile of Android Auto 17.7 (third party)
- `aatorque70`, `aatorque126`, `aatorque128`: aa-torque issues on the install gate and Play (third party)
- `homecar`: HomeCar issue 9, CAR.VALIDATOR denials (third party)
- `unknownauto`, `unknownauto-src`: UnknownAuto README and source (third party)
- `shizuku-api`: Shizuku API README (third party)
- `upload-action`: r0adkll/upload-google-play README (third party)
- `aab-required`, `internal-testing`, `internal-sharing`: Play Console help (Google; not reachable from this sandbox, quoted from search snippets)
