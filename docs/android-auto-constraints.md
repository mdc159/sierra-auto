# Android Auto constraints: check this before building a feature

**Checked against Google's documentation on:** 2026-11-04
**Applies to:** Sierra Auto, a Car App Library app in the point-of-interest (POI) category. It is sideloaded onto a Galaxy A54 and projected over wired Android Auto to a 2018 GMC Sierra Denali.

Every claim below links to the page it came from (link labels are defined at the bottom). Claims marked **unverified** could not be confirmed in official Google documentation. Treat them as hypotheses to test, not as facts.

---

## 0. Read this first: the sideloading risk

> **Google's testing page says the Android Auto "Unknown sources" developer option does not apply to Car App Library apps.**
>
> "Android Auto has a developer option that lets you run apps that aren't installed from a trusted source. This setting applies to media, messaging notifications, and parked apps but doesn't apply to apps built using the Android for Cars App Library." ([testing])
>
> The same page says: "To test your app in real vehicles, you must install it from a trusted source such as Google Play, with one exception detailed in Allow unknown sources." It suggests Play **Internal App Sharing** or an **Internal Test Track** to get onto devices without full Play review. ([testing])

Sierra Auto is a Car App Library app. If it is installed from a GitHub Release APK, it **may not appear on the truck screen even with "Unknown sources" turned on.** M1 ("Hello, truck") exists to test exactly this, so treat it as the first thing to prove.

Mitigations, in order of confidence:

1. **Desktop Head Unit (DHU) during development.** The DHU is Google's supported way to run apps under development ([dhu]). Whether the DHU also enforces the trusted-installer check for a sideloaded APK is **unverified**.
2. **Play Console Internal Testing or Internal App Sharing.** This is the official route for real vehicles ([testing]). It needs a Play developer account. The APK must still be signed consistently.
3. **Install with Play recorded as the installer.** A third-party project (UnknownAuto) states: "Android Auto won't run an app built with its Car App Library unless Android thinks the app was installed from Google Play. The developer 'Unknown sources' switch doesn't change that." It also says the Play mark "can only be set when the app is installed," and its workaround uses Shizuku ([unknownauto]). **Unverified, not Google documentation.** A related workaround that some people use is `adb install -i com.android.vending app.apk`. It is not documented by Google or by that project and is also **unverified**.
4. **"The toggle resets after updates."** I found no official or reliable source saying the "Unknown sources" toggle resets when Android Auto updates. One third-party guide says that clearing Android Auto's app data resets all developer options. **Unverified.** If the app disappears after an Android Auto update, re-check the developer settings first.

---

## 1. Current library version and API levels

| Item | Value | Source |
| --- | --- | --- |
| Latest **stable** `androidx.car.app` | **1.7.0** (released July 16, 2025) | [release] |
| Release candidate | 1.8.0-rc01 (Aug 26, 2026). "This release includes a security fix. If you are using a lower version, please update to use this version." | [release] |
| Alpha | 1.9.0-alpha03 (Oct 7, 2026) | [release] |
| Artifacts | `androidx.car.app:app`, `app-projected` (Android Auto specific), `app-automotive` (AAOS), `app-testing` | [release] |
| Highest Car App API level introduced | Level 8 was introduced in 1.7.0-beta01. The 1.9 alphas mention "API 9" features (for example `SearchHeader`). | [release] |
| `CarAudioRecord` (car microphone) | Car App **API level 5**, added in library 1.3.0 | [release], [carmic] |
| `ConstraintManager` | Library 1.1.0, `@RequiresCarApi(2)` | [constraint] |
| `isAppDrivenRefreshEnabled()` | `@RequiresCarApi(6)` | [constraint] |
| `PlaceListMapTemplate` refresh listener | `@RequiresCarApi(5)` | [plm-ref] |
| Permission dialogs on Android 14+ phones | "Update to `androidx.car.app:1.7.0-rc01` or higher to ensure permission dialogs appear on Android 14 and higher (phone)" | [perms] |
| No library 1.5 or 1.6 | The release notes jump from 1.4 to 1.7 | [release] |

Declare the minimum Car App API level in the manifest. The host's actual level is available at runtime from `CarContext.getCarAppApiLevel()` ([setup]):

```xml
<meta-data android:name="androidx.car.app.minCarApiLevel" android:value="5"/>
```

