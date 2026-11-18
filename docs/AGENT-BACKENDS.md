# Agent backends for Sierra

As of **2026-11-08, main `210448b`**: gateway/bridge selection and adapters are implemented; named spoken profiles remain proposed. [STATUS.md](STATUS.md) records unfixed bridge identity/framing/redirect/cleanup defects and limited device evidence. For the historically checked Victoria connection, one-tap phone setup and implemented cable-free microphone
tests (not a physical acceptance claim), see [Microphone diagnostics and Hermes on Victoria](MICROPHONE-DIAGNOSTICS.md).
The phone's existing Studio54 project now supplies `SIERRA_BRIDGE_URL` and
`SIERRA_BRIDGE_TOKEN`; the deployment script reconciles them without manual key entry.

Sierra's Talk screen sends each question to one OpenAI-compatible chat endpoint, configured on the phone as **Base URL**, **API key** and **Model**. Anything that speaks `POST /v1/chat/completions` can be the agent. This page retains setup reference for implemented routes; endpoint availability/model names must be tested for the configured account. Original lane H research was November 5; the bridge lives in `bridge/`.

How the app calls the endpoint (`app/src/main/java/com/mdc159/sierra/agent/AgentClient.kt`): a plain, non-streaming POST with `model` and `messages`, where `messages` is Sierra's driving-assistant system prompt plus the last six turns plus the new question. The tool loop reads `choices[0].message`/tool calls, up to four model rounds with exchange/tool budgets and a plain-chat fallback when tools are rejected. Screens choose bounded row chunks; `Conversation.forSpeech` currently takes at most 800 characters (not a universal sentence-safe cut). Errors show `error.message` from the body. The base URL may be `https://host`, `https://host/v1` or the full `/chat/completions` URL.

## 1. The LiteLLM gateway (today's default)

The owner runs a LiteLLM proxy on the tailnet. It fronts several providers under one URL and one key per device, so the phone never holds a provider key.

| Phone field | Value |
| --- | --- |
| Base URL | `http://<litellm-host>.<tailnet>.ts.net:4000/v1` |
| API key | the LiteLLM virtual key made for the phone (one key per device, so it can be revoked alone) |
| Model | any model name the proxy exposes, for example `gpt-4.1-mini` or `claude-sonnet-4-5` |

Setup: on the LiteLLM box, create a key for the phone (`litellm` UI or `POST /key/generate` with a `key_alias` like `sierra-a54` and the allowed models). Put the base URL, that key and a model name in the phone, tap **Test connection**. The phone must be on the tailnet (Tailscale app) for the hostname to resolve; `http://` works because WireGuard encrypts the link. Transcription: if the proxy routes `/v1/audio/transcriptions`, set the same base URL as the Transcription URL to use the car microphone; otherwise Automatic can use the system recognizer; explicit input selection needs transcription. Confirm the effective route in Voice settings/diagnostics.

Good for: fast general questions. Not for: talking to the owner's own agents, which have tools, memory and project context the gateway does not.

## 2. A provider directly

Any provider with an OpenAI-compatible API works without the gateway: OpenAI (`https://api.openai.com/v1`), OpenRouter (`https://openrouter.ai/api/v1`), Groq, Together, Ollama on the tailnet (`http://<ollama-host>.<tailnet>.ts.net:11434/v1`, or an HTTPS endpoint; key requirements depend on its configuration), and so on. Enter the provider's base URL, a key made just for the phone, and a model name. Remote plain HTTP must use a `*.ts.net` hostname; ordinary LAN names/IPs are blocked. Only the listed development hosts (`localhost`, `127.0.0.1`, `10.0.2.2`) have additional cleartext exceptions.

Trade-offs: no per-device cutoff at the gateway, the provider key lives (encrypted) on the phone, and you cannot switch providers without re-entering settings. Fine as a fallback when the tailnet is down.

## 3. The bridge: the owner's own agents

Most of the owner's agents (a Hermes agent, Pi coding agents on a VPS, assorted CLIs) have no OpenAI-compatible HTTP API, or have one without speech-friendly output. `bridge/` is a small Python server that gives them one. It runs on a Linux box on the tailnet, needs a bearer token, and maps **model names to adapters**, so a single phone entry reaches every agent: change the Model field to switch. Full setup, systemd unit and Tailscale notes: [`bridge/README.md`](../bridge/README.md).

