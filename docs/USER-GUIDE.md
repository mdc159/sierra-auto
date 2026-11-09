# Sierra user guide

## As is

Verified against main **`210448b`, 2026-11-08**. Set up and diagnose **while parked**. Software features below exist; current-build truck audio, real OBD and host acceptance still need [recorded testing](TRUCK-TEST-PLAN.md). See [known defects](STATUS.md) before depending on them.

## 1. Install and check identity

Use the enrolled Google account's **Play internal testing** listing and update through Play. This is not a public store release. GitHub APKs remain a development channel; an ADB install with Play named as installer is not an actual Play installation. In Sierra, open **Updates and install record** and record the version, installer and initiating package. Play builds say **Updates come from Google Play** and hide GitHub download/install and Shizuku controls. Do not uninstall just to change channels without planning for lost settings. [Details](UPDATES.md).

The review saw version 100005 but initiator `com.android.shell`: truck visibility remains a risk to test, not a proven rejection. Never infer truck launch from a DHU icon or a version number.

## 2. Permissions and agent

1. Open **Sierra** on the phone. **Grant permissions** handles microphone and location. Grant microphone for speech and location for nearby searches; approximate location may be less useful. Bluetooth and phone-mic notification permissions are separate steps below.
2. For an enrolled device, open **Studio 54 sign-in**, enter the device's own vault credentials, then **Sign in**. An operator can import them over USB only with on-screen confirmation. Keep Tailscale connected; **Refresh now** checks the vault. Do not paste credentials into reports. [Sign-in/cache/revocation](STUDIO54.md).
3. Under **Who answers**, choose **A model (gateway settings below)** or **My agent, through the Sierra bridge**. A model uses **Base URL**, **API key**, **Model**; bridge uses **Bridge URL**, **Bridge token**, **Agent (bridge model name)** (default `hermes`). A complete vault URL/key pair overrides the typed pair; hints say **From Studio 54 vault**. Otherwise enter the configured endpoint's values manually.
4. **Save**, then **Test connection**. This saves and tests the effective selected route, including vault overlay; it can make a real billable agent request. **Connect to Hermes on Victoria** saves bridge selection/model, refreshes the existing enrollment and tests it; it does not enroll a new phone or create server credentials. A missing bridge is an error, not a silent model fallback.

Only chat follows this selector: Talk and duplex `ask_agent`. Transcription and gateway speech continue to use the main/gateway configuration, not the bridge token. These are two routing choices, **not** a named-profile manager or voice-switchable agent list. [Backends](AGENT-BACKENDS.md).

## 3. Voice and microphone

- **Transcription (optional)** on the main page sets the speech endpoint/model. Blank transcription key uses the main agent/gateway key, not the selected bridge token. In Automatic input, a configured transcription route plus supported host uses the selected capture pipeline; otherwise Talk uses the system speech recognizer. Explicit Car/Phone selection needs transcription. The system-recognizer permission fallback has a known gap; grant mic permission on the phone first.
- **Voice settings** selects Android, gateway or ElevenLabs output, transcription provider/model and **Conversation mode: keep listening after each reply**. **Test voice** tests phone output, not truck routing. The older **Realtime speech-to-speech through the gateway (off by default)** switch is saved but not connected to Talk; use the separate full-duplex settings instead.
- **Full-duplex voice settings** configures OpenAI Realtime or ElevenLabs Agents, voice/model/agent, instructions, and Guarded/Full duplex. Start with Guarded. **Try it on this phone**, **Interrupt (tap)** and **Stop** permit a parked phone test. A status of “Listening” is handshake evidence only, not a completed exchange. ElevenLabs Agents need account-side client tools/overrides; [DUPLEX.md](DUPLEX.md) has the setup.
- **Microphone & diagnostics** offers Automatic, Car and Phone input. Stop sessions before switching. Android ultimately controls the physical route; selecting Phone is not proof of which mic supplied samples.
- For Phone input from Android Auto, while the phone activity is visible use **Enable phone microphone for Android Auto** and grant requested microphone/notification access. Its foreground notification has Stop; enable again after reboot or process termination. This is not a wake-word service.
- Run **Test phone mic only** (five-second level test) before **Test phone mic + transcription** (sends audio to the configured provider). In the car use **Mic test** for levels without network. Temporarily enable **Talk diagnostic mode** to record until Done or twenty seconds when speech detection misses you.
- After a failure use **Copy diagnostics** or **Share diagnostics**. The bounded report survives restart and excludes audio/transcript/key/endpoint content. Ordinary history and backend logs have different privacy behavior. [Diagnostic reference](MICROPHONE-DIAGNOSTICS.md).