Sierra needs `CarAudioRecord`, so **`minCarApiLevel` should be at least 5** (inferred from [carmic] and [release]). Alpha releases and `@ExperimentalCarApi` APIs "can change—and potentially break—your app when the hosts are updated. We strongly recommend that you don't refer to these categories of APIs in your production APKs." ([setup])

---

## 2. App categories and what each may do

Android Auto and AAOS only support certain kinds of apps ([cars]). The Car App Library categories, declared as an intent-filter `<category>` on the exported `CarAppService`, are ([setup]):

| Category (`androidx.car.app.category.*`) | What it is for | Usage | Source |
| --- | --- | --- | --- |
| `NAVIGATION` | Turn-by-turn navigation | Driving or parked | [setup], [cars] |
| `POI` (**Sierra uses this**) | "finding points of interest such as parking spots, charging stations, and gas stations" | Driving or parked | [setup], [cars] |
| `IOT` | Actions on connected devices (garage door, lights) | Driving or parked | [setup], [cars] |
| `WEATHER` | Weather for the current location or route | Driving or parked | [setup], [cars] |
| `MEDIA` (templated) | Audio browse and play. Templated media is Early Access Program only. | Driving or parked | [setup], [cars] |
| `MESSAGING` (templated, labs) | Short-form messaging | Driving or parked | [setup], [cars] |
| `CALLING` (labs) | Voice calling | Driving or parked | [setup], [cars] |

`PARKING` and `CHARGING` are deprecated in favor of `POI` ([poi]). The categories that are not Car App Library apps are media (`MediaBrowserService`), messaging notifications, and the **parked-only** categories. Video and Browsers are AAOS only. Games are parked only on both platforms ([cars]).

Quality rules that bind a POI app ([quality]):

- **PC-1:** "The app must not include features outside the app types intended for cars."
- **PF-1:** "The app must provide meaningful functionality relevant to driving."
- **AC-1:** "Users must be able to complete tasks in the app using five screens or fewer."
- **SA-1:** "The app must not display animated elements on the screen, such as animated graphics or video."
- **ST-1:** "The app must not display automatically scrolling text."
- **IU-1:** No images on screen except a few allowed cases, including "images and photographs to aid in driving decision-making."
- **DR-1/2/3:** Buttons respond within 2 s. The app launches within 10 s and loads content within 10 s.
- **VI-1:** If the user must go to the phone, show a message telling them to look at the phone only when it is safe.
- **MR-1:** Apps that draw maps must support light and dark themes.
- **TH-1:** Custom theming with library 1.9 or later must support light and dark themes.
- **DD-1:** "The navigation audio channel can only be used by navigation apps and for navigation instructions."

These are Play Store review criteria. A sideloaded app is not reviewed. However, the host enforces many limits (list sizes, step quota, parked-only content) at runtime regardless ([restrictions], [constraint]).

> **Talk feature and PC-1 (unverified interpretation).** A general-purpose chat agent is not one of the listed categories. Google does document car microphone recording "to support building features such as an in-app digital assistant" ([voice]). For a personal sideloaded app, Play policy is not the gate. The runtime host is, and the host does not police the topic of a conversation.

---

## 3. Templates

Two kinds of templates are documented: general purpose templates, usable by every Car App Library app, and special purpose templates restricted by category ([templates]).

