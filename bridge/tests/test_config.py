import pytest

from sierra_bridge.config import TOKEN_ENV, ConfigError, load, parse, secret_from_env, token_from_env


def test_token_required():
    with pytest.raises(ConfigError, match=TOKEN_ENV):
        token_from_env({})
    with pytest.raises(ConfigError):
        token_from_env({TOKEN_ENV: "   "})
    with pytest.raises(ConfigError, match="too short"):
        token_from_env({TOKEN_ENV: "short"})
    assert token_from_env({TOKEN_ENV: " a-long-enough-token-123 "}) == "a-long-enough-token-123"


def test_parse_defaults_and_models():
    cfg = parse({"models": {"echo": {"adapter": "command", "command": ["cat"], "driving_prompt": True, "timeout_seconds": 5}}})
    assert cfg.server.host == "127.0.0.1"
    assert cfg.server.port == 8787
    m = cfg.models["echo"]
    assert m.adapter == "command"
    assert m.driving_prompt is True
    assert m.timeout_seconds == 5
    assert m.options == {"command": ["cat"]}


@pytest.mark.parametrize(
    "data, match",
    [
        ({}, "at least one model"),
        ({"models": {"x": {"adapter": "nope"}}}, "adapter must be one of"),
        ({"models": {"bad name": {"adapter": "openai"}}}, "not allowed"),
        ({"server": {"bogus": 1}, "models": {"x": {"adapter": "openai"}}}, "unknown key"),
        ({"server": {"port": 70000}, "models": {"x": {"adapter": "openai"}}}, "port"),
        ({"server": {"timeout_seconds": 0}, "models": {"x": {"adapter": "openai"}}}, "timeout_seconds"),
        ({"models": {"x": {"adapter": "openai", "timeout_seconds": -1}}}, "timeout_seconds"),
    ],
)
def test_parse_rejects(data, match):
    with pytest.raises(ConfigError, match=match):
        parse(data)


def test_load_file(tmp_path):
    p = tmp_path / "bridge.toml"
    p.write_text('[server]\nport = 1234\n[models.echo]\nadapter = "command"\ncommand = ["cat"]\n')
    cfg = load(p)
    assert cfg.server.port == 1234
    assert "echo" in cfg.models
    with pytest.raises(ConfigError, match="not found"):
        load(tmp_path / "missing.toml")
    p.write_text("not = [valid")
    with pytest.raises(ConfigError, match="not valid TOML"):
        load(p)


def test_secret_from_env():
    assert secret_from_env({"api_key_env": "K"}, "api_key", {"K": "v"}) == "v"
    assert secret_from_env({}, "api_key", {}) == ""
    with pytest.raises(ConfigError, match="K"):
        secret_from_env({"api_key_env": "K"}, "api_key", {}, required=True)
