# Sierra Auto — OBD-II Plan

**Status:** wired to the car screen (Truck and Codes) through demo, Bluetooth classic and Bluetooth LE transports. Demo mode is tested; the real transports are unverified until an adapter is plugged in. See section 11.  
**Vehicle:** 2018 GMC Sierra 1500 Denali (6.2L L86, 8-speed 8L90 or 10-speed 10L80 depending on build)  
**Phone:** Samsung Galaxy A54, Android 14+  
**Adapter:** Bluetooth ELM327-class dongle in the OBD-II port under the dash

This document is the reference for the `com.mdc159.sierra.obd` package. Code in that package is testable on the JVM with a fake transport and has no Android dependency. Anything marked **UNVERIFIED** has not been confirmed on this truck and must be shown as such if it ever reaches a screen.

## 1. The bus

Every 2008+ GM light truck speaks **ISO 15765-4 CAN, 11-bit identifiers, 500 kbaud** on OBD pins 6 and 14. On an ELM327 that is protocol **6** (`ATSP6`). Setting it explicitly skips the ~10 s protocol search that `ATSP0` (auto) triggers on the first request.

Addresses that matter:

| CAN ID | Module | Notes |
| --- | --- | --- |
| `7DF` | Functional broadcast | Default request header. Every emissions ECU answers. |
| `7E0` → `7E8` | ECM (engine) | Request → response |
| `7E1` → `7E9` | Second powertrain module on some GM | Rarely the TCM on this platform |
| `7E2` → `7EA` | TCM (transmission) | Where GM trucks usually put transmission data. **UNVERIFIED** for this truck; try `7E1` if `7E2` returns `NO DATA`. |

`ObdSafety.ALLOWED_HEADERS` is exactly `7DF`, `7E0`, `7E1`, `7E2`. Nothing else may be addressed.

## 2. Adapter choice: Bluetooth classic vs BLE

| | Bluetooth classic (SPP/RFCOMM) | Bluetooth Low Energy (GATT) |
| --- | --- | --- |
| Typical products | Most cheap "ELM327 v1.5" dongles, OBDLink MX+, Veepeak OBDCheck BLE+ (dual mode) | Veepeak BLE, vLinker FS/BLE, Carista, some OBDLink models in BLE mode |
| Android API | `BluetoothSocket` via `createRfcommSocketToServiceRecord(SPP UUID 00001101-0000-1000-8000-00805F9B34FB)` | `BluetoothGatt` with a vendor service; a write characteristic and a notify characteristic |
| Pairing | System Settings pairing, PIN usually `1234` or `0000` | Usually none; app connects by scan |
| Throughput | High, stream-like; 20+ PIDs/s realistic | Lower; 20-byte MTU unless negotiated; 5-10 PIDs/s typical |
| Coexistence with Android Auto | Android Auto on this truck is **wired USB**, so the phone's Bluetooth is free. If the truck is also paired for phone calls (HFP/A2DP), classic SPP still works alongside. | Same, and BLE uses less radio time |
| Android permissions (API 31+) | `BLUETOOTH_CONNECT` | `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`; location-free if `neverForLocation` flag is set |
| Simplicity | Simplest: an `InputStream`/`OutputStream` pair maps straight onto `ObdTransport` | Needs chunking writes to the MTU and reassembling notifications |

**Recommendation:** start with a Bluetooth classic adapter. The `ObdTransport` interface is byte-stream shaped so a classic socket is a 40-line wrapper. Add a BLE transport later only if the chosen adapter needs it. Avoid Wi-Fi adapters: they take over the phone's Wi-Fi interface.

Adapter quality matters more than the radio. Many $10 clones report `ELM327 v1.5` (a version Elm Electronics never shipped) and have incomplete command support, slow protocol handling, or drop frames on multi-ECU replies. A genuine ELM327 or an STN11xx/STN22xx-based adapter (OBDLink) behaves per the datasheet. The `ATI` response is logged in `Elm327Info.identity` so we know what we are talking to.

