# Sierra bridge

A small HTTP server that makes agents without an HTTP API look like an OpenAI-compatible chat endpoint, so the Sierra phone app can talk to them from the truck. It runs on a Linux box on the owner's Tailscale network and is reached only over Tailscale.

```
phone (Sierra app) --Tailscale--> sierra-bridge --> pi --mode rpc      (Pi coding agent, one session per conversation)
                                               --> hermes gateway     (Hermes API server, OpenAI-compatible)
                                               --> any CLI on stdin   (claude -p, codex, a script, ...)
                                               --> any OpenAI URL     (LiteLLM, a provider, another bridge)
```

Endpoints: `POST /v1/chat/completions` (plain and `stream: true`), `GET /v1/models`, `GET /health`.
The **model name in the request selects the adapter**, so one bridge can front several agents.

Standard library only; `pytest` is the sole dev dependency. Python 3.11 or newer, managed with [uv](https://docs.astral.sh/uv/) in a project-local `.venv`. Never run it with the system Python.

## Safety rules the bridge enforces

- **Bearer token required.** It comes from `SIERRA_BRIDGE_TOKEN`; the bridge refuses to start without one. Every `/v1` route checks it (constant-time compare). `/health` is public and returns only `{"status": "ok"}`.
- **Binds to localhost by default.** Set `[server] host` to the machine's Tailscale IP, or keep loopback and publish with `tailscale serve` (below). Never expose it on a public interface.
- **Per-request timeout** (`timeout_seconds`, default 120, per model override). A timed-out CLI is killed, a timed-out Pi turn is aborted, and the phone gets HTTP 504.
- **No request text ever reaches a shell.** Commands and arguments come only from the config file (`shell=False`). The question travels on stdin (command adapter) or inside a JSON record (Pi). The conversation id goes in an environment variable.
- **The agent behind `pi` or `command` is not so constrained.** It receives the driver's words and runs with whatever autonomy its own flags grant (the example passes `--no-approve` to Pi; `claude -p` runs its tools), as the bridge's user, in `cwd`. Keep that user and directory disposable: a dedicated service account, a checkout you can throw away, no production credentials in its environment.
- **Replies are trimmed for speech** (`speech_max_chars`, default 600) at a sentence boundary, with markdown, code blocks and URLs stripped. The **full reply is kept** in `log_file` as JSONL, with the user text, model, conversation id and timing.
- **Upstream keys come from the environment** (`api_key_env = "NAME"`), never from the config file.
- Request bodies over `max_body_bytes` (1 MB) are rejected.

## Quick start (any machine)

```sh
cd bridge
uv sync                                  # creates .venv with pytest
cp bridge.example.toml bridge.toml       # edit: pick your models
export SIERRA_BRIDGE_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
echo "$SIERRA_BRIDGE_TOKEN"              # this is the API key you type into the phone
uv run sierra-bridge --config bridge.toml --check   # validates config and token
uv run sierra-bridge --config bridge.toml
```

Try it:

```sh
curl -s http://127.0.0.1:8787/v1/models -H "Authorization: Bearer $SIERRA_BRIDGE_TOKEN"
curl -s http://127.0.0.1:8787/v1/chat/completions \
  -H "Authorization: Bearer $SIERRA_BRIDGE_TOKEN" -H 'Content-Type: application/json' \
  -d '{"model":"pi","messages":[{"role":"user","content":"What does the oil life monitor actually measure?"}]}'
```

Run the tests with `uv run pytest`. CI runs them in `.github/workflows/bridge.yml` on every push that touches `bridge/`.

## Configuration

`bridge.toml` (see `bridge.example.toml` for a full example):

```toml
[server]
host = "127.0.0.1"
port = 8787
timeout_seconds = 120
speech_max_chars = 600
log_file = "/var/log/sierra-bridge/replies.jsonl"

[models.<name>]          # <name> is what the phone's Model field must say
adapter = "pi" | "hermes" | "command" | "openai"
driving_prompt = true    # optional: wrap the agent in the driving-assistant prompt
timeout_seconds = 110    # optional per-model override; keep below the app's 120 s
speech_max_chars = 400   # optional per-model override
# ... adapter options below
```

### `driving_prompt`

Coding agents answer with paragraphs, lists and code. With `driving_prompt = true` the bridge adds a wrapper prompt that asks for **one to three spoken sentences** of plain English, no lists, no code, no URLs, key point first. How it is applied depends on the adapter: Pi gets it through `--append-system-prompt`, a command gets it prepended to stdin (or as a leading system message with `input_format = "messages"`), and the OpenAI and Hermes adapters get it as a system message only when the request carries none. The Sierra app already sends its own system prompt, so for those two adapters the flag mostly matters for other clients.

### Conversations

The chat completions API is stateless, so the bridge works out which conversation a request belongs to:

1. `X-Sierra-Conversation: <id>` header or the OpenAI `user` field, if present (letters, digits, `.`, `_`, `-`, up to 64 chars).
2. Otherwise, the last assistant message in the request is matched against replies the bridge sent earlier. Sierra sends the reply it got back on the next turn, so this survives Sierra's rolling six-turn window.
3. Otherwise a new conversation starts.

The id is the Pi session id, the Hermes `X-Hermes-Session-Id`, and `SIERRA_CONVERSATION_ID` for commands. Entries expire after `conversation_ttl_seconds` (6 hours).

## Adapters

### `pi`: Pi coding agent, one session per conversation

```toml
[models.pi]
adapter = "pi"
command = ["pi"]                      # or ["/home/me/.local/bin/pi"]
cwd = "/home/me/projects/sierra-auto" # the project Pi works in
session_dir = "/var/lib/sierra-bridge/pi-sessions"
extra_args = ["--no-approve"]         # anything from `pi --help`: --model, --thinking, --tools, ...
driving_prompt = true
timeout_seconds = 110
idle_seconds = 600                    # stop an idle Pi process; the session file keeps the context
max_sessions = 8                      # concurrent Pi processes
```

The bridge starts `pi --mode rpc --session-dir <dir> --session-id <uuid5 of the conversation id> [--append-system-prompt ...] <extra_args>` in `cwd`, writes `{"type":"prompt","message":...}` on stdin, and reads JSONL events until `agent_settled`. Only the text of the **final** assistant message is returned; "let me look at that" lines before tool calls are not spoken. After `idle_seconds` the process is shut down (stdin closed, then SIGTERM); the next turn reopens the same session id, and Pi's own session file restores the context. On timeout the bridge sends `abort` and keeps the process for the next turn.

Protocol references (read 2026-11-05): `packages/coding-agent/docs/rpc.md`, `rpc-commands.md`, `json.md`, `cli.md` and `sessions.md` in <https://github.com/earendil-works/pi>. Install Pi with `npm install -g --ignore-scripts @earendil-works/pi-coding-agent` (Node 22.19+), run `pi` once interactively in `cwd` to set the provider and model, and check `pi --help` on the installed version: the flag names above are from the docs at that date and the bridge has only been exercised against a fake Pi that speaks the documented protocol.

Trust: non-interactive modes do not prompt for project trust and use `defaultProjectTrust` from Pi's settings. `--no-approve` ignores trust-gated project-local config (safer), `--approve` trusts it.

### `hermes`: Hermes Agent API server

```toml
[models.hermes]
adapter = "hermes"
base_url = "http://hermes-box.<tailnet>.ts.net:8642/v1"
api_key_env = "HERMES_API_KEY"   # the value of API_SERVER_KEY on the Hermes box
model = "hermes-agent"           # default; use the profile name for a named profile
session_header = true            # default; sends X-Hermes-Session-Id = conversation id
# session_key = "sierra-truck"   # optional X-Hermes-Session-Key for long-term memory scoping
```

On the Hermes machine, per its docs (`website/docs/user-guide/features/api-server.md`), put `API_SERVER_ENABLED=true` and `API_SERVER_KEY=<key>` in `~/.hermes/.env`, optionally `API_SERVER_HOST=0.0.0.0` (or the Tailscale IP) and `API_SERVER_PORT=8642`, then run `hermes gateway`. The server is OpenAI-compatible: `/v1/chat/completions` with streaming, `/v1/models`, `/v1/responses`. If Hermes is on the same box as the bridge, the Sierra app can skip the bridge and point at Hermes directly; the bridge still adds speech trimming, the full-reply log and the single token for several agents.

**Unverified:** this adapter has been tested only against a fake server that reproduces the documented behaviour (SSE with `: keepalive` comments and `hermes.tool.progress` events). Whether Hermes de-duplicates the replayed history when `X-Hermes-Session-Id` is set is not confirmed; if follow-up answers come back doubled, set `session_header = false`.

### `command`: any CLI agent

```toml
[models.claude]
adapter = "command"
command = ["claude", "-p", "--output-format", "text"]
cwd = "/home/me/projects/sierra-auto"
driving_prompt = true
timeout_seconds = 110
# input_format = "messages"   # whole history as a JSON array on stdin instead of just the question
# env = { SOME_VAR = "value" }
# max_output_bytes = 1000000
```

Runs the command once per turn with the user's text on stdin and takes stdout as the reply. Non-zero exit, no output, or a timeout become HTTP 502/504 with a one-line explanation (the last stderr line, truncated). `SIERRA_CONVERSATION_ID`, `SIERRA_MODEL` and `SIERRA_REQUEST_ID` are in the environment, so a wrapper script can keep per-conversation state (for example `claude -p --resume`). The command is stateless otherwise; the question alone is sent unless `input_format = "messages"`.

### `openai`: pass-through

```toml
[models.fast]
adapter = "openai"
base_url = "http://<litellm-host>.<tailnet>.ts.net:4000/v1"   # host, host/v1 or the full URL all work
api_key_env = "LITELLM_KEY"
model = "gpt-4.1-mini"      # upstream model name; defaults to this entry's name
# headers = { X-Extra = "..." }
```

Forwards the messages, `temperature`, `top_p`, `max_tokens`, `max_completion_tokens` and `stop`, streams when asked, and rewrites the model name in the reply to the bridge's name.

## Deployment on a Linux VPS, reachable only over Tailscale

Assumes the repo is checked out at `/opt/sierra-auto` and a service user `sierra` exists (`sudo useradd -r -m -s /usr/sbin/nologin sierra`). Any CLI agents the bridge runs (`pi`, `claude`) must be installed for that user and configured once interactively as that user.

```sh
# 1. Install uv for the service user and build the venv
sudo -u sierra -H bash -c 'curl -LsSf https://astral.sh/uv/install.sh | sh'
cd /opt/sierra-auto/bridge
sudo -u sierra -H ~sierra/.local/bin/uv sync --locked --no-dev

# 2. Config, token, data and log directories
sudo install -d -o sierra -g sierra -m 750 /etc/sierra-bridge /var/lib/sierra-bridge /var/log/sierra-bridge
sudo cp bridge.example.toml /etc/sierra-bridge/bridge.toml      # edit models and paths
sudo cp deploy/sierra-bridge.env.example /etc/sierra-bridge/env # put the token and upstream keys here
sudo chown sierra:sierra /etc/sierra-bridge/env && sudo chmod 600 /etc/sierra-bridge/env

# 3. systemd
sudo cp deploy/sierra-bridge.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now sierra-bridge
sudo systemctl status sierra-bridge
journalctl -u sierra-bridge -f
```

The unit runs `.venv/bin/sierra-bridge --config /etc/sierra-bridge/bridge.toml`, restarts on failure, and reads the token from `EnvironmentFile=/etc/sierra-bridge/env`. Loosen `ReadWritePaths` in the unit if a Pi `cwd` or `session_dir` is elsewhere.

### Reach it only over Tailscale

Pick one:

- **Loopback plus `tailscale serve`** (recommended; gives HTTPS with a Tailscale certificate):

  ```sh
  sudo tailscale serve --bg --https=443 http://127.0.0.1:8787
  tailscale serve status
  ```

  The bridge keeps `host = "127.0.0.1"`. The base URL for the phone is `https://<machine>.<tailnet>.ts.net/v1`. Do **not** use `tailscale funnel`; that would publish it to the internet.

- **Bind to the Tailscale IP** directly: set `host = "100.x.y.z"` (from `tailscale ip -4`) and open nothing else. Use `http://<machine>.<tailnet>.ts.net:8787/v1` with MagicDNS in Sierra. WireGuard encrypts this transport, and Sierra's remote cleartext allowlist requires the `*.ts.net` hostname rather than a bare IP.

Either way, confirm from another device on the tailnet, and confirm from outside the tailnet that nothing answers.

Health check: `curl -s https://<machine>.<tailnet>.ts.net/health` returns `{"status":"ok"}` without a token.

### Point the Sierra phone app at it

On the phone, open **Sierra**. Under **Who answers**, choose **My agent, through the Sierra bridge**. Then fill in the **My agent (Sierra bridge)** section:

| Field | Value |
| --- | --- |
| Bridge URL | `https://<machine>.<tailnet>.ts.net/v1` (or with the `tailscale serve` port, e.g. `:8443/v1`), or `http://<machine>.<tailnet>.ts.net:8787/v1` when bound to the Tailscale IP. Not a bare `100.x.y.z`: for a remote host the app allows plain `http` only for `*.ts.net` names (plus `localhost`, `127.0.0.1` and the emulator's `10.0.2.2` for development) |
| Bridge token | the value of `SIERRA_BRIDGE_TOKEN` |
| Agent (bridge model name) | the `[models.<name>]` entry to talk to: `hermes` (default), `pi`, `claude`, ... |

When the phone is signed in to Studio 54, the vault entries `SIERRA_BRIDGE_URL` and `SIERRA_BRIDGE_TOKEN`, if both are present in the phone's project, replace the typed URL and token. The enrolled Sierra phone's entries were provisioned and verified on 2026-11-06 with `deploy/publish_phone_profile.py`. Tap **Connect to Hermes on Victoria**; see [operator instructions](../docs/MICROPHONE-DIAGNOSTICS.md) for provisioning another existing phone project.

The bridge profile changes only where chat goes, that is Talk and full-duplex `ask_agent`. Transcription and gateway speech keep the main gateway settings, and the bridge token is never sent to them. Full-duplex voice has its own settings screen and is not affected; only its `ask_agent` tool follows this choice.

Time limits: on the bridge profile one model round may take 120 s and a whole exchange 140 s. On the gateway the limits are 30 s and 60 s, because an agent can run tools before it answers. Keep the bridge's own `timeout_seconds` below 120 (110 on victoria), so the bridge's 504 reaches the phone first.

Tap **Test connection**. It sends "Reply with the single word: ready" through the chosen adapter, so a coding agent takes a few seconds. The phone must be on the tailnet (the Tailscale app installed and connected) for the URL to resolve. To switch agents, change the agent name. `GET /v1/models` lists the names.

## Logs

- `journalctl -u sierra-bridge`: one line per turn with the model, conversation prefix, timing and whether the reply was trimmed, plus Pi's stderr at `--log-level DEBUG`.
- `log_file` (JSONL): per turn `ts`, `request_id`, `model`, `conversation`, `status`, `elapsed_s`, `user`, `reply` (full), `spoken` (what the car got), `trimmed`. Rotate it with logrotate; the bridge reopens the file on every write.

## Layout

```
bridge/
  sierra_bridge/
    __main__.py       CLI: --config, --host, --port, --check, --log-level
    config.py         TOML config, token from env, api keys from env
    server.py         HTTP routes, auth, streaming (SSE over chunked HTTP/1.1), timeouts
    speech.py         DRIVING_PROMPT, markdown cleanup, sentence-aware trimming (streaming too)
    conversations.py  which conversation a stateless request belongs to
    replylog.py       JSONL full-reply log
    adapters/         base.py, command.py, pi.py, hermes.py, openai.py
  tests/              pytest; fakes/ has an echo CLI, a fake Pi (JSONL RPC) and a fake upstream server
  deploy/             systemd unit and env file example
  bridge.example.toml
```
