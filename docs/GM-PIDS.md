# GM Mode 22 PIDs for the 2018 Sierra 1500 Denali

**Researched:** 2026-11-05  
**Truck:** 2018 GMC Sierra 1500 Denali, K2XX platform, 5.3L L83 or 6.2L L86 (E92 ECM), 8L90 8-speed (T87A TCM); the 6L80 is the other possibility  
**Code:** `app/src/main/java/com/mdc159/sierra/obd/gm/` (`GmDids`, `GmResponseDecoder`, `GmTruckReader`)  
**Companion docs:** `docs/OBD2-PLAN.md` (bus, adapter, safety rules), `docs/lanes/f-gm-pids.md` (the brief)

> ## Nothing here is verified on this truck
>
> Every request, header and formula below comes from public community sources: scan-tool
> PID lists, enthusiast forums and open-source projects. **None has been checked on the
> owner's Sierra.** The confidence column says how many independent sources agree, not
> whether the truck answers. A value from one of these DIDs must be shown as *unverified*
> on any screen until the owner has compared it with the driver information center (DIC)
> and recorded the result in this file.
>
> **How the research was done.** The cloud sandbox's network policy blocks every forum and
> vendor host involved (silveradosierra.com, gm-trucks.com, tahoeyukonforum.com,
> coloradofans.com, gmtnation.com, ssforums.com, torque-bhp.com, chevroletforum.com,
> scangauge.com, autometer.com, support.obdlink.com, forum.gps-laptimer.de). Those pages
> were read through search-engine excerpts that quote the posts, not in full. Only the
> GitHub source was read directly. Where an excerpt could have cut context, the table says
> so. Re-reading the full threads from the owner's PC is a worthwhile 20 minutes before
> the first truck test.

## 1. What Mode 22 is and how the truck is addressed

Service `22` is UDS ReadDataByIdentifier (ISO 14229, used by GM as "enhanced" data).
The request is `22 <DID high> <DID low>`; a positive reply is `62 <DID high> <DID low> <data…>`;
a refusal is `7F 22 <NRC>`. Unlike Mode 01, nothing is broadcast: the request has to be
addressed to the module that owns the DID with `AT SH <header>`. With CAN auto-formatting on,
the ELM327 listens for the physical reply (`header + 8`) by itself.

| Module | `AT SH` | Replies on | Owns |
| --- | --- | --- | --- |
| ECM (E92) | `7E0` | `7E8` | oil pressure, oil temperature, oil life, (probably) current gear |
| TCM | `7E2` | `7EA` | transmission fluid temperature on most GM trucks |
| Alternate TCM address | `7E1` | `7E9` | the TCM on some GM vehicles; try it if `7E2` is silent |

The 8L90's T87A TCM is a separate module, unlike the 6L80's TEHCM inside the transmission.
Which diagnostic address it uses on this truck is exactly what the first test (section 7)
finds out. All three headers are already on `ObdSafety.ALLOWED_HEADERS`.

## 2. DIDs with two or more independent sources (implemented in `GmDids`)

Formulas use the `ObdPid` convention: `A` is the first data byte, values are SI, the display
layer converts to °F and psi. "Bytes" is the data after the `62 DD DD` echo. Confidence is
given twice: for the DID itself (is this the right request?) and for the formula.

| DID | Request | To | Reply | Bytes | Formula | Unit | DID confidence | Formula confidence | Sources |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `1940` | `22 19 40` | TCM `7E2` (fallback `7E0`, then `7E1`) | `62 19 40 A` | 1 | `A − 40` | °C | **Confirmed** (5) | **Confirmed** (5) | torque-gm-set, scangauge-gm, coloradofans, gmtnation, python-obd-287 |
| `1154` | `22 11 54` | ECM `7E0` | `62 11 54 A` | 1 | `A − 40` | °C | **Confirmed** (5) | **Confirmed** (5) | torque-gm-set, harrys-gps, ssforums, coloradofans, gmtnation |
| `1470` | `22 14 70` | ECM `7E0` | `62 14 70 A` | 1 | `A × 4` (gauge) | kPa | **Confirmed** (4) | **Confirmed** (3) | torque-gm-set, ssforums, scangauge-gm, gmtnation |
| `115C` | `22 11 5C` | ECM `7E0` | `62 11 5C A` | 1 | `A × 4` (absolute, see note) | kPa | **Confirmed** (4) | Single source (reconciled) | tahoeyukon, coloradofans-oilp, chevroletforum, scangauge-gm |
| `119F` | `22 11 9F` | ECM `7E0` | `62 11 9F A` | 1 | `A × 100 / 255` | % | **Confirmed** (2) | **Confirmed** (2) | scangauge-oil-life, gmtnation-envoy |
| `199A` | `22 19 9A` | ECM `7E0` (fallback `7E2`) | `62 19 9A A` | 1 | `A` | gear | **Confirmed** (2) | Single source | coloradofans, torque-bhp-gm-pids |

