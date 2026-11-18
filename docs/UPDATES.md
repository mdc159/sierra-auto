# Distribution, updates and install records

## Workflow update — 2026-11-17

See [development and staging workflow](DEVELOPMENT-WORKFLOW.md). Play internal testing now deploys explicitly from staging after validation; version_code is supplied explicitly above all uploaded/installed versions. GitHub APK releases are optional manual actions from main. The historical records below retain their original dates; they do not establish current phone or Console state.

As of **2026-11-08, main `210448b`**. Package `com.mdc159.sierra`, POI Car App Library app. **Play internal testing is operational**, not a future recommendation; signed GitHub releases remain a separate development channel. This is not a public production listing or a guarantee of policy/host acceptance. No external policy/account/fee limits were rechecked for this docs update.

## 1. Verified release and device state

The review recorded successful main build/release CI, GitHub APK **v0.1.110** (November 6), and successful Play-internal upload **100005** from the same main commit. A preceding upload failed because the release name exceeded 50 characters; a shorter name succeeded. Prefer the generated default name or keep a custom name within that observed bound.

The inspected A54 had **0.1.100005 / 100005**, non-debuggable and signed `CN=Sierra Auto, O=mdc159`, with the Play build's self-update permissions absent. Version/UI/manifest match that feature set, but no embedded SHA or byte-for-byte Play-served artifact comparison was available. **Version alone is not exact source provenance.**

Android reported installer **`com.android.vending`**, initiator **`com.android.shell`**. This is not a demonstrated direct Play/Play record. Current `InstallRecord` predicts a visibility risk on a real head unit; the review did not test that host, so do not claim definite acceptance or rejection. Confirm installation/update through the enrolled Play listing and record both fields plus actual truck launch.

## 2. Installation constraints: observations, not platform guarantees

Android exposes installing and initiating package names separately. An ADB install requesting `-i com.android.vending` can name Play as installer while remaining shell-initiated; it does **not** prove installation from Play. Changing a source label is not a verified substitute for the intended channel.

Historical Google testing guidance said the Android Auto “Unknown sources” option does not cover Car App Library apps and recommended Play internal testing/sharing for real vehicles. November 4 DHU observations accepted install records that were not proven on the truck. Those observations plus third-party validator reverse-engineering motivated `InstallRecord`'s heuristic, not a stable contract for all Android Auto/Samsung/host versions. A DHU pass cannot establish trusted-source acceptance or release-host validation.

Historical research links are retained below; recheck official guidance when changing distribution or target APIs. Do not infer that internal-track delivery exempts category, data-safety or other review requirements, or that POI permits every assistant feature.

## 3. Available channels and what the app implements

| Channel | As-is / use | Limits and next steps |
| --- | --- | --- |
| **Play internal testing** | Enrolled tester installs/updates through Play. `.github/workflows/play.yml` manually builds signed AAB with `-Pplay=true` and uploads to `internal`. Preferred truck channel. | Confirm phone record and actual host launch. Console processing and review behavior may change; no timing guarantee. |
| GitHub release + ADB | Signed `sierra-auto.apk`; `scripts/install-phone.ps1` can install latest/tag/local APK and requests Play installer of record. Useful for device development. | Still shell-initiated; can replace previous provenance. Play's current high versionCode blocks ordinary lower-code APK updates. Do not uninstall casually: settings are lost. |
| Non-Play Sierra PackageInstaller | Downloads GitHub APK, checks package/signing certificate against installed copy, then starts a session with user confirmation as needed. | Sierra becomes installer/initiator; no demonstrated truck acceptance. Do not promise later silent installs. Source-only updater races need tests. |
| Non-Play experimental Shizuku | Optional library/button uses shell access to open the system installer requesting Play attribution; Shizuku setup/permission required. | No successful target record demonstrated on this A54 through the button; Samsung/version behavior unverified. Reboot/lifecycle and physical acceptance need separate tests. |
| Old installer experiment | Retained linked tree **`.worktrees/install-record`**, `f52dcdc`, draft PR #44 at review time. | Not main. SystemInstaller/button/tooling claims are unproven; reassess selectively after later Play changes rather than merge unchanged. |

Do not revive historical scratch automerge scripts as an unattended pipeline. They were archival operational material; green syntax does not validate installation/merge behavior.

### Current Updates screen

Open **Sierra → Updates and install record**:

- **How this build was installed** shows version and source record plus a heuristic verdict. The screen's broad Android Auto wording is not independent proof; actual host launch remains the acceptance criterion.
- In **Play builds**, `BuildConfig.PLAY_BUILD` hides GitHub check/download, install permission and both install paths. It displays **Updates come from Google Play**. `src/play/AndroidManifest.xml` removes `REQUEST_INSTALL_PACKAGES` and `UPDATE_PACKAGES_WITHOUT_USER_ACTION` from the release manifest.
- In **non-Play builds**, **Check for updates** reads GitHub `releases/latest`, derives versionCode from `v0.1.N`, and compares with installed version. **Download and verify signer** checks package/certificate; **Install (Sierra becomes the installer)** and **Install through Shizuku** are available only after their prerequisites. Shizuku is optional; the app runs without its service.
- A signer check is useful integrity enforcement, not proof of source SHA, Play provenance or safe application behavior. Review concerns about overlapping downloads/non-Play update state are not reachable through the installed Play build's hidden install UI.

