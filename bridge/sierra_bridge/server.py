"""The OpenAI-compatible HTTP front: /v1/chat/completions (plain and streaming), /v1/models.

Standard-library ``http.server`` with a thread per connection. Every /v1 route
requires ``Authorization: Bearer <token>``. ``/health`` is unauthenticated and
says nothing beyond "ok" so a systemd or Tailscale health check can use it.
"""

from __future__ import annotations

import hmac
import json
import logging
import threading
import time
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Iterator

from . import __version__
from .adapters import REGISTRY, Adapter, AdapterError, AdapterTimeout, Turn
from .config import Config, ModelConfig
from .conversations import ConversationRegistry, explicit_id, message_text
from .replylog import ReplyLog
from .speech import SpeechTrimmer

log = logging.getLogger("sierra_bridge")

CONVERSATION_HEADER = "X-Sierra-Conversation"


class HttpError(Exception):
    def __init__(self, status: int, message: str, error_type: str = "invalid_request_error", code: str | None = None):
        super().__init__(message)
        self.status = status
        self.error_type = error_type
        self.code = code


class Bridge:
    """Everything the request handler needs: config, token, adapters, registry, log."""

    def __init__(self, config: Config, token: str, environ: dict | None = None) -> None:
        self.config = config
        self.token = token
        self.adapters: dict[str, Adapter] = {}
        for name, model in config.models.items():
            self.adapters[name] = REGISTRY[model.adapter](name, model.options, environ)
        self.registry = ConversationRegistry(ttl_seconds=config.server.conversation_ttl_seconds)
        self.reply_log = ReplyLog(config.server.log_file)
        self.started = int(time.time())

    def close(self) -> None:
        for adapter in self.adapters.values():
            try:
                adapter.close()
            except Exception:  # noqa: BLE001 - best effort at shutdown
                log.exception("closing adapter %s", adapter.model_name)

    def check_token(self, header: str | None) -> bool:
        if not header:
            return False
        scheme, _, value = header.strip().partition(" ")
        if scheme.lower() != "bearer":
            return False
        return hmac.compare_digest(value.strip().encode(), self.token.encode())

    def models_payload(self) -> dict:
        return {
            "object": "list",
            "data": [
                {
                    "id": name,
                    "object": "model",
                    "created": self.started,
                    "owned_by": f"sierra-bridge/{model.adapter}",
                }
                for name, model in self.config.models.items()
            ],
        }

    def model_for(self, name) -> ModelConfig:
        if not isinstance(name, str) or name not in self.config.models:
            names = ", ".join(sorted(self.config.models))
            raise HttpError(404, f"Unknown model {name!r}. Configured models: {names}", code="model_not_found")
        return self.config.models[name]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = f"sierra-bridge/{__version__}"
    sys_version = ""
    bridge: Bridge  # set on the server class by create_server

    # Logging ----------------------------------------------------------------

    def log_message(self, fmt, *args):  # noqa: D401 - BaseHTTPRequestHandler API
        log.info("%s %s", self.address_string(), fmt % args)

    # Routing ----------------------------------------------------------------

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        try:
            path = self.path.split("?", 1)[0]
            if path in ("/health", "/v1/health"):
                self._send_json(200, {"status": "ok"})
                return
            self._require_auth()
            if path == "/v1/models":
                self._send_json(200, self.bridge.models_payload())
                return
            raise HttpError(404, f"No route for GET {path}", code="not_found")
        except HttpError as e:
            self._send_error(e)

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        try:
            path = self.path.split("?", 1)[0]
            self._require_auth()
            if path == "/v1/chat/completions":
                self._chat_completions()
                return
            raise HttpError(404, f"No route for POST {path}", code="not_found")
        except HttpError as e:
            self._send_error(e)

    # Helpers ----------------------------------------------------------------

    def _require_auth(self) -> None:
        if not self.bridge.check_token(self.headers.get("Authorization")):
            self._drain_body()
            raise HttpError(401, "Missing or invalid bearer token.", "authentication_error", "invalid_api_key")

    def _drain_body(self) -> None:
        length = self._content_length()
        if length:
            self.rfile.read(min(length, self.bridge.config.server.max_body_bytes))

    def _content_length(self) -> int:
        try:
            return int(self.headers.get("Content-Length") or 0)
        except ValueError:
            raise HttpError(400, "Bad Content-Length header.") from None

    def _read_json(self) -> dict:
        length = self._content_length()
        if length <= 0:
            raise HttpError(400, "Request body is empty.")
        if length > self.bridge.config.server.max_body_bytes:
            raise HttpError(413, "Request body is too large.", code="request_too_large")
        raw = self.rfile.read(length)
        try:
            body = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise HttpError(400, "Request body is not valid JSON.") from None
        if not isinstance(body, dict):
            raise HttpError(400, "Request body must be a JSON object.")
        return body

    def _send_json(self, status: int, payload: dict) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        if status == 401:
            self.send_header("WWW-Authenticate", "Bearer")
        self.end_headers()
        self.wfile.write(data)

    def _send_error(self, e: HttpError) -> None:
        self._send_json(e.status, {"error": {"message": str(e), "type": e.error_type, "code": e.code}})

    # Chat completions -------------------------------------------------------

    def _chat_completions(self) -> None:
        body = self._read_json()
        bridge = self.bridge
        model = bridge.model_for(body.get("model"))
        messages = body.get("messages")
        if not isinstance(messages, list) or not messages:
            raise HttpError(400, "messages must be a non-empty list.")
        messages = [m for m in messages if isinstance(m, dict)]
        user_text = ""
        for m in reversed(messages):
            if m.get("role") == "user":
                user_text = message_text(m.get("content")).strip()
                break
        if not user_text:
            raise HttpError(400, "The last user message is empty.")
        stream = bool(body.get("stream", False))

        conv_hint = explicit_id(self.headers.get(CONVERSATION_HEADER)) or explicit_id(body.get("user"))
        conversation_id, is_new = bridge.registry.resolve(messages, conv_hint)

        timeout = model.timeout_seconds or bridge.config.server.timeout_seconds
        request_id = "chatcmpl-" + uuid.uuid4().hex[:24]
        turn = Turn(
            conversation_id=conversation_id,
            messages=messages,
            user_text=user_text,
            stream=stream,
            deadline=time.monotonic() + timeout,
            request_id=request_id,
            model=model.name,
            driving_prompt=model.driving_prompt,
            extra={k: body[k] for k in ("temperature", "top_p", "max_tokens", "max_completion_tokens", "stop") if k in body},
        )
        max_chars = model.speech_max_chars or bridge.config.server.speech_max_chars
        trimmer = SpeechTrimmer(max_chars)
        adapter = bridge.adapters[model.name]
        started = time.monotonic()
        log.info(
            "turn %s model=%s conversation=%s%s stream=%s chars=%d",
            request_id, model.name, conversation_id[:12], " (new)" if is_new else "", stream, len(user_text),
        )

        if stream:
            self._stream_reply(adapter, turn, trimmer, started)
        else:
            self._plain_reply(adapter, turn, trimmer, started)

    def _deadline_guard(self, adapter: Adapter, turn: Turn) -> Iterator[str]:
        for piece in adapter.complete(turn):
            if time.monotonic() > turn.deadline:
                raise AdapterTimeout()
            yield piece

    def _finish_turn(self, turn: Turn, trimmer: SpeechTrimmer, started: float, status: str) -> None:
        elapsed = round(time.monotonic() - started, 3)
        self.bridge.registry.remember(turn.conversation_id, trimmer.sent_text)
        self.bridge.reply_log.record(
            request_id=turn.request_id,
            model=turn.model,
            conversation=turn.conversation_id,
            status=status,
            elapsed_s=elapsed,
            user=turn.user_text,
            reply=trimmer.full_text,
            spoken=trimmer.sent_text,
            trimmed=trimmer.trimmed,
        )
        log.info("turn %s %s in %.1fs: %d chars, %d spoken%s",
                 turn.request_id, status, elapsed, len(trimmer.full_text), len(trimmer.sent_text),
                 " (trimmed)" if trimmer.trimmed else "")

    def _plain_reply(self, adapter: Adapter, turn: Turn, trimmer: SpeechTrimmer, started: float) -> None:
        try:
            for piece in self._deadline_guard(adapter, turn):
                trimmer.feed(piece)
            trimmer.finish()
        except AdapterError as e:
            self._log_failure(turn, started, e)
            raise HttpError(e.status, str(e), "upstream_error" if e.status != 504 else "timeout", code="agent_error")
        except Exception as e:  # noqa: BLE001 - never leak a traceback to the car
            log.exception("turn %s failed", turn.request_id)
            self._log_failure(turn, started, e)
            raise HttpError(500, "The bridge hit an internal error.", "server_error", code="internal_error")
        if not trimmer.sent_text:
            raise HttpError(502, "The agent sent an empty reply.", "upstream_error", code="empty_reply")
        self._finish_turn(turn, trimmer, started, "ok")
        self._send_json(200, completion_payload(turn, trimmer.sent_text))

    def _stream_reply(self, adapter: Adapter, turn: Turn, trimmer: SpeechTrimmer, started: float) -> None:
        # Nothing is written until the adapter produces its first piece, so an
        # early failure still gets a proper HTTP error status.
        it = self._deadline_guard(adapter, turn)
        pending: list[str] = []
        try:
            for piece in it:
                out = trimmer.feed(piece)
                if out:
                    pending.append(out)
                    break
        except AdapterError as e:
            self._log_failure(turn, started, e)
            raise HttpError(e.status, str(e), "upstream_error" if e.status != 504 else "timeout", code="agent_error")
        except Exception as e:  # noqa: BLE001
            log.exception("turn %s failed", turn.request_id)
            self._log_failure(turn, started, e)
            raise HttpError(500, "The bridge hit an internal error.", "server_error", code="internal_error")

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        self._chunk(sse(chunk_payload(turn, role="assistant")))
        status = "ok"
        try:
            for out in pending:
                self._chunk(sse(chunk_payload(turn, content=out)))
            for piece in it:
                out = trimmer.feed(piece)
                if out:
                    self._chunk(sse(chunk_payload(turn, content=out)))
            tail = trimmer.finish()
            if tail:
                self._chunk(sse(chunk_payload(turn, content=tail)))
        except AdapterError as e:
            status = f"error: {e}"
            self._chunk(sse({"error": {"message": str(e), "type": "upstream_error", "code": "agent_error"}}))
        except Exception as e:  # noqa: BLE001
            log.exception("turn %s failed mid-stream", turn.request_id)
            status = f"error: {e}"
            self._chunk(sse({"error": {"message": "The bridge hit an internal error.", "type": "server_error"}}))
        self._chunk(sse(chunk_payload(turn, finish_reason="stop" if not trimmer.trimmed else "length")))
        self._chunk("data: [DONE]\n\n")
        self._chunk("")
        self._finish_turn(turn, trimmer, started, status)

    def _chunk(self, text: str) -> None:
        data = text.encode("utf-8")
        self.wfile.write(f"{len(data):x}\r\n".encode() + data + b"\r\n")
        self.wfile.flush()

    def _log_failure(self, turn: Turn, started: float, error: Exception) -> None:
        self.bridge.reply_log.record(
            request_id=turn.request_id,
            model=turn.model,
            conversation=turn.conversation_id,
            status=f"error: {error}",
            elapsed_s=round(time.monotonic() - started, 3),
            user=turn.user_text,
        )