Notes per DID:

- **`1940` transmission fluid temperature.** The most widely published GM extended PID. Torque's
  built-in "Pontiac/GM/Opel/Vauxhall" set carries it twice: *Method 1* with header Auto and
  *Method 2* with header `7E2`, both `A − 40`; the Chevy SS forum says Method 2 is the one that
  works. ScanGauge's GM CANSF X-Gauge is `07E2 221940(01)` with math `(A×9/5) − 40`, which is
  the same `A − 40 °C` expressed in °F, and ScanGauge also lists an "Alt" entry on `07E0`, so the
  ECM may answer it too. A Chevy Bolt CSV on GitHub lists it under the same name (it fails on the
  Bolt, an EV, which is expected). Torque's *Method 3* (`22 1949`) is reported by several people
  to return coolant temperature, so it is not used.
- **`1154` engine oil temperature.** Reported to match the dash on a GMT360 and to work on a 2015
  Chevy SS. The E92 should also answer standard Mode 01 PID `5C` (already in `ObdPids`); read both
  on the first test and keep whichever answers. One LS3 post claimed `115C` returns a *calculated*
  oil temperature instead; that conflicts with everything else and is treated as a mix-up.
- **`1470` engine oil pressure.** Three sources give the same scale in different units, which is
  the strongest kind of agreement in this table: Torque's GM set uses `A × 0.578 psi` (confirmed
  working on a 2015 Chevy SS), ScanGauge's Duramax X-Gauge for the same DID uses math `29/50`
  psi per count (= 0.58 psi = 4.0 kPa), and a GMT360 list has `A × 3.985` labelled PSI, which is
  the kPa scale with the wrong unit written next to it. The GMT360 poster got nothing because the
  4.2L has only an oil pressure *switch*; the L83/L86 have a real sensor. One Impala poster saw
  `1470` read zero while `115C` worked, so `115C` stays as the fallback.