| Phone field | Value |
| --- | --- |
| Base URL | `https://<bridge-host>.<tailnet>.ts.net/v1` (via `tailscale serve`) or `http://<bridge-host>.<tailnet>.ts.net:8787/v1` (remote cleartext bare IP is not allowed by the phone) |
| API key | `SIERRA_BRIDGE_TOKEN` from the bridge's environment |
| Model | a `[models.<name>]` entry: `pi`, `hermes`, `claude`, `fast`, ... |

What every adapter gets from the bridge: bearer auth, a per-request timeout, replies trimmed to a spoken length at a sentence boundary with markdown and URLs removed, the full untrimmed reply in a JSONL log, and an optional **driving-assistant wrapper prompt** (`driving_prompt = true`) that asks a coding agent for one to three spoken sentences.

### 3a. `pi`: Pi coding agent, one session per conversation

For: asking a coding agent about a project from the truck ("did the build pass", "what did you change in the OBD client", "start a branch that adds a fuel log") and following up, with Pi keeping the conversation and its own tool access to the project.

Setup on the VPS as the bridge's service user: `npm install -g --ignore-scripts @earendil-works/pi-coding-agent`, run `pi` once in the project directory to pick a provider and model, then in `bridge.toml`:

```toml
[models.pi]
adapter = "pi"
cwd = "/home/sierra/projects/sierra-auto"
session_dir = "/var/lib/sierra-bridge/pi-sessions"
extra_args = ["--no-approve"]
driving_prompt = true
timeout_seconds = 110
```

The bridge runs `pi --mode rpc --session-id <per conversation> --session-dir ...`, sends the question as a `prompt` command and returns the final assistant message once the run settles. The bridge accepts an explicit X-Sierra-Conversation header. November 9 phone and car Talk clients now supply stable conversation IDs. Older clients still use the reply-text fallback, where identical replies collide; removing that ambiguous server fallback remains a next step. Idle Pi processes are stopped and reopened on the same session id, so context survives. Timeouts abort the Pi run and answer with HTTP 504; for phone use keep `timeout_seconds` below Android's 120-second bridge round limit (110 seconds here), within its 140-second whole-exchange budget. Longer backend limits are appropriate only for a different client with matching budgets; raising the bridge limit alone will not extend the phone's wait. Flags and protocol are from Pi's docs as of 2026-11-05 and are exercised against a fake Pi in CI; the first run against the real binary should start with `pi --help` to confirm `--mode rpc`, `--session-id` and `--session-dir` exist in the installed version.

### 3b. `hermes`: Hermes Agent

Hermes (Nous Research) **does** expose an OpenAI-compatible API: `hermes gateway` with `API_SERVER_ENABLED=true` and `API_SERVER_KEY=<key>` in `~/.hermes/.env` listens on `http://127.0.0.1:8642/v1` (documented in its `api-server.md`; `API_SERVER_HOST` and `API_SERVER_PORT` move it). It offers `/v1/chat/completions` with streaming, `/v1/models` and `/v1/responses`, and takes model name `hermes-agent` (or a profile name). Chat completions are stateless unless `X-Hermes-Session-Id` is sent.

Two ways to use it:

- **Directly from the phone** if Hermes listens on its Tailscale IP: Base URL `http://<hermes-host>.<tailnet>.ts.net:8642/v1`, API key = `API_SERVER_KEY`, Model `hermes-agent`. No bridge needed. Replies are not trimmed beyond what the app does itself, and Hermes gets Sierra's system prompt layered on its own.
- **Through the bridge** (`adapter = "hermes"`, `base_url`, `api_key_env = "HERMES_API_KEY"`) to get speech trimming, the full-reply log, one token for all agents, and a stable `X-Hermes-Session-Id` per conversation.

The general Hermes API setup above is historical documentation-based reference plus fake-server tests. A November 6 Victoria bridge/profile request returned HTTP 200 and “Hermes on Victoria is ready”; that narrow live check is not a completed current phone/truck exchange. Whether Hermes de-duplicates replayed history when the session header is set is unknown; `session_header = false` turns the header off.

### 3c. `command`: any CLI agent

For a CLI that answers on stdout, such as `claude -p --output-format text`, `codex exec`, or a shell script around an SSH call. The question goes to stdin, the reply is stdout, non-zero exit becomes an error on the car screen. `SIERRA_CONVERSATION_ID` is in the environment so a wrapper can resume a session. Stateless by default; `input_format = "messages"` sends the whole history as JSON instead.

### 3d. `openai`: pass-through

