"""Drive the Pi coding agent (``@earendil-works/pi-coding-agent``) in RPC mode.

Protocol, from packages/coding-agent/docs/rpc.md, rpc-commands.md, json.md and
cli.md in https://github.com/earendil-works/pi (read 2026-11-05):

* ``pi --mode rpc`` reads JSONL commands on stdin and writes JSONL responses and
  events on stdout. One JSON object per line, LF terminated; split only on LF and
  strip a preceding CR. Stderr is diagnostics, never protocol.
* ``{"id": "...", "type": "prompt", "message": "..."}`` starts a turn. The
  response ``{"type":"response","command":"prompt","success":true,
  "data":{"disposition":"started"|"queued"|"handled"}}`` only confirms handling.
* Events then stream: ``message_update`` (``assistantMessageEvent.type ==
  "text_delta"`` carries ``delta``), ``message_end`` (``message.role``,
  ``message.content`` as blocks with ``type: "text"``), ``agent_end`` and finally
  ``agent_settled`` (``aborted`` flag). ``agent_settled`` is the one to wait for.
* ``{"type":"abort"}`` stops a run; its response arrives once Pi is idle.
* ``{"type":"get_last_assistant_text"}`` returns ``data.text``.
* Closing stdin requests an orderly shutdown.
* ``--session-id <id>`` opens the existing session with that id in the current
  directory or creates it; ``--session-dir <dir>`` overrides session storage;
  ``--append-system-prompt <text>`` appends to the system prompt; ``--no-approve``
  ignores trust-gated project-local config.

One Pi process per conversation, keyed by the bridge's conversation id, which is
also the Pi session id. The process is shut down after ``idle_seconds`` and
re-opened with the same session id on the next turn, so Pi's own session file
carries the context across restarts. Only the final assistant message of a turn
is returned: text from intermediate tool-calling steps is not something to read
aloud, and it cannot be told apart from the final answer until the run settles.
"""

from __future__ import annotations

import json
import logging
import os
import queue
import subprocess
import threading
import time
import uuid
from pathlib import Path
from typing import Iterator

from ..config import ConfigError
from ..speech import DRIVING_PROMPT
from .base import Adapter, AdapterError, AdapterTimeout, Turn

log = logging.getLogger("sierra_bridge.pi")


def session_id_for(conversation_id: str) -> str:
    """Pi session ids are UUID-shaped; derive a stable one from the conversation id."""
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"sierra-bridge:{conversation_id}"))


def message_text_blocks(message: dict) -> str:
    content = message.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(str(b.get("text", "")) for b in content if isinstance(b, dict) and b.get("type") == "text")
    return ""