- **`115C` oil pressure sensor.** Four sources agree on the DID, none on the formula: `(A×0.65)−17.5
  psi` (2005 Yukon XL, header Auto), `(A×0.578)−14.7 psi`, `0.6×(A−27) psi` ("agrees better with my
  Tech2"), `(A×0.65)−26.5 psi` (trial and error, "no science"). `(A×0.578) − 14.7 psi` is exactly
  `A × 4 kPa − 101.3 kPa`, i.e. **4 kPa per count of absolute pressure**, and the other fits are
  within a few psi of that line. The decoder therefore returns absolute kPa; subtract barometric
  pressure (Mode 01 PID `33`) before comparing with the gauge. Treat the formula as a hypothesis.
- **`119F` oil life remaining.** ScanGauge's "GM specific CANSF remaining oil life" X-Gauge
  (`07E0 22119F(01)`, percent) and an Envoy Torque setup (`A/2.55`) agree. The existing
  `GmExtendedPids.OIL_LIFE` in `obd/ObdPids.kt` guesses `119C`; no source supports `119C`, so an
  issue was filed rather than editing lane C's file.
- **`199A` current gear.** The Colorado/Canyon list has `199A01` "trans current gear" on the ECM with
  equation `A`; a Torque forum list has `22199A` "Commanded Gear", equation `A`, range 0–4, "shows 3
  in Park". One user got nothing on either `7E0` or `7E2`. Which module answers on the 8L90 and what
  Park, Reverse and Neutral read are unknown. Useful only after the truck has shown it works.

### The trailing `01`

ScanGauge and some Torque files write the request as `22194001`. The extra byte is a scan-tool
habit (Torque's Auto header "retries with an appended 01"); the plain `221940` is what Torque's
predefined set sends and what every forum post uses. `ObdSafety` allows exactly one 2-byte DID
after `22`, so `22194001` is refused. That is correct behaviour and needs no change.

## 3. DIDs with one source or none (documented only, not in code)

| DID | To | Name | Formula | Confidence | Source and caveat |
| --- | --- | --- | --- | --- | --- |
| `1992` | `7E2` | Transmission slip speed | `A / 40`? | Single source | coloradofans lists `199201 A/40`; unit unclear from the excerpt |
| `1991` | `7E2`? | TCC slip speed | unknown | Single source | gmtnation ZD3SF list names it, no formula in the excerpt |
| `1949` | `7E2` | "Trans temp method 3" | `A − 40` | Guess (disputed) | Torque GM set; reported to be coolant temperature |
| `17AC` | `7E2` | Transmission gear (older guess) | unknown | Guess | Named in `docs/OBD2-PLAN.md` only; no public source found for the K2XX |
| `165E`… | `7E0` | Misfire counters | unknown | Guess | Named in `docs/OBD2-PLAN.md` only |
| — | — | AFM / V4 mode state | — | Not found | Searched; only hardware disablers turned up, no read DID |
| — | — | Knock retard, ethanol content | — | Not found | Not found for the E92 in public lists |
| `43AF`, `28CB`, … | `7E4`/`7E1` | Bolt EV battery and motor DIDs | various | n/a | From python-OBD issue 287; EV only, listed to show the header `7E4` is *not* needed on this truck |

## 4. What `ObdSafety` allows, and why nothing needed to change

`ObdSafety.blockReason` passes `22` followed by exactly four hex digits, and `AT SH` to `7DF`,
`7E0`, `7E1`, `7E2`. Every request in section 2 is therefore allowed as-is; `GmDidsTest` proves it
for each DID and header, and `GmDid`'s constructor refuses any definition the allowlist would
block. No change to the allowlist was needed, and none is proposed.

Why Mode 22 is still read-only: ReadDataByIdentifier only returns data. It cannot start a
diagnostic session (`10`), unlock security access (`27`), write a DID (`2E`), control an actuator
(`2F`, `AE`) or run a routine (`31`); all of those stay refused. The worst a wrong DID can do is
a `7F 22 31` refusal or `NO DATA`, which `GmReading` reports as ordinary results.

## 5. Polling while driving

All six DIDs are reads and are safe to request while driving in the sense that matters: they
cannot change module state. The limits are bus courtesy and usefulness, so `GmDids` carries an
interval per DID:

| DID | While driving | Interval | Why |
| --- | --- | --- | --- |
| `1940` trans fluid temp | Yes | 5 s | Changes over minutes; the towing number |
| `1470` oil pressure | Yes | 1 s | The one worth a live gauge |
| `115C` oil pressure sensor | Only until `1470` is proven | 1 s | Fallback; drop once `1470` reads |
| `1154` oil temp | Yes | 5 s | Slow; cross-check with Mode 01 `5C` |
| `199A` current gear | Yes, once proven | 1 s | Only after Park/Neutral semantics are known |
| `119F` oil life | Read once per trip | — | Changes once per drive at most |

Cost: `Elm327Client.readExtended` sends `AT SH <module>`, the request, then `AT SH 7DF`, so one
extended read is three commands (roughly 0.3–0.5 s on a decent adapter, more on a clone). With
the intervals above the steady-state load is about 2 reads/s, well under what the bus and a
cheap ELM327 handle. If the coordinator wants a tighter loop, a small hook in `Elm327Client`
that leaves the header set between consecutive reads to the same module (and restores `7DF`
on the way out) would cut this to one command per read. That hook is described in the PR,
not implemented here, because `Elm327Client` belongs to lane C.

Never run `readWithFallbacks` in a loop: it is for discovery, up to three module addresses per
DID. Once the module is known, poll with `read(did, module)`.

## 6. Negative response codes you may see

| `7F 22 xx` | Meaning | What to do |
| --- | --- | --- |
| `11` | serviceNotSupported | This module does not do Mode 22 at this address; try the fallback module |
| `12` | subFunctionNotSupported | Same as above in practice |
| `31` | requestOutOfRange | The module does not know this DID; try the fallback module |
| `22` | conditionsNotCorrect | The module knows the DID but will not answer now (engine off, bus busy); do not change module |
| `33` | securityAccessDenied | The DID needs a security session; this app never opens one, so drop the DID |
| `78` | responsePending | Wait for the final reply; `GmResponseDecoder` treats a lone `78` as no reply |
| `NO DATA` | nobody answered | Wrong address, key off, or the DID is silent; try the fallback module |

## 7. First test on the truck

Parked, engine warm (after a 15-minute drive), key on, adapter paired, `Elm327Client.initialize()`
done. Call `GmTruckReader(client).firstTest()`, or send the commands by hand from a terminal app.
`firstTest()` probes the fallback modules automatically and returns a `GmReading` per DID.

Try these three, in this order:

1. **Transmission fluid temperature, `22 1940`.**
   `AT SH 7E2` → `22 19 40`. Expect `62 19 40 xx`. If `7F 22 31` or `NO DATA`, `AT SH 7E0` and
   repeat, then `AT SH 7E1`. Compare with the DIC: Settings → Info → Trans Temp.
   **Sane after a warm drive, at idle:** 70–95 °C (160–200 °F). The 8L90's thermal bypass valve
   opens near 90 °C, so a warm truck hovers in the high 80s to low 90s. Towing in summer: up to
   ~110 °C. Readings of exactly −40 (`00`) or 215 (`FF`) mean the byte is not a temperature.
2. **Engine oil pressure, `22 1470`.**
   `AT SH 7E0` → `22 14 70`. Expect `62 14 70 xx`. Compare with the DIC oil pressure gauge.
   **Sane at warm idle:** 170–280 kPa (25–40 psi); rising to 350–450 kPa (50–65 psi) by
   3,000 rpm. GM bulletin PIP5121B notes the 2015–2018 5.3L/6.2L two-stage oil pump makes the
   gauge jump from about 40 to about 60 psi above roughly 3,700 rpm; that is normal. If `1470`
   reads `00` while the gauge shows pressure, read `22 11 5C` instead, subtract barometric
   pressure (Mode 01 PID `33`, about 100 kPa at sea level) and compare again.
3. **Engine oil temperature, `22 1154`.**
   `AT SH 7E0` → `22 11 54`. Expect `62 11 54 xx`. Also read Mode 01 `01 5C` on `7DF`.
   **Sane at warm idle:** 90–110 °C (195–230 °F), within about 15 °C of coolant (Mode 01 `05`),
   and rising more slowly than coolant after a cold start. The DIC does not show oil
   temperature on this truck, so the Mode 01 reading and plausibility against coolant are the
   check.

Then, still parked: `22 11 9F` on `7E0` should match the DIC's "Remaining oil life" percentage
within one percent. Leave `22 19 9A` for a drive with a passenger watching: note what it reads in
Park, Reverse, Neutral and each gear, and write the mapping here.

Record results in this table (date, VIN prefix, reading, DIC value, verdict), and only then flip
anything to "verified" in code:

| Date | DID | Module that answered | Raw byte | Decoded | DIC showed | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| | | | | | | |

## 8. Sources

Labels are the ones used in `GmDids.sources`. "Excerpt" means the page could not be fetched
from the sandbox and was read through search-engine quotations of the post.

| Label | Source | Read how |
| --- | --- | --- |
| torque-gm-set | Torque Pro's predefined "Pontiac/GM/Opel/Vauxhall" PID set, as quoted in https://torque-bhp.com/community/main-forum/transmission-temperature-2/ and https://www.ssforums.com/threads/transmission-temp-pid.130250/ | Excerpt |
| ssforums | https://www.ssforums.com/threads/obd2-pid-codes.41346/ (2015 Chevy SS: `221470 A×0.578` psi and `221154` work; `221940` Method 2) | Excerpt |
| scangauge-gm | ScanGauge X-Gauge GM lists: https://www.scangauge.com/x-gauge-commands/gm/ , https://www.scangauge.com/xgauge/gm-specific-cansf-allison-transmission-temp-5-speed/ (`07E2 221940(01)`, MTH `00090005FFD8`), LB7/LLY oil pressure `6C10F1 22147001` MTH `001D00320000` psi, VPW `6C10F1 22115C` kPa | Excerpt |
| scangauge-oil-life | https://www.scangauge.com/xgauge/gm-specific-cansf-remaining-oil-life/ (`07E0 22119F(01)`, %) | Excerpt |
| coloradofans | https://www.coloradofans.com/threads/gm-pids-for-scantools-obd-tools-torque-app-xgauge-etc.294922/ (`194001` on `7E2` `A−40`; `115401` ECM; `199A01` ECM gear; `199201` `7E2` slip) | Excerpt |
| coloradofans-oilp | https://www.coloradofans.com/threads/torque-pro-oil-pressure-pid.432131/ (`22115C (A×0.65)−17.5` psi, read ~5 psi high, adjusted to −26.5) | Excerpt |
| gmtnation | https://gmtnation.com/forums/threads/more-pids-for-torque-app.20176/ and https://gmtnation.com/forums/threads/extra-pids-from-with-obdlink-app.22218/ (GMT360: `194001 A−40`, `115401 A−40` matched dash, `199A01` gear, `199101` TCC slip, `147001 A×3.985`) | Excerpt |
| gmtnation-envoy | https://gmtnation.com/forums/threads/2008-envoy-sle-oil-life-monitor-pid.14559/ (`22119F`, `A/2.55` %) | Excerpt |
| tahoeyukon | https://www.tahoeyukonforum.com/threads/oil-pressure-pid-for-torque-pro.85316/ (`22115c (A×0.65)−17.5` psi on a 2005 Yukon XL; `0.6×(A−27)`; `A×0.578`) | Excerpt |
| chevroletforum | https://chevroletforum.com/forum/express-savana-g-series-vans-30/elm-torque-thread-87881/ (`22115c (A×0.578)−14.7` psi) | Excerpt |
| harrys-gps | http://forum.gps-laptimer.de/viewtopic.php?t=2292 (`221154` C `A−40` −40..215; `221940` C `A−40`) | Excerpt |
| torque-bhp-gm-pids | https://torque-bhp.com/community/main-forum/some-extended-gm-pids-found-online/ (`22199A` Commanded Gear, `A`, 0–4, 3 in Park) | Excerpt |
| python-obd-287 | https://github.com/brendan-w/python-OBD/issues/287 (community CSV: `221940` transmission fluid temperature; Bolt EV) | Read in full |
| gm-trucks | https://www.gm-trucks.com/forums/topic/183534-torque-app-oil-pressure-gear-other-good-info/ (2014–2018 Silverado/Sierra thread; the title promises oil pressure and gear PIDs, but no excerpt with the actual PIDs was retrievable; **read this one first from the PC**) | Not readable |
| obdlink | https://support.obdlink.com/support/solutions/articles/43000708843-display-transmission-temperature-gauge (OBDLink's OEM trans temp list includes a GM entry) | Not readable |
| GM PIP5121B | https://static.nhtsa.gov/odi/tsbs/2018/MC-10137209-9999.pdf (two-stage oil pump, 2015–2018 5.3L/6.2L, gauge step ~40→60 psi above ~3,700 rpm) | Excerpt |
| GM TBV bulletin | https://static.nhtsa.gov/odi/tsbs/2024/MC-10251353-0001.pdf (8L90 thermal bypass valve opens at ~194 °F / 90 °C) | Excerpt |

Not found despite searching: an authoritative E92 or T87A DID list (HP Tuners and EFILive have
them but do not publish them), a Car Scanner or OBD Fusion GM PID export, and any GitHub
repository with a K2XX Torque PID file.
