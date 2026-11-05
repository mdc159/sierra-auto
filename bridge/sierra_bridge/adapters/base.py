from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Iterator


class AdapterError(Exception):
    """A backend failure that should become an HTTP error for the client."""

    def __init__(self, message: str, status: int = 502) -> None:
        super().__init__(message)
        self.status = status


class AdapterTimeout(AdapterError):
    def __init__(self, message: str = "The agent did not answer in time.") -> None:
        super().__init__(message, status=504)


@dataclass
class Turn:
    """One request from the car: the whole history the client remembers plus the new question."""

    conversation_id: str
    messages: list[dict]
    user_text: str
    stream: bool
    deadline: float  # time.monotonic() value
    request_id: str
    model: str
    driving_prompt: bool = False
    extra: dict = field(default_factory=dict)  # optional pass-through fields (temperature, ...)

    def remaining(self) -> float:
        return max(0.0, self.deadline - time.monotonic())


class Adapter:
    """Base class. Subclasses implement ``complete`` and may override ``close``."""

    name = "base"

    def __init__(self, model_name: str, options: dict, environ: dict | None = None) -> None:
        self.model_name = model_name
        self.options = options

    def complete(self, turn: Turn) -> Iterator[str]:
        """Yield reply text, possibly in several pieces. Must respect ``turn.deadline``."""
        raise NotImplementedError

    def close(self) -> None:
        """Release subprocesses or connections. Called at shutdown."""