class PiProcess:
    """One running ``pi --mode rpc`` bound to one session."""

    def __init__(self, argv: list[str], cwd: str | None, startup_timeout: float) -> None:
        self.argv = argv
        self.lock = threading.Lock()
        self.last_used = time.monotonic()
        self.events: queue.Queue = queue.Queue()
        self.alive = True
        try:
            self.proc = subprocess.Popen(  # noqa: S603 - argv is fixed by config, shell=False
                argv,
                cwd=cwd,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=dict(os.environ),
            )
        except OSError as e:
            raise AdapterError(f"Could not start pi: {e}") from e
        self._write_lock = threading.Lock()
        threading.Thread(target=self._read_stdout, name="pi-stdout", daemon=True).start()
        threading.Thread(target=self._read_stderr, name="pi-stderr", daemon=True).start()
        self.startup_timeout = startup_timeout

    # I/O ------------------------------------------------------------------

    def _read_stdout(self) -> None:
        stream = self.proc.stdout
        assert stream is not None
        buf = b""
        while True:
            chunk = stream.read1(65536) if hasattr(stream, "read1") else stream.read(1)
            if not chunk:
                break
            buf += chunk
            while True:
                nl = buf.find(b"\n")
                if nl < 0:
                    break
                line, buf = buf[:nl], buf[nl + 1 :]
                if line.endswith(b"\r"):
                    line = line[:-1]
                if not line.strip():
                    continue
                try:
                    record = json.loads(line.decode("utf-8"))
                except (json.JSONDecodeError, UnicodeDecodeError):
                    log.debug("pi: non-JSON line on stdout: %r", line[:200])
                    continue
                if isinstance(record, dict):
                    self.events.put(record)
        self.alive = False
        self.events.put({"type": "__exit__"})

    def _read_stderr(self) -> None:
        stream = self.proc.stderr
        assert stream is not None
        for raw in stream:
            log.debug("pi stderr: %s", raw.decode("utf-8", "replace").rstrip())

    def send(self, command: dict) -> None:
        line = json.dumps(command, ensure_ascii=False) + "\n"
        try:
            with self._write_lock:
                assert self.proc.stdin is not None
                self.proc.stdin.write(line.encode("utf-8"))
                self.proc.stdin.flush()
        except (BrokenPipeError, OSError, ValueError) as e:
            self.alive = False
            raise AdapterError(f"pi is not accepting commands: {e}") from e

    def close(self, grace: float = 5.0) -> None:
        self.alive = False
        try:
            if self.proc.stdin:
                self.proc.stdin.close()
        except OSError:
            pass
        try:
            self.proc.wait(timeout=grace)
        except subprocess.TimeoutExpired:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=grace)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait()

    # Protocol -------------------------------------------------------------

    def _drain(self) -> None:
        while True:
            try:
                self.events.get_nowait()
            except queue.Empty:
                return

    def _next(self, deadline: float) -> dict:
        wait = deadline - time.monotonic()
        if wait <= 0:
            raise AdapterTimeout()
        try:
            return self.events.get(timeout=wait)
        except queue.Empty:
            raise AdapterTimeout() from None

    def prompt(self, text: str, deadline: float) -> str:
        """Run one turn and return the final assistant text."""
        with self.lock:
            self.last_used = time.monotonic()
            self._drain()
            req_id = f"turn-{uuid.uuid4().hex[:12]}"
            self.send({"id": req_id, "type": "prompt", "message": text})
            accepted = False
            last_text = ""
            aborted = False
            try:
                while True:
                    rec = self._next(deadline)
                    kind = rec.get("type")
                    if kind == "__exit__":
                        raise AdapterError("pi exited during the turn.")
                    if kind == "response" and rec.get("id") == req_id:
                        if not rec.get("success", False):
                            raise AdapterError(f"pi rejected the prompt: {rec.get('error', 'unknown error')}")
                        disposition = (rec.get("data") or {}).get("disposition")
                        if disposition == "handled":
                            # Consumed by an extension or input handler; no run follows.
                            return self._last_assistant_text(deadline) or ""
                        accepted = True
                        continue
                    if kind == "message_end":
                        message = rec.get("message") or {}
                        if message.get("role") == "assistant":
                            text_now = message_text_blocks(message).strip()
                            if text_now:
                                last_text = text_now
                    elif kind == "agent_settled":
                        aborted = bool(rec.get("aborted"))
                        break
            except AdapterTimeout:
                self._abort(grace=min(10.0, max(1.0, deadline - time.monotonic() + 10.0)))
                raise
            if not accepted:
                raise AdapterError("pi settled without acknowledging the prompt.")
            if aborted:
                raise AdapterError("pi aborted the run.")
            if not last_text:
                last_text = self._last_assistant_text(deadline) or ""
            if not last_text:
                raise AdapterError("pi finished without an answer.")
            self.last_used = time.monotonic()
            return last_text

    def _abort(self, grace: float) -> None:
        try:
            self.send({"id": "abort", "type": "abort"})
        except AdapterError:
            return
        end = time.monotonic() + grace
        while time.monotonic() < end:
            try:
                rec = self.events.get(timeout=max(0.05, end - time.monotonic()))
            except queue.Empty:
                break
            if rec.get("type") == "__exit__":
                break
            if rec.get("type") == "response" and rec.get("command") == "abort":
                break
            if rec.get("type") == "agent_settled":
                break

    def _last_assistant_text(self, deadline: float) -> str | None:
        req_id = f"last-{uuid.uuid4().hex[:8]}"
        self.send({"id": req_id, "type": "get_last_assistant_text"})
        while True:
            rec = self._next(min(deadline, time.monotonic() + 10.0))
            if rec.get("type") == "__exit__":
                return None
            if rec.get("type") == "response" and rec.get("id") == req_id:
                if not rec.get("success"):
                    return None
                text = (rec.get("data") or {}).get("text")
                return text.strip() if isinstance(text, str) else None