| Template | Allowed for | Driving or parked | Content limits | Source |
| --- | --- | --- | --- | --- |
| **List** (`ListTemplate`) | All | Both | Row count from `ConstraintManager` (`CONTENT_LIMIT_TYPE_LIST`). Secondary text is truncated to **2 lines while driving**, with the full text shown when parked. A section header is required when sections exist. Do not mix radio rows with other row types. | [list] |
| **Grid** (`GridTemplate`) | All | Both | Item limit "won't be less than 6", read from `ConstraintManager` (`CONTENT_LIMIT_TYPE_GRID`). Do not show an action strip and a FAB at the same time. | [grid] |
| **Sectioned Item** (`SectionedItemTemplate`) | All | Both | Mixes lists, grids, chips, banners, condensed items and spotlight sections. Google now says List and Grid "are no longer needed". It was experimental in 1.7 and became accessible for API 8+ in 1.8 alphas, so it is **not usable on stable 1.7.0** without experimental APIs. | [sectioned], [release] |
| **Pane** (`PaneTemplate`) | All | Both | Up to **4 non-actionable rows** (at least 1 required) and up to **2 buttons** (one may be primary). Optional large image. Images are hidden inside Map + Content. | [pane] |
| **Message** (`MessageTemplate`) | All | Both | Up to **2 lines** of wrapping text, an optional image, icon or loading spinner, and up to **2 buttons**. Use it for errors and permission prompts. | [message] |
| **Long Message** (`LongMessageTemplate`) | All | **Parked only** (hidden while driving) | Unlimited scrollable text and up to 2 buttons. Does not increase step count. Car API level 2. | [longmsg], [release] |
| **Search** (`SearchTemplate`) | All | Both, but **no keyboard while driving** (speech-to-text only) | Result row count from `ConstraintManager`. Show a loading indicator if a search takes more than 1 s. | [search] |
| **Sign-in** (`SignInTemplate`) | All | **Parked only** | Provider, username and password, PIN (up to 12 chars), or QR code. Input fields are for sign-in only. Car API level 2. | [signin], [release] |
| **Tab** (`TabTemplate`) | All | Both | **2 to 4 tabs**, each with an icon and a title. Embeds List, Grid, Search, Pane, Message, Navigation or Sectioned Item. Headers of embedded templates are ignored. | [tab] |
| **Place List (map)** (`PlaceListMapTemplate`) | **POI only** (plus the deprecated PARKING and CHARGING categories). Needs the `androidx.car.app.MAP_TEMPLATES` permission. | Both | **The host draws the map.** Items are limited by `CONTENT_LIMIT_TYPE_PLACE_LIST`, and the host ignores the overflow. Rows have up to 2 text lines and no toggles, and cannot be a selectable list. No `IMAGE_TYPE_LARGE`. A row cannot have both an image and a place marker. **Every non-browsable row must carry a `DistanceSpan`.** Every row must have an action. Action strip has up to 4 actions. The map is not interactive. The refresh button does not count as a step. **Not deprecated.** | [plm], [plm-ref], [poi] |
| **Map + Content** (`MapWithContentTemplate`) | Navigation, POI, Weather. Needs `MAP_TEMPLATES` or `NAVIGATION_TEMPLATES`, **plus `ACCESS_SURFACE`**. | Both | **The app draws the map** on a `Surface`. Embeds List, Grid, Pane or Message. Replaces the deprecated `MapTemplate`, `PlaceListNavigationTemplate` and `RoutePreviewNavigationTemplate`. | [mapcontent], [drawmaps], [release] |
| **Navigation** (`NavigationTemplate`) | **Navigation only** (`NAVIGATION_TEMPLATES`) | Both | The app draws a full-screen map. Optional routing card and travel estimate. Action strip up to 4 actions, and map action strip up to 4 buttons. | [nav], [drawmaps] |
| **Media Playback** (`MediaPlaybackTemplate`) | Media only | Both | Not relevant to Sierra | [templates] |

**For the Places screen:** `PlaceListMapTemplate` is the right template if we want the host to draw the map, which means no map SDK. `MapWithContentTemplate` would require Sierra to render its own map tiles on the surface ([poi], [drawmaps]).

### Component limits

- **Row:** title up to 2 lines. Secondary text up to 2 lines, truncated while driving and shown in full when parked. Up to 2 action buttons in some templates. A browsable row must be clickable and cannot have actions. A row has either a toggle or a radio button, never both ([row]).
- **Action strip** on `PlaceListMapTemplate`: up to 4 actions ([plm-ref]).

---

## 4. List limits (`ConstraintManager`) and screen or step limits

### Content limits

Read them at runtime. They vary by host ([constraint]):

```kotlin
val cm = carContext.getCarService(ConstraintManager::class.java)
val maxPlaces = cm.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST)
```

