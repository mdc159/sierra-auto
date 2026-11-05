"""Append-only JSONL log of every turn, with the untrimmed reply."""

from __future__ import annotations

import json
import logging
import threading
import time
from pathlib import Path

log = logging.getLogger("sierra_bridge.replylog")


class ReplyLog:
    def __init__(self, path: str | None) -> None:
        self.path = Path(path).expanduser() if path else None
        self._lock = threading.Lock()
        self._warned = False

    def record(self, **fields) -> None:
        if self.path is None:
            return
        entry = {"ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"), **fields}
        line = json.dumps(entry, ensure_ascii=False)
        with self._lock:
            try:
                self.path.parent.mkdir(parents=True, exist_ok=True)
                with self.path.open("a", encoding="utf-8") as fh:
                    fh.write(line + "\n")
            except OSError as e:
                # Logging must never break a turn, but say so once.
                if not self._warned:
                    self._warned = True
                    log.error("cannot write reply log %s: %s", self.path, e)