Known conditional failures remain in encrypted settings, signed-URL startup, native mic Stop and legacy speech focus. Do not treat recent cleanup changes as a universal fix for the intermittent truck microphone problem.

## 4. Truck data setup

Open **Truck data settings**. Keep the default **demo** choice first: it simulates a drive and stored P0420/P0171, not your truck. For real hardware, park, follow the maker's pairing/firmware instructions, use **Grant Bluetooth permissions**, select a paired classic device or scan for BLE, then **Test adapter**. Record identity, protocol, voltage and BLE layout if reported. The November 8 inspection had Bluetooth CONNECT/SCAN ungranted; no physical adapter pass is established.

Truck shows standard values only; GM transmission temperature, spoken `read_vehicle`/`read_trouble_codes`, configurable thresholds and alerts are not integrated. **No stored codes is not trustworthy when the ECU did not answer**: current code conflates NO DATA with empty codes. Do not use it to rule out faults. There is no clear-codes car action. Stop polling and unplug an unvalidated adapter after testing; key-off behavior/battery effects are not measured. [OBD reference](OBD2-PLAN.md), [hardware research](HARDWARE.md).

## 5. Car screens (wired Android Auto)

Connect a reliable data cable and open Sierra on the actual host. If absent, capture install record and host evidence before changing settings. Developer “Unknown sources” and DHU visibility are not guarantees.

| Entry / action | What it does |
| --- | --- |
| **Talk** | Starts listening. Ask “find diesel nearby”; `find_places` uses actual place sources. **Done** finishes capture; **Ask again**, **Repeat** support follow-up/replay. Conversation mode can continue automatically on the capture pipeline. |
| **Conversation** (Home or from Talk) | Separate duplex session with **Interrupt** and **Stop**. No car transcript stream. “Ask the agent …” can delegate through `ask_agent` to the selected chat target; ordinary realtime replies need not call Hermes. |
| **Places** | Browse categories/results on a host-drawn map, select a detail and request navigation. Actual navigation is delegated to the host/navigation app, not implemented by Sierra. Confirm it really opened; stale location/premature tool success are known defects. |
| **Truck → Codes** | Standard readings/stored codes from demo or selected adapter, not voice-agent data. `n/a` is missing data, not zero or healthy. |
| **History** | Recent exchanges and replay. History/repeat still uses the legacy speaker; it does not prove the selected network voice path. |
| **Mic test** | Five seconds of levels from selected input, no transcription request. |

Sierra does not own the steering-wheel assistant button or provide a wake word. Do not ask the agent for current transmission temperature expecting a real reading: no vehicle tool supplies one. For ideas, **Idea settings (GitHub token)** configures a repo-scoped Issues token and `file_idea` files `idea-voice`; the intended manual review gate has an **unfixed mention-trigger bypass**, so avoid voice-filing untrusted text until repaired. [Idea workflow](IDEA-LOOP.md).

## Next steps

First confirm the Play installation record and actual truck launch, then use the ordered parked checks in [TRUCK-TEST-PLAN.md](TRUCK-TEST-PLAN.md) for microphone → transcription → selected agent → audible reply → interrupt/stop. Validate standard OBD only after pairing/permission checks; GM/vehicle voice/alerts need implementation and physical evidence, not model answers. Keep failures and NOT RUN steps explicit.

## If something fails

Stay parked. Record version/install source, selected chat/voice/input modes, permission state, network/Tailscale state, exact action and sanitized diagnostic report. Do not include keys, raw vault imports, full VIN, personal transcripts or unrelated app logs in public reports. Keep project evidence under `.local/runs/<session>/`; use [the test plan](TRUCK-TEST-PLAN.md) to separate PASS, FAIL, BLOCKED and NOT RUN. A successful phone setting test is not a car-audio pass.
