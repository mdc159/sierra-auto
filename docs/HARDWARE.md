# Sierra Auto — Hardware Guide

**Checked on:** 2026-11-04 (prices are the makers' own store prices on that day)
**Truck:** 2018 GMC Sierra 1500 Denali, 8-inch factory IntelliLink screen
**Phone:** Samsung Galaxy A54, Android 16
**Goes with:** [`OBD2-PLAN.md`](OBD2-PLAN.md) (how the app talks to the adapter) and [`android-auto-constraints.md`](android-auto-constraints.md) (what the car screen allows)

Every claim links to where it came from (the list is at the bottom). **Unverified** means I could not confirm it from a source I read myself, or it has to be tested on this truck. All the unverified items are collected in section 7.

---

## 1. What to buy

| Item | Buy this | Price | Why |
| --- | --- | --- | --- |
| **OBD-II adapter, first choice** | **OBDLink LX** | **$89.95** ([lx]) | Classic Bluetooth, which is the transport `OBD2-PLAN.md` builds first. A physical button must be pressed before a new phone can pair. It sleeps at 2 mA, so it can stay in the port. Its command set is documented by the maker and no SDK or licence is needed. |
| OBD-II adapter, step up | OBDLink MX+ | $139.95 ([mxp]) | Same behaviour for Sierra Auto. The extra $50 buys GM's single-wire body network, iPhone support and the maker's GM add-on in its own app. Sierra Auto uses none of these today (section 2.7). |
| **OBD-II adapter, budget** | **Veepeak OBDCheck BLE+** | **$41.99** ([veepeak]) | Works over classic Bluetooth and Bluetooth LE and reports itself as ELM327 v2.2. Two costs: anyone nearby can pair with PIN 1234, and it has no low-power sleep, so unplug it when the truck will sit. |
| USB cable | A short (under 1 m) USB-IF certified data cable with a USB-C plug for the phone | not priced | Google and GM both name the cable as a common cause when Android Auto misbehaves ([aa-trouble], [bulletin]). Look at the truck's port before ordering: it is expected to be the older rectangular USB-A type (**unverified**). |
| Phone mount | An air-vent mount on the driver's side, out of direct sun | not priced | Heat is the A54's main enemy on long drives (section 5). Do not buy a wireless-charging mount: the A54 has no wireless charging listed ([gsmarena]). |
| Wireless Android Auto dongle | Nothing for now | — | They do work on this generation of truck, but they add heat, a slower start and one more thing to debug (section 4). |

Do not buy: an unbranded "ELM327 v1.5" or "v2.1" dongle (section 2.6), or any Wi-Fi OBD adapter (`OBD2-PLAN.md` §2: it takes over the phone's Wi-Fi).

**When the adapter arrives**, do these in order, parked:

1. Key off, plug it in. If its light comes on, the port is powered with the ignition off (section 2.3).
2. Key on, engine off. Pair it from the phone's Bluetooth settings (press the adapter's button first on an OBDLink).
3. Install the maker's app and let it update the adapter's firmware.
4. Only then point Sierra Auto at it.

---

## 2. OBD-II adapter

### 2.1 What Sierra Auto needs from the adapter

From `OBD2-PLAN.md`: plain ELM327 commands over a serial-style link; CAN at 500 kbaud with 11-bit IDs (`ATSP6`); the ability to change the request header with `ATSH` to `7E0`, `7E1` or `7E2` for GM Mode 22 requests; and about five requests per second. It never needs GM's other networks, never writes, and never changes adapter settings.

That is a low bar. Every branded adapter below clears it on paper. They differ in what happens when the adapter is left plugged in, who else can connect to it, and how well the maker documents it.

### 2.2 Comparison

| Adapter | Radio | Bluetooth LE service / characteristics | Command set | Awake / asleep current | Who can pair | Maker's price |
| --- | --- | --- | --- | --- | --- | --- |
| **OBDLink LX** | Classic Bluetooth 3.0 ([lx]) | none (classic only) | ELM327 AT set plus OBDLink's own ST set ([frpm]) | 60 mA / 2 mA ([lx]) | Button press needed ([lx]) | $89.95 |
| **OBDLink MX+** | Classic Bluetooth 3.0 ([mxp]) | none (classic only) | Same ([frpm]) | 62 mA / 2 mA ([mxp]) | Button press needed ([mxp], [usenix]) | $139.95 |
| **OBDLink CX** | Bluetooth LE 5.1 only, no classic ([cx-notes]) | Published by the maker: service `FFF0`, notify `FFF1`, write `FFF2` ([cx-notes]) | Same ([frpm]) | 55 mA / under 2 mA ([cx], [cx-notes]) | Only in the first 5 minutes after it gets power; link is always encrypted ([cx-notes]) | $79.95 |
| **Vgate vLinker MC+** | Sold as "BT4.0"; Android pairs with a PIN, which points to classic Bluetooth ([vgate-mcp]) | Not published by the maker. Open-source projects use service `18F0`, notify `2AF0`, write `2AF1` ([espcomp], [obdgraphs]). **Unverified** | Maker claims the ELM327 AT set and the STN ST set ([vgate-mcp]) | Not published for this model. **Unverified** | Fixed PIN 1234 ([vgate-mcp]) | $66.99 |
| **Vgate vLinker FS (Bluetooth)** | Bluetooth, Apple MFi; type not stated ([vgate-fs]) | Not published. **Unverified** | Not stated ([vgate-fs]) | 28 mA / 3 mA ([vgate-fs]) | Not stated ([vgate-fs]) | $65.99 |
| **Veepeak OBDCheck BLE+** | Classic and Bluetooth LE ([veepeak]) | Not published by the maker. Open-source projects use `FFF0` / `FFF1` / `FFF2` ([particle], [sprinter]). **Unverified** | Reports `ELM327 v2.2` ([veepeak], [sprinter]) | 45 mA max, no sleep mode listed ([veepeak]) | Fixed PIN 1234 on Android; no pairing at all over LE ([veepeak], [veepeak-howto]) | $41.99 |
| **Unbranded ELM327 clone** | Usually classic | varies | A copy of old v1.4 firmware, whatever the label says ([elm-wiki]) | unknown | Usually a fixed PIN (`OBD2-PLAN.md` §2) | not price-checked |

Notes on the table:

- **Custom headers and Mode 22.** `ATSH` with an 11-bit header such as `7DF` is in OBDLink's manual ([frpm]), and OBDLink's own app reads GM transmission fluid temperature from a "GM - CAN" manufacturer list ([obdlink-tft]). For the vLinker MC+, an open-source dashboard reads transmission fluid temperature and oil pressure through one on a Chevrolet Colorado ([espcomp]). For the Veepeak I found no report specific to GM Mode 22; `ATSH` is a basic ELM327 command, so it should work, but that is **unverified**. None of this confirms the particular IDs in `OBD2-PLAN.md` §7 for this truck.
- **The vLinker FS is a Ford tool.** Its page is about Ford's two networks and FORScan ([vgate-fs]). Nothing on it is useful for a GM truck that the cheaper vLinker MC+ lacks.
- **The OBDLink CX is sold for BMW coding** ([cx], [obdlink-choose]). It supports the CAN protocol this truck uses ([cx]), so it would work, and it is the only adapter here whose Bluetooth LE layout is published by its maker. It is the one to pick if the app ever goes LE-only.
- **Speed.** OBDLink advertises about 100 values per second for the MX+ on Android ([mxp-spec]). I found no independent measurement for any of these adapters that I could read first-hand, so real rates are **unverified**. OBDLink's manual says Bluetooth latency holds performance back ([frpm]). Sierra Auto plans about five requests per second, so speed does not decide this purchase.

### 2.3 Leaving it plugged in

**Is the Sierra's port powered with the ignition off?** Almost certainly yes, but it is **unverified** for this truck. Pin 16 of the standard connector is battery power for the scan tool ([obd-wiki]), and OBDLink's sleep-and-wake design assumes the adapter stays powered ([frpm]). A third-party fuse chart for this generation lists the port on the same fuse as the driver's seat module (seen only in a search summary, so **unverified**). The key-off light test in section 1 settles it in ten seconds.

What each adapter does once the engine is off:

- **OBDLink LX and MX+.** They go to sleep after 600 seconds with no traffic from the phone, and wake when battery voltage changes by 0.2 V within a second ([frpm]: the defaults). Starting the engine should do that (my reading, not the manual's words). Asleep they draw 2 mA ([lx], [mxp]). That is about 0.05 Ah a day, which a truck battery will not notice. The MX+ also wakes on network activity, and will not sleep until the truck's network has been quiet for 5 minutes ([frpm]).
- **OBDLink CX.** Under 2 mA asleep; the maker says it can stay plugged in ([cx-notes]).
- **Vgate.** The maker says the vLinker models sleep and wake by themselves ([vgate-mcp], [vgate-mc]) and gives 3 mA for the FS ([vgate-fs]). No figure is published for the MC+.
- **Veepeak BLE+.** No sleep mode. The maker says a few days is fine, and to remove it if the truck will sit for more than a week ([veepeak]). At its 45 mA maximum that is about 1 Ah a day.

Two things for the app, whichever adapter is used:

1. **After a sleep the adapter resets itself** ([frpm]: it performs a warm reset on wake). Echo, spacing, header and protocol settings are gone, so the app has to run its full init sequence again after every reconnect, not only on first use.
2. **Stop asking once the engine is off.** Requests on the truck's network keep the adapter awake, and may keep the truck's own modules awake (the second part is **unverified**). The polling loop should stop when the engine stops answering.

### 2.4 Who else can connect

A Bluetooth OBD adapter is a radio wired to the truck's network. In a 2020 study of 77 wireless OBD dongles, 71 let any nearby device connect, and only 6 required anything first. The OBDLink MX+ was one of the 6, through its button ([usenix]).

- **OBDLink LX and MX+.** A new phone can pair only after the button on the adapter is pressed ([lx], [mxp]); on the MX+ the window is two minutes ([mxp-qsg]). The link is encrypted ([lx], [mxp]). One caveat: the current OBDLink manual describes a setting, `STBTPM`, whose default is "Auto: automatically enters pairing mode" when the adapter powers up ([frpm]). So a new unit may also be open to pairing for a while each time it gets power. How long, and whether waking from sleep counts, is **unverified**. `STBTPM 1` makes it button-only. Sierra Auto will refuse to send that, correctly, because it writes to the adapter's memory (`OBD2-PLAN.md` §8 rule 3). If wanted, set it once from a Bluetooth serial terminal app.
- **OBDLink CX.** New pairings are accepted only in the first 5 minutes after it gets power ([cx-notes]). In a port that is always powered, that means only after it is physically plugged in.
- **Veepeak and Vgate.** Android pairs with the fixed PIN 1234 ([veepeak-howto], [vgate-mcp]), and the Veepeak needs no pairing at all over Bluetooth LE ([veepeak-howto]). Anyone in Bluetooth range of the parked truck can connect. Vgate's pages claim a "multi-layered link security mechanism" ([vgate-mc]) but do not say what it is. **Unverified.**

If you buy the budget adapter, unplug it when you park somewhere public.

### 2.5 Can Sierra Auto talk to it without the maker's SDK?

Yes for all of them. No maker SDK or licence came up anywhere.

- **OBDLink** publishes its full command manual ([frpm], [obdlink-docs]) and developer notes for the CX ([cx-notes]), and says the adapters are "fully compatible" with the ELM327 command set. When reset, an OBDLink prints `ELM327 v1.4b` ([frpm]), so `Elm327Info.identity` will not say "OBDLink"; that is normal.
- **Vgate** says the vLinker MC+ supports "user-defined" apps and lists Torque and others ([vgate-mcp]).
- **Veepeak** lists Torque Pro, Car Scanner, OBD Fusion and "other apps that are compatible with ELM327 adapters" ([veepeak]).

On Android the LX, MX+, vLinker and Veepeak appear as an ordinary Bluetooth serial device, which is what `OBD2-PLAN.md` §2 expects. That the standard serial-port UUID in the plan is the right one for each of them is common practice but **unverified** in maker documentation.

Bluetooth LE details that matter if an LE transport is used with the CX ([cx-notes]): the app must trigger pairing by subscribing to `FFF1` or writing to `FFF2`; the largest packet size is 247 bytes and must be requested; writes must wait for the previous write to finish.

### 2.6 Fakes and old firmware

- **Unbranded clones.** Elm Electronics never made a "v1.5". Clones are copies of the old v1.4 firmware, bugs included, and may report any version number ([elm-wiki]). Elm Electronics closed in June 2022 ([elm-wiki]), so no new "genuine ELM327" adapters exist. This is why the choice is between branded adapters with their own chips.
- **OBDLink.** I found no counterfeit warning from the maker and no reports of fake units. That is an absence of evidence, so **unverified**. Buying from obdlink.com avoids the question. Firmware updates are free through the OBDLink app ([lx], [mxp]); do one before first use, since a unit may have sat on a shelf.
- **Vgate.** The maker's own page says "Pirated products cannot be upgraded" ([vgate-fs]), so fakes exist. Buy from vgatemall.com or the maker's own storefront. Vgate's Android instructions say to pair with the device named `vLinker MC` or `vLinker MC-Android`, not `vLinker MC+` or `vLinker MC-IOS` ([vgate-mcp]).
- **Veepeak.** I found no firmware update path for it. **Unverified.**

### 2.7 The recommendation

**First choice: OBDLink LX, $89.95.** Three facts decided it:

1. It is classic Bluetooth, the transport `OBD2-PLAN.md` builds first, and its command set is the one the plan is written against, documented by the maker.
2. It is the cheapest adapter here that both needs a button press to pair and sleeps at 2 mA. Those are the two things that matter for an adapter that lives in the port.
3. The MX+ costs $50 more for things Sierra Auto cannot use. Its extra networks (GM single-wire CAN and Ford's medium-speed CAN, [mxp]) are not where the engine and transmission modules answer (`OBD2-PLAN.md` §1), and the app's safety list only allows four addresses on the main network anyway.

Buy the **MX+** instead if any of these apply: you also want the GM enhanced-diagnostics add-on in the maker's own app, which the maker recommends for trouble codes and which is MX+ only ([obdlink-tft]); you may move to an iPhone (the LX lists Android and Windows only, [lx]); or you want the adapter that was independently tested for pairing security ([usenix]).

**Budget choice: Veepeak OBDCheck BLE+, $41.99.** It offers both radios, so it works with the classic transport now and an LE transport later. A developer's log shows it answering `ELM327 v2.2` and holding a link through a 15-minute drive ([sprinter]). The price is paid in section 2.3 (no sleep) and section 2.4 (PIN 1234). Treat it as a tool you plug in for a drive, not a fixture.

---

## 3. The truck's Android Auto setup

**Wired only.** GM's manual for this radio family says to connect "by using the compatible phone USB cable and plugging into a USB data port" ([il17]). The module behind the screen in 2016-and-later trucks of this generation supports wired CarPlay and Android Auto ([hmi]). GM's bulletin on Android Auto launch problems covers the 2016–2018 Sierra with radio codes IO5 and IO6 ([bulletin]), which are the touchscreen IntelliLink radios without and with navigation ([hmi]).

A note on the manual: [il17] is GM's 2017 edition of the IntelliLink manual. I could not get the 2018 edition; the sites that host it block automated reading. The radio is the same family, but treat page-level details as **unverified** for 2018.

**Screen.** 8 inches. Replacement-panel sellers list it as 800×480, which is a seller's claim and **unverified** with GM. Sierra Auto is a template app, so it does not draw pixels; the Android Auto host lays out the templates. What matters is how many rows and how much text fit, and the app reads those limits at run time (`android-auto-constraints.md` §4). On the desktop head unit, test at 800×480 first.

**USB ports.** The manual says the truck has "two USB ports in the center console. These ports are for data and charging", and may have more in the armrest, the upper glove box and behind the radio ([il17]). Use a centre-console port. One 2018 Sierra owner reported that the glove-box port kept dropping Android Auto and that the dealer called it unsupported (single report, seen only in a search summary, **unverified**).

**Cable.**

- GM: "use the device's factory-provided USB cable. Aftermarket or third-party cables may not work" ([il17]); and use "a genuine brand" cable ([bulletin]).
- Google: under 1 m (3 ft), no hubs or extensions, and a cable that meets USB-IF standards. If Android Auto used to work and stopped, replace the cable ([aa-trouble]).

**Steering-wheel voice button.**

- Short press: answers a call, or starts the truck's own voice recognition ([il17]).
- Press and hold: passes through to the phone's assistant ([il17]). Under Android Auto, Google says to hold until you hear the tone, and warns that letting go too early can drop you out of Android Auto ([aa-voice]).
- So the button reaches the truck's system or Google Assistant. It does not reach Sierra's Talk screen. Sierra's Talk is started from its on-screen mic button. I found no way for an app like this to take over the wheel button; that is an inference from the constraints doc, so **unverified**.

**Cabin microphone.** When Sierra records through the Car App Library it gets the car's microphone, routed through the head unit (`android-auto-constraints.md` §7). Where that microphone sits in this truck is **unverified**. Per the README, if no transcription URL is set, Sierra uses the phone's speech recognizer instead; which microphone that uses under Android Auto is also **unverified**, so mount the phone where it can hear you.

**Known quirks for these model years** (all from [bulletin] unless noted):

- Projection has to be switched on in the radio's settings.
- The phone's and the radio's time and date must match, or Android Auto can fail with launch error 7 or 11.
- If Android Auto does not start by itself, touch the projection icon on the home screen.
- If problems began after a dealer replaced a USB socket, the wrong part may have been fitted.
- The screen module's storage wears out with age (6 to 10 years, says a company that sells replacements). Symptoms are boot loops, a black screen, or losing Android Auto ([hmi]). The truck is in that window. If Android Auto fails on every phone and cable, suspect the truck.

---

## 4. Wireless Android Auto dongles

**Do they work on this truck?** They should. Both AAWireless and the Motorola MA1 need only a car that already has wired Android Auto ([aaw-info], [ma1]). The phone needs Android 11 or later and 5 GHz Wi-Fi ([aa-req]); the A54 has dual-band Wi-Fi ([gsmarena]). Owners of this truck generation report AAWireless working, with a slow first connection and an occasional need to unplug and replug (forum reports seen only in a search summary, **unverified**).

**Would Sierra Auto behave differently through one?** I found no source that answers this directly, so the answer is reasoning and is **unverified** until tested:

- The dongle sits where the phone's cable would go and relays the same Android Auto session over Bluetooth and Wi-Fi ([aaw-info]). The apps still run on the phone.
- The check that decides whether a sideloaded app appears is made by Android Auto on the phone (`android-auto-constraints.md` §0), not by the truck or the dongle. So the same apps should appear.

What does change:

- **Voice.** Microphone audio and spoken replies cross a Wi-Fi link. AAWireless ships an "audio stutter fix" setting ([aaw-trouble]), which says stutter happens. Expect Talk to feel slower.
- **The phone's Wi-Fi is taken** by the link to the dongle, so the agent's calls go over mobile data.
- **Bluetooth is busier.** It carries the dongle's link and phone calls ([aaw-info]) as well as the OBD adapter. Whether the three interfere is **unverified**.
- **Heat and battery.** The phone is no longer charged by the cable (section 5).
- **Start-up.** Connection takes longer than plugging in.

Price: the Motorola MA1 launched at $89.95 ([ma1]). I could not confirm a current price for either product (**unverified**).

Recommendation: stay wired until Sierra Auto is proven on the truck. A dongle is a fine later convenience, but it adds an unknown while the sideloading question (`android-auto-constraints.md` §0) is still open.

---

## 5. Mounting and power for the Galaxy A54

What the phone is: 5000 mAh battery, 25 W wired charging, a USB 2.0 Type-C port, no wireless charging listed, dual-band Wi-Fi, Bluetooth 5.3 ([gsmarena]).

What Samsung says about heat ([samsung-temp]):

- Galaxy phones are meant for surroundings between 32 and 95 °F.
- A parked car on a hot day, use in sunlight, and long sessions of navigation or GPS apps all raise the phone's temperature.
- When it overheats, "the screen may get dimmer, the device may slow down, charging may pause, and apps will be automatically closed."

A phone running an agent, navigation and Bluetooth for hours is doing exactly what that list describes. A54 owners also report it getting hot during fast charging (owner forum posts seen only in a search summary, **unverified**).

So:

- **Mount it on an air vent on the driver's side**, where the air conditioning blows across its back. Keep it off the top of the dash and off the windshield in summer; both sit in direct sun.
- **Do not leave it in the parked truck** on a hot day.
- **Wired Android Auto charges the phone from the truck's USB port.** How much current that port gives is **unverified**. Expect a slow charge that holds the battery level, not a fast one. Slow charging makes less heat, which helps here.
- **With a wireless dongle the phone gets no power**, and wireless Android Auto works the phone harder. Add a 25 W USB-C charger in the 12 V socket. Fast charging plus wireless projection plus sun is the combination most likely to trigger the overheating behaviour above.
- **Let the phone's screen stay off** while Android Auto is running. The truck's screen is the display. (My inference, not from a source.)
- If the phone warms up on long drives, try it without its case.

---

## 6. Safety and legal

- While the truck is moving, Sierra Auto only reads. Clearing codes erases evidence a mechanic may need and resets the emissions readiness monitors, so it is a parked, deliberate act on the phone, never on the car screen (`OBD2-PLAN.md` §6). The app never writes to or reprograms a module (`OBD2-PLAN.md` §8).
- The adapter is a radio on the truck's network. Researchers have used the OBD port to control vehicle components ([obd-wiki]) and found most dongles open to anyone nearby ([usenix]). Prefer the adapter with a pairing button, and unplug the budget one in public places.
- Phone-use and hands-free laws differ by state and I did not research them (**unverified**). Set up, pair and change settings while parked. While driving, use voice and the truck's screen, not the phone.

---

## 7. Unverified items

To test on the truck:

- The OBD port stays powered with the ignition off (section 2.3). Test: key off, plug in, look for the light.
- Whether polling with the engine off keeps the truck's modules awake.
- Real polling rate of the chosen adapter.
- The truck's USB ports are USB-A, and how much charging current they give.
- Where the cabin microphone is, and which microphone the phone's speech recognizer uses under Android Auto.
- Sierra Auto appears and behaves the same through a wireless dongle.
- Bluetooth holds up with a dongle, phone calls and the OBD adapter at once.

Not confirmed from a source I read myself:

- The 8-inch panel is 800×480 (seller listings only).
- 2018-edition wording of the IntelliLink manual (the 2017 edition was used).
- The glove-box USB port is unreliable for Android Auto (one owner's report).
- Owner reports of wireless dongles on this truck generation, and of the A54 running hot while charging (forum threads I could not open).
- The OBD port's fuse is shared with the driver's seat module.
- Bluetooth LE layouts for the Veepeak BLE+ and vLinker MC+ (from open-source code, not the makers).
- Sleep current for the vLinker MC+; Vgate's "link security mechanism".
- Whether a new OBDLink LX or MX+ is open for pairing after every power-up, and for how long.
- No fake OBDLink units exist; no firmware update path exists for the Veepeak.
- Veepeak handles GM Mode 22 requests with a custom header.
- The standard serial-port UUID is correct for each classic adapter.
- An independent speed comparison of OBDLink against cheap adapters. A tuning-software developer's forum post is quoted in search results as 47 against 20 values per second; the page would not load.
- Current US street prices. Prices here are from each maker's own store; clone and dongle prices were not checked.
- Nothing I read says Android 16 changes classic Bluetooth serial connections, but I did not research it specifically.
- State hands-free law.

---

## 8. Sources

OBD-II adapters

- `lx` — OBDLink LX product page (maker): https://www.obdlink.com/products/obdlink-lx/
- `mxp` — OBDLink MX+ product page (maker): https://www.obdlink.com/products/obdlink-mxp/
- `mxp-spec` — OBDLink MX+ specification table (maker): https://www.scantool.net/obdlink-mxp/
- `mxp-qsg` — OBDLink MX+ Quick Start Guide for Android (maker, PDF): https://www.scantool.net/scantool/downloads/264/mxp_qsq_android.pdf
- `cx` — OBDLink CX product page (maker): https://www.obdlink.com/products/obdlink-cx/
- `cx-notes` — OBDLink CX adapter notes for developers (maker): https://support.obdlink.com/support/solutions/articles/43000746707-obdlink-cx-adapter-notes
- `obdlink-choose` — OBDLink "which adapter" support article (maker): https://support.obdlink.com/support/solutions/articles/43000713351
- `frpm` — OBDLink Family Reference and Programming Manual, revision F, August 2025 (maker, PDF): https://www.scantool.net/scantool/downloads/682/obdlink_frpm_f.pdf
- `obdlink-docs` — OBDLink product documentation index (maker): https://scantool.net/scantool/downloads/product-documentation
- `obdlink-tft` — OBDLink support article on showing transmission temperature (maker): https://support.obdsol.com/en/support/solutions/articles/43000708843
- `vgate-mcp` — Vgate vLinker MC+ product page (maker's store): https://vgatemall.com/products-detail/i-5/
- `vgate-mc` — Vgate vLinker MC product page (maker's store): https://www.vgatemall.com/products-detail/i-12/
- `vgate-fs` — Vgate vLinker FS Bluetooth product page (maker's store): https://www.vgatemall.com/products-detail/i-52/
- `veepeak` — Veepeak OBDCheck BLE+ product page and FAQ (maker): https://veepeak.com/products/obdcheck-ble-plus
- `veepeak-howto` — Veepeak OBDCheck BLE+ user instructions (maker): https://veepeak.com/pages/obdcheck-ble-plus-user-instructions
- `particle` — Particle BLE gateway library, Veepeak example (open source): https://github.com/particle-iot/ble-gateway-library/blob/main/examples/veepeak/README.md
- `sprinter` — sprinter-obd-gauge, connection log with a Veepeak BLE+ (open source, one developer): https://github.com/ttarlov/sprinter-obd-gauge/blob/main/issues/OBD-19.md
- `espcomp` — ESPHome dashboard for a Chevrolet Colorado using a vLinker MC+ (open source, one developer): https://github.com/eigger/espcomponents/blob/master/packages/display/colorado/README.md
- `obdgraphs` — ObdGraphs Bluetooth LE profiles (open source): https://github.com/tzebrowski/ObdGraphs/blob/master/datalogger/src/main/java/org/obd/graphs/bl/datalogger/connectors/BleProfiles.kt
- `usenix` — Wen, Chen, Lin, "Plug-N-Pwned: Comprehensive Vulnerability Analysis of OBD-II Dongles", USENIX Security 2020 (PDF): https://www.usenix.org/system/files/sec20-wen.pdf
- `elm-wiki` — Wikipedia, "ELM327": https://en.wikipedia.org/wiki/ELM327
- `obd-wiki` — Wikipedia, "On-board diagnostics": https://en.wikipedia.org/wiki/On-board_diagnostics

Truck and Android Auto

- `il17` — GMC IntelliLink Infotainment System manual, 2017 edition (GM document, hosted by a third party, PDF): https://cdn.shopify.com/s/files/1/0234/3499/files/2k17gmcintellilink1stPrint.pdf
- `bulletin` — GM Service Bulletin 17-NA-074, December 2017, "Tips for Customers Having Difficulty Launching Android Auto or Apple CarPlay" (NHTSA copy, PDF): https://static.nhtsa.gov/odi/tsbs/2017/MC-10139225-9999.pdf
- `hmi` — White Automotive & Media Services, guide to the GM HMI module (a company that sells replacements): https://www.whiteautoandmedia.com/guides/gm-hmi-module/
- `aa-voice` — Google, Android Auto Help, "Voice commands": https://support.google.com/androidauto/answer/6348334
- `aa-req` — Google, Android Auto Help, minimum requirements: https://support.google.com/androidauto/answer/6348019
- `aa-trouble` — Google, Android Auto Help, troubleshooting: https://support.google.com/androidauto/answer/6348190

Wireless dongles

- `aaw-info` — AAWireless support, product information: https://www.aawireless.io/en/support/topics-faq-aawireless/product-info
- `aaw-trouble` — AAWireless support, troubleshooting: https://www.aawireless.io/en/support/topics-faq-aawireless/troubleshooting
- `ma1` — XDA Developers on the Motorola MA1 launch: https://www.xda-developers.com/motorola-ma1/

Phone

- `gsmarena` — GSMArena, Samsung Galaxy A54 specifications: https://www.gsmarena.com/samsung_galaxy_a54-12070.php
- `samsung-temp` — Samsung US support, "Keep your Galaxy device at its normal operating temperature": https://www.samsung.com/us/support/answer/ANS10002887/

[lx]: https://www.obdlink.com/products/obdlink-lx/
[mxp]: https://www.obdlink.com/products/obdlink-mxp/
[mxp-spec]: https://www.scantool.net/obdlink-mxp/
[mxp-qsg]: https://www.scantool.net/scantool/downloads/264/mxp_qsq_android.pdf
[cx]: https://www.obdlink.com/products/obdlink-cx/
[cx-notes]: https://support.obdlink.com/support/solutions/articles/43000746707-obdlink-cx-adapter-notes
[obdlink-choose]: https://support.obdlink.com/support/solutions/articles/43000713351
[frpm]: https://www.scantool.net/scantool/downloads/682/obdlink_frpm_f.pdf
[obdlink-docs]: https://scantool.net/scantool/downloads/product-documentation
[obdlink-tft]: https://support.obdsol.com/en/support/solutions/articles/43000708843
[vgate-mcp]: https://vgatemall.com/products-detail/i-5/
[vgate-mc]: https://www.vgatemall.com/products-detail/i-12/
[vgate-fs]: https://www.vgatemall.com/products-detail/i-52/
[veepeak]: https://veepeak.com/products/obdcheck-ble-plus
[veepeak-howto]: https://veepeak.com/pages/obdcheck-ble-plus-user-instructions
[particle]: https://github.com/particle-iot/ble-gateway-library/blob/main/examples/veepeak/README.md
[sprinter]: https://github.com/ttarlov/sprinter-obd-gauge/blob/main/issues/OBD-19.md
[espcomp]: https://github.com/eigger/espcomponents/blob/master/packages/display/colorado/README.md
[obdgraphs]: https://github.com/tzebrowski/ObdGraphs/blob/master/datalogger/src/main/java/org/obd/graphs/bl/datalogger/connectors/BleProfiles.kt
[usenix]: https://www.usenix.org/system/files/sec20-wen.pdf
[elm-wiki]: https://en.wikipedia.org/wiki/ELM327
[obd-wiki]: https://en.wikipedia.org/wiki/On-board_diagnostics
[il17]: https://cdn.shopify.com/s/files/1/0234/3499/files/2k17gmcintellilink1stPrint.pdf
[bulletin]: https://static.nhtsa.gov/odi/tsbs/2017/MC-10139225-9999.pdf
[hmi]: https://www.whiteautoandmedia.com/guides/gm-hmi-module/
[aa-voice]: https://support.google.com/androidauto/answer/6348334
[aa-req]: https://support.google.com/androidauto/answer/6348019
[aa-trouble]: https://support.google.com/androidauto/answer/6348190
[aaw-info]: https://www.aawireless.io/en/support/topics-faq-aawireless/product-info
[aaw-trouble]: https://www.aawireless.io/en/support/topics-faq-aawireless/troubleshooting
[ma1]: https://www.xda-developers.com/motorola-ma1/
[gsmarena]: https://www.gsmarena.com/samsung_galaxy_a54-12070.php
[samsung-temp]: https://www.samsung.com/us/support/answer/ANS10002887/
