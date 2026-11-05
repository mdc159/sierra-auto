"""Work out which conversation an OpenAI-style request belongs to.

The chat completions API is stateless: every request carries the whole history
the client still remembers. Sierra keeps a rolling window of the last few turns,
so the first message eventually falls out. The registry therefore chains on
replies instead: after each turn it remembers "this reply text belongs to
conversation X". The next request from the same conversation contains that
reply as its last assistant message, which identifies the conversation again.

A client that can do better sends ``X-Sierra-Conversation: <id>`` or the
OpenAI ``user`` field; both win over the heuristic.
"""

from __future__ import annotations

import hashlib
import re
import threading
import time
import uuid

_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")


def message_text(content) -> str:
    """Text of an OpenAI message ``content``, which may be a string or a list of parts."""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for part in content:
            if isinstance(part, dict) and part.get("type") == "text":
                parts.append(str(part.get("text", "")))
            elif isinstance(part, str):
                parts.append(part)
        return "".join(parts)
    return ""


def explicit_id(value) -> str | None:
    """Validate a client-supplied conversation id; None if unusable."""
    if not isinstance(value, str):
        return None
    value = value.strip()
    return value if _ID_RE.match(value) else None


def _fingerprint(text: str) -> str:
    return hashlib.sha256(text.strip().encode("utf-8")).hexdigest()


class ConversationRegistry:
    def __init__(self, ttl_seconds: float = 6 * 3600, max_entries: int = 2000, clock=time.monotonic) -> None:
        self.ttl = ttl_seconds
        self.max_entries = max_entries
        self._clock = clock
        self._lock = threading.Lock()
        self._by_reply: dict[str, tuple[str, float]] = {}

    def resolve(self, messages: list[dict], explicit: str | None = None) -> tuple[str, bool]:
        """Return (conversation_id, is_new)."""
        if explicit:
            return explicit, False
        last_reply = None
        for msg in reversed(messages):
            if isinstance(msg, dict) and msg.get("role") == "assistant":
                last_reply = message_text(msg.get("content"))
                break
        if last_reply is None:
            return self._new_id(), True
        with self._lock:
            self._expire()
            hit = self._by_reply.get(_fingerprint(last_reply))
        if hit is None:
            return self._new_id(), True
        return hit[0], False

    def remember(self, conversation_id: str, reply_text: str) -> None:
        if not reply_text.strip():
            return
        with self._lock:
            self._expire()
            self._by_reply[_fingerprint(reply_text)] = (conversation_id, self._clock())
            while len(self._by_reply) > self.max_entries:
                oldest = min(self._by_reply, key=lambda k: self._by_reply[k][1])
                del self._by_reply[oldest]

    def _expire(self) -> None:
        now = self._clock()
        stale = [k for k, (_, t) in self._by_reply.items() if now - t > self.ttl]
        for k in stale:
            del self._by_reply[k]

    @staticmethod
    def _new_id() -> str:
        return uuid.uuid4().hex
