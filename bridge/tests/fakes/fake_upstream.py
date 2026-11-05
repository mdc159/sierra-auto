"""Fake OpenAI-compatible upstream (stands in for LiteLLM, Hermes, or a provider).

Records every request so tests can check headers and bodies. The last user
message selects behaviour: "error" -> 500 with an OpenAI error body, "slow" ->
sleep 3 s, "empty" -> empty content. Streaming replies include the quirks the
Hermes docs describe: ``: keepalive`` comments and a named
``hermes.tool.progress`` event.
"""

from __future__ import annotations

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class FakeUpstream(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self) -> None:
        self.requests: list[dict] = []
        super().__init__(("127.0.0.1", 0), _Handler)
        self.thread = threading.Thread(target=self.serve_forever, daemon=True)
        self.thread.start()

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.server_address[1]}/v1"

    def stop(self) -> None:
        self.shutdown()
        self.server_close()


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # silence
        pass

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        self.server.requests.append({"path": self.path, "headers": dict(self.headers), "body": body})
        if self.path != "/v1/chat/completions":
            self._json(404, {"error": {"message": "no such route"}})
            return
        messages = body.get("messages", [])
        user = next((m["content"] for m in reversed(messages) if m.get("role") == "user"), "")
        if "error" in user:
            self._json(500, {"error": {"message": "upstream exploded", "type": "server_error"}})
            return
        if "slow" in user:
            time.sleep(3)
        reply = "" if "empty" in user else f"Upstream says: {user} (saw {len(messages)} messages, model {body.get('model')})"
        if body.get("stream"):
            self._stream(reply, body.get("model"))
        else:
            self._json(200, {
                "id": "chatcmpl-up", "object": "chat.completion", "created": 0, "model": body.get("model"),
                "choices": [{"index": 0, "message": {"role": "assistant", "content": reply}, "finish_reason": "stop"}],
            })

    def _json(self, status: int, payload: dict) -> None:
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _stream(self, reply: str, model) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Connection", "close")
        self.end_headers()

        def chunk(delta: dict, finish=None) -> bytes:
            obj = {"id": "chatcmpl-up", "object": "chat.completion.chunk", "created": 0, "model": model,
                   "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
            return f"data: {json.dumps(obj)}\n\n".encode()

        self.wfile.write(b": keepalive\n\n")
        self.wfile.write(chunk({"role": "assistant"}))
        self.wfile.write(b"event: hermes.tool.progress\ndata: {\"tool\": \"terminal\", \"status\": \"running\"}\n\n")
        for i in range(0, len(reply), 9):
            self.wfile.write(chunk({"content": reply[i:i + 9]}))
            self.wfile.flush()
        self.wfile.write(chunk({}, "stop"))
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()
        self.close_connection = True
