"""Pass-through to another OpenAI-compatible chat completions endpoint.

Lets one bridge front several agents under different model names, and is the
base for the Hermes adapter. Standard library only (urllib).
"""

from __future__ import annotations

import json
import socket
import urllib.error
import urllib.request
from typing import Iterator

from ..config import ConfigError, secret_from_env
from ..conversations import message_text
from ..speech import DRIVING_PROMPT
from .base import Adapter, AdapterError, AdapterTimeout, Turn

PASS_THROUGH_FIELDS = ("temperature", "top_p", "max_tokens", "max_completion_tokens", "stop")


def chat_completions_url(base_url: str) -> str:
    """Accept ``https://host``, ``https://host/v1`` or the full endpoint URL."""
    base = base_url.strip().rstrip("/")
    if base.endswith("/chat/completions"):
        return base
    last = base.rsplit("/", 1)[-1]
    if len(last) >= 2 and last[0] == "v" and last[1:].isdigit():
        return f"{base}/chat/completions"
    return f"{base}/v1/chat/completions"


class OpenAIAdapter(Adapter):
    name = "openai"
    default_model: str | None = None

    def __init__(self, model_name: str, options: dict, environ: dict | None = None) -> None:
        super().__init__(model_name, options, environ)
        base_url = options.get("base_url")
        if not isinstance(base_url, str) or not base_url.strip():
            raise ConfigError(f"[models.{model_name}] base_url is required.")
        self.url = chat_completions_url(base_url)
        self.upstream_model = options.get("model") or self.default_model or model_name
        self.api_key = secret_from_env(options, "api_key", environ)
        self.extra_headers = {str(k): str(v) for k, v in dict(options.get("headers", {})).items()}

    # Hooks for subclasses -------------------------------------------------

    def headers_for(self, turn: Turn) -> dict[str, str]:
        headers = {"Content-Type": "application/json", "Accept": "text/event-stream, application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        headers.update(self.extra_headers)
        return headers

    def body_for(self, turn: Turn) -> dict:
        messages = [m for m in turn.messages if isinstance(m, dict)]
        if turn.driving_prompt and not any(m.get("role") == "system" for m in messages):
            messages = [{"role": "system", "content": DRIVING_PROMPT}, *messages]
        body = {"model": self.upstream_model, "messages": messages, "stream": turn.stream}
        for key in PASS_THROUGH_FIELDS:
            if key in turn.extra:
                body[key] = turn.extra[key]
        return body

    # Main path ----------------------------------------------------------------

    def complete(self, turn: Turn) -> Iterator[str]:
        data = json.dumps(self.body_for(turn)).encode("utf-8")
        request = urllib.request.Request(self.url, data=data, headers=self.headers_for(turn), method="POST")
        timeout = turn.remaining()
        if timeout <= 0:
            raise AdapterTimeout()
        try:
            response = urllib.request.urlopen(request, timeout=timeout)  # noqa: S310 - URL fixed by config
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            raise AdapterError(f"Upstream error {e.code}: {error_detail(raw)}".rstrip(": "), status=502) from None
        except (TimeoutError, socket.timeout):
            raise AdapterTimeout() from None
        except urllib.error.URLError as e:
            if isinstance(e.reason, (TimeoutError, socket.timeout)):
                raise AdapterTimeout() from None
            raise AdapterError(f"Could not reach the upstream agent: {e.reason}") from None
        with response:
            content_type = response.headers.get("Content-Type", "")
            if turn.stream and "text/event-stream" in content_type:
                yield from self._stream(response, turn)
            else:
                raw = response.read().decode("utf-8", "replace")
                yield self._parse_completion(raw)

    def _stream(self, response, turn: Turn) -> Iterator[str]:
        try:
            for raw_line in response:
                if turn.remaining() <= 0:
                    raise AdapterTimeout()
                line = raw_line.decode("utf-8", "replace").rstrip("\r\n")
                if not line or line.startswith(":"):
                    continue  # keepalive comment or blank separator
                if not line.startswith("data:"):
                    continue  # named events such as Hermes' tool progress
                payload = line[5:].strip()
                if payload == "[DONE]":
                    return
                try:
                    chunk = json.loads(payload)
                except json.JSONDecodeError:
                    continue
                if not isinstance(chunk, dict):
                    continue
                if "error" in chunk and not chunk.get("choices"):
                    raise AdapterError(f"Upstream error: {error_detail(payload)}")
                for choice in chunk.get("choices") or []:
                    delta = (choice or {}).get("delta") or {}
                    text = delta.get("content")
                    if text:
                        yield message_text(text)
        except (TimeoutError, socket.timeout):
            raise AdapterTimeout() from None

    @staticmethod
    def _parse_completion(raw: str) -> str:
        try:
            obj = json.loads(raw)
            choice = obj["choices"][0]
            content = choice.get("message", {}).get("content")
            if content is None:
                content = choice.get("text")
            text = message_text(content).strip()
        except (json.JSONDecodeError, KeyError, IndexError, TypeError, AttributeError):
            raise AdapterError("The upstream agent sent a reply the bridge couldn't read.") from None
        if not text:
            raise AdapterError("The upstream agent sent an empty reply.")
        return text


def error_detail(body: str) -> str:
    """Pull ``error.message`` out of an OpenAI-style error body, if there is one."""
    try:
        obj = json.loads(body)
    except json.JSONDecodeError:
        return " ".join(body.split())[:200]
    if not isinstance(obj, dict):
        return " ".join(body.split())[:200]
    err = obj.get("error")
    if isinstance(err, dict):
        detail = err.get("message", "")
    elif isinstance(err, str):
        detail = err
    else:
        detail = obj.get("message") or obj.get("detail") or ""
    return " ".join(str(detail).split())[:200]