## 4. Play workflow and one-time setup reference

Sierra setup was reported completed November 5: app created, existing signing key enrolled, owner internal-tester list, service account limited to testing release access, `PLAY_SERVICE_ACCOUNT_JSON` configured. These are historical setup records, not a fresh account audit. Shared Android toolchain and local credential directory remain outside the project; never move private signing/service credentials into `.local` or Git.

For rebuilding the setup on an authorized account (consult current Console instructions rather than stale fee/tester/policy limits):

1. Create/verify the developer account and app in [Play Console](https://play.google.com/console); complete the actual required declarations, privacy policy, data safety and testing enrollment. Assess audio/text/location/provider/backend behavior accurately; do not assume a repository README automatically satisfies privacy-policy requirements.
2. Enroll in Play App Signing. To preserve compatibility with existing signed GitHub APKs, choose the existing app-signing key and follow Google's PEPK export/upload procedure. Compare actual app-signing and upload certificate fingerprints; do not assume they must stay the same key forever.
3. First upload may require Console setup before API uploads work. Historical initial code was 100000; **do not reuse it now**. Always choose a versionCode greater than every uploaded/installed version that must be updated. Build with protected `SIGNING_KEYSTORE_PATH`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` and `-Pplay=true`:

   ```powershell
   # Example only: select a fresh, authorized version code before running.
   .\gradlew.bat --no-daemon bundleRelease -PversionCode=<fresh-code> -Pplay=true
   ```

   Without release inputs local release builds use debug signing. Never upload that fallback. The workflow checks the bundle certificate and rejects `CN=Android Debug`.
4. Create an internal tester list, enroll the owner's account and install through its opt-in/listing. Copy no tester account/credentials into public documentation.
5. Enable the Android Developer API for a dedicated service account, grant only required app/testing permissions in Play Console, and store its JSON as repository secret **`PLAY_SERVICE_ACCOUNT_JSON`**. Keep the four existing CI signing secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
6. Actions → **play** → Run workflow on **staging**, supplying an unused **version_code** above all uploaded and installed builds. `status=completed` requests internal availability; `draft` leaves a draft. Upload success/green job alone does not prove a tester received the build. Missing secrets fail the deployment. The Play workflow runs Android unit tests, debug/release lint, bridge tests and secret scanning before building/uploading the signed bundle. Verify the workflow SHA and actual upload step.
7. After processing, update through Play and capture version, source record and real-host acceptance. Do not infer processing time or future policy acceptance from the earlier successful run.

**Version codes:** GitHub `build.yml` uses its run number `N`; Play now requires an explicit `version_code`. Historically Play used `100000 + play.yml` run number. In the reviewed state 100005 is far above GitHub 110. This is an offset convention, **not an eternal mathematical guarantee that two independent counters cannot collide**. Monitor before any future numbering change or very large GitHub run count. A regular low-code GitHub build will not update 100005 in place; compatible certificates do not remove downgrade restrictions.

## 5. Acceptance / maintenance next steps

- Follow [TRUCK-TEST-PLAN.md](TRUCK-TEST-PLAN.md) A1 for Play provenance and real release-host launch, not just DHU visibility. No new install or truck test was made during documentation consolidation.
- Review non-Play downloader cancellation/state and experimental installer evidence only if that channel remains useful; do not broaden privileges merely to force a source mark.
- Revalidate imported key provenance and exact build identity when publishing. Keep local build artifacts in canonical `.local/runs`, release downloads in `.local/downloads/releases`; neither location is a credential store.

## Historical research sources (not freshly checked)

- [Google car testing](https://developer.android.com/training/cars/testing)
- [InstallSourceInfo](https://developer.android.com/reference/android/content/pm/InstallSourceInfo), [SessionParams](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams), [Session](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session)
- AOSP: [PackageInstallerService](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/pm/PackageInstallerService.java), [InstallSource](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/pm/InstallSource.java), [InstallStart](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/packages/PackageInstaller/src/com/android/packageinstaller/InstallStart.java), [InstallPackageHelper](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/pm/InstallPackageHelper.java)
- Third-party observations: [Fermata #846](https://github.com/AndreyPavlenko/Fermata/issues/846), aa-torque [#70](https://github.com/YuhiAida/aa-torque/issues/70), [#126](https://github.com/YuhiAida/aa-torque/issues/126), [#128](https://github.com/YuhiAida/aa-torque/issues/128), [HomeCar #9](https://github.com/RenanEspinar/HomeCar/issues/9)
- [UnknownAuto](https://github.com/GitMag/UnknownAuto), [source](https://github.com/GitMag/UnknownAuto/tree/main/src/com/mag/unknownauto), [Shizuku API](https://github.com/RikkaApps/Shizuku-API)
- [Play upload action](https://github.com/r0adkll/upload-google-play), [App Bundles](https://support.google.com/googleplay/android-developer/answer/9844679), [internal testing](https://support.google.com/googleplay/android-developer/answer/9845334), [internal sharing](https://support.google.com/googleplay/android-developer/answer/9303479)