## 3. ELM327 init sequence

`Elm327Client.initialize()` sends exactly this, waiting for the `>` prompt after each line:

| Command | Purpose | Expected reply |
| --- | --- | --- |
| `ATZ` | Reset the chip. Takes 1-2 s on clones. | banner, e.g. `ELM327 v1.5` |
| `ATE0` | Echo off, so replies don't contain our own command | `OK` |
| `ATL0` | Linefeeds off; lines end in `\r` only | `OK` |
| `ATS0` | Spaces off, so `410C0BB8` instead of `41 0C 0B B8` | `OK` |
| `ATH0` | Headers off; the client reads the functional reply without caring which ECU | `OK` |
| `ATAT1` | Adaptive timing on: adapter shortens its wait once it learns ECU response times | `OK` |
| `ATSP6` | ISO 15765-4 CAN 11/500 | `OK` |
| `0100` | First real request; proves the bus answers and wakes ECUs | `4100BE3EA813` or similar |
| `ATDPN` | Describe protocol by number, for logging | `6` (or `A6` if auto-detected) |

Other setup commands on the allowlist but not sent by default: `ATH1` (headers on, needed if we ever want to know which ECU replied), `ATSTxx` (fixed timeout in 4 ms units), `ATRV` (adapter's reading of battery voltage, works with the key off), `ATCAF0/1` (CAN auto-formatting; keep on), `ATWS` (warm start), `ATD` (defaults), `ATI`, `AT@1`.

Never sent: `ATPP` (programmable parameters, written to adapter EEPROM), `ATSD`/`ATCV`/`ATBD`/`ATMA`/`ATRTR`/`ATWM`/`ATFC*`/`ATCRA` and any other command that writes to the adapter's persistent settings, monitors or crafts arbitrary frames. See section 7.

### Reply format with those settings

```
410C0BB8            single frame
410C0BB8\r410C0000  two ECUs answered (ECM and TCM both report RPM)
014                 multi-frame: total length in hex
0:490201314743      then numbered frames (6 bytes in frame 0, 7 in each after)
1:5431324533374A
2:35393337313131
NO DATA             bus up, nobody answered this request
UNABLE TO CONNECT   adapter can't find a bus (key off, wrong protocol)
CAN ERROR           bus fault or wrong baud
?                   adapter didn't understand the command
```

The `>` prompt always follows. `ElmResponseParser` handles all of these; `Elm327Client` only returns when the prompt arrives or the deadline passes.

### Timeouts

| Situation | Default | Why |
| --- | --- | --- |
| Normal command | 2 s | A CAN reply arrives in < 200 ms; 2 s covers a sleepy clone |
| `ATZ` | 5 s | Chip reboot |
| First request after `ATSP0` | 12 s | Protocol search |
| Resync after a timeout | 0.5 s | Swallow a late reply so it isn't mistaken for the next answer |

On timeout the client throws `ObdTimeoutException`, marks itself out of sync, and before the next command drains anything the adapter prints late. It never sends a bare `\r`: on an ELM327 that **repeats the last command**, which is exactly how an accidental second Mode 04 would happen.

## 4. Mode 01: live data

The ECM on this truck should support the standard J1979 set. `Elm327Client.supportedPids()` walks the `0100`/`0120`/`0140`/… bitmaps so the UI never asks for a PID the truck lacks. PIDs are in `ObdPids` with J1979 formulas; values are metric and converted at display time.

| PID | Name | Bytes | Formula | Useful for |
| --- | --- | --- | --- | --- |
| `01` | Monitor status | 4 | bit 7 of A = MIL on; A & 0x7F = DTC count | "Check engine light is on, 2 codes" |
| `04` | Engine load | 1 | A·100/255 % | Towing load |
| `05` | Coolant temp | 1 | A − 40 °C | Warm-up, overheating |
| `06`-`09` | Fuel trims | 1 | (A − 128)·100/128 % | Early warning for vacuum leaks, injectors |
| `0B` | Intake MAP | 1 | A kPa | |
| `0C` | RPM | 2 | (256A + B)/4 | |
| `0D` | Speed | 1 | A km/h | Also used as the "truck is stopped" check before Mode 04 |
| `0E` | Timing advance | 1 | A/2 − 64 ° | |
| `0F` | Intake air temp | 1 | A − 40 °C | |
| `10` | MAF | 2 | (256A + B)/100 g/s | Fuel economy estimate |
| `11` | Throttle | 1 | A·100/255 % | |
| `1F` | Run time | 2 | 256A + B s | |
| `21` | Distance with MIL on | 2 | 256A + B km | |
| `2F` | Fuel level | 1 | A·100/255 % | Range estimate |
| `31` | Distance since clear | 2 | 256A + B km | |
| `33` | Barometric pressure | 1 | A kPa | Altitude |
| `3C` | Catalyst temp B1S1 | 2 | (256A + B)/10 − 40 °C | |
| `42` | Control module voltage | 2 | (256A + B)/1000 V | Battery/alternator health |
| `43` | Absolute load | 2 | (256A + B)·100/255 % | |
| `46` | Ambient air temp | 1 | A − 40 °C | |
| `5C` | Engine oil temp | 1 | A − 40 °C | Supported on L86; confirm with `0140` bitmap |
| `5E` | Engine fuel rate | 2 | (256A + B)/20 L/h | Instant MPG |

Polling plan for a future "Gauges" screen: RPM, speed, coolant, oil temp and voltage in a tight loop (~5 Hz total), everything else every few seconds. One request per PID keeps parsing simple; multi-PID requests (`010C0D05`) are allowed by the safety layer but not used yet.

## 5. Mode 03/07/0A: trouble codes

| Mode | Reply | What it is |
| --- | --- | --- |
| `03` | `43 <count> <2 bytes each>` | Stored (confirmed) codes. These light the MIL. |
| `07` | `47 <count> …` | Pending codes: a fault seen on one drive cycle, not yet confirmed. Useful after a repair. |
| `0A` | `4A <count> …` | Permanent codes. Set by the ECU, cannot be cleared by Mode 04; clear themselves when the monitor passes. |

On CAN the payload begins with a count byte, so `Dtc.parseList` strips it when the payload length is odd. Each ECU replies separately; `Elm327Client.readStoredCodes()` merges and de-duplicates. `NO DATA` for Mode 03 is treated as "no codes" because some ECUs answer that way instead of `4300`.

Code letters: `P` powertrain, `C` chassis, `B` body, `U` network, from the top two bits of the first byte. Looking up a code's meaning is not in scope here; the agent (M3) can do that in natural language.

## 6. Mode 04: clearing codes

`04` tells every ECU to erase stored DTCs, freeze frame, and reset readiness monitors and fuel-trim learning. It is the **only command in this package that changes anything in the truck**, and it hides evidence a mechanic may want.

`Elm327Client.clearDiagnosticCodes(ClearCodesRequest)` only sends it when:

1. `userConfirmed` is true, meaning the UI showed a confirmation that names what will be lost;
2. `vehicleStopped` is true, set by the caller from its own knowledge;
3. the truck itself reports `0 km/h` on PID `0D`, read immediately before;
4. the request header is `7DF`.

Everything else raises `ObdCommandBlockedException` before the write. Anything that would clear codes from the Android Auto head unit while driving is out of the question; the Car App Quality guidelines wouldn't allow the dialog anyway. If a clear is ever exposed, it belongs on the phone UI, parked.

## 7. GM Mode 22 extended PIDs — all UNVERIFIED

Service `22` (UDS ReadDataByIdentifier) returns manufacturer-specific data by a two-byte DID. GM uses it for everything the dash shows that J1979 doesn't carry. These are collected from enthusiast forums and Torque Pro / Car Scanner PID lists for 2014-2018 GM full-size trucks. **None has been checked on this truck.** The code marks each with `verified = false` and the UI must display an "unverified" label until someone compares a reading against the cluster.

| DID | Module / header | Name | Formula | Confidence |
| --- | --- | --- | --- | --- |
| `1940` | TCM, `7E2` | Transmission fluid temperature | A − 40 °C | Commonly cited; the one worth verifying first (towing) |
| `115C` | ECM, `7E0` | Engine oil pressure | A·4 kPa | Commonly cited, scaling varies between lists |
| `119C` | ECM, `7E0` | Engine oil life remaining | A·100/255 % | Commonly cited |

Verification procedure, parked with the engine warm:

1. `ATSH7E2` then `221940`. Expect `62 19 40 xx`. A `7F 22 31` means "request out of range": the DID is wrong for this module; try `7E1`. A `7F 22 11` means the service isn't supported at that address.
2. Compare the decoded value with the transmission temperature in the driver information center (Settings → Info → Trans Temp on the 2018 cluster).
3. If they agree within a couple of degrees across a cold-to-warm run, flip `verified = true` in `GmExtendedPids` and record the date and VIN prefix in this table.

Candidates not yet implemented, also unverified: `2217AC` (trans gear), `221154` (oil temp via Mode 22 as a fallback when `015C` is unsupported), `22165E`-series (misfire counters). Only DIDs on this allowlist pattern (`22` + 4 hex digits, to an allowed header) pass `ObdSafety`.

## 8. Read-only safety rules

These are enforced in `ObdSafety`, tested in `ObdSafetyTest`, and must stay true:

1. **Deny by default.** A command reaches the wire only if it matches the allowlist: the setup AT commands in section 3, `ATSH` to `7DF/7E0/7E1/7E2`, services `01`, `03`, `07`, `09`, `0A`, `22` with well-formed arguments, and `04` with no data when the caller has opted in.
2. **No UDS writes or sessions.** Services `10` (diagnostic session), `11` (ECU reset), `14` (clear DTC info), `27` (security access), `28` (communication control), `2E` (write data), `2F` (I/O control), `31` (routine control), `34`-`37` (download/upload), `3E` (tester present), `85` (control DTC setting), `AE` (GM device control) are refused. This is what keeps the app from ever commanding an actuator, flashing anything, or leaving a module in a non-default session.
3. **No adapter reprogramming.** `ATPP*` writes the adapter's EEPROM and can brick a clone; `ATSD`, `ATCV`, `ATBD`, `ATMA`, `ATFC*`, `ATCRA`, `ATRTR`, `ATWM` and others are refused because they send or monitor arbitrary frames.
4. **Never a bare carriage return.** The ELM327 repeats the previous command on an empty line.
5. **One command in flight.** The client is synchronized; no interleaving.
6. **Every wait has a deadline.** Nothing blocks a thread forever; a wedged adapter surfaces as `ObdTimeoutException` and the next command resyncs.
7. **Driving-safe by design.** Only reading happens while the truck moves. Mode 04 requires a stopped truck confirmed by the truck itself.
8. **Secrets and privacy.** The VIN is personal data; log it only at debug level and never ship it to the agent endpoint without the owner choosing to.
9. **Label what we don't trust.** Anything from `GmExtendedPids` carries `verified = false` until proven.

## 9. Android wiring (done 2026-11-04, lane C)

### Transports (`obd/bt/`)

| Class | What it does | Status |
| --- | --- | --- |
| `BluetoothClassicTransport` | RFCOMM socket on the SPP UUID. `read()` polls `available()` in 10 ms slices up to the deadline. `connect()` runs a watchdog thread that closes the socket after 15 s, because `BluetoothSocket.connect()` has no timeout of its own and a paired-but-unplugged dongle otherwise hangs for the stack's internal limit. | Code complete, **not tried on a real adapter** |
| `BluetoothLeTransport` | GATT "UART": write characteristic + notify characteristic. Tries each candidate layout in `BleUartLayouts.CANDIDATES` (Nordic UART, `FFF0/FFF1/FFF2`, `FFE0/FFE1`, `18F0/2AF0/2AF1`, OBDLink-style custom UUID), enables notifications through the CCCD, requests MTU 247, chunks writes to the negotiated payload and serializes them on the write callback. Replies are queued in `ByteInbox`, which gives `read()` its timeout. | Code complete, **every UUID is UNVERIFIED**; the matched layout name is shown by the phone's adapter test |
| `BluetoothPermissions` | `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` on API 31+, `ACCESS_FINE_LOCATION` for scans on 29–30. | Manifest declares both runtime permissions, `BLUETOOTH_SCAN` with `neverForLocation`, plus the legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` capped at API 30 |

### Demo adapter (`obd/live/DemoTransport.kt`)

A simulated ELM327 in front of a simulated 6.2L V8 (`DemoEngine`). It is the default until an adapter is picked, and it is how everything above the transport is tested on the emulator:

- Speaks the real init sequence: echo, spaces and linefeeds start **on** (like a freshly reset chip) and turn off with `ATE0`/`ATS0`/`ATL0`, so the parser's stripping is exercised. `ATZ` returns `ELM327 v1.5 DEMO`, `ATDPN` returns `6`, `ATRV` returns the simulated voltage.
- Supported-PID bitmaps for `0100`/`0120`/`0140`; one-PID and multi-PID Mode 01 requests; multi-frame replies for the VIN (`SIERRADEMO0000001`).
- 90 s drive cycle (20 s idle, 15 s accelerate, 30 s cruise at ~65 mph, 15 s slow down, 10 s idle), coolant warming from ~62 °C to ~92 °C over three minutes, fuel dropping ~4 %/hour, small ripples on everything so a 1 Hz screen visibly changes.
- Mode 03 returns **P0420 and P0171**; Mode 07 and 0A return none; PID 01 reports MIL on with 2 codes. Mode 22 to the TCM header returns `7F 22 31`.
- Commands that `ObdSafety` would block get `?`, so the demo cannot be used to sneak a Mode 04 past the tests.

### Settings on the phone (`ObdSettingsActivity`)

Reached by the "Truck data settings" button in `MainActivity`. Lists demo mode, every paired device (classic, LE or both for dual-mode devices), and anything found by an 8 s BLE scan. The choice is saved to plain SharedPreferences (`ObdSettingsStore`; a MAC address is not a secret). **Test adapter** opens the transport on an IO thread, runs `initialize()`, reads `ATRV`, and prints the banner, the protocol by name (`ObdProtocols`), the battery voltage and, for BLE, which layout matched.

### Monitor (`obd/live/TruckMonitor.kt`)

One worker thread per Truck screen. It opens the transport, runs init, walks the supported PIDs once, then loops: RPM, speed, coolant, intake air, engine load, fuel level and control-module voltage (falling back to `ATRV` when PID `42` is unsupported), publishing a `TruckReadings` on a `StateFlow`. A PID that answers `NO DATA` shows as `n/a` rather than failing the screen. Three consecutive timeouts count as a dropped link. A link that drops after it worked is reconnected up to three times, 3 s apart; an adapter that cannot be reached at all (`AdapterUnavailableException`: Bluetooth off, permission missing, not paired, connect timed out) fails immediately with a message that says what to do on the phone. Codes are read on request from the same thread, so one command is in flight at any time. `close()` stops the thread and closes the socket.

### Car screens

- **Home** is now a `GridTemplate` with Talk, Places, Truck and History (four items; the host grid limit is never below six).
- **Truck** (`TruckScreen`) is a `PaneTemplate` with four fixed-title rows ("Engine", "Speed", "Temperature", "Fuel and battery") whose secondary text carries the seven values in US units. Fixed titles mean each update is a refresh of the same template, not a new task step. The screen collects the monitor's flow and calls `invalidate()` **at most once per second** (`TruckScreen.MIN_REFRESH_MILLIS`), whatever rate the adapter answers at; the constraints doc gives no number, so this is a deliberate, conservative choice. Loading, failure (`MessageTemplate` with Retry) and missing-permission (`MessageTemplate` with Allow, which raises the dialog on the phone) are separate states. Retry pops and pushes a fresh screen so the popped template steps are handed back. The monitor is created with the screen and closed in `onDestroy`.
- **Codes** (`TruckCodesScreen`) is a read-only `ListTemplate`: MIL status row, one row per stored code within the host list limit (overflow collapses into a "+N more" row), and a final "Read-only: Sierra never clears codes" row. There is no clear action anywhere on the car screen; see section 6.

### Not done

- The agent does not yet read codes or live data into its prompt (that belongs with lane B's tools).
- No foreground service: the connection lives only while the Truck screen is on the stack. That is deliberate for this milestone, so the socket cannot outlive the screen.

## 10. Testing

`app/src/test/java/com/mdc159/sierra/obd/` runs on the JVM with no Android SDK or device:

- `FakeTransport` scripts command → reply pairs, can stay silent to force timeouts, and can inject late bytes.
- `Elm327ClientTest` covers init sequence, multi-ECU replies, `NO DATA`, negative responses, timeout + resync, disconnect, DTC reading, VIN multi-frame reassembly, supported-PID walking, header switching for Mode 22, and every Mode 04 refusal path. `Elm327BatteryVoltageTest` covers `ATRV`.
- `ObdSafetyTest` is the regression gate for section 8. Any change that makes a write service pass must fail there.
- `obd/live/DemoTransportTest` drives the demo through the real client: init, plausible idle values, the drive cycle changing them, warm-up, supported-PID bitmaps, the two stored codes, VIN, and that Mode 04 is still blocked.
- `obd/live/TruckMonitorTest` runs the worker against the demo: going live with all seven values, codes on request, a missing adapter failing without retry, an init failure, a dropped link reconnecting, and `close()` closing the transport.
- `obd/bt/ByteInboxTest` covers the BLE inbox (chunk boundaries, timeouts, close) and the layout picker and write chunking.
- `car/TruckTemplatesTest` builds every Truck, Codes and Home template state with the real Car App Library builders, which throw on host-rule violations.

Run: `./gradlew testDebugUnitTest --tests 'com.mdc159.sierra.obd.*' --tests 'com.mdc159.sierra.car.*'`

## 11. What still needs a real adapter

None of this has met a physical ELM327 on this truck. In order of risk:

1. **Bluetooth classic connect on the Galaxy A54.** Some phones need `createInsecureRfcommSocketToServiceRecord` for cheap clones; this code uses the secure variant. If `connect()` fails with "Couldn't connect", try the insecure call first.
2. **`InputStream.available()` on `BluetoothSocket`.** Documented to work, and it is how most Android OBD apps poll, but a stack that always returns 0 would make every command time out. The fallback is a reader thread that blocks on `read()` and feeds a `ByteInbox`, the same shape the BLE transport already uses.
3. **BLE layouts.** Every UUID in `BleUartLayouts` is unverified. The adapter test on the phone prints which layout matched; record it here with the adapter model once confirmed. If none matches, the message says "no known serial service" and the service table from a BLE scanner app is what to add.
4. **Protocol.** `ATSP6` is correct for every 2008+ GM truck. If the first `0100` returns `UNABLE TO CONNECT` with the key on, try `ATSP0` (auto) once by changing `Elm327Config.protocol`.
5. **PID support.** The demo answers every PID the Truck screen asks for. The real ECM may not report fuel level (PID `2F`) or oil temperature on all builds; those rows show `n/a`, which is the intended behavior, but confirm the rest match the cluster.
6. **Timing.** 250 ms between polls of seven PIDs is comfortable on a genuine ELM327 or STN chip and may be slow on a $10 clone. The screen's 1 Hz refresh is independent of this.
