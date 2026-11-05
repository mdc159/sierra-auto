import pytest

from tests.conftest import assistant, stream_text, user
from tests.fakes.fake_upstream import FakeUpstream


@pytest.fixture
def upstream():
    srv = FakeUpstream()
    yield srv
    srv.stop()


@pytest.fixture
def bridge(bridge_factory, upstream):
    client = bridge_factory({
        "hermes": {"adapter": "hermes", "base_url": upstream.base_url, "api_key_env": "HERMES_KEY", "session_key": "sierra-truck"},
        "hermes-nosession": {"adapter": "hermes", "base_url": upstream.base_url, "session_header": False, "model": "work"},
    }, environ={"HERMES_KEY": "change-me-local-dev"})
    client.upstream = upstream
    return client


def test_defaults_match_hermes_docs(bridge):
    status, _, body = bridge.chat("hermes", [user("hello")])
    assert status == 200, body
    req = bridge.upstream.requests[-1]
    assert req["path"] == "/v1/chat/completions"
    assert req["body"]["model"] == "hermes-agent"
    assert req["headers"]["Authorization"] == "Bearer change-me-local-dev"
    assert req["headers"]["X-Hermes-Session-Key"] == "sierra-truck"
    assert "model hermes-agent" in body["choices"][0]["message"]["content"]


def test_session_header_follows_conversation(bridge):
    status, _, b1 = bridge.chat("hermes", [user("one")])
    sid1 = bridge.upstream.requests[-1]["headers"]["X-Hermes-Session-Id"]
    reply = b1["choices"][0]["message"]["content"]
    bridge.chat("hermes", [user("one"), assistant(reply), user("two")])
    sid2 = bridge.upstream.requests[-1]["headers"]["X-Hermes-Session-Id"]
    assert sid1 == sid2
    bridge.chat("hermes", [user("fresh")])
    assert bridge.upstream.requests[-1]["headers"]["X-Hermes-Session-Id"] != sid1


def test_explicit_conversation_header_is_passed_on(bridge):
    bridge.chat("hermes", [user("x")], headers={"X-Sierra-Conversation": "truck"})
    assert bridge.upstream.requests[-1]["headers"]["X-Hermes-Session-Id"] == "truck"


def test_session_header_can_be_disabled_and_profile_model_used(bridge):
    bridge.chat("hermes-nosession", [user("x")])
    req = bridge.upstream.requests[-1]
    assert "X-Hermes-Session-Id" not in req["headers"]
    assert req["body"]["model"] == "work"


def test_streaming_with_hermes_quirks(bridge):
    status, _, events = bridge.chat("hermes", [user("stream")], stream=True)
    assert status == 200
    assert stream_text(events).startswith("Upstream says: stream")


def test_default_base_url(bridge_factory):
    client = bridge_factory({"h": {"adapter": "hermes"}})
    assert client.server.bridge.adapters["h"].url == "http://127.0.0.1:8642/v1/chat/completions"
