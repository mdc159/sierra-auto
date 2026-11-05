import json
import subprocess
import sys
from pathlib import Path

import pytest

from sierra_bridge.config import TOKEN_ENV
from tests.conftest import TOKEN, fake_command, user


@pytest.fixture
def srv(bridge_factory):
    return bridge_factory({
        "echo": {"adapter": "command", "command": fake_command("echo_agent.py")},
        "tiny": {"adapter": "command", "command": fake_command("echo_agent.py"), "speech_max_chars": 60},
    })


def test_models_lists_configured_names(srv):
    status, _, raw = srv.request("GET", "/v1/models")
    assert status == 200
    body = json.loads(raw)
    assert body["object"] == "list"
    assert [m["id"] for m in body["data"]] == ["echo", "tiny"]
    assert body["data"][0]["owned_by"] == "sierra-bridge/command"


@pytest.mark.parametrize("token", [None, "", "wrong-token-000000000", TOKEN + "x", TOKEN[:-1]])
def test_auth_required_everywhere(srv, token):
    status, headers, raw = srv.request("GET", "/v1/models", token=token)
    assert status == 401
    assert headers.get("WWW-Authenticate") == "Bearer"
    assert json.loads(raw)["error"]["type"] == "authentication_error"
    status, _, raw = srv.request("POST", "/v1/chat/completions", {"model": "echo", "messages": [user("x")]}, token=token)
    assert status == 401


def test_basic_scheme_rejected(srv):
    status, _, _ = srv.request("GET", "/v1/models", headers={"Authorization": f"Basic {TOKEN}"}, token=None)
    assert status == 401


def test_health_is_public_and_minimal(srv):
    status, _, raw = srv.request("GET", "/health", token=None)
    assert status == 200
    assert json.loads(raw) == {"status": "ok"}


def test_unknown_model_404(srv):
    status, _, body = srv.chat("nope", [user("x")])
    assert status == 404
    assert "Configured models: echo, tiny" in body["error"]["message"]


def test_bad_requests_400(srv):
    assert srv.chat("echo", [])[0] == 400
    assert srv.chat("echo", [{"role": "assistant", "content": "only me"}])[0] == 400
    assert srv.chat("echo", [{"role": "user", "content": "   "}])[0] == 400
    status, _, raw = srv.request("POST", "/v1/chat/completions", headers={"Content-Type": "application/json"})
    assert status == 400
    status, _, raw = srv.request("POST", "/v1/chat/completions", body=[1, 2])
    assert status == 400


def test_unknown_routes_404(srv):
    assert srv.request("GET", "/v1/other")[0] == 404
    assert srv.request("POST", "/v1/models", {"a": 1})[0] == 404


def test_body_limit_413(bridge_factory):
    client = bridge_factory({"echo": {"adapter": "command", "command": fake_command("echo_agent.py")}}, server={"max_body_bytes": 200})
    status, _, body = client.chat("echo", [user("x" * 500)])
    assert status == 413


def test_reply_trimmed_but_logged_in_full(srv):
    long = "This is sentence number one of the reply. " * 10
    status, _, body = srv.chat("tiny", [user(long)])
    assert status == 200
    spoken = body["choices"][0]["message"]["content"]
    assert len(spoken) <= 60 + 2
    assert spoken.endswith("…")
    entry = json.loads(srv.log_path.read_text().splitlines()[-1])
    assert entry["trimmed"] is True
    assert entry["spoken"] == spoken
    assert len(entry["reply"]) > 400
    assert long.strip() in entry["reply"]


def test_streaming_trim_reports_length_finish(srv):
    long = "Sentence one is here. " * 20
    status, _, events = srv.chat("tiny", [user(long)], stream=True)
    assert status == 200
    assert events[-2]["choices"][0]["finish_reason"] == "length"


def test_content_parts_accepted(srv):
    status, _, body = srv.chat("echo", [{"role": "user", "content": [{"type": "text", "text": "parts work"}]}])
    assert status == 200
    assert body["choices"][0]["message"]["content"].endswith("parts work")


def test_user_field_selects_conversation(srv):
    status, _, body = srv.chat("echo", [user("x")], user="phone-a54")
    assert body["choices"][0]["message"]["content"].startswith("echo[phone-a54]:")


def test_keep_alive_connection_serves_two_requests(srv):
    import http.client
    conn = http.client.HTTPConnection(srv.host, srv.port, timeout=10)
    for _ in range(2):
        conn.request("GET", "/v1/models", headers={"Authorization": f"Bearer {TOKEN}"})
        resp = conn.getresponse()
        assert resp.status == 200
        resp.read()
    conn.close()


# CLI ---------------------------------------------------------------------

ROOT = Path(__file__).resolve().parents[1]


def run_cli(args, env):
    return subprocess.run([sys.executable, "-m", "sierra_bridge", *args], cwd=ROOT, env=env, capture_output=True, text=True, timeout=30)


def test_cli_refuses_to_start_without_token(tmp_path):
    cfg = tmp_path / "bridge.toml"
    cfg.write_text('[models.echo]\nadapter = "command"\ncommand = ["cat"]\n')
    env = {"PATH": "/usr/bin:/bin", "HOME": str(tmp_path)}
    result = run_cli(["--config", str(cfg), "--check"], env)
    assert result.returncode == 2
    assert TOKEN_ENV in result.stderr
    assert "Refusing to start" in result.stderr


def test_cli_check_passes_with_token(tmp_path):
    cfg = tmp_path / "bridge.toml"
    cfg.write_text('[server]\nport = 0\n[models.echo]\nadapter = "command"\ncommand = ["cat"]\n')
    env = {"PATH": "/usr/bin:/bin", "HOME": str(tmp_path), TOKEN_ENV: "a-long-enough-token-123"}
    result = run_cli(["--config", str(cfg), "--check"], env)
    assert result.returncode == 0, result.stderr
    assert "OK: 1 model(s)" in result.stdout
    assert "127.0.0.1" in result.stdout


def test_cli_reports_missing_upstream_key(tmp_path):
    cfg = tmp_path / "bridge.toml"
    cfg.write_text('[models.up]\nadapter = "openai"\nbase_url = "http://127.0.0.1:1/v1"\napi_key_env = "NOPE_KEY"\n')
    env = {"PATH": "/usr/bin:/bin", "HOME": str(tmp_path), TOKEN_ENV: "a-long-enough-token-123"}
    result = run_cli(["--config", str(cfg), "--check"], env)
    # A missing upstream key is allowed (some endpoints need none); it must not crash.
    assert result.returncode == 0, result.stderr
