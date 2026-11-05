"""A stand-in for ``pi --mode rpc`` that speaks the documented JSONL protocol.

It persists a turn counter per ``--session-id`` under ``--session-dir`` so tests
can prove that the bridge keeps one session per conversation across process
restarts. Prompt text selects extra behaviour:

  "tool"   -> an intermediate assistant message with a tool call before the answer
  "sleep"  -> take a long time (abortable), for timeout tests
  "empty"  -> settle without any assistant text
  "crash"  -> exit mid-turn
  "reject" -> prompt response with success=false
"""

from __future__ import annotations

import json
import os
import sys
import threading
import time


def emit(record: dict) -> None:
    sys.stdout.write(json.dumps(record) + "\n")
    sys.stdout.flush()


def parse_args(argv: list[str]) -> dict:
    opts = {"mode": None, "session_dir": None, "session_id": None, "system": [], "extra": []}
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--mode":
            opts["mode"] = argv[i + 1]
            i += 2
        elif a == "--session-dir":
            opts["session_dir"] = argv[i + 1]
            i += 2
        elif a == "--session-id":
            opts["session_id"] = argv[i + 1]
            i += 2
        elif a == "--append-system-prompt":
            opts["system"].append(argv[i + 1])
            i += 2
        else:
            opts["extra"].append(a)
            i += 1
    return opts


class FakePi:
    def __init__(self, opts: dict) -> None:
        self.opts = opts
        self.abort = threading.Event()
        self.worker: threading.Thread | None = None
        self.last_text: str | None = None
        self.session_path = os.path.join(opts["session_dir"], f"{opts['session_id']}.jsonl")
        os.makedirs(opts["session_dir"], exist_ok=True)
        # Record how the process was started, for the tests.
        with open(os.path.join(opts["session_dir"], "argv.log"), "a", encoding="utf-8") as fh:
            fh.write(json.dumps(opts) + "\n")

    def turn_number(self) -> int:
        try:
            with open(self.session_path, encoding="utf-8") as fh:
                return sum(1 for _ in fh) + 1
        except FileNotFoundError:
            return 1

    def handle(self, cmd: dict) -> None:
        kind = cmd.get("type")
        rid = cmd.get("id")
        if kind == "prompt":
            text = cmd.get("message", "")
            if "reject" in text:
                emit({"id": rid, "type": "response", "command": "prompt", "success": False, "error": "nope"})
                return
            emit({"id": rid, "type": "response", "command": "prompt", "success": True, "data": {"disposition": "started"}})
            self.abort.clear()
            self.worker = threading.Thread(target=self.run_turn, args=(text,), daemon=True)
            self.worker.start()
        elif kind == "abort":
            self.abort.set()
            if self.worker:
                self.worker.join()
            emit({"id": rid, "type": "response", "command": "abort", "success": True})
        elif kind == "get_last_assistant_text":
            emit({"id": rid, "type": "response", "command": "get_last_assistant_text", "success": True, "data": {"text": self.last_text}})
        elif kind == "get_state":
            emit({"id": rid, "type": "response", "command": "get_state", "success": True,
                  "data": {"sessionId": self.opts["session_id"], "sessionFile": self.session_path, "isStreaming": False}})
        else:
            emit({"id": rid, "type": "response", "command": kind, "success": False, "error": f"unknown command {kind}"})

    def run_turn(self, text: str) -> None:
        n = self.turn_number()
        with open(self.session_path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps({"type": "message", "role": "user", "text": text}) + "\n")
        emit({"type": "agent_start"})
        emit({"type": "turn_start"})
        if "crash" in text:
            os._exit(1)
        if "sleep" in text:
            end = time.monotonic() + 20
            while time.monotonic() < end and not self.abort.is_set():
                time.sleep(0.02)
            if self.abort.is_set():
                emit({"type": "agent_end", "messages": [], "willRetry": False})
                emit({"type": "agent_settled", "aborted": True})
                return
        if "tool" in text:
            self.assistant_message("Let me look at that.", tool_call=True)
            emit({"type": "tool_execution_start", "toolCallId": "call_1", "toolName": "read", "args": {"path": "x"}})
            emit({"type": "tool_execution_end", "toolCallId": "call_1", "toolName": "read", "result": {}, "isError": False, "durationMs": 1})
            emit({"type": "turn_end", "message": {}, "toolResults": []})
            emit({"type": "turn_start"})
        if "empty" in text:
            emit({"type": "agent_end", "messages": [], "willRetry": False})
            emit({"type": "agent_settled", "aborted": False})
            return
        driving = "driving " if self.opts["system"] else ""
        reply = f"{driving}turn {n}: {text}"
        self.assistant_message(reply)
        emit({"type": "turn_end", "message": {}, "toolResults": []})
        emit({"type": "agent_end", "messages": [], "willRetry": False})
        emit({"type": "agent_settled", "aborted": False})

    def assistant_message(self, text: str, tool_call: bool = False) -> None:
        emit({"type": "message_start", "message": {"role": "assistant", "content": [], "stopReason": "pending"}})
        for i in range(0, len(text), 7):
            emit({"type": "message_update", "usage": {}, "assistantMessageEvent": {"type": "text_delta", "contentIndex": 0, "delta": text[i:i + 7]}})
        content = [{"type": "text", "text": text}]
        if tool_call:
            content.append({"type": "toolCall", "id": "call_1", "name": "read", "arguments": {"path": "x"}})
        else:
            self.last_text = text
        emit({"type": "message_end", "message": {"role": "assistant", "content": content, "stopReason": "toolUse" if tool_call else "stop"}})


def main() -> int:
    opts = parse_args(sys.argv[1:])
    if opts["mode"] != "rpc":
        print("fake pi only supports --mode rpc", file=sys.stderr)
        return 2
    if not opts["session_dir"] or not opts["session_id"]:
        print("fake pi needs --session-dir and --session-id", file=sys.stderr)
        return 2
    pi = FakePi(opts)
    print("fake pi ready", file=sys.stderr)
    for raw in sys.stdin.buffer:
        line = raw.rstrip(b"\r\n")
        if not line:
            continue
        try:
            cmd = json.loads(line)
        except json.JSONDecodeError:
            emit({"type": "response", "command": "parse", "success": False, "error": "bad json"})
            continue
        pi.handle(cmd)
    if pi.worker:
        pi.abort.set()
        pi.worker.join()
    return 0


if __name__ == "__main__":
    sys.exit(main())
