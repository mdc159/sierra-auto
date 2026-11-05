LANE H: agent bridge. Branch `agent-bridge`.
Sierra's Talk screen speaks to any OpenAI-compatible chat endpoint. The owner runs other agents on other machines on a private Tailscale network (a Hermes agent, Pi coding agents on a VPS, and others) and wants to talk to those from the truck. Most of them do not expose an OpenAI-compatible HTTP API.
You own: a new top-level folder bridge/, a new workflow .github/workflows/bridge.yml, and docs/AGENT-BACKENDS.md. Do not touch app/ or the existing workflows.

Build:
1. bridge/: a small, dependency-light HTTP server (Python, managed with uv, a project-local .venv, never system Python) that exposes POST /v1/chat/completions (including streaming) and GET /v1/models, and forwards each conversation to a backend adapter. Adapters:
   - command: runs a configured command per turn with the user's text on stdin and returns stdout (works for any CLI agent);
   - pi: drives the Pi coding agent in its non-interactive or RPC mode. Read Pi's current documentation for the exact flags and protocol; do not guess. Keep one session per conversation so follow-up questions have context;
   - hermes: forwards to a Hermes agent's API if it has one. Research what Hermes exposes and record what you find; mark it unverified if you cannot confirm it;
   - openai: plain pass-through to another OpenAI-compatible URL, so one bridge can front several agents under different model names.
2. Safety: bearer-token auth is required (token from an environment variable; refuse to start without one). Bind to a configured address, defaulting to localhost. Per-request timeout. The bridge never executes text from the request as a shell command. Replies are trimmed to a length suitable for speech, with the full text kept in a log file.
3. A driving-assistant wrapper prompt option, so a coding agent answers in one to three spoken sentences.
4. Tests with pytest for every adapter using fake backends, run by bridge.yml on pushes that touch bridge/.
5. Deployment notes in bridge/README.md: run under systemd on a Linux VPS, reachable only over Tailscale, and how to point the Sierra phone app at it (base URL, token as the API key, model name selects the adapter).
6. docs/AGENT-BACKENDS.md: the backends Sierra can use today and how to set each up: the owner's LiteLLM gateway (plain http on a private Tailscale name, one key per device), a provider directly, and each bridge adapter. Include a short section on what the phone app would need for named agent profiles that can be switched by voice ("ask Hermes ..."), as a proposal for a later lane. Do not implement app changes.
