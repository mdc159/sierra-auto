import pytest

from sierra_bridge.adapters.openai import chat_completions_url, error_detail
from sierra_bridge.config import ConfigError
from sierra_bridge.speech import DRIVING_PROMPT
from tests.conftest import assistant, stream_text, system, user
from tests.fakes.fake_upstream import FakeUpstream


@pytest.fixture
def upstream():
    srv = FakeUpstream()
    yield srv
    srv.stop()


@pytest.fixture
def bridge(bridge_factory, upstream):
    client = bridge_factory({
        "gpt": {"adapter": "openai", "base_url": upstream.base_url, "model": "gpt-4.1-mini", "api_key_env": "UP_KEY",
                "timeout_seconds": 2},
        "gpt-driving": {"adapter": "openai", "base_url": upstream.base_url, "driving_prompt": True},
    }, environ={"UP_KEY": "sk-upstream"})
    client.upstream = upstream
    return client


@pytest.mark.parametrize("base, expected", [
    ("https://h", "https://h/v1/chat/completions"),
    ("https://h/", "https://h/v1/chat/completions"),
    ("https://h/v1", "https://h/v1/chat/completions"),
    ("https://h/v1/", "https://h/v1/chat/completions"),
    ("https://h/openai/v1", "https://h/openai/v1/chat/completions"),
    ("https://h/v1/chat/completions", "https://h/v1/chat/completions"),
])
def test_chat_completions_url(base, expected):
    assert chat_completions_url(base) == expected


def test_error_detail():
    assert error_detail('{"error": {"message": "bad key"}}') == "bad key"
    assert error_detail('{"error": "nope"}') == "nope"
    assert error_detail('{"detail": "x"}') == "x"
    assert error_detail("<html>  boom </html>") == "<html> boom </html>"


def test_pass_through_with_model_override_and_key(bridge):
    status, _, body = bridge.chat("gpt", [system("be brief"), user("hi")], temperature=0.2)
    assert status == 200, body
    assert body["model"] == "gpt"  # the bridge's name, not the upstream's
    assert body["choices"][0]["message"]["content"] == "Upstream says: hi (saw 2 messages, model gpt-4.1-mini)"
    req = bridge.upstream.requests[-1]
    assert req["headers"]["Authorization"] == "Bearer sk-upstream"
    assert req["body"]["model"] == "gpt-4.1-mini"
    assert req["body"]["temperature"] == 0.2
    assert req["body"]["stream"] is False
    assert req["body"]["messages"][0] == {"role": "system", "content": "be brief"}


def test_driving_prompt_injected_only_when_no_system_message(bridge):
    bridge.chat("gpt-driving", [user("hi")])
    assert bridge.upstream.requests[-1]["body"]["messages"][0] == {"role": "system", "content": DRIVING_PROMPT}
    bridge.chat("gpt-driving", [system("mine"), user("hi")])
    assert bridge.upstream.requests[-1]["body"]["messages"][0] == {"role": "system", "content": "mine"}
    assert "Authorization" not in bridge.upstream.requests[-1]["headers"]


def test_upstream_model_defaults_to_bridge_name(bridge):
    bridge.chat("gpt-driving", [user("hi")])
    assert bridge.upstream.requests[-1]["body"]["model"] == "gpt-driving"


def test_streaming_pass_through_skips_keepalives_and_foreign_events(bridge):
    status, _, events = bridge.chat("gpt", [user("stream please")], stream=True)
    assert status == 200
    assert stream_text(events) == "Upstream says: stream please (saw 1 messages, model gpt-4.1-mini)"
    assert bridge.upstream.requests[-1]["body"]["stream"] is True
    assert events[-1] == "[DONE]"
    assert all(e == "[DONE]" or e["object"] == "chat.completion.chunk" for e in events)


def test_upstream_error_is_502(bridge):
    status, _, body = bridge.chat("gpt", [user("error")])
    assert status == 502
    assert "upstream exploded" in body["error"]["message"]
    assert "500" in body["error"]["message"]


def test_upstream_timeout_is_504(bridge):
    status, _, body = bridge.chat("gpt", [user("slow")])
    assert status == 504


def test_empty_upstream_reply_is_502(bridge):
    status, _, body = bridge.chat("gpt", [user("empty")])
    assert status == 502
    assert "empty" in body["error"]["message"]


def test_history_forwarded_untouched(bridge):
    msgs = [user("a"), assistant("b"), user("c")]
    bridge.chat("gpt", msgs)
    assert bridge.upstream.requests[-1]["body"]["messages"] == msgs


def test_unreachable_upstream(bridge_factory):
    client = bridge_factory({"dead": {"adapter": "openai", "base_url": "http://127.0.0.1:9/v1", "timeout_seconds": 2}})
    status, _, body = client.chat("dead", [user("x")])
    assert status == 502
    assert "Could not reach" in body["error"]["message"]


def test_base_url_required(bridge_factory):
    with pytest.raises(ConfigError, match="base_url"):
        bridge_factory({"x": {"adapter": "openai"}})