def completion_payload(turn: Turn, text: str) -> dict:
    return {
        "id": turn.request_id,
        "object": "chat.completion",
        "created": int(time.time()),
        "model": turn.model,
        "choices": [
            {"index": 0, "message": {"role": "assistant", "content": text}, "finish_reason": "stop"},
        ],
        "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
    }


def chunk_payload(turn: Turn, content: str | None = None, role: str | None = None, finish_reason: str | None = None) -> dict:
    delta: dict = {}
    if role:
        delta["role"] = role
    if content is not None:
        delta["content"] = content
    return {
        "id": turn.request_id,
        "object": "chat.completion.chunk",
        "created": int(time.time()),
        "model": turn.model,
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish_reason}],
    }


def sse(payload: dict) -> str:
    return "data: " + json.dumps(payload, ensure_ascii=False) + "\n\n"


class BridgeServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], bridge: Bridge) -> None:
        handler = type("BoundHandler", (Handler,), {"bridge": bridge})
        super().__init__(address, handler)
        self.bridge = bridge

    def server_close(self) -> None:
        super().server_close()
        self.bridge.close()


def create_server(config: Config, token: str, environ: dict | None = None, host: str | None = None, port: int | None = None) -> BridgeServer:
    bridge = Bridge(config, token, environ)
    return BridgeServer((host or config.server.host, config.server.port if port is None else port), bridge)


def serve_in_thread(server: BridgeServer) -> threading.Thread:
    thread = threading.Thread(target=server.serve_forever, name="sierra-bridge", daemon=True)
    thread.start()
    return thread
