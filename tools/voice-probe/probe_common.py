"""Shared helpers for the voice probes.

Keys are read from the environment only and are never printed or written.
Every string that leaves this module for a file or the console goes through
scrub(), which removes any environment value that looks like a credential.
"""
from __future__ import annotations

import datetime as _dt
import json
import os
import re
import sys
import time
from pathlib import Path

# Model output can hold characters a Windows console cannot print.
sys.stdout.reconfigure(errors="replace")

HERE = Path(__file__).resolve().parent
AUDIO = HERE / "audio"          # generated clips, not committed
RESULTS = HERE / "results"      # small JSON result files, committed

_SECRET_NAME = re.compile(r"(KEY|TOKEN|SECRET|PASSWORD)", re.I)
_SECRET_SHAPE = re.compile(r"\b(sk|xi|gsk|pk)[-_][A-Za-z0-9_\-]{8,}")


def gateway() -> tuple[str, str]:
    """Return (base_url ending in /v1, bearer key) from the environment."""
    base = os.environ.get("OPENAI_BASE_URL", "").rstrip("/")
    key = os.environ.get("OPENAI_API_KEY", "")
    if not base or not key:
        sys.exit("OPENAI_BASE_URL and OPENAI_API_KEY must be set (run under the launcher)")
    return base, key


def elevenlabs_key() -> str:
    key = os.environ.get("ELEVENLABS_API_KEY", "")
    if not key:
        sys.exit("ELEVENLABS_API_KEY must be set (run under the launcher)")
    return key


def scrub(text: str) -> str:
    """Remove credential values from text before it is shown or stored."""
    out = str(text)
    for name, value in os.environ.items():
        if _SECRET_NAME.search(name) and len(value) >= 8:
            out = out.replace(value, f"<{name}>")
    return _SECRET_SHAPE.sub("<redacted>", out)


def now_iso() -> str:
    return _dt.datetime.now().astimezone().isoformat(timespec="seconds")


def ms(seconds: float) -> int:
    return int(round(seconds * 1000))


class Clock:
    """Monotonic stopwatch in milliseconds."""

    def __init__(self) -> None:
        self.t0 = time.perf_counter()

    def lap(self) -> int:
        return ms(time.perf_counter() - self.t0)


def save(name: str, payload: dict) -> Path:
    RESULTS.mkdir(exist_ok=True)
    payload = {"recorded_at": now_iso(), **payload}
    path = RESULTS / f"{name}.json"
    path.write_text(scrub(json.dumps(payload, indent=2, ensure_ascii=False)), encoding="utf-8")
    print(f"wrote {path}")
    return path


def say(*parts: object) -> None:
    print(scrub(" ".join(str(p) for p in parts)), flush=True)


def short(text: str, limit: int = 600) -> str:
    text = scrub(text).strip()
    return text if len(text) <= limit else text[:limit] + " ...[truncated]"


_LITELLM_HEADERS = (
    "x-litellm-response-cost",
    "x-litellm-response-duration-ms",
    "x-litellm-overhead-duration-ms",
    "x-litellm-model-id",
    "x-litellm-version",
)


def litellm_meta(headers) -> dict:
    """The gateway's own timing and cost headers, when it sends them."""
    out = {}
    for name in _LITELLM_HEADERS:
        value = headers.get(name)
        if value is None:
            continue
        short_name = name.removeprefix("x-litellm-").replace("-", "_")
        if short_name == "model_id":
            continue  # deployment hash, not useful to readers
        try:
            out[short_name] = round(float(value), 6)
        except ValueError:
            out[short_name] = value
    return out


_NUMBER_WORDS = {"97": "ninety seven", "30": "thirty", "1": "one", "2": "two"}


def normalize(text: str) -> list[str]:
    """Lowercase words with punctuation removed and the clip's numbers spelled out."""
    text = text.lower().replace("-", " ")
    text = re.sub(r"[^a-z0-9' ]+", " ", text).replace("'", "")
    words = []
    for w in text.split():
        words.extend(_NUMBER_WORDS.get(w, w).split())
    return words


def word_error_rate(truth: str, heard: str) -> float:
    """Word-level edit distance divided by the number of true words."""
    a, b = normalize(truth), normalize(heard)
    prev = list(range(len(b) + 1))
    for i, wa in enumerate(a, 1):
        cur = [i]
        for j, wb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (wa != wb)))
        prev = cur
    return round(prev[-1] / max(len(a), 1), 3)


def gateway_rtt(http, base: str, n: int = 5) -> list[int]:
    """Round-trip times in ms for a trivial unauthenticated gateway request.

    The first value includes opening the connection; the rest reuse it.
    """
    root = base[:-3] if base.endswith("/v1") else base
    times = []
    for _ in range(n):
        clock = Clock()
        http.get(f"{root}/health/liveliness")
        times.append(clock.lap())
    return times


def resample(x, up: int, down: int, rate_in: int):
    """Rational resample of a float sample array: zero-stuff by `up`, low-pass, keep every `down`th."""
    import numpy as np

    stuffed = np.zeros(len(x) * up, dtype=np.float64)
    stuffed[::up] = x
    taps = 95
    n = np.arange(taps) - (taps - 1) / 2
    cutoff = 0.95 * min(rate_in, rate_in * up / down) / 2 / (rate_in * up)  # cycles per sample
    h = 2 * cutoff * np.sinc(2 * cutoff * n) * np.hamming(taps)
    h = h / h.sum() * up  # gain restores the level lost to zero-stuffing
    return np.convolve(stuffed, h, mode="same")[::down]


def read_wav_16k(path):
    """Samples of a 16 kHz mono 16-bit WAV as float64."""
    import wave

    import numpy as np

    with wave.open(str(path), "rb") as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64)
