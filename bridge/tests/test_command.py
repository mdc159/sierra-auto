import json

import pytest

from sierra_bridge.config import ConfigError
from sierra_bridge.speech import DRIVING_PROMPT
from tests.conftest import assistant, fake_command, stream_text, system, user


@pytest.fixture
def echo(bridge_factory):
    return bridge_factory({
        "echo": {"adapter": "command", "command": fake_command("echo_agent.py"), "timeout_seconds": 2},
        "echo-driving": {"adapter": "command", "command": fake_command("echo_agent.py"), "driving_prompt": True},
        "echo-json": {"adapter": "command", "command": fake_command("echo_agent.py"), "input_format": "messages"},
    })


def test_stdin_in_stdout_out(echo):
    status, _, body = echo.chat("echo", [user("hello truck")])
    assert status == 200, body
    assert body["object"] == "chat.completion"
    assert body["choices"][0]["message"]["content"].startswith("echo[")
    assert body["choices"][0]["message"]["content"].endswith("]: hello truck")
    assert body["model"] == "echo"


def test_driving_prompt_prepended(echo):
    status, _, body = echo.chat("echo-driving", [user("what is a pid")])
    assert status == 200
    content = body["choices"][0]["message"]["content"]
    assert DRIVING_PROMPT.split(".")[0] in content
    assert content.endswith("what is a pid")


def test_messages_input_format_gets_whole_history(echo):
    status, _, body = echo.chat("echo-json", [system("s"), user("a"), assistant("b"), user("c")])
    assert status == 200
    assert body["choices"][0]["message"]["content"] == "messages=4 roles=system,user,assistant,user"


def test_nonzero_exit_is_502_with_stderr_tail(echo):
    status, _, body = echo.chat("echo", [user("please fail")])
    assert status == 502
    assert "status 3" in body["error"]["message"]
    assert "the agent broke" in body["error"]["message"]


def test_silent_command_is_502(echo):
    status, _, body = echo.chat("echo", [user("silent")])
    assert status == 502
    assert "printed nothing" in body["error"]["message"]


def test_timeout_is_504(echo):
    status, _, body = echo.chat("echo", [user("sleep now")])
    assert status == 504
    assert body["error"]["type"] == "timeout"


def test_streaming_works_for_one_shot_command(echo):
    status, headers, events = echo.chat("echo", [user("stream me")], stream=True)
    assert status == 200
    assert headers["Content-Type"].startswith("text/event-stream")
    assert events[-1] == "[DONE]"
    assert events[0]["choices"][0]["delta"] == {"role": "assistant"}
    assert stream_text(events).endswith("]: stream me")
    assert events[-2]["choices"][0]["finish_reason"] == "stop"


def test_conversation_id_reaches_command_via_env(echo):
    status, _, body = echo.chat("echo", [user("one")], headers={"X-Sierra-Conversation": "truck-7"})
    assert status == 200
    assert body["choices"][0]["message"]["content"].startswith("echo[truck-7]:")


def test_request_text_never_hits_argv(echo):
    # The adapter's argv is fixed at construction; the request only changes stdin.
    adapter = echo.server.bridge.adapters["echo"]
    before = list(adapter.command)
    echo.chat("echo", [user("; rm -rf / $(evil) `evil`")])
    assert adapter.command == before
    assert not any("evil" in a for a in adapter.command)


def test_full_reply_logged(echo):
    echo.chat("echo", [user("log me")])
    entries = [json.loads(line) for line in echo.log_path.read_text().splitlines()]
    last = entries[-1]
    assert last["model"] == "echo"
    assert last["user"] == "log me"
    assert last["reply"].endswith("]: log me")
    assert last["status"] == "ok"


def test_bad_command_config_rejected(bridge_factory):
    with pytest.raises(ConfigError, match="command must be"):
        bridge_factory({"x": {"adapter": "command"}})
    with pytest.raises(ConfigError, match="input_format"):
        bridge_factory({"x": {"adapter": "command", "command": ["cat"], "input_format": "yaml"}})
