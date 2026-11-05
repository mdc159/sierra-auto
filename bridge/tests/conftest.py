from __future__ import annotations

import http.client
import json
import sys
from pathlib import Path

import pytest

from sierra_bridge.config import parse
from sierra_bridge.server import create_server, serve_in_thread

FAKES = Path(__file__).parent / "fakes"
TOKEN = "test-token-0123456789abcdef"


def fake_command(script: str) -> list[str]:
    """argv that runs a fake backend script with an isolated interpreter."""
    return [sys.executable, "-I", str(FAKES / script)]


class Client:
    def __init__(self, host: str, port: int, token: str | None = TOKEN) -> None:
        self.host, self.port, self.token = host, port, token

    def request(self, method: str, path: str, body=None, headers: dict | None = None, token="unset"):
        conn = http.client.HTTPConnection(self.host, self.port, timeout=30)
        hdrs = dict(headers or {})
        tok = self.token if token == "unset" else token
        if tok:
            hdrs["Authorization"] = f"Bearer {tok}"
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            hdrs["Content-Type"] = "application/json"
        conn.request(method, path, body=data, headers=hdrs)
        resp = conn.getresponse()
        raw = resp.read()
        conn.close()
        return resp.status, dict(resp.getheaders()), raw

    def chat(self, model: str, messages, stream: bool = False, headers: dict | None = None, token="unset", **extra):
        body = {"model": model, "messages": messages, "stream": stream, **extra}
        status, hdrs, raw = self.request("POST", "/v1/chat/completions", body, headers, token)
        if stream and status == 200:
            return status, hdrs, parse_sse(raw.decode())
        return status, hdrs, json.loads(raw.decode()) if raw else None


def parse_sse(text: str) -> list:
    events = []
    for block in text.split("\n\n"):
        for line in block.splitlines():
            if line.startswith("data: "):
                payload = line[6:]
                events.append("[DONE]" if payload == "[DONE]" else json.loads(payload))
    return events


def stream_text(events: list) -> str:
    return "".join(
        e["choices"][0]["delta"].get("content", "")
        for e in events
        if isinstance(e, dict) and e.get("choices")
    )


@pytest.fixture
def bridge_factory(tmp_path, monkeypatch):
    servers = []

    def make(models: dict, server: dict | None = None, environ: dict | None = None, token: str = TOKEN):
        cfg = {"server": {"host": "127.0.0.1", "port": 0, "log_file": str(tmp_path / "replies.jsonl"), **(server or {})},
               "models": models}
        config = parse(cfg)
        srv = create_server(config, token, environ=environ or {})
        serve_in_thread(srv)
        servers.append(srv)
        host, port = srv.server_address[:2]
        client = Client(host, port)
        client.server = srv
        client.log_path = tmp_path / "replies.jsonl"
        return client

    yield make
    for srv in servers:
        srv.shutdown()
        srv.server_close()


def user(text: str) -> dict:
    return {"role": "user", "content": text}


def assistant(text: str) -> dict:
    return {"role": "assistant", "content": text}


def system(text: str) -> dict:
    return {"role": "system", "content": text}
