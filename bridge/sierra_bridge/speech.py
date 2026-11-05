"""Make agent replies fit for text-to-speech in a moving truck.

Two jobs:

* ``DRIVING_PROMPT`` is the wrapper prompt that turns a coding agent into a
  one-to-three-sentence spoken assistant.
* ``SpeechTrimmer`` cleans markdown out of a reply and cuts it to a length that
  can be read aloud, sentence by sentence, while the full text is kept for the log.
"""

from __future__ import annotations

import re

DRIVING_PROMPT = (
    "The person talking to you is driving a truck and hears your answer read aloud "
    "through the car speakers. Answer in one to three short spoken sentences of plain "
    "English: no lists, no markdown, no code, no file paths, no URLs, no emoji. Give the "
    "key point first. If a full answer needs more, give the gist and offer to continue. "
    "Never ask the driver to look at or type on a screen."
)

ELLIPSIS = " …"

_FENCE_RE = re.compile(r"```.*?```", re.DOTALL)
_INLINE_CODE_RE = re.compile(r"`([^`\n]*)`")
_LINK_RE = re.compile(r"\[([^\]]*)\]\([^)]*\)")
_URL_RE = re.compile(r"https?://\S+?(?=[.,;:!?)\]]*(?:\s|$))")
_HEADING_RE = re.compile(r"^\s{0,3}#{1,6}\s*", re.MULTILINE)
_BULLET_RE = re.compile(r"^\s*(?:[-*+]|\d+[.)])\s+", re.MULTILINE)
_EMPHASIS_RE = re.compile(r"[*_~]{1,3}")
_BLOCKQUOTE_RE = re.compile(r"^\s*>+\s?", re.MULTILINE)
_WS_RE = re.compile(r"\s+")
_SENTENCE_END_RE = re.compile(r"[.!?][\"')\]]*(?=\s|$)")


def clean_for_speech(text: str) -> str:
    """Strip markdown and other things a speech engine would read out badly."""
    out = _FENCE_RE.sub(" (code omitted) ", text)
    out = _INLINE_CODE_RE.sub(r"\1", out)
    out = _LINK_RE.sub(r"\1", out)
    out = _URL_RE.sub("link", out)
    out = _HEADING_RE.sub("", out)
    out = _BLOCKQUOTE_RE.sub("", out)
    out = _BULLET_RE.sub("", out)
    out = _EMPHASIS_RE.sub("", out)
    out = out.replace("|", " ")
    return _WS_RE.sub(" ", out).strip()


def trim_for_speech(text: str, max_chars: int) -> tuple[str, bool]:
    """Clean ``text`` and cut it to ``max_chars``. Returns (spoken_text, was_trimmed)."""
    trimmer = SpeechTrimmer(max_chars)
    first = trimmer.feed(text)
    tail = trimmer.finish()
    return first + tail, trimmer.trimmed


def _split_sentences(text: str) -> tuple[list[str], str]:
    """Split off complete sentences; return (sentences, remainder)."""
    sentences: list[str] = []
    start = 0
    for m in _SENTENCE_END_RE.finditer(text):
        end = m.end()
        sentence = text[start:end].strip()
        if sentence:
            sentences.append(sentence)
        start = end
    return sentences, text[start:]


class SpeechTrimmer:
    """Incrementally cleans and cuts a streamed reply.

    Feed raw deltas in; get back the text that may be sent to the car. Once the
    limit is reached nothing more is emitted, but everything fed is still kept in
    ``full_text`` so the caller can log the complete reply.
    """

    def __init__(self, max_chars: int) -> None:
        if max_chars < 40:
            raise ValueError("max_chars must be at least 40")
        self.max_chars = max_chars
        self.full_text = ""
        self.sent_text = ""
        self.trimmed = False
        self._pending = ""  # raw text not yet flushed as sentences
        self._finished = False

    def feed(self, delta: str) -> str:
        if self._finished or not delta:
            return ""
        self.full_text += delta
        self._pending += delta
        if self.trimmed:
            return ""
        # Do not flush an open fenced code block: the fence must be replaced as
        # a whole. Sentences before it can still go out.
        held = ""
        flushable = self._pending
        if flushable.count("```") % 2 == 1:
            fence = flushable.find("```")
            flushable, held = flushable[:fence], flushable[fence:]
        # Closed fences go first, so a period inside code cannot end a sentence.
        sentences, remainder = _split_sentences(_FENCE_RE.sub(" (code omitted) ", flushable))
        self._pending = remainder + held
        return "".join(self._emit(s) for s in sentences)

    def finish(self) -> str:
        if self._finished:
            return ""
        self._finished = True
        if self.trimmed:
            return ""
        out = self._emit(self._pending)
        self._pending = ""
        return out

    def _emit(self, raw_sentence: str) -> str:
        if self.trimmed:
            return ""
        sentence = clean_for_speech(raw_sentence)
        if not sentence:
            return ""
        sep = " " if self.sent_text and not self.sent_text.endswith(" ") else ""
        if len(self.sent_text) + len(sep) + len(sentence) <= self.max_chars:
            piece = sep + sentence
            self.sent_text += piece
            return piece
        self.trimmed = True
        if self.sent_text:
            # Earlier sentences were sent whole. Mark the cut and stop.
            piece = ELLIPSIS
            self.sent_text += piece
            return piece
        # The very first sentence is too long on its own: cut at a word boundary.
        window = sentence[: self.max_chars - len(ELLIPSIS)]
        space = window.rfind(" ")
        if space >= self.max_chars // 2:
            window = window[:space]
        piece = window.rstrip(",;: ") + ELLIPSIS
        self.sent_text += piece
        return piece
