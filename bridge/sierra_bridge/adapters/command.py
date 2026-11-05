"""Run a fixed command per turn: the user's text goes to stdin, stdout is the reply.

The command and its arguments come only from the config file. Nothing from the
request is ever placed on a command line; the request text travels on stdin and
the conversation id in an environment variable, so a CLI agent that supports
resuming (for example ``claude -p --resume``) can still keep context.
"""

from __future__ import annotations

import json
import os
import subprocess
from typing import Iterator

from ..config import ConfigError
from ..speech import DRIVING_PROMPT
from .base import Adapter, AdapterError, AdapterTimeout, Turn


class CommandAdapter(Adapter):
    name = "command"

    def __init__(self, model_name: str, options: dict, environ: dict | None = None) -> None:
        super().__init__(model_name, options, environ)
        command = options.get("command")
        if isinstance(command, str):
            command = [command]
        if not isinstance(command, list) or not command or not all(isinstance(c, str) for c in command):
            raise ConfigError(f"[models.{model_name}] command must be a non-empty list of strings.")
        self.command = list(command)
        self.cwd = options.get("cwd")
        self.input_format = options.get("input_format", "text")
        if self.input_format not in ("text", "messages"):
            raise ConfigError(f"[models.{model_name}] input_format must be 'text' or 'messages'.")
        self.max_output_bytes = int(options.get("max_output_bytes", 1_000_000))
        self.env_extra = {str(k): str(v) for k, v in dict(options.get("env", {})).items()}

    def complete(self, turn: Turn) -> Iterator[str]:
        if self.input_format == "messages":
            messages = list(turn.messages)
            if turn.driving_prompt and not any(m.get("role") == "system" for m in messages):
                messages.insert(0, {"role": "system", "content": DRIVING_PROMPT})
            stdin_text = json.dumps(messages, ensure_ascii=False)
        else:
            stdin_text = turn.user_text
            if turn.driving_prompt:
                stdin_text = f"{DRIVING_PROMPT}\n\n{turn.user_text}"

        env = dict(os.environ)
        env.update(self.env_extra)
        env["SIERRA_CONVERSATION_ID"] = turn.conversation_id
        env["SIERRA_MODEL"] = turn.model
        env["SIERRA_REQUEST_ID"] = turn.request_id

        try:
            proc = subprocess.Popen(  # noqa: S603 - argv is fixed by config, shell=False
                self.command,
                cwd=self.cwd,
                env=env,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
        except OSError as e:
            raise AdapterError(f"Could not start the agent command: {e}") from e
        try:
            out, err = proc.communicate(stdin_text.encode("utf-8"), timeout=turn.remaining())
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.communicate()
            raise AdapterTimeout() from None
        if proc.returncode != 0:
            detail = err.decode("utf-8", "replace").strip().splitlines()
            tail = detail[-1][:200] if detail else ""
            msg = f"The agent command exited with status {proc.returncode}."
            raise AdapterError(f"{msg} {tail}".strip())
        text = out[: self.max_output_bytes].decode("utf-8", "replace").strip()
        if not text:
            raise AdapterError("The agent command printed nothing.")
        yield text
