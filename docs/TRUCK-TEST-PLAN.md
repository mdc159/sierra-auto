# Truck acceptance plan — parked first

## As is and evidence scope

Prepared **2026-11-08 for main `210448b`** and its current Play feature set. This is a **plan, not a passed test record**. Consolidates code/docs/review evidence; historical PR closing comments were not freshly fetched. Record exact build/source evidence: installed version alone cannot establish the source SHA. Known defects are in [STATUS.md](STATUS.md); application fixes are not part of this documentation consolidation.

## Safety, prerequisites and evidence

- Owner authorizes any installs, network/billable calls or permission changes. Park safely before setup; engine-running tests only with appropriate ventilation. No driver phone operation. No ECU writes, clearing, security sessions, routine control or high-RPM diagnostic experiments. Do not reproduce security boundary failures against live services.
- Stop if controls freeze, focus interferes with calls, false speech/navigation occurs, adapter behavior is unexplained or the driver is distracted. Restore a safe state before continuing. Do not rely on Sierra to judge vehicle health.
- Use wired Android Auto, a known-good cable and intended Play-internal installation. Keep Tailscale connected for private services. Set credentials beforehand without recording them. Start with demo OBD and Guarded duplex.
- Record phone model/Android/AA version, head-unit identity, app version, install record, signing/build provenance where available, cable/adapter model/firmware, input/provider/guard ratio/volume and permission state. Do not record keys, full VIN or private hostnames in public reports.
- Save sanitized observations, screenshots and diagnostics under `.local/runs/truck-YYYYMMDD-HHMM/` in the canonical checkout. Record **PASS / FAIL / BLOCKED / NOT RUN**, action, time, expected/actual result and artifact paths for each step. An absent log or failed capture is **missing evidence**, never PASS. Smoke-script summaries require manual review (#45); desktop fallback screenshots may include unrelated visible pixels.
- Review's USB inspection did not run any test below. Earlier desktop probes and a phone “Listening” status do not replace these tests.

## A. Blocking parked checks (allow more than 15 minutes if needed)

| ID | Do | Pass / capture on failure |
| --- | --- | --- |
| A1 delivery | Update from enrolled Play-internal listing. Open **Updates and install record**. Record version and installer/initiator, then launch Sierra on the real truck host. | Actual app binds/opens with Home, not just a cached icon. Capture both record fields and host rejection/bind evidence. Shell-initiated/Play-recorded install is a visibility risk; do not infer definite rejection without test. Stop here if launch blocked. |
| A2 permissions | Verify mic/location grants on phone; Bluetooth only for later real-adapter steps. Inspect microphone privacy switch and diagnostics state. | Settings load without crash/reset; no unexplained credential loss. Record failure without repeated destructive recovery attempts (known store defect). |
| A3 UI | Open Home → Places, Talk, History, Truck/Codes, Mic test, and Conversation (also reachable from Talk) as configured; return Home between tasks. Try light/dark host mode where possible. | Templates render, buttons reachable, no host error or quota failure. Capture real screen/logs. FakeHost/debug tests do not prove release-host acceptance. |
| A4 location/navigation | With a known current position, find a known nearby place, inspect distance and select navigation; repeat through Talk `navigate_to`. | Actual navigation app opens with correct destination. Record location timestamp/accuracy if available and host result; a tool saying success alone is insufficient. Fresh-fix timeout may use stale location (known defect); fail or mark BLOCKED rather than trust it. |
| A5 mic levels | **Mic test** with Automatic, then Car; speak normally, pause, repeat. Stop before changing input. | Nonzero/meaningful levels corresponding to speech, selected source and host indicator; no silence-only result. Save diagnostic report and permissions/mute/input state. Selected input is not proof of physical routing. |
| A6 Talk chain | With transcription configured, say a harmless known question. Complete with natural silence and then with Done on another turn. | Capture → real transcript → selected-agent reply → audible car output; no clipping or endless road-noise capture. Record stage times and redacted diagnostics. No diagnostic event means NOT RUN/missing evidence, not proof of no historical use. |
| A7 output/follow-up | Repeat reply, Ask again, enable conversation mode while parked and complete at least three turns; try stop phrase and silence ending. | Correct reply, natural stop, focus released and no overlapping recording. Repeat/History legacy speaker and configured network speech are separate paths; test both. Check long reply truncation for intelligibility (current 800-character path can cut text). |
| A8 routed agent | Test gateway/model, then bridge/Hermes with same harmless question; in duplex explicitly ask the agent. | Correct route or clear setup error, no silent fallback. Independent transcription/speech still work and bridge token is not used for speech. Do not assume every realtime answer invokes Hermes. Backend logs are sensitive; redact. |
| A9 phone microphone | While visible on phone enable **Enable phone microphone for Android Auto**, grant requested access, test phone mic levels/transcription, then select Phone and test car Talk. Stop notification, retry, restart phone/process and verify explicit re-enable requirement. | No hidden/wake-word capture; disabled service yields a clear error. Record actual device type/client-silenced state if reported; physical A54/background-route behavior is unproven. |
| A10 recognizer fallback | While parked use Automatic with no transcription endpoint, grant mic permission first, test system recognizer. Separately deny/revoke only on a disposable/test configuration. | Usable fallback or explicit error; known permission-check gap must not crash/disappear silently. Record which microphone actually picked up speech; do not assume cabin routing. |

## B. Parked duplex, echo and lifecycle

Use **Full-duplex voice settings → Try it on this phone** first, then real-host **Talk → Conversation**. Test each provider separately; ElevenLabs Agents account/tool/override setup is a prerequisite, not something the app creates. A websocket “Listening”/handshake is not PASS for a completed exchange.

| ID | Do | Pass / capture on failure |
| --- | --- | --- |
| B1 duplex chain | Complete a harmless spoken turn with OpenAI Realtime, and separately ElevenLabs if configured. | Understandable transcript/reply/audio with expected route/sample rate, not just socket acceptance. Record end-of-speech → first sound, provider/model and diagnostics. |
| B2 interrupt/echo | Guarded ratio 2.0, low/normal/high safe volume: let assistant speak with nobody talking, then interrupt by voice and tap. Compare Full duplex only after Guarded. | No repeated self-interruption; tap always stops playback; count false interruptions/minute and voice-cut delay. Ratio increases are experiments, not an echo-cancellation guarantee. |
| B3 stop/start | Stop during connect, during a reply, during mic startup and after a failed start; restart. | Prompt UI response, socket/mic/focus released, no second reader. Source review still identifies signed-URL HTTP cancellation and native open/close Stop blocking; if hung, mark FAIL and stop testing rather than waiting indefinitely. |
| B4 focus | Start music, start/end voice, receive a safely arranged call, end it and start a new session. Exercise legacy Repeat too. | Media/call takes precedence; recording stops on focus loss; no stale callback releasing a newer utterance's focus. Record exact sequence. |
| B5 duration | Parked, five-minute conversation with pauses up to 30 s; note host mic indicator and any dismissal. Dismiss indicator deliberately in a separate session. | Session ends only for a documented reason; release/restart works. Maximum host mic duration and repeated opens remain unknown until measured. |
| B6 network/auth | With a safe test configuration, interrupt tailnet/mobile connectivity and restore. Test unavailable endpoint and refused credentials without exposing real keys. | Bounded error/recovery, no endless reconnect/playback, settings retained. Record status and elapsed times; do not claim PC gateway latency as mobile latency. |
| B7 controls | Try steering-wheel short/long voice button while parked, host Back/Home and reconnect USB. | Record observed owner/system behavior, not an assumption that Sierra owns the button. No orphan capture/focus after leaving; repeated launch works. |

## C. Adapter/ECU acceptance — parked, after voice/host blockers

These steps require a real adapter and separate Bluetooth grants. Demo success proves no physical pairing, UUID, ECU accuracy or battery behavior. Use [OBD2-PLAN.md](OBD2-PLAN.md), [GM-PIDS.md](GM-PIDS.md) and historical [HARDWARE.md](HARDWARE.md) reference details, not unverified prices/powertrain guesses.

1. **Demo**: confirm synthetic label/selection, changing RPM/speed/etc., expected P0420/P0171 codes and read-only UI. Never record demo values as truck measurements.
2. **Pair/test one transport**: record adapter/firmware, classic secure RFCOMM or matched BLE service/characteristics/MTU. Test adapter initialization and record banner/protocol/ATRV. Failure capture: permissions, pairing state, elapsed connect time and sanitized error; cancel and ensure no worker survives. Do not blindly switch to insecure pairing without a separate reviewed change.
3. **Standard values**: supported PID bitmaps, RPM/speed/coolant/intake/load/fuel/ECU voltage; compare available cluster readings cold and warm, with units and raw replies. Unsupported values must stay unknown/n/a, not zero. ATRV is adapter voltage, not proof of ECU communication.
4. **Codes/no response**: record a valid response separately from missing ECU/NO DATA. Current “No stored codes” can be false when ECU is silent. Mark this known defect, not a clean bill of health; never clear codes for testing. Do not intentionally induce engine faults.
5. **Disconnect/reconnect**: disconnect adapter safely, return, leave/reopen Truck and cancel while Connecting. Record attempts/reinit/backoff and worker exit. Physical reconnect exceptions currently bypass retry; a manual Retry is not proof the retry budget works.
6. **Key off**: stop when engine/ECU stops answering; observe adapter state, close Truck and confirm polling/link closes. Determine sleep/wake and key-off power with appropriate measurement, not just a light or ATRV. Current repeated NO DATA cycles may poll indefinitely; do not leave unattended. Battery drain and module wake effects are unmeasured. Unplug after test until established safe.
7. **GM discovery is separate**: no live Truck UI button currently invokes `GmTruckReader.firstTest()`. Only use reviewed read-only instrumentation/terminal tooling, engine warm and parked. Probe transmission DID `1940`, compare cluster across cold/warm readings, then oil pressure/temp and oil life as in GM reference; record raw DID/header/bytes/formula/cluster/time. Do not mark catalogs verified just because one plausible number appeared. Resolve `119C` versus `119F`, absolute versus gauge pressure and header restoration after timeout before integration. No arbitrary probing or allowlist expansion.
8. **Unavailable features**: spoken vehicle tools, live GM Truck rows, named spoken profiles and configurable alerts are **NOT IMPLEMENTED**, not test failures to work around with model guesses. Acceptance tests come after implementation with explicit unknown/stale/demo semantics.

## D. Road tests only after parked gates, passenger-operated

Driver drives; passenger handles evidence and any allowed controls. If no passenger, defer. Do not perform phone setup or failure injection in motion.

- Normal cabin/road noise, windows up: Talk capture/follow-up and a brief duplex exchange at safe volumes; compare thresholds/false interruptions/latency with parked results. Never tune by code changes while moving.
- Music/call interactions only if safely arranged; otherwise use parked results. Verify host restrictions with equivalent DHU `restrict all` before a road attempt; actual host limits still need observation.
- Passenger compares standard OBD values/units and freshness without distracting driver. No GM gear interpretation or temperature alerts may be relied on before independent validation. No code clearing.
- Observe normal coverage loss/recovery rather than deliberately breaking a driver's navigation or phone connection. Record heat/charging/battery over longer sessions; wireless-AA/OBD coexistence is a separate later experiment, not wired acceptance.

## Next steps and completion record

For each ID retain PASS/FAIL/BLOCKED/NOT RUN plus artifacts, build identity, setup and exact limitation. A partial phone pass does not certify the truck or another provider/adapter. Re-run failed paths only after a named fix and record before/after evidence. Owner chooses go/no-go; unresolved P1s, misleading ECU health results and unreliable Stop remain explicit risks even if the happy path passes.