class PiAdapter(Adapter):
    name = "pi"

    def __init__(self, model_name: str, options: dict, environ: dict | None = None) -> None:
        super().__init__(model_name, options, environ)
        command = options.get("command", ["pi"])
        if isinstance(command, str):
            command = [command]
        if not isinstance(command, list) or not command or not all(isinstance(c, str) for c in command):
            raise ConfigError(f"[models.{model_name}] command must be a non-empty list of strings.")
        self.command = list(command)
        self.cwd = options.get("cwd")
        session_dir = options.get("session_dir") or os.path.join(
            os.path.expanduser("~"), ".sierra-bridge", "pi-sessions", model_name
        )
        self.session_dir = str(Path(session_dir).expanduser())
        extra = options.get("extra_args", [])
        if not isinstance(extra, list) or not all(isinstance(a, str) for a in extra):
            raise ConfigError(f"[models.{model_name}] extra_args must be a list of strings.")
        self.extra_args = list(extra)
        self.idle_seconds = float(options.get("idle_seconds", 600))
        self.startup_timeout = float(options.get("startup_timeout", 30))
        self.max_sessions = int(options.get("max_sessions", 8))
        self._sessions: dict[str, PiProcess] = {}
        self._sessions_lock = threading.Lock()

    def argv_for(self, conversation_id: str, driving_prompt: bool) -> list[str]:
        argv = [
            *self.command,
            "--mode",
            "rpc",
            "--session-dir",
            self.session_dir,
            "--session-id",
            session_id_for(conversation_id),
        ]
        if driving_prompt:
            argv += ["--append-system-prompt", DRIVING_PROMPT]
        argv += self.extra_args
        return argv

    def _get_process(self, conversation_id: str, driving_prompt: bool) -> PiProcess:
        with self._sessions_lock:
            self._reap_idle()
            proc = self._sessions.get(conversation_id)
            if proc is not None and proc.alive and proc.proc.poll() is None:
                return proc
            if proc is not None:
                proc.close(grace=1.0)
                del self._sessions[conversation_id]
            while len(self._sessions) >= self.max_sessions:
                oldest = min(self._sessions, key=lambda k: self._sessions[k].last_used)
                self._sessions.pop(oldest).close(grace=1.0)
            Path(self.session_dir).mkdir(parents=True, exist_ok=True)
            proc = PiProcess(self.argv_for(conversation_id, driving_prompt), self.cwd, self.startup_timeout)
            self._sessions[conversation_id] = proc
            return proc

    def _reap_idle(self) -> None:
        now = time.monotonic()
        for key in list(self._sessions):
            proc = self._sessions[key]
            if proc.lock.locked():
                continue
            if now - proc.last_used > self.idle_seconds or not proc.alive:
                proc.close(grace=2.0)
                del self._sessions[key]

    def complete(self, turn: Turn) -> Iterator[str]:
        proc = self._get_process(turn.conversation_id, turn.driving_prompt)
        try:
            text = proc.prompt(turn.user_text, turn.deadline)
        except AdapterError:
            if not proc.alive:
                with self._sessions_lock:
                    if self._sessions.get(turn.conversation_id) is proc:
                        del self._sessions[turn.conversation_id]
                proc.close(grace=1.0)
            raise
        yield text

    def close(self) -> None:
        with self._sessions_lock:
            for proc in self._sessions.values():
                proc.close()
            self._sessions.clear()
