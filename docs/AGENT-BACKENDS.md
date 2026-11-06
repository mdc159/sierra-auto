# Agent backends for Sierra

For the verified Victoria connection, one-tap phone setup and cable-free microphone
tests, see [Microphone diagnostics and Hermes on Victoria](MICROPHONE-DIAGNOSTICS.md).
The phone's existing Studio54 project now supplies `SIERRA_BRIDGE_URL` and
`SIERRA_BRIDGE_TOKEN`; the deployment script reconciles them without manual key entry.

Sierra's Talk screen sends each question to one OpenAI-compatible chat endpoint, configured on the phone as **Base URL**, **API key** and **Model**. Anything that speaks `POST /v1/chat/completions` can be the agent. This page lists what the owner can plug in today and how to set each up. Written 2026-11-05 for lane H; the bridge lives in `bridge/`.

How the app calls the endpoint (`app/src/main/java/com/mdc159/sierra/agent/AgentClient.kt`): a plain, non-streaming POST with `model` and `messages`, where `messages` is Sierra's driving-assistant system prompt plus the last six turns plus the new question. The reply is `choices[0].message.content`, cut to 300 characters for the screen and 800 for speech. Errors show `error.message` from the body. The base URL may be `https://host`, `https://host/v1` or the full `/chat/completions` URL.

## 1. The LiteLLM gateway (today's default)

The owner runs a LiteLLM proxy on the tailnet. It fronts several providers under one URL and one key per device, so the phone never holds a provider key.

| Phone field | Value |
| --- | --- |
| Base URL | `http://<litellm-host>.<tailnet>.ts.net:4000/v1` |
| API key | the LiteLLM virtual key made for the phone (one key per device, so it can be revoked alone) |
| Model | any model name the proxy exposes, for example `gpt-4.1-mini` or `claude-sonnet-4-5` |

Setup: on the LiteLLM box, create a key for the phone (`litellm` UI or `POST /key/generate` with a `key_alias` like `sierra-a54` and the allowed models). Put the base URL, that key and a model name in the phone, tap **Test connection**. The phone must be on the tailnet (Tailscale app) for the hostname to resolve; `http://` works because WireGuard encrypts the link. Transcription: if the proxy routes `/v1/audio/transcriptions`, set the same base URL as the Transcription URL to use the car microphone; otherwise leave it blank and the phone's recognizer is used.

Good for: fast general questions. Not for: talking to the owner's own agents, which have tools, memory and project context the gateway does not.

## 2. A provider directly

Any provider with an OpenAI-compatible API works without the gateway: OpenAI (`https://api.openai.com/v1`), OpenRouter (`https://openrouter.ai/api/v1`), Groq, Together, a local Ollama (`http://<host>:11434/v1`, any key), and so on. Enter the provider's base URL, a key made just for the phone, and a model name.

Trade-offs: no per-device cutoff at the gateway, the provider key lives (encrypted) on the phone, and you cannot switch providers without re-entering settings. Fine as a fallback when the tailnet is down.

## 3. The bridge: the owner's own agents

Most of the owner's agents (a Hermes agent, Pi coding agents on a VPS, assorted CLIs) have no OpenAI-compatible HTTP API, or have one without speech-friendly output. `bridge/` is a small Python server that gives them one. It runs on a Linux box on the tailnet, needs a bearer token, and maps **model names to adapters**, so a single phone entry reaches every agent: change the Model field to switch. Full setup, systemd unit and Tailscale notes: [`bridge/README.md`](../bridge/README.md).

| Phone field | Value |
| --- | --- |
| Base URL | `https://<bridge-host>.<tailnet>.ts.net/v1` (via `tailscale serve`) or `http://100.x.y.z:8787/v1` |
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
timeout_seconds = 180
```

The bridge runs `pi --mode rpc --session-id <per conversation> --session-dir ...`, sends the question as a `prompt` command and returns the final assistant message once the run settles. A follow-up from the phone lands in the same Pi session (the bridge recognises the conversation by the reply it sent last time). Idle Pi processes are stopped and reopened on the same session id, so context survives. Timeouts abort the Pi run and answer with HTTP 504; set `timeout_seconds` to what a real task needs, because Pi may run tools for a while before it answers. Flags and protocol are from Pi's docs as of 2026-11-05 and are exercised against a fake Pi in CI; the first run against the real binary should start with `pi --help` to confirm `--mode rpc`, `--session-id` and `--session-dir` exist in the installed version.

### 3b. `hermes`: Hermes Agent

Hermes (Nous Research) **does** expose an OpenAI-compatible API: `hermes gateway` with `API_SERVER_ENABLED=true` and `API_SERVER_KEY=<key>` in `~/.hermes/.env` listens on `http://127.0.0.1:8642/v1` (documented in its `api-server.md`; `API_SERVER_HOST` and `API_SERVER_PORT` move it). It offers `/v1/chat/completions` with streaming, `/v1/models` and `/v1/responses`, and takes model name `hermes-agent` (or a profile name). Chat completions are stateless unless `X-Hermes-Session-Id` is sent.

