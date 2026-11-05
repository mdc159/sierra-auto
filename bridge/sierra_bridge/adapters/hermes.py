"""Forward to a Hermes Agent (Nous Research) API server.

What the Hermes docs say (website/docs/user-guide/features/api-server.md, read
2026-11-05): ``hermes gateway`` with ``API_SERVER_ENABLED=true`` and
``API_SERVER_KEY=<key>`` in ``~/.hermes/.env`` starts an OpenAI-compatible
server on ``http://127.0.0.1:8642/v1``. It offers ``POST /v1/chat/completions``
(streaming via ``stream: true``, SSE with ``: keepalive`` comments and extra
``hermes.tool.progress`` events), ``GET /v1/models`` and ``POST /v1/responses``.
The model name is ``hermes-agent`` (or the profile name). Chat completions are
stateless unless the request carries ``X-Hermes-Session-Id``, which continues a
specific Hermes session.

UNVERIFIED: none of this has been exercised against a live Hermes install from
this bridge. The bridge sends the full message history *and* the session header,
matching the documented stateless default; whether Hermes then de-duplicates the
replayed history is not confirmed. If follow-up answers look doubled, set
``session_header = false`` in the model config.
"""

from __future__ import annotations

from .base import Turn
from .openai import OpenAIAdapter


class HermesAdapter(OpenAIAdapter):
    name = "hermes"
    default_model = "hermes-agent"

    def __init__(self, model_name: str, options: dict, environ: dict | None = None) -> None:
        options = dict(options)
        options.setdefault("base_url", "http://127.0.0.1:8642/v1")
        super().__init__(model_name, options, environ)
        self.session_header = bool(options.get("session_header", True))
        self.session_key = options.get("session_key")  # stable per-device memory key, optional

    def headers_for(self, turn: Turn) -> dict[str, str]:
        headers = super().headers_for(turn)
        if self.session_header:
            headers["X-Hermes-Session-Id"] = turn.conversation_id
        if self.session_key:
            headers["X-Hermes-Session-Key"] = str(self.session_key)[:256]
        return headers
