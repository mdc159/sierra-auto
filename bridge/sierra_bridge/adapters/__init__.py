"""Backend adapters. Each one turns a conversation turn into reply text."""

from __future__ import annotations

from .base import Adapter, AdapterError, AdapterTimeout, Turn
from .command import CommandAdapter
from .hermes import HermesAdapter
from .openai import OpenAIAdapter
from .pi import PiAdapter

REGISTRY: dict[str, type[Adapter]] = {
    "command": CommandAdapter,
    "pi": PiAdapter,
    "hermes": HermesAdapter,
    "openai": OpenAIAdapter,
}

__all__ = [
    "Adapter",
    "AdapterError",
    "AdapterTimeout",
    "Turn",
    "CommandAdapter",
    "HermesAdapter",
    "OpenAIAdapter",
    "PiAdapter",
    "REGISTRY",
]