A plain forward to another OpenAI-compatible URL, so the bridge can also front the LiteLLM gateway (section 1) or a provider under its own model name, and the phone keeps one base URL and one key for everything.

## What the bridge does not do

- It does not run text from a request as a shell command, ever. Commands are fixed in the config.
- It does not keep history for the command and openai adapters beyond what the request carries.
- Health routes `/health` and `/v1/health` are public; other supported routes check the token, but the unauthorized body-drain path is not safely bounded yet.
- It is not a complete tool-contract proxy: selected generation fields and messages pass through, not all OpenAI tool schemas/results. Verify any phone-tool expectation against the configured adapter.
- Tailnet deployment does not fix reply-text identity collisions, credential redirects, request framing, shutdown or stream cleanup. See [bridge README](../bridge/README.md).

## Built: a profile switch on the phone (2026-11-06)

The phone settings have a **Who answers** switch with two choices:

- **A model**: the main settings, which means the gateway when signed in to Studio 54.
- **My agent, through the Sierra bridge**: a bridge URL, a token and an agent name, `hermes` by default.

What the switch controls:

- Only chat follows it: Talk and full-duplex `ask_agent`, through `AgentConfig.chatBaseUrl`, `chatKey` and `chatModel`.
- Transcription and gateway speech stay on the main settings. Full-duplex voice has its own settings screen and is not touched; only its `ask_agent` tool follows the switch.
- The app reads the bridge pair from the vault as `SIERRA_BRIDGE_URL` and `SIERRA_BRIDGE_TOKEN` when both are in the phone's project. The phone profile was provisioned and verified on 2026-11-06 by `bridge/deploy/publish_phone_profile.py`; tap **Connect to Hermes on Victoria** to refresh and select it. Other enrolled devices need their own profile provisioned or a typed bridge configuration.
- A bridge profile without a token does not count as set up; the bridge always checks one.
- Bridge turns get 120 s per round and 140 s per exchange, against 30 s and 60 s on the gateway. Transcription keeps the normal client's limits.

The voice-switched profiles below are still a proposal.

## Next steps

Repair bridge identity/framing/redirect boundaries and add portable Windows fixtures before broadening agent access. Record an actual current phone/truck selected-agent exchange; the November 6 desktop/profile response is narrower evidence. Only then add named profiles with stable IDs/history isolation, ambiguity handling and credential-route tests. The sketch below remains a proposal, not shipped behavior.

## Proposal: named agent profiles switched by voice

Today the two-way selector chooses model/gateway or bridge; switching among several bridge agents still means editing the bridge model field. The owner wants "ask Hermes whether the deploy finished" or "ask Pi to check the OBD tests" from the wheel. What the app would need, as a sketch, with no app changes made in this lane:

1. **Profiles in settings.** Replace the single (base URL, key, model) with a list of named profiles, each with a spoken name and optional aliases ("Hermes", "Pi", "the coder", "fast"), plus one marked default. Stored encrypted like today (`ConfigStore`). The phone settings screen gets an add/edit list in its own Activity; nothing is typed in the car (constraints doc §keyboard).
2. **Routing by prefix.** After transcription, `TalkScreen` matches the start of the text against the profile names: `^(ask|tell|hey)\s+(<name>)[,:]?\s+(.*)` and `^(<name>)[,:]?\s+(.*)`. On a match, the rest of the text goes to that profile; otherwise the default profile. The spoken name is confirmed in the reply prefix on screen ("Hermes: ...") so a wrong match is obvious. Many profiles can share one bridge URL and token and differ only in model, which is why the bridge keys adapters by model name.
3. **Per-profile conversation.** `Conversation` becomes one per profile, so switching agents mid-drive does not leak one agent's history into another, and a new stable profile-scoped client conversation ID must keep the right Pi or Hermes session; current reply-chaining does not reliably do that.
4. **Sticky target.** "Ask again" continues with the profile last used; a plain question without a name goes to the default. Optional: the last-used profile stays sticky for a few minutes so "and the tests?" after "ask Pi ..." still goes to Pi.
5. **History and errors.** History rows record the profile name. Error text stays short ("Hermes is not answering") and never shows a URL.
6. **Discovery.** A profile pointing at the bridge can offer the bridge's `GET /v1/models` list as a picker on the phone, so adding an agent on the VPS needs no typing of model names.

Nothing in this list needs new car templates, so it fits the constraints doc: the voice flow stays mic, one reply on screen, one spoken answer.
