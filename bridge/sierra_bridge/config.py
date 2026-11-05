"""Bridge configuration: a TOML file plus the bearer token from the environment.

The token never lives in the file. API keys for upstream services are named by
environment variable (``api_key_env``) so the config file can be committed to a
private dotfiles repo without leaking anything.
"""

from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass, field
from pathlib import Path

TOKEN_ENV = "SIERRA_BRIDGE_TOKEN"
CONFIG_ENV = "SIERRA_BRIDGE_CONFIG"

ADAPTERS = ("command", "pi", "hermes", "openai")


class ConfigError(Exception):
    """Raised for a config problem the operator must fix before the bridge starts."""


@dataclass
class ServerConfig:
    host: str = "127.0.0.1"
    port: int = 8787
    timeout_seconds: float = 120.0
    speech_max_chars: int = 600
    log_file: str | None = None
    max_body_bytes: int = 1_000_000
    conversation_ttl_seconds: float = 6 * 3600


@dataclass
class ModelConfig:
    name: str
    adapter: str
    options: dict = field(default_factory=dict)
    driving_prompt: bool = False
    timeout_seconds: float | None = None
    speech_max_chars: int | None = None


@dataclass
class Config:
    server: ServerConfig
    models: dict[str, ModelConfig]
    path: Path | None = None


def token_from_env(environ: dict | None = None) -> str:
    """Return the bearer token, or raise ConfigError when it is missing or blank."""
    env = os.environ if environ is None else environ
    token = env.get(TOKEN_ENV, "").strip()
    if not token:
        raise ConfigError(
            f"{TOKEN_ENV} is not set. Refusing to start without a bearer token. "
            f"Generate one with: python3 -c 'import secrets; print(secrets.token_urlsafe(32))'"
        )
    if len(token) < 16:
        raise ConfigError(f"{TOKEN_ENV} is too short; use at least 16 characters.")
    return token


def parse(data: dict, path: Path | None = None) -> Config:
    """Build a Config from already-parsed TOML data."""
    server_raw = data.get("server", {})
    if not isinstance(server_raw, dict):
        raise ConfigError("[server] must be a table.")
    server = ServerConfig()
    known = {f for f in ServerConfig.__dataclass_fields__}
    for key, value in server_raw.items():
        if key not in known:
            raise ConfigError(f"[server] has an unknown key: {key}")
        setattr(server, key, value)
    if not isinstance(server.port, int) or not (0 <= server.port <= 65535):
        raise ConfigError("[server] port must be an integer between 0 and 65535.")
    if server.timeout_seconds <= 0:
        raise ConfigError("[server] timeout_seconds must be positive.")
    if server.speech_max_chars < 40:
        raise ConfigError("[server] speech_max_chars must be at least 40.")

    models_raw = data.get("models", {})
    if not isinstance(models_raw, dict) or not models_raw:
        raise ConfigError("Configure at least one model under [models.<name>].")
    models: dict[str, ModelConfig] = {}
    for name, raw in models_raw.items():
        if not isinstance(raw, dict):
            raise ConfigError(f"[models.{name}] must be a table.")
        if not _valid_model_name(name):
            raise ConfigError(
                f"Model name {name!r} is not allowed. Use letters, digits, '.', '_', '-' and ':' (max 64)."
            )
        options = dict(raw)
        adapter = options.pop("adapter", None)
        if adapter not in ADAPTERS:
            raise ConfigError(f"[models.{name}] adapter must be one of {', '.join(ADAPTERS)}.")
        model = ModelConfig(
            name=name,
            adapter=adapter,
            driving_prompt=bool(options.pop("driving_prompt", False)),
            timeout_seconds=options.pop("timeout_seconds", None),
            speech_max_chars=options.pop("speech_max_chars", None),
            options=options,
        )
        if model.timeout_seconds is not None and model.timeout_seconds <= 0:
            raise ConfigError(f"[models.{name}] timeout_seconds must be positive.")
        models[name] = model
    return Config(server=server, models=models, path=path)


def load(path: str | os.PathLike) -> Config:
    p = Path(path)
    try:
        with p.open("rb") as fh:
            data = tomllib.load(fh)
    except FileNotFoundError:
        raise ConfigError(f"Config file not found: {p}") from None
    except tomllib.TOMLDecodeError as e:
        raise ConfigError(f"Config file {p} is not valid TOML: {e}") from None
    return parse(data, p)


def _valid_model_name(name: str) -> bool:
    if not name or len(name) > 64:
        return False
    return all(c.isalnum() or c in "._-:" for c in name)


def secret_from_env(options: dict, key: str, environ: dict | None = None, *, required: bool = False) -> str:
    """Resolve ``<key>_env`` in a model's options to the value of that environment variable."""
    env = os.environ if environ is None else environ
    env_name = options.get(f"{key}_env")
    if env_name is None:
        if required:
            raise ConfigError(f"{key}_env is required.")
        return ""
    value = env.get(str(env_name), "")
    if not value and required:
        raise ConfigError(f"Environment variable {env_name} (from {key}_env) is not set.")
    return value