Two ways to use it:

- **Directly from the phone** if Hermes listens on its Tailscale IP: Base URL `http://<hermes-host>.<tailnet>.ts.net:8642/v1`, API key = `API_SERVER_KEY`, Model `hermes-agent`. No bridge needed. Replies are not trimmed beyond what the app does itself, and Hermes gets Sierra's system prompt layered on its own.
- **Through the bridge** (`adapter = "hermes"`, `base_url`, `api_key_env = "HERMES_API_KEY"`) to get speech trimming, the full-reply log, one token for all agents, and a stable `X-Hermes-Session-Id` per conversation.

Unverified: everything above is from the Hermes docs, not from a run against a live Hermes. The bridge's adapter is tested against a fake that reproduces the documented stream format. Whether Hermes de-duplicates replayed history when the session header is set is unknown; `session_header = false` turns the header off.

### 3c. `command`: any CLI agent

For a CLI that answers on stdout, such as `claude -p --output-format text`, `codex exec`, or a shell script around an SSH call. The question goes to stdin, the reply is stdout, non-zero exit becomes an error on the car screen. `SIERRA_CONVERSATION_ID` is in the environment so a wrapper can resume a session. Stateless by default; `input_format = "messages"` sends the whole history as JSON instead.

### 3d. `openai`: pass-through

A plain forward to another OpenAI-compatible URL, so the bridge can also front the LiteLLM gateway (section 1) or a provider under its own model name, and the phone keeps one base URL and one key for everything.

## What the bridge does not do

- It does not run text from a request as a shell command, ever. Commands are fixed in the config.
- It does not keep history for the command and openai adapters beyond what the request carries.
- It does not expose anything without the token, except `/health`.

## Built: a profile switch on the phone (2026-11-06)

The phone settings have a **Who answers** switch with two choices:

- **A model**: the main settings, which means the gateway when signed in to Studio 54.
- **My agent, through the Sierra bridge**: a bridge URL, a token and an agent name, `hermes` by default.

What the switch controls:

- Only chat follows it: Talk and full-duplex `ask_agent`, through `AgentConfig.chatBaseUrl`, `chatKey` and `chatModel`.
- Transcription and gateway speech stay on the main settings. Full-duplex voice has its own settings screen and is not touched; only its `ask_agent` tool follows the switch.
- The app reads the bridge pair from the vault as `SIERRA_BRIDGE_URL` and `SIERRA_BRIDGE_TOKEN` when both are in the phone's project. **Studio 54's enrollment does not write them yet (2026-11-06)**, so until that change lands the bridge token is typed in the bridge section.
- A bridge profile without a token does not count as set up; the bridge always checks one.
- Bridge turns get 120 s per round and 140 s per exchange, against 30 s and 60 s on the gateway. Transcription keeps the normal client's limits.

The voice-switched profiles below are still a proposal.

## Proposal for a later lane: named agent profiles switched by voice

Today one agent is configured and switching means editing the Model field on the phone. The owner wants "ask Hermes whether the deploy finished" or "ask Pi to check the OBD tests" from the wheel. What the app would need, as a sketch, with no app changes made in this lane:

1. **Profiles in settings.** Replace the single (base URL, key, model) with a list of named profiles, each with a spoken name and optional aliases ("Hermes", "Pi", "the coder", "fast"), plus one marked default. Stored encrypted like today (`ConfigStore`). The phone settings screen gets an add/edit list in its own Activity; nothing is typed in the car (constraints doc §keyboard).
2. **Routing by prefix.** After transcription, `TalkScreen` matches the start of the text against the profile names: `^(ask|tell|hey)\s+(<name>)[,:]?\s+(.*)` and `^(<name>)[,:]?\s+(.*)`. On a match, the rest of the text goes to that profile; otherwise the default profile. The spoken name is confirmed in the reply prefix on screen ("Hermes: ...") so a wrong match is obvious. Many profiles can share one bridge URL and token and differ only in model, which is why the bridge keys adapters by model name.
3. **Per-profile conversation.** `Conversation` becomes one per profile, so switching agents mid-drive does not leak one agent's history into another, and the bridge's reply-chaining keeps the right Pi or Hermes session per profile.
4. **Sticky target.** "Ask again" continues with the profile last used; a plain question without a name goes to the default. Optional: the last-used profile stays sticky for a few minutes so "and the tests?" after "ask Pi ..." still goes to Pi.
5. **History and errors.** History rows record the profile name. Error text stays short ("Hermes is not answering") and never shows a URL.
6. **Discovery.** A profile pointing at the bridge can offer the bridge's `GET /v1/models` list as a picker on the phone, so adding an agent on the VPS needs no typing of model names.

Nothing in this list needs new car templates, so it fits the constraints doc: the voice flow stays mic, one reply on screen, one spoken answer.