| Constant | Library fallback default (used only if the host can't be queried) |
| --- | --- |
| `CONTENT_LIMIT_TYPE_LIST` (0) | 6 |
| `CONTENT_LIMIT_TYPE_GRID` (1) | 6 |
| `CONTENT_LIMIT_TYPE_PLACE_LIST` (2) | 6 |
| `CONTENT_LIMIT_TYPE_ROUTE_LIST` (3) | 3 |
| `CONTENT_LIMIT_TYPE_PANE` (4) | 4 |

The fallback defaults come from the library source, `car/app/app/src/main/res/values/integers.xml` ([integers]). The reference docs only say the host limit "should be no smaller than" these values ([constraint]). **Design for 6 places and 6 list rows**, and never assume more.

### Step (template) quota

- The host allows **at most 5 templates per task**. The last one must be Navigation, Pane, Message, MediaPlayback, SignIn or LongMessage ([restrictions]).
- The limit counts **templates, not Screens**: "if an app sends two templates while in screen A and then pushes screen B, it can now send three more templates." ([restrictions])
- **Refreshes do not count.** A new template "of the same type and contains the same main content as the previous template" is free, for example a toggle flip ([restrictions]). For a **Message** template, a refresh is "only updates that don't change the title and message" ([taskflows]). If you change the title, it counts as a step.
- **Going back restores quota** by the number of templates popped. When popping back, the app must resend the same template type ([restrictions]).
- **Reset:** reaching a NavigationTemplate, launching from the launcher or a notification, or opening another app starts a new task ([restrictions], [taskflows]).
- **Exceeding the quota:** "the host displays an error message to the user before closing the app." ([restrictions])
- **Adaptive task limits:** when the OEM or host enables it, more than 5 steps are allowed while parked, and refreshes are throttled while driving. Check before relying on it. The design page names `IsAppRefreshEnabled`, and the API reference has `ConstraintManager.isAppDrivenRefreshEnabled()` (Car API 6) ([taskflows], [constraint]).
- Design guidance: aim for 2–3 steps. Never end a 5-step flow on a List or Grid ([ux], [taskflows]).
- **Debug aid:** Android Auto developer settings → **Enable debug overlay** shows the current step count on screen ([restrictions]).

**Implication for Talk:** a mic → "listening" → reply flow should stay on one template type and *refresh* it. Google's own voice sample flow keeps the step count at 1 by refreshing the same template ([voice]). Changing a MessageTemplate's message text is a new step ([taskflows]). Use a template whose content updates count as refreshes, or budget the steps.

---

## 5. Text and truncation

- List and row secondary text is **truncated to 2 lines while driving**. Put the important words first ([list], [row]).
- Message template: up to 2 lines ([message]). Routing card message: up to 2 lines ([nav]).
- `CarText` supports **multiple-length variants** (`CarText.Builder.addVariant`), and "the host will pick the variant that best fits the screen" (Car API level 2+) ([cartext], [release]).
- Only specific `CarSpan` types are honored, and only where an API documents them. Other spans "will be simply ignored". For example, `PlaceListMapTemplate` titles accept only `DistanceSpan` and `DurationSpan` ([cartext], [plm-ref]).
- The host does not localize text ([cartext]).
- **No auto-scrolling text** (ST-1) ([quality]).
- **Long agent replies:** while driving, the only screen surface for long text is `LongMessageTemplate`, and it is **parked only** ([longmsg]). While driving, show a short summary of 2 lines or row text, and **speak** the full reply with TTS.

---

## 6. Blocked or restricted while driving

| Thing | Rule | Source |
| --- | --- | --- |
| Keyboard | Unavailable while driving. Search falls back to speech-to-text. Sign-in is parked only. | [search], [signin] |
| Long text | `LongMessageTemplate` content is hidden while driving | [longmsg] |
| Video and animation | SA-1: no animated graphics or video for POI, nav and similar categories. Video is a parked-only category on AAOS only. | [quality], [cars] |
| Sending the user to the phone | Wrap the click in `ParkedOnlyOnClickListener`. When driving, "the host will display a message to the user indicating that the action can only be used while parked." | [parkedonly] |
| Permission prompts | They appear **on the phone**, so use a Message template and a toast that sends the user to the phone. The phone dialog opens only when not driving. | [perms], [grantflow] |
| Over 5 steps | Paused after step 5 if adaptive limits are on, otherwise the app crashes | [taskflows] |

Simulate driving on the DHU with `restrict all` (Shift+U), and lift it with `restrict none` (U) ([dhu]).

---

## 7. Microphone, audio focus and TTS

**Recording from the car microphone** ([carmic]):

1. Declare `android.permission.RECORD_AUDIO` and request it at runtime with `CarContext.requestPermissions()`. On Android Auto the dialog shows on the phone ([perms]).
2. `CarAudioRecord.create(carContext)` → `startRecording()` → `read(buf, 0, CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)` in a loop while the result is `>= 0` → `stopRecording()`.
3. **Take audio focus first.** "When recording from the car microphone, you must acquire audio focus first. This stops any ongoing media. If you lose audio focus, stop recording." Google's sample uses `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` with `CONTENT_TYPE_SPEECH`.
4. Requires **Car App API level 5** ([release]).
5. Audio format: **16 kHz, `audio/l16`** (16-bit PCM). If the user dismisses the mic on the car screen, `read()` returns -1. These come from the `CarAudioRecord` reference as summarized by search; **unverified** against the full reference page text.
6. UX requirements: get permission before recording, provide a mic entry point, and stop recording when the user stops talking ([ux]). While recording, the host shows an indicator ([voice]).

**Audio usage type (be careful):** Google's microphone sample uses `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` with the comment "use the most appropriate usage type for your use case" ([carmic]). However, quality rule **DD-1** reserves the navigation audio channel for navigation apps ([quality]). For a POI app, `USAGE_ASSISTANT` is the better fit. Which usage type routes best on this head unit is **unverified**, so test it on the truck.

**TTS:** I found no Car App Library page that is specific to text-to-speech. Plain Android `TextToSpeech` on the phone plays through the car speakers over Android Auto like any other phone audio. Request transient audio focus while speaking and abandon it afterwards. This is **unverified** as official Car App Library guidance. Do not read long replies on screen while driving (section 5).

**DHU mic testing:** `mic begin` (key M) uses the computer microphone. `mic play file.wav` plays a recording. `mic reject on` simulates rejected mic requests ([dhu]).

---

## 8. Location

- Standard Android permission rules apply. Request location with `CarContext.requestPermissions()` ([perms]).
- `PlaceListMapTemplate.Builder.setCurrentLocationEnabled(true)` "requires the app to have either the `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION` permission". The host may then receive location updates from the app ([plm-ref]).
- Place rows attach a `Place` (with `CarLocation`) through `Row.Builder.setMetadata(Metadata)` so the host can draw the marker. Non-browsable rows must show distance with a `DistanceSpan` ([plm-ref]).
- Car API level 4 added "a mechanism to allow apps to send location updates to the car host" ([release]). Car hardware data, including car location sensors, is available through `CarHardwareManager` (Car API 3) ([release]). Sierra can simply use the phone's location provider. Whether the Sierra head unit exposes its own GPS through `CarHardwareManager` is **unverified**.
- The DHU can fake location with `location lat long [accuracy] [altitude] [speed] [bearing]`, which needs `[sensors] location = true` in the `.ini` file ([dhu]).

---

## 9. Navigation hand-off

A POI app does not navigate. It hands off to the default navigation app ([carcontext], [ux]):

```kotlin
carContext.startCarApp(
    Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:${lat},${lng}"))
)
```

- The action must be `CarContext.ACTION_NAVIGATE` (`"androidx.car.app.action.NAVIGATE"`), **not** `Intent.ACTION_VIEW` ([carcontext]).
- The data URI may be `geo:12.345,14.8767` (coordinates), `geo:0,0?q=123+Main+St,+Seattle,+WA+98101` (address), or `geo:0,0?q=a+place+name` (search) ([carcontext]).
- `startCarApp` also accepts a dial-phone intent and an intent that restarts this app's own `CarAppService`. Explicitly targeting a different app throws `SecurityException` ([carcontext]).
- Don't call it until the `Session` lifecycle is at least `CREATED` ([carcontext]).
- Opening another app starts a new task for step counting ([taskflows]).
- POI UX: "SHOULD provide a way to launch a navigation app in order to navigate to the point of interest." ([ux])

---

## 10. What only some categories can do: the map surface

- **Drawing on the map `Surface`** (`SurfaceCallback` through `AppManager.setSurfaceCallback`) is available to **navigation, POI and weather** apps using `NavigationTemplate` (navigation only) or `MapWithContentTemplate`. It requires `androidx.car.app.ACCESS_SURFACE` plus `NAVIGATION_TEMPLATES` or `MAP_TEMPLATES` ([drawmaps]).
- **Navigation-only:** `NavigationTemplate`, turn-by-turn, routing cards, navigation alerts, cluster display, the navigation audio channel (DD-1), and receiving `ACTION_NAVIGATE` from other apps (NF-6) ([nav], [quality]).
- **Interactive maps** (pan, zoom, map taps through `SurfaceCallback`) need an `Action.PAN` button in the map action strip ([interactmap]). The `PlaceListMapTemplate` map is **not** interactive ([taskflows]).
- Only map and drive-related content may be drawn on the surface ([ux]). Apps that draw maps must redraw for dark mode (`CarContext.isDarkMode()`, `Session.onCarConfigurationChanged`) ([drawmaps], [quality] MR-1).

---

## 11. Sideloading: Android Auto developer mode and "Unknown sources"

**Enable Android Auto developer mode** ([testing]):

1. On Android 10+: **Settings > Apps & notifications > See all apps > Android Auto > Advanced > Additional settings in the app.** On a Samsung A54 (One UI) the path differs slightly; open the Android Auto settings screen. The Samsung path is **unverified**.
2. Scroll to **About**, tap **Version** to show "Version and permission info".
3. Tap **Version and permission info** 10 times, then confirm **Allow development settings?**
4. Developer settings appear in the overflow (⋮) menu. Enabling them is a one-time step.

**Unknown sources:** in the developer settings. Read section 0 first, because Google says this option does not apply to Car App Library apps ([testing]).

**Other sideload notes:**

- Android Auto supports phones on Android 9 (API 28) and higher ([cars]).
- Keep the signing key stable. An APK signed with a different key cannot update the installed app. This is general Android behavior and not specific to the car documentation.
- Exported `CarAppService` must override `createHostValidator`. That method decides which hosts may connect ([setup], [glossary]). For a personal build, allowing all hosts is the simplest option. Restricting it to known hosts is the safer one.

---

## 12. Testing with the Desktop Head Unit (DHU)

All of the following comes from [dhu] unless noted otherwise.

- **Install:** Android Studio → SDK Manager → SDK Tools → **Android Auto Desktop Head Unit Emulator**. It installs to `SDK_LOCATION/extras/google/auto/`. On Android 10+ phones, sign in to Play and update Android Auto before the first run.
- **Connect, ADB tunneling (simplest on Windows):**
  1. Enable Android Auto developer mode.
  2. In the Android Auto overflow menu, choose **Start head unit server**.
  3. Make sure **Previously connected cars > Add new cars to Android Auto** is on.
  4. Connect USB, unlock the phone, and run `adb forward tcp:5277 tcp:5277`.
  5. Run `desktop-head-unit.exe` (Windows) or `./desktop-head-unit` from `SDK_LOCATION/extras/google/auto`.
- **Connect, Accessory Mode:** `./desktop-head-unit --usb`. On Windows this may need the WinUSB driver and can conflict with adb. Try `adb kill-server` first.
- **Flags:** `-c/--config FILE.ini`, `-i/--input touch|rotary|hybrid`, `--adb=HOSTPORT`, `--usb=DEVICE_ID`, `-h/--headless`, `-t` (always on top).
- **Console commands:** `restrict all` / `restrict none` (simulate driving), `day` / `night`, `mic begin` / `mic play f.wav`, `location …`, `speed …`, `gear 101` (park) / `gear 100` (drive), `parking_brake true|false`, `screenshot f.png`, `keycode back|home|navigation`, `focus audio|video|nav on|off`. Sensors must be enabled in the `[sensors]` section of the `.ini`.
- **Screen sizes:** 800x480 (default), 1280x720 and 1920x1080, with margins for other aspect ratios. The Sierra's factory screen is 8". Its exact projected resolution is **unverified**, so test at both 800x480 and 1280x720.
- **Scriptable:** `./desktop-head-unit < script.txt` with `sleep N` lines, which is useful for CI screenshots. Running the DHU in GitHub Actions has not been tested and is **unverified**.
- **Blank screen on first connect:** close the DHU, restart the head unit server, start the DHU again, and grant any permissions requested on the phone.

---

## Feature idea checklist

Answer every question before accepting a feature request. A "no" on any starred (★) question means redesign or reject the feature.

1. ★ **Is it usable without the keyboard while driving?** Text entry while moving must come from voice (Search template speech-to-text or `CarAudioRecord`). Otherwise the feature is parked only ([search], [signin]).
2. ★ **Does every on-screen task finish in 5 templates or fewer**, ending on a Pane, Message or another allowed final template? Do status updates count as *refreshes* (same template type, same main content) rather than new steps ([restrictions], [taskflows])?
3. ★ **Does the screen respect host content limits?** Plan for 6 or fewer rows or places from `ConstraintManager`, 4 or fewer Pane rows, 2 or fewer buttons, and 2 lines of text while driving. Is the key information in the first words ([constraint], [list], [pane])?
4. ★ **Is it free of video, animation and auto-scrolling text**, with images used only to aid a driving decision (SA-1, ST-1, IU-1) ([quality])?
5. ★ **Does it fit the POI category, or the templates POI may use?** If it needs `NavigationTemplate`, turn-by-turn, the nav audio channel or a custom-drawn map, it is out of scope unless we change category ([setup], [drawmaps], [quality]).
6. **If it needs the phone screen** (permissions, settings, long input), is the action wrapped in `ParkedOnlyOnClickListener` with a "look at your phone only when safe" message ([parkedonly], [quality] VI-1)?
7. **If it uses audio:** does it take and release audio focus, stop recording on focus loss, avoid the navigation audio channel, and speak long content rather than display it ([carmic], [quality] DD-1)?
8. **Does it need a Car App API level above 5**, or anything in 1.8/1.9 alpha or `@ExperimentalCarApi`? If so, guard it with `getCarAppApiLevel()` or skip it ([setup], [release]).
9. **Does it respond within 2 s per tap and load within 10 s**, with a loading state for anything slower (DR-1/2/3) ([quality])?
10. **Can it be tested on the DHU** (`restrict all`, `mic play`, `location`) before it goes to the truck ([dhu])?

---

## Unverified items (summary)

- Whether a GitHub-sideloaded Car App Library APK shows up on a real Android Auto head unit at all. Google says "Unknown sources" does not apply ([testing]), and a third party says Play must be recorded as the installer ([unknownauto]). **This is M1's first test.**
- `adb install -i com.android.vending` as a workaround.
- That the "Unknown sources" toggle resets after Android Auto updates.
- Whether the DHU enforces the trusted-installer check.
- The `CarAudioRecord` format (16 kHz `audio/l16`) and the `read() == -1` on dismissal. Both came from a search summary of the reference, not from verbatim page text.
- The best `AudioAttributes` usage for a POI app's mic and TTS (`USAGE_ASSISTANT` is suggested).
- TTS guidance for Car App Library apps. No official page was found.
- The Android Auto settings path on Samsung One UI.
- The Sierra head unit's projected resolution and whether it exposes car GPS through `CarHardwareManager`.
- Running the DHU headless in CI.
- How a general chat assistant fits the PC-1 category rule (relevant only for Play publishing).

---

## Sources

[testing]: https://developer.android.com/training/cars/testing
[dhu]: https://developer.android.com/training/cars/testing/dhu
[release]: https://developer.android.com/jetpack/androidx/releases/car-app
[quality]: https://developer.android.com/docs/quality-guidelines/car-app-quality
[cars]: https://developer.android.com/training/cars
[apps]: https://developer.android.com/training/cars/apps
[setup]: https://developer.android.com/training/cars/apps/library/set-up-project
[glossary]: https://developer.android.com/training/cars/apps/library/glossary-concepts
[restrictions]: https://developer.android.com/training/cars/apps/library/template-restrictions
[carmic]: https://developer.android.com/training/cars/apps/library/car-microphone
[perms]: https://developer.android.com/training/cars/apps/library/request-permissions
[drawmaps]: https://developer.android.com/training/cars/apps/library/draw-maps
[interactmap]: https://developer.android.com/training/cars/apps/library/interact-map
[poi]: https://developer.android.com/training/cars/apps/poi
[templates]: https://developer.android.com/design/ui/cars/guides/templates/overview
[list]: https://developer.android.com/design/ui/cars/guides/templates/list-template
[grid]: https://developer.android.com/design/ui/cars/guides/templates/grid-template
[sectioned]: https://developer.android.com/design/ui/cars/guides/templates/sectioned-item-template
[pane]: https://developer.android.com/design/ui/cars/guides/templates/pane-template
[message]: https://developer.android.com/design/ui/cars/guides/templates/message-template
[longmsg]: https://developer.android.com/design/ui/cars/guides/templates/long-message-template
[search]: https://developer.android.com/design/ui/cars/guides/templates/search-template
[signin]: https://developer.android.com/design/ui/cars/guides/templates/sign-in-template
[tab]: https://developer.android.com/design/ui/cars/guides/templates/tab-template
[plm]: https://developer.android.com/design/ui/cars/guides/templates/place-list-map-template
[mapcontent]: https://developer.android.com/design/ui/cars/guides/templates/map-content-template
[nav]: https://developer.android.com/design/ui/cars/guides/templates/navigation-template
[row]: https://developer.android.com/design/ui/cars/guides/components/row
[ux]: https://developer.android.com/design/ui/cars/guides/ux-requirements/overview
[taskflows]: https://developer.android.com/design/ui/cars/guides/ux-requirements/plan-task-flows
[voice]: https://developer.android.com/design/ui/cars/guides/ux-requirements/communicate-app-by-voice
[grantflow]: https://developer.android.com/design/ui/cars/guides/flows/grant-permissions-on-phone
[constraint]: https://developer.android.com/reference/androidx/car/app/constraints/ConstraintManager
[carcontext]: https://developer.android.com/reference/androidx/car/app/CarContext
[cartext]: https://developer.android.com/reference/androidx/car/app/model/CarText
[parkedonly]: https://developer.android.com/reference/androidx/car/app/model/ParkedOnlyOnClickListener
[plm-ref]: https://developer.android.com/reference/androidx/car/app/model/PlaceListMapTemplate.Builder
[integers]: https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/res/values/integers.xml
[unknownauto]: https://github.com/GitMag/UnknownAuto/wiki

Visible list of the same sources:

- `testing`: https://developer.android.com/training/cars/testing
- `dhu`: https://developer.android.com/training/cars/testing/dhu
- `release`: https://developer.android.com/jetpack/androidx/releases/car-app
- `quality`: https://developer.android.com/docs/quality-guidelines/car-app-quality
- `cars`: https://developer.android.com/training/cars
- `apps`: https://developer.android.com/training/cars/apps
- `setup`: https://developer.android.com/training/cars/apps/library/set-up-project
- `glossary`: https://developer.android.com/training/cars/apps/library/glossary-concepts
- `restrictions`: https://developer.android.com/training/cars/apps/library/template-restrictions
- `carmic`: https://developer.android.com/training/cars/apps/library/car-microphone
- `perms`: https://developer.android.com/training/cars/apps/library/request-permissions
- `drawmaps`: https://developer.android.com/training/cars/apps/library/draw-maps
- `interactmap`: https://developer.android.com/training/cars/apps/library/interact-map
- `poi`: https://developer.android.com/training/cars/apps/poi
- `templates`: https://developer.android.com/design/ui/cars/guides/templates/overview
- `list`: https://developer.android.com/design/ui/cars/guides/templates/list-template
- `grid`: https://developer.android.com/design/ui/cars/guides/templates/grid-template
- `sectioned`: https://developer.android.com/design/ui/cars/guides/templates/sectioned-item-template
- `pane`: https://developer.android.com/design/ui/cars/guides/templates/pane-template
- `message`: https://developer.android.com/design/ui/cars/guides/templates/message-template
- `longmsg`: https://developer.android.com/design/ui/cars/guides/templates/long-message-template
- `search`: https://developer.android.com/design/ui/cars/guides/templates/search-template
- `signin`: https://developer.android.com/design/ui/cars/guides/templates/sign-in-template
- `tab`: https://developer.android.com/design/ui/cars/guides/templates/tab-template
- `plm`: https://developer.android.com/design/ui/cars/guides/templates/place-list-map-template
- `mapcontent`: https://developer.android.com/design/ui/cars/guides/templates/map-content-template
- `nav`: https://developer.android.com/design/ui/cars/guides/templates/navigation-template
- `row`: https://developer.android.com/design/ui/cars/guides/components/row
- `ux`: https://developer.android.com/design/ui/cars/guides/ux-requirements/overview
- `taskflows`: https://developer.android.com/design/ui/cars/guides/ux-requirements/plan-task-flows
- `voice`: https://developer.android.com/design/ui/cars/guides/ux-requirements/communicate-app-by-voice
- `grantflow`: https://developer.android.com/design/ui/cars/guides/flows/grant-permissions-on-phone
- `constraint`: https://developer.android.com/reference/androidx/car/app/constraints/ConstraintManager
- `carcontext`: https://developer.android.com/reference/androidx/car/app/CarContext
- `cartext`: https://developer.android.com/reference/androidx/car/app/model/CarText
- `parkedonly`: https://developer.android.com/reference/androidx/car/app/model/ParkedOnlyOnClickListener
- `plm-ref`: https://developer.android.com/reference/androidx/car/app/model/PlaceListMapTemplate.Builder
- `integers`: https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/res/values/integers.xml (androidx source on GitHub)
- `unknownauto`: https://github.com/GitMag/UnknownAuto/wiki (third party, not Google)
