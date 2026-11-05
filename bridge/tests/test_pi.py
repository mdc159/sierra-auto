import json
import time

import pytest

from sierra_bridge.adapters.pi import session_id_for
from sierra_bridge.speech import DRIVING_PROMPT
from tests.conftest import assistant, fake_command, stream_text, user


@pytest.fixture
def pi(bridge_factory, tmp_path):
    session_dir = tmp_path / "pi-sessions"
    client = bridge_factory({
        "pi": {"adapter": "pi", "command": fake_command("fake_pi.py"), "session_dir": str(session_dir),
               "timeout_seconds": 3, "extra_args": ["--no-approve"]},
        "pi-driving": {"adapter": "pi", "command": fake_command("fake_pi.py"), "session_dir": str(session_dir / "d"),
                       "driving_prompt": True},
    })
    client.session_dir = session_dir
    return client


def argv_log(session_dir):
    return [json.loads(line) for line in (session_dir / "argv.log").read_text().splitlines()]


def test_prompt_and_final_text(pi):
    status, _, body = pi.chat("pi", [user("hello")], headers={"X-Sierra-Conversation": "c1"})
    assert status == 200, body
    assert body["choices"][0]["message"]["content"] == "turn 1: hello"
    started = argv_log(pi.session_dir)
    assert started[-1]["mode"] == "rpc"
    assert started[-1]["session_id"] == session_id_for("c1")
    assert started[-1]["extra"] == ["--no-approve"]


def test_follow_up_keeps_session(pi):
    s1, _, b1 = pi.chat("pi", [user("first")])
    reply1 = b1["choices"][0]["message"]["content"]
    assert reply1 == "turn 1: first"
    s2, _, b2 = pi.chat("pi", [user("first"), assistant(reply1), user("second")])
    assert b2["choices"][0]["message"]["content"] == "turn 2: second"
    # One pi process served both turns.
    assert len(argv_log(pi.session_dir)) == 1
    # A fresh conversation gets its own session.
    s3, _, b3 = pi.chat("pi", [user("other")])
    assert b3["choices"][0]["message"]["content"] == "turn 1: other"
    assert len(argv_log(pi.session_dir)) == 2


def test_session_survives_process_restart(pi):
    adapter = pi.server.bridge.adapters["pi"]
    pi.chat("pi", [user("a")], headers={"X-Sierra-Conversation": "keep"})
    adapter.close()  # as the idle reaper or a restart would
    status, _, body = pi.chat("pi", [user("b")], headers={"X-Sierra-Conversation": "keep"})
    assert status == 200
    assert body["choices"][0]["message"]["content"] == "turn 2: b"
    log = argv_log(pi.session_dir)
    assert len(log) == 2 and log[0]["session_id"] == log[1]["session_id"]


def test_idle_sessions_are_reaped(pi):
    adapter = pi.server.bridge.adapters["pi"]
    adapter.idle_seconds = 0.05
    pi.chat("pi", [user("a")], headers={"X-Sierra-Conversation": "idle"})
    assert "idle" in adapter._sessions
    time.sleep(0.1)
    pi.chat("pi", [user("b")], headers={"X-Sierra-Conversation": "other"})
    assert "idle" not in adapter._sessions


def test_only_final_message_is_spoken_when_tools_run(pi):
    status, _, body = pi.chat("pi", [user("use a tool")])
    assert status == 200
    assert body["choices"][0]["message"]["content"] == "turn 1: use a tool"


def test_streaming_delivers_final_text(pi):
    status, _, events = pi.chat("pi", [user("stream")], stream=True)
    assert status == 200
    assert stream_text(events) == "turn 1: stream"
    assert events[-1] == "[DONE]"


def test_driving_prompt_appended_to_system_prompt(pi):
    status, _, body = pi.chat("pi-driving", [user("x")])
    assert status == 200
    assert body["choices"][0]["message"]["content"] == "driving turn 1: x"
    log = argv_log(pi.session_dir / "d")
    assert log[-1]["system"] == [DRIVING_PROMPT]


def test_timeout_aborts_and_recovers(pi):
    status, _, body = pi.chat("pi", [user("sleep")], headers={"X-Sierra-Conversation": "t"})
    assert status == 504
    # The same process is still usable afterwards.
    status, _, body = pi.chat("pi", [user("after")], headers={"X-Sierra-Conversation": "t"})
    assert status == 200
    assert body["choices"][0]["message"]["content"] == "turn 2: after"
    assert len(argv_log(pi.session_dir)) == 1


def test_empty_turn_is_502(pi):
    status, _, body = pi.chat("pi", [user("empty")])
    assert status == 502
    assert "without an answer" in body["error"]["message"]


def test_rejected_prompt_is_502(pi):
    status, _, body = pi.chat("pi", [user("reject")])
    assert status == 502
    assert "nope" in body["error"]["message"]


def test_crash_is_reported_and_process_restarted(pi):
    status, _, body = pi.chat("pi", [user("crash")], headers={"X-Sierra-Conversation": "c"})
    assert status == 502
    assert "exited" in body["error"]["message"]
    status, _, body = pi.chat("pi", [user("back")], headers={"X-Sierra-Conversation": "c"})
    assert status == 200
    assert body["choices"][0]["message"]["content"] == "turn 2: back"


def test_session_id_is_stable_uuid():
    a = session_id_for("abc")
    assert a == session_id_for("abc")
    assert a != session_id_for("abd")
    assert len(a) == 36 and a.count("-") == 4
